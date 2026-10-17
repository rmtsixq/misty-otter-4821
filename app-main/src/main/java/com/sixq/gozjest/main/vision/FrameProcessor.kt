package com.sixq.gozjest.main.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max

private const val TAG = "FrameProcessor"

/**
 * CameraX analyser for the main device's front camera: converts each frame to
 * an upright bitmap, runs both landmarkers over it and feeds the result to the
 * two detectors.
 *
 * Runs on a single-threaded executor with `KEEP_ONLY_LATEST` back-pressure, so
 * frames are dropped rather than queued when inference falls behind — stale
 * landmarks are worse than fewer landmarks.
 */
class FrameProcessor(
    private val engine: VisionEngine,
    private val gestures: TouchGestureDetector,
    private val eyes: EyeCloseDetector,
    private val listener: Listener,
) : ImageAnalysis.Analyzer {

    interface Listener {
        /** A deliberate fingertip touch completed. */
        fun onTrigger(target: TouchTarget)

        /** Eyes stayed shut for the full hold; flip the screen. */
        fun onBlackoutToggle()

        /** Throttled UI state. */
        fun onStatus(status: Status)

        /** Models finished loading; reports which delegate won. */
        fun onEngineReady(delegate: String)

        /** Inference blew up; the pipeline has stopped. */
        fun onFatalError(message: String)
    }

    data class Status(
        val faceFound: Boolean = false,
        val faceTooSmall: Boolean = false,
        val handFound: Boolean = false,
        val pointing: Boolean = false,
        val eyesClosed: Boolean = false,
        val holdProgress: Float = 0f,
        val holdRemainingMs: Long = 0L,
        val arming: Boolean = false,
        val needsReopen: Boolean = false,
        val tracking: TouchTarget? = null,
        val trackingFrames: Int = 0,
        val trackingNeeded: Int = 0,
        val fps: Float = 0f,
        val debug: TouchGestureDetector.Debug = TouchGestureDetector.Debug(),
    )

    /** When false the finger gestures are ignored (but the eye hold is not). */
    @Volatile
    var gesturesEnabled: Boolean = true

    @Volatile
    var tuning: Tuning = Tuning.DEFAULT
        set(value) {
            field = value
            gestures.tuning = value
            eyes.tuning = value
        }

    private var engineReady = false
    private var lastTimestampMs = 0L
    private var lastStatusAt = 0L
    private var frameCount = 0
    private var fpsWindowStart = 0L
    private var fps = 0f
    private var fatal = false

    override fun analyze(image: ImageProxy) {
        if (fatal) {
            image.close()
            return
        }
        var bitmap: Bitmap? = null
        try {
            bitmap = image.toUprightBitmap()
            if (bitmap != null) process(bitmap)
        } catch (t: Throwable) {
            Log.e(TAG, "Frame analysis failed", t)
            fatal = true
            listener.onFatalError(t.message ?: t.javaClass.simpleName)
        } finally {
            bitmap?.recycle()
            image.close()
        }
    }

    private fun process(bitmap: Bitmap) {
        // The landmarkers are created on this thread, the only thread that
        // ever calls them: the GPU delegate holds a GL context and does not
        // appreciate being built on one thread and used from another.
        if (!engineReady) {
            engine.setup()
            engineReady = true
            listener.onEngineReady(engine.delegateName)
        }

        val now = SystemClock.uptimeMillis()
        val timestamp = max(lastTimestampMs + 1, now)
        lastTimestampMs = timestamp

        val analysis = engine.analyze(bitmap, timestamp)
        val config = tuning

        // A face that is too far away gives landmark noise bigger than the
        // thresholds, so treat it as "no face" rather than guessing.
        val usableFace = analysis.facePose
            ?.takeIf { analysis.faceCoverage >= config.minFaceCoverage }

        val eyeOutcome = eyes.update(usableFace, analysis.blinkScore, now)
        if (eyeOutcome is EyeCloseDetector.Outcome.Toggle) {
            listener.onBlackoutToggle()
        }

        val gestureOutcome = if (gesturesEnabled) {
            gestures.update(usableFace, analysis.hands, now)
        } else {
            gestures.reset()
            TouchGestureDetector.Outcome.Idle
        }
        if (gestureOutcome is TouchGestureDetector.Outcome.Fired) {
            listener.onTrigger(gestureOutcome.target)
        }

        updateFps(now)
        maybeEmitStatus(now, analysis, usableFace != null, eyeOutcome, gestureOutcome)
    }

    private fun updateFps(now: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = now
        frameCount++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000L) {
            fps = frameCount * 1_000f / elapsed
            frameCount = 0
            fpsWindowStart = now
        }
    }

    private fun maybeEmitStatus(
        now: Long,
        analysis: VisionEngine.Analysis,
        faceUsable: Boolean,
        eyeOutcome: EyeCloseDetector.Outcome,
        gestureOutcome: TouchGestureDetector.Outcome,
    ) {
        val important = eyeOutcome is EyeCloseDetector.Outcome.Toggle ||
            gestureOutcome is TouchGestureDetector.Outcome.Fired
        if (!important && now - lastStatusAt < STATUS_INTERVAL_MS) return
        lastStatusAt = now

        val tracking = gestureOutcome as? TouchGestureDetector.Outcome.Tracking
        val holding = eyeOutcome as? EyeCloseDetector.Outcome.Holding

        listener.onStatus(
            Status(
                faceFound = faceUsable,
                faceTooSmall = analysis.facePose != null && !faceUsable,
                handFound = analysis.hands.isNotEmpty(),
                pointing = gestures.debug.pointing,
                eyesClosed = eyes.eyesClosed,
                holdProgress = holding?.progress ?: 0f,
                holdRemainingMs = holding?.remainingMs ?: 0L,
                arming = eyeOutcome is EyeCloseDetector.Outcome.Arming,
                needsReopen = eyeOutcome is EyeCloseDetector.Outcome.NeedsReopen,
                tracking = tracking?.target,
                trackingFrames = tracking?.frames ?: 0,
                trackingNeeded = tracking?.needed ?: 0,
                fps = fps,
                debug = gestures.debug,
            )
        )
    }

    private fun ImageProxy.toUprightBitmap(): Bitmap? {
        val source = runCatching { toBitmap() }.getOrNull() ?: return null
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return source
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull() ?: return source
        if (rotated !== source) source.recycle()
        return rotated
    }

    /** Must run on the analyser thread, for the same reason as setup. */
    fun releaseEngine() {
        engine.close()
        engineReady = false
    }

    private companion object {
        const val STATUS_INTERVAL_MS = 100L
    }
}
