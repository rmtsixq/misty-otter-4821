package com.sixq.gozjest.main.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

private const val TAG = "VisionEngine"
private const val FACE_MODEL = "face_landmarker.task"
private const val HAND_MODEL = "hand_landmarker.task"

/**
 * Wraps the two MediaPipe landmarkers.
 *
 * Both run in [RunningMode.VIDEO], which is synchronous *and* keeps the
 * frame-to-frame tracker. That means one call site gets the face and the hands
 * for the very same frame, with no need to fuse two asynchronous callbacks by
 * timestamp — and a mismatched pair is exactly the kind of thing that would
 * produce a phantom "finger on the eye".
 */
class VisionEngine(private val context: Context) {

    data class Analysis(
        val facePose: Pose?,
        val hands: List<Pose>,
        /** Lower of the two blink blendshape scores, or NaN if unavailable. */
        val blinkScore: Float,
        val faceCoverage: Float,
    )

    private var faceLandmarker: FaceLandmarker? = null
    private var handLandmarker: HandLandmarker? = null

    /** Which delegate actually loaded, for the status line. */
    var delegateName: String = "-"
        private set

    @Throws(IllegalStateException::class)
    fun setup() {
        close()
        val errors = mutableListOf<String>()
        for (delegate in listOf(Delegate.GPU, Delegate.CPU)) {
            try {
                faceLandmarker = createFaceLandmarker(delegate)
                handLandmarker = createHandLandmarker(delegate)
                delegateName = if (delegate == Delegate.GPU) "GPU" else "CPU"
                Log.i(TAG, "Landmarkers ready on $delegateName")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "Delegate $delegate unavailable", t)
                errors += "$delegate: ${t.message}"
                close()
            }
        }
        throw IllegalStateException(
            "MediaPipe modelleri yüklenemedi. ${errors.joinToString(" | ")}"
        )
    }

    private fun createFaceLandmarker(delegate: Delegate): FaceLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(FACE_MODEL)
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    private fun createHandLandmarker(delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(HAND_MODEL)
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return HandLandmarker.createFromOptions(context, options)
    }

    /**
     * Runs both models over one upright frame. [timestampMs] must increase
     * strictly between calls — MediaPipe's video tracker rejects anything else.
     */
    fun analyze(bitmap: Bitmap, timestampMs: Long): Analysis {
        val face = faceLandmarker ?: return EMPTY
        val hand = handLandmarker ?: return EMPTY

        val width = bitmap.width
        val height = bitmap.height
        val image = BitmapImageBuilder(bitmap).build()

        return try {
            val faceResult = face.detectForVideo(image, timestampMs)
            val handResult = hand.detectForVideo(image, timestampMs)

            val facePose = faceResult.faceLandmarks().firstOrNull()?.toPose(width, height)
            Analysis(
                facePose = facePose,
                hands = handResult.landmarks().map { it.toPose(width, height) },
                blinkScore = faceResult.extractBlinkScore(),
                faceCoverage = facePose
                    ?.let { FaceGeometry.faceCoverage(it, width, height) }
                    ?: 0f,
            )
        } finally {
            runCatching { image.close() }
        }
    }

    fun close() {
        runCatching { faceLandmarker?.close() }
        runCatching { handLandmarker?.close() }
        faceLandmarker = null
        handLandmarker = null
    }

    private fun List<NormalizedLandmark>.toPose(width: Int, height: Int): Pose {
        val xs = FloatArray(size)
        val ys = FloatArray(size)
        for (i in indices) {
            val landmark = this[i]
            xs[i] = landmark.x() * width
            ys[i] = landmark.y() * height
        }
        return Pose(xs, ys)
    }

    /**
     * The quieter of the two eyelids: taking the minimum means one eye left
     * open keeps the score low, so a wink can never start the blackout hold.
     */
    private fun com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
        .extractBlinkScore(): Float {
        val blendshapes = faceBlendshapes()
        if (!blendshapes.isPresent) return Float.NaN
        val categories = blendshapes.get().firstOrNull() ?: return Float.NaN
        var left = Float.NaN
        var right = Float.NaN
        for (category in categories) {
            when (category.categoryName()) {
                "eyeBlinkLeft" -> left = category.score()
                "eyeBlinkRight" -> right = category.score()
            }
        }
        if (left.isNaN() || right.isNaN()) return Float.NaN
        return minOf(left, right)
    }

    private companion object {
        val EMPTY = Analysis(
            facePose = null,
            hands = emptyList(),
            blinkScore = Float.NaN,
            faceCoverage = 0f,
        )
    }
}
