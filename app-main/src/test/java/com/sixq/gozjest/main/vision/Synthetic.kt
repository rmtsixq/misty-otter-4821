package com.sixq.gozjest.main.vision

import kotlin.math.sqrt

/**
 * Synthetic landmark builders. Frame is 480x640, face upright and centred.
 * Only the indices the detectors actually read are given real positions.
 */
object Synthetic {

    const val FRAME_W = 480
    const val FRAME_H = 640

    const val IOD = 100f
    const val EYE_Y = 230f
    const val RIGHT_EYE_X = 200f   // subject's right eye = image left
    const val LEFT_EYE_X = 300f
    const val MID_EYE_X = 250f
    const val CHIN_Y = 360f        // eye line -> chin = 130 px
    const val AXIS = CHIN_Y - EYE_Y

    fun verticalToY(t: Float) = EYE_Y + t * AXIS

    val NOSE_TIP_POINT = floatArrayOf(MID_EYE_X, verticalToY(0.40f))
    val LIPS_POINT = floatArrayOf(MID_EYE_X, verticalToY(0.66f))
    val RIGHT_EYE_POINT = floatArrayOf(RIGHT_EYE_X, EYE_Y)

    /**
     * @param eyeOpening half-height of the lid gap in pixels; ~8 is wide open,
     *   ~1 is shut.
     */
    fun face(eyeOpening: Float = 8f, rightEyeOpening: Float = eyeOpening): Pose {
        val xs = FloatArray(478)
        val ys = FloatArray(478)
        fun put(index: Int, x: Float, y: Float) { xs[index] = x; ys[index] = y }

        // --- right eye (image left) -------------------------------------
        val r = RIGHT_EYE_X
        put(33, r - 18f, EYE_Y)          // outer corner
        put(133, r + 18f, EYE_Y)         // inner corner
        put(160, r - 8f, EYE_Y - rightEyeOpening)
        put(158, r + 8f, EYE_Y - rightEyeOpening)
        put(159, r, EYE_Y - rightEyeOpening)
        put(144, r - 8f, EYE_Y + rightEyeOpening)
        put(153, r + 8f, EYE_Y + rightEyeOpening)
        put(145, r, EYE_Y + rightEyeOpening)
        put(246, r - 14f, EYE_Y - rightEyeOpening * 0.6f)
        put(161, r - 11f, EYE_Y - rightEyeOpening * 0.9f)
        put(157, r + 12f, EYE_Y - rightEyeOpening * 0.8f)
        put(173, r + 15f, EYE_Y - rightEyeOpening * 0.4f)
        put(7, r - 14f, EYE_Y + rightEyeOpening * 0.6f)
        put(163, r - 11f, EYE_Y + rightEyeOpening * 0.9f)
        put(154, r + 12f, EYE_Y + rightEyeOpening * 0.8f)
        put(155, r + 15f, EYE_Y + rightEyeOpening * 0.4f)

        // --- left eye ----------------------------------------------------
        val l = LEFT_EYE_X
        put(263, l + 18f, EYE_Y)         // outer corner
        put(362, l - 18f, EYE_Y)         // inner corner
        put(387, l + 8f, EYE_Y - eyeOpening)
        put(385, l - 8f, EYE_Y - eyeOpening)
        put(386, l, EYE_Y - eyeOpening)
        put(373, l + 8f, EYE_Y + eyeOpening)
        put(380, l - 8f, EYE_Y + eyeOpening)
        put(374, l, EYE_Y + eyeOpening)
        put(466, l + 14f, EYE_Y - eyeOpening * 0.6f)
        put(388, l + 11f, EYE_Y - eyeOpening * 0.9f)
        put(384, l - 12f, EYE_Y - eyeOpening * 0.8f)
        put(398, l - 15f, EYE_Y - eyeOpening * 0.4f)
        put(249, l + 14f, EYE_Y + eyeOpening * 0.6f)
        put(390, l + 11f, EYE_Y + eyeOpening * 0.9f)
        put(381, l - 12f, EYE_Y + eyeOpening * 0.8f)
        put(382, l - 15f, EYE_Y + eyeOpening * 0.4f)

        // --- nose tip cluster --------------------------------------------
        val noseY = NOSE_TIP_POINT[1]
        put(1, MID_EYE_X, noseY)
        put(4, MID_EYE_X, noseY + 4f)
        put(19, MID_EYE_X, noseY + 8f)
        put(45, MID_EYE_X - 12f, noseY + 6f)
        put(275, MID_EYE_X + 12f, noseY + 6f)

        // --- lips ----------------------------------------------------------
        val mouthY = LIPS_POINT[1]
        put(61, MID_EYE_X - 35f, mouthY)
        put(291, MID_EYE_X + 35f, mouthY)
        put(0, MID_EYE_X, mouthY - 11f)
        put(17, MID_EYE_X, mouthY + 12f)
        put(13, MID_EYE_X, mouthY - 3f)
        put(14, MID_EYE_X, mouthY + 3f)
        put(78, MID_EYE_X - 28f, mouthY)
        put(308, MID_EYE_X + 28f, mouthY)
        put(40, MID_EYE_X - 20f, mouthY - 8f)
        put(37, MID_EYE_X - 8f, mouthY - 10f)
        put(267, MID_EYE_X + 8f, mouthY - 10f)
        put(270, MID_EYE_X + 20f, mouthY - 8f)
        put(321, MID_EYE_X + 20f, mouthY + 9f)
        put(314, MID_EYE_X + 8f, mouthY + 11f)
        put(84, MID_EYE_X - 8f, mouthY + 11f)
        put(91, MID_EYE_X - 20f, mouthY + 9f)
        put(82, MID_EYE_X - 8f, mouthY - 4f)
        put(312, MID_EYE_X + 8f, mouthY - 4f)
        put(87, MID_EYE_X - 8f, mouthY + 4f)
        put(317, MID_EYE_X + 8f, mouthY + 4f)

        put(152, MID_EYE_X, CHIN_Y)      // chin

        return Pose(xs, ys)
    }

    /**
     * Hand with the index fingertip exactly on [targetX]/[targetY].
     *
     * @param curl extension of middle/ring as a fraction of palm length;
     *   0.45 is a folded finger, 1.05 an extended one (an open palm).
     */
    fun hand(
        targetX: Float,
        targetY: Float,
        curl: Float = 0.45f,
        palm: Float = 100f,
        approachX: Float = 0.30f,
        approachY: Float = -0.95f,
    ): Pose {
        val xs = FloatArray(21)
        val ys = FloatArray(21)
        fun put(index: Int, x: Float, y: Float) { xs[index] = x; ys[index] = y }

        // u = wrist -> fingertip direction, p = perpendicular across knuckles
        val norm = sqrt(approachX * approachX + approachY * approachY)
        val ux = approachX / norm
        val uy = approachY / norm
        val px = -uy
        val py = ux

        val fingerLength = 0.95f * palm
        // Place the wrist so the index tip lands exactly on the target.
        val wristX = targetX - (palm * 0.95f + fingerLength) * ux - 22f * px
        val wristY = targetY - (palm * 0.95f + fingerLength) * uy - 22f * py

        fun at(along: Float, across: Float) = floatArrayOf(
            wristX + along * ux + across * px,
            wristY + along * uy + across * py,
        )

        put(0, wristX, wristY)

        val indexMcp = at(palm * 0.95f, 22f)
        put(5, indexMcp[0], indexMcp[1])
        val indexPip = at(palm * 0.95f + fingerLength * 0.45f, 22f)
        put(6, indexPip[0], indexPip[1])
        put(7, targetX - fingerLength * 0.2f * ux, targetY - fingerLength * 0.2f * uy)
        put(8, targetX, targetY)

        val middleMcp = at(palm, 0f)
        put(9, middleMcp[0], middleMcp[1])
        val middleTip = at(palm + curl * palm, 0f)
        put(12, middleTip[0], middleTip[1])

        val ringMcp = at(palm * 0.97f, -20f)
        put(13, ringMcp[0], ringMcp[1])
        val ringTip = at(palm * 0.97f + curl * palm, -20f)
        put(16, ringTip[0], ringTip[1])

        val pinkyMcp = at(palm * 0.90f, -38f)
        put(17, pinkyMcp[0], pinkyMcp[1])
        val pinkyTip = at(palm * 0.90f + curl * palm * 0.9f, -38f)
        put(20, pinkyTip[0], pinkyTip[1])

        put(4, wristX + 30f * px, wristY + 30f * py)

        return Pose(xs, ys)
    }
}
