package com.sixq.gozjest.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sixq.gozjest.camera.cam.PhotoEncoder
import com.sixq.gozjest.camera.databinding.ActivityCameraBinding
import com.sixq.gozjest.camera.net.MainClient
import com.sixq.gozjest.core.CameraEvent
import com.sixq.gozjest.core.Protocol
import com.sixq.gozjest.core.ui.BlackoutController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * The camera device.
 *
 * It has no gesture detection of its own: it keeps the rear camera warm, holds
 * one long-poll request open against the main device, and the instant a
 * `CAPTURE` command comes back it shoots, shrinks and uploads one frame.
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var prefs: CameraPrefs
    private lateinit var client: MainClient
    private lateinit var dim: BlackoutController

    private var imageCapture: ImageCapture? = null
    private var captureExecutor: ExecutorService? = null
    private var pollJob: Job? = null
    private var cameraStarted = false
    private var sentCount = 0
    private var failedCount = 0

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else toast(getString(R.string.permission_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // The camera is released when the activity pauses, so the screen has
        // to stay awake for the phone to stay useful.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = CameraPrefs(this)
        client = MainClient(applicationContext, prefs)

        dim = BlackoutController(
            activity = this,
            onManualExit = { toast("Karartma kapatıldı") },
            onStateChanged = { black ->
                binding.previewView.visibility = if (black) View.GONE else View.VISIBLE
            },
        )

        binding.testButton.setOnClickListener { manualCapture() }
        binding.dimButton.setOnClickListener {
            dim.enable()
            toast(getString(R.string.dim_hint))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, CameraSettingsActivity::class.java))
        }

        renderCounts()
    }

    override fun onStart() {
        super.onStart()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
        // Settings may have changed the host; make discovery run again.
        client.invalidate()
        startPolling()
    }

    override fun onStop() {
        super.onStop()
        pollJob?.cancel()
        pollJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        captureExecutor?.shutdown()
        captureExecutor = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (isVolumeKey && dim.onEscapeKey()) return true
        return super.onKeyDown(keyCode, event)
    }

    /* ----- camera ------------------------------------------------------- */

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        captureExecutor = Executors.newSingleThreadExecutor()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse {
                toast("Kamera açılamadı: ${it.message}")
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            // Latency matters more than noise reduction here: the shutter has
            // to follow the fingertip, not trail a second behind it.
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()
            imageCapture = capture

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
            }.onFailure { toast("Arka kamera bağlanamadı: ${it.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Suspends until the capture completes; null means it failed. */
    private suspend fun capture(): ByteArray? = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        val executor = captureExecutor
        if (capture == null || executor == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        capture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bytes = try {
                        PhotoEncoder.encode(image, prefs.maxEdge, prefs.quality)
                    } finally {
                        image.close()
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            },
        )
    }

    /* ----- command loop ------------------------------------------------- */

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val target = client.resolve()
                if (target == null) {
                    withContext(Dispatchers.Main) { renderConnection(false, null) }
                    delay(RETRY_DELAY_MS)
                    continue
                }

                try {
                    val command = client.poll(target)
                    withContext(Dispatchers.Main) { renderConnection(true, target) }
                    when (command.type) {
                        Protocol.CMD_CAPTURE -> handleCapture(target, command.id)
                        Protocol.CMD_RESET -> clearLocalCopies()
                        else -> Unit
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    client.invalidate()
                    withContext(Dispatchers.Main) { renderConnection(false, target) }
                    delay(RETRY_DELAY_MS)
                }
            }
        }
    }

    private suspend fun handleCapture(target: MainClient.Resolved, commandId: String) {
        val bytes = capture()
        if (bytes == null) {
            failedCount++
            withContext(Dispatchers.Main) { renderCounts("çekim başarısız") }
            client.reportEvent(
                target,
                CameraEvent(CameraEvent.KIND_ERROR, "Fotoğraf çekilemedi"),
            )
            return
        }

        if (prefs.keepLocalCopy) saveLocalCopy(bytes)

        val uploaded = client.uploadPhoto(target, bytes, commandId)
        if (uploaded) {
            sentCount++
            withContext(Dispatchers.Main) {
                renderCounts("son: ${timestamp()} · ${bytes.size / 1024} KB")
            }
        } else {
            failedCount++
            withContext(Dispatchers.Main) { renderCounts("yükleme başarısız") }
            client.reportEvent(
                target,
                CameraEvent(CameraEvent.KIND_ERROR, "Yükleme başarısız"),
            )
        }
    }

    private fun manualCapture() {
        lifecycleScope.launch(Dispatchers.IO) {
            val target = client.resolve()
            if (target == null) {
                withContext(Dispatchers.Main) { toast("Ana cihaz bulunamadı") }
                return@launch
            }
            handleCapture(target, commandId = "manual")
        }
    }

    /* ----- local copies ------------------------------------------------- */

    private fun localDirectory(): File =
        File(cacheDir, "sent").apply { mkdirs() }

    private fun saveLocalCopy(bytes: ByteArray) {
        runCatching {
            val directory = localDirectory()
            File(directory, "${System.currentTimeMillis()}.jpg").writeBytes(bytes)
            // Keep the local ring buffer bounded.
            val files = directory.listFiles()?.sortedBy { it.name } ?: return
            if (files.size > MAX_LOCAL_COPIES) {
                files.take(files.size - MAX_LOCAL_COPIES).forEach { it.delete() }
            }
        }
    }

    private fun clearLocalCopies() {
        runCatching { localDirectory().listFiles()?.forEach { it.delete() } }
    }

    /* ----- rendering ---------------------------------------------------- */

    private fun renderConnection(connected: Boolean, target: MainClient.Resolved?) {
        binding.connectionText.text = getString(
            if (connected) R.string.status_connected else R.string.status_disconnected
        )
        binding.connectionText.setTextColor(
            ContextCompat.getColor(
                this,
                if (connected) R.color.accent else R.color.accent_error,
            )
        )
        binding.hostText.text = target?.let { "${it.host}:${it.port}" }
            ?: "adres yok — ana cihazın hotspot'una bağlanın"
    }

    private fun renderCounts(detail: String? = null) {
        binding.countText.text = buildString {
            append("Gönderilen: $sentCount")
            if (failedCount > 0) append("  ·  hata: $failedCount")
        }
        if (detail != null) binding.lastText.text = detail
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun toast(message: String) {
        if (dim.isBlack) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val RETRY_DELAY_MS = 1_800L
        const val MAX_LOCAL_COPIES = 120
    }
}
