package com.sixq.gozjest.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sixq.gozjest.core.CameraEvent
import com.sixq.gozjest.core.DiscoveryResponder
import com.sixq.gozjest.core.NetUtils
import com.sixq.gozjest.core.Protocol
import com.sixq.gozjest.main.databinding.ActivityMainBinding
import com.sixq.gozjest.main.gemini.GeminiClient
import com.sixq.gozjest.main.net.MainServer
import com.sixq.gozjest.main.store.PhotoCache
import com.sixq.gozjest.core.ui.BlackoutController
import com.sixq.gozjest.main.ui.Haptics
import com.sixq.gozjest.main.vision.EyeCloseDetector
import com.sixq.gozjest.main.vision.FrameProcessor
import com.sixq.gozjest.main.vision.TouchGestureDetector
import com.sixq.gozjest.main.vision.TouchTarget
import com.sixq.gozjest.main.vision.VisionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The main (display) device.
 *
 * It owns the front camera and the gesture detection, runs the little server
 * the camera phone talks to, keeps the photo batch, and calls Gemini when the
 * lip gesture fires. It never takes a photo itself.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var photoCache: PhotoCache
    private lateinit var haptics: Haptics
    private lateinit var blackout: BlackoutController

    private val visionEngine by lazy { VisionEngine(applicationContext) }
    private val gestureDetector = TouchGestureDetector()
    private val eyeDetector = EyeCloseDetector()
    private val gemini = GeminiClient()
    private val discoveryResponder = DiscoveryResponder()

    private var frameProcessor: FrameProcessor? = null
    private var analysisExecutor: ExecutorService? = null
    private var server: MainServer? = null
    private var cameraStarted = false
    private var askInFlight = false

    private val ui = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showBlockingMessage(getString(R.string.permission_needed))
    }

    /** Keeps the "camera phone connected" label honest. */
    private val clientTicker = object : Runnable {
        override fun run() {
            server?.refreshClientState()
            renderConnection()
            ui.postDelayed(this, CLIENT_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The front camera must keep running even while the screen is black,
        // otherwise the eye gesture could never switch it back on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        photoCache = PhotoCache(this)
        haptics = Haptics(this).apply { enabled = prefs.hapticFeedback }

        blackout = BlackoutController(
            activity = this,
            onManualExit = { toast("Karartma elle kapatıldı") },
            onStateChanged = ::onBlackoutStateChanged,
        )

        binding.captureButton.setOnClickListener { requestCapture(manual = true) }
        binding.askButton.setOnClickListener { askGemini(manual = true) }
        binding.clearButton.setOnClickListener {
            photoCache.clear()
            renderPhotoInfo()
            toast("Önbellek temizlendi")
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        startServer()
        renderPhotoInfo()
        renderAddresses()
    }

    override fun onStart() {
        super.onStart()
        applySettings()
        ui.post(clientTicker)
        ensureCameraPermission()
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(clientTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        discoveryResponder.stop()
        runCatching { server?.stop() }
        server = null

        // Release the landmarkers on the thread that created them.
        val executor = analysisExecutor
        val processor = frameProcessor
        if (executor != null && processor != null) {
            runCatching { executor.submit { runCatching { processor.releaseEngine() } } }
        }
        executor?.shutdown()
        analysisExecutor = null
        frameProcessor = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (isVolumeKey && blackout.onEscapeKey()) return true
        return super.onKeyDown(keyCode, event)
    }

    /** Re-reads the settings; called on every return from the settings screen. */
    private fun applySettings() {
        val tuning = prefs.tuning()
        gestureDetector.tuning = tuning
        eyeDetector.tuning = tuning
        frameProcessor?.tuning = tuning
        haptics.enabled = prefs.hapticFeedback
        frameProcessor?.gesturesEnabled = !blackout.isBlack || prefs.gesturesWhileBlack
        binding.debugText.visibility = if (prefs.showDebug) View.VISIBLE else View.GONE
    }

    /* ----- camera ------------------------------------------------------ */

    private fun ensureCameraPermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true

        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor

        val processor = FrameProcessor(
            engine = visionEngine,
            gestures = gestureDetector,
            eyes = eyeDetector,
            listener = processorListener,
        ).apply {
            tuning = prefs.tuning()
            gesturesEnabled = true
        }
        frameProcessor = processor

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse {
                showBlockingMessage("Kamera açılamadı: ${it.message}")
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // 640×480 is CameraX's ImageAnalysis default and plenty for
            // landmarks; a larger frame only buys inference latency.
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor, processor) }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure {
                showBlockingMessage("Ön kamera bağlanamadı: ${it.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Every callback arrives on the analyser thread, so each hops to the UI. */
    private val processorListener = object : FrameProcessor.Listener {
        override fun onTrigger(target: TouchTarget) {
            ui.post {
                when (target) {
                    TouchTarget.EYE, TouchTarget.NOSE -> requestCapture(manual = false)
                    TouchTarget.LIPS -> askGemini(manual = false)
                }
            }
        }

        override fun onBlackoutToggle() {
            ui.post { blackout.toggle() }
        }

        override fun onStatus(status: FrameProcessor.Status) {
            ui.post { renderStatus(status) }
        }

        override fun onEngineReady(delegate: String) {
            ui.post { toast("Model yüklendi ($delegate)") }
        }

        override fun onFatalError(message: String) {
            ui.post { showBlockingMessage("Görüntü işleme durdu: $message") }
        }
    }

    /* ----- actions ------------------------------------------------------ */

    private fun requestCapture(manual: Boolean) {
        val queued = server?.enqueue(Protocol.CMD_CAPTURE) ?: false
        if (!queued) {
            haptics.error()
            toast("Foto telefonu bağlı değil")
            renderConnection()
            return
        }
        haptics.capture()
        if (manual) toast("Çekim isteği gönderildi")
    }

    private fun askGemini(manual: Boolean) {
        if (askInFlight) {
            haptics.error()
            if (manual) toast("Önceki istek sürüyor")
            return
        }
        val photos = photoCache.snapshot()
        if (photos.isEmpty()) {
            haptics.error()
            toast("Önbellek boş — önce göze/burna dokunup fotoğraf çekin")
            return
        }

        askInFlight = true
        setBusy(true)
        haptics.asking()
        showAnswer(getString(R.string.asking))

        val apiKey = prefs.apiKey
        val model = prefs.model
        val prompt = prefs.prompt

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                gemini.ask(apiKey, model, prompt, photos)
            }
            askInFlight = false
            setBusy(false)
            when (result) {
                is GeminiClient.Result.Ok -> {
                    showAnswer(result.text)
                    haptics.success()
                    if (prefs.clearAfterAnswer) {
                        photoCache.clear()
                        renderPhotoInfo()
                    }
                }

                is GeminiClient.Result.Failure -> {
                    showAnswer("⚠ ${result.message}")
                    haptics.error()
                }
            }
        }
    }

    /* ----- server ------------------------------------------------------- */

    private fun startServer() {
        val instance = MainServer(
            port = Protocol.HTTP_PORT,
            callbacks = object : MainServer.Callbacks {
                override fun onPhotoReceived(bytes: ByteArray, commandId: String) {
                    photoCache.add(bytes)
                    ui.post {
                        renderPhotoInfo()
                        haptics.capture()
                    }
                }

                override fun onClientEvent(event: CameraEvent) {
                    ui.post {
                        if (event.kind == CameraEvent.KIND_ERROR) {
                            haptics.error()
                            toast("Foto telefonu: ${event.message}")
                        }
                    }
                }

                override fun onClientStateChanged(online: Boolean) {
                    ui.post { renderConnection() }
                }
            },
        )
        runCatching { instance.start(SOCKET_READ_TIMEOUT_MS, true) }
            .onSuccess {
                server = instance
                discoveryResponder.start()
            }
            .onFailure {
                showBlockingMessage(
                    "Sunucu ${Protocol.HTTP_PORT} portunda başlatılamadı: ${it.message}"
                )
            }
    }

    /* ----- rendering ---------------------------------------------------- */

    private fun onBlackoutStateChanged(black: Boolean) {
        binding.previewView.visibility = if (black) View.GONE else View.VISIBLE
        binding.holdOverlay.visibility = View.GONE
        frameProcessor?.gesturesEnabled = !black || prefs.gesturesWhileBlack
        haptics.toggle()
    }

    private fun renderStatus(status: FrameProcessor.Status) {
        val face = when {
            status.faceFound -> "yüz ✓"
            status.faceTooSmall -> "yüz UZAK"
            else -> "yüz ✗"
        }
        val hand = if (status.handFound) "el ✓" else "el ✗"
        val point = if (status.pointing) "işaret ✓" else "işaret ✗"
        val tracking = status.tracking?.let {
            "  ▸ ${it.label()} ${status.trackingFrames}/${status.trackingNeeded}"
        } ?: ""

        binding.statusText.text = buildString {
            append("$face  $hand  $point")
            append("\n%.0f fps".format(status.fps))
            if (status.arming) append("  · hazırlanıyor")
            if (status.needsReopen) append("  · gözünü aç")
            append(tracking)
        }

        val holding = status.holdProgress > 0.02f && !blackout.isBlack
        binding.holdOverlay.visibility = if (holding) View.VISIBLE else View.GONE
        if (holding) {
            binding.holdProgress.progress = (status.holdProgress * 100).toInt()
            binding.holdText.text = "%.1f s".format(status.holdRemainingMs / 1000f)
        }

        if (prefs.showDebug) {
            val debug = status.debug
            binding.debugText.text = buildString {
                append("göz=%.2f burun=%.2f dudak=%.2f dikey=%.2f".format(
                    debug.eyeDistance, debug.noseDistance,
                    debug.lipsDistance, debug.verticalRatio,
                ))
                append(if (debug.armed) "  hazır" else "  bekliyor")
                debug.rejection?.let { append("  ret: $it") }
            }
        }
    }

    private fun renderConnection() {
        val online = server?.clientOnline == true
        binding.connectionText.text = getString(
            if (online) R.string.camera_online else R.string.camera_offline
        )
        binding.connectionText.setTextColor(
            ContextCompat.getColor(
                this,
                if (online) R.color.accent else R.color.accent_error,
            )
        )
    }

    private fun renderPhotoInfo() {
        val count = photoCache.count()
        binding.photoCountText.text = "Önbellek: $count fotoğraf"
        binding.thumbnailView.setImageBitmap(photoCache.latestThumbnail())
    }

    private fun renderAddresses() {
        val addresses = NetUtils.localIpv4Addresses()
        binding.addressText.text = if (addresses.isEmpty()) {
            "Bu cihazın IP'si yok (Wi-Fi/hotspot kapalı?)"
        } else {
            addresses.joinToString(" · ") { "$it:${Protocol.HTTP_PORT}" }
        }
    }

    private fun showAnswer(text: String) {
        binding.answerText.text = text
        binding.answerScroll.post { binding.answerScroll.scrollTo(0, 0) }
    }

    private fun setBusy(busy: Boolean) {
        binding.busyBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.askButton.isEnabled = !busy
    }

    private fun showBlockingMessage(message: String) {
        showAnswer("⚠ $message")
        toast(message)
    }

    /** Suppressed while blacked out — a toast window would leak light. */
    private fun toast(message: String) {
        if (blackout.isBlack) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun TouchTarget.label(): String = when (this) {
        TouchTarget.EYE -> "GÖZ"
        TouchTarget.NOSE -> "BURUN"
        TouchTarget.LIPS -> "DUDAK"
    }

    private companion object {
        const val CLIENT_TICK_MS = 2_000L
        const val SOCKET_READ_TIMEOUT_MS = 10_000
    }
}
