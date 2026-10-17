package com.sixq.gozjest.main.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Landmark indices and measurements for the MediaPipe 478-point face mesh.
 *
 * Every length produced here is divided by the inter-ocular distance before it
 * is compared against a threshold, so the thresholds hold whether the face
 * fills the frame or sits half a metre away.
 */
object FaceGeometry {

    /* ----- eyes -------------------------------------------------------- */

    /** Full ring of the eye on the subject's right (left half of the image). */
    val EYE_RIGHT = intArrayOf(
        33, 246, 161, 160, 159, 158, 157, 173,
        133, 155, 154, 153, 145, 144, 163, 7,
    )

    /** Full ring of the eye on the subject's left. */
    val EYE_LEFT = intArrayOf(
        263, 466, 388, 387, 386, 385, 384, 398,
        362, 382, 381, 380, 374, 373, 390, 249,
    )

    /** Six-point eye-aspect-ratio probes: outer, top×2, inner, bottom×2. */
    private val EAR_RIGHT = intArrayOf(33, 160, 158, 133, 153, 144)
    private val EAR_LEFT = intArrayOf(263, 387, 385, 362, 380, 373)

    private val EYE_RIGHT_CENTER = intArrayOf(33, 133, 159, 145)
    private val EYE_LEFT_CENTER = intArrayOf(263, 362, 386, 374)

    /* ----- nose tip ---------------------------------------------------- */

    /** Tip of the nose plus the nostril wings — deliberately not the bridge. */
    val NOSE_TIP = intArrayOf(1, 4, 19, 45, 275)

    private const val NOSE_TIP_PRIMARY = 1

    /* ----- lips -------------------------------------------------------- */

    /** Outer and inner lip contour, corners included. */
    val LIPS = intArrayOf(
        61, 40, 37, 0, 267, 270, 291,
        321, 314, 17, 84, 91,
        78, 82, 13, 312, 308, 317, 14, 87,
    )

    /* ----- face axis --------------------------------------------------- */

    private const val CHIN = 152

    /** Highest index this object touches; frames shorter than this are unusable. */
    const val REQUIRED_LANDMARKS = 468

    /**
     * Distance between the two eye centres, in pixels. Used as the unit of
     * length for every other measurement.
     */
    fun interOcularDistance(pose: Pose): Float {
        val right = pose.centroid(EYE_RIGHT_CENTER)
        val left = pose.centroid(EYE_LEFT_CENTER)
        return Pose.hypot(right[0] - left[0], right[1] - left[1])
    }

    fun eyeRightCenter(pose: Pose): FloatArray = pose.centroid(EYE_RIGHT_CENTER)

    fun eyeLeftCenter(pose: Pose): FloatArray = pose.centroid(EYE_LEFT_CENTER)

    /** Midpoint of the eye line — the origin of the face-local vertical axis. */
    fun midEye(pose: Pose): FloatArray {
        val right = eyeRightCenter(pose)
        val left = eyeLeftCenter(pose)
        return floatArrayOf((right[0] + left[0]) / 2f, (right[1] + left[1]) / 2f)
    }

    /**
     * Where a point sits along the face, independent of head tilt:
     * `0.0` at the eye line, `~0.40` at the nose tip, `~0.66` at the mouth
     * and `1.0` at the chin. Negative values are above the eyes.
     */
    fun verticalRatio(pose: Pose, px: Float, py: Float): Float {
        if (!pose.has(CHIN)) return Float.NaN
        val origin = midEye(pose)
        val axisX = pose.xs[CHIN] - origin[0]
        val axisY = pose.ys[CHIN] - origin[1]
        val lengthSquared = axisX * axisX + axisY * axisY
        if (lengthSquared < 1e-3f) return Float.NaN
        return ((px - origin[0]) * axisX + (py - origin[1]) * axisY) / lengthSquared
    }

    /**
     * Eye aspect ratio, averaged over both eyes. Wide-open eyes sit around
     * 0.30, a closed lid around 0.08.
     */
    fun eyeAspectRatio(pose: Pose): Float {
        val right = singleEyeAspectRatio(pose, EAR_RIGHT)
        val left = singleEyeAspectRatio(pose, EAR_LEFT)
        return when {
            right.isNaN() && left.isNaN() -> Float.NaN
            right.isNaN() -> left
            left.isNaN() -> right
            else -> (right + left) / 2f
        }
    }

    /** The more-open of the two eyes, so a deliberate wink never counts. */
    fun maxEyeAspectRatio(pose: Pose): Float {
        val right = singleEyeAspectRatio(pose, EAR_RIGHT)
        val left = singleEyeAspectRatio(pose, EAR_LEFT)
        return when {
            right.isNaN() && left.isNaN() -> Float.NaN
            right.isNaN() -> left
            left.isNaN() -> right
            else -> max(right, left)
        }
    }

    private fun singleEyeAspectRatio(pose: Pose, probe: IntArray): Float {
        if (probe.any { !pose.has(it) }) return Float.NaN
        val horizontal = pose.dist(probe[0], probe[3])
        if (horizontal < 1e-3f) return Float.NaN
        val vertical = pose.dist(probe[1], probe[5]) + pose.dist(probe[2], probe[4])
        return vertical / (2f * horizontal)
    }

    /**
     * Rough head-yaw sanity check. On a frontal face the nose tip is about
     * equally far from both eyes; in profile the ratio runs away and landmark
     * distances stop meaning what the thresholds assume.
     */
    fun isFrontalEnough(pose: Pose, minRatio: Float = 0.42f, maxRatio: Float = 2.4f): Boolean {
        if (!pose.has(NOSE_TIP_PRIMARY)) return false
        val nx = pose.xs[NOSE_TIP_PRIMARY]
        val ny = pose.ys[NOSE_TIP_PRIMARY]
        val right = eyeRightCenter(pose)
        val left = eyeLeftCenter(pose)
        val toRight = Pose.hypot(nx - right[0], ny - right[1])
        val toLeft = Pose.hypot(nx - left[0], ny - left[1])
        if (toRight < 1e-3f || toLeft < 1e-3f) return false
        val ratio = toRight / toLeft
        return ratio in minRatio..maxRatio
    }

    /** Fraction of the frame the face spans — a crude "is the user close enough". */
    fun faceCoverage(pose: Pose, frameWidth: Int, frameHeight: Int): Float {
        if (frameWidth <= 0 || frameHeight <= 0 || pose.count == 0) return 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until pose.count) {
            minX = min(minX, pose.xs[i])
            maxX = max(maxX, pose.xs[i])
            minY = min(minY, pose.ys[i])
            maxY = max(maxY, pose.ys[i])
        }
        val widthRatio = abs(maxX - minX) / frameWidth
        val heightRatio = abs(maxY - minY) / frameHeight
        return max(widthRatio, heightRatio)
    }
}
