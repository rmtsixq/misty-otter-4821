package com.sixq.gozjest.main.vision

/**
 * Landmark indices and posture tests for the MediaPipe 21-point hand model.
 *
 * The posture test is the single biggest false-positive filter in the whole
 * pipeline: an idle hand resting against the cheek, a palm wiping the face or
 * fingers combing through hair all put *some* landmark near the eyes, but only
 * a deliberate point has the index finger extended while middle and ring are
 * folded away.
 */
object HandGeometry {

    const val WRIST = 0
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_TIP = 20

    const val REQUIRED_LANDMARKS = 21

    /**
     * Palm length (wrist to middle-finger knuckle). Every finger length below
     * is expressed as a multiple of this, which makes the posture test
     * independent of how close the hand is to the camera.
     */
    fun palmLength(hand: Pose): Float = hand.dist(WRIST, MIDDLE_MCP)

    private fun fingerExtension(hand: Pose, tip: Int, mcp: Int, palm: Float): Float =
        if (palm < 1e-3f) Float.NaN else hand.dist(tip, mcp) / palm

    /**
     * True when the hand is making a pointing shape: index clearly extended,
     * middle and ring clearly folded. An extended finger measures about one
     * palm length from knuckle to tip, a folded one well under two thirds.
     */
    fun isPointing(
        hand: Pose,
        indexExtendedMin: Float = 0.70f,
        othersCurledMax: Float = 0.66f,
    ): Boolean {
        if (hand.count < REQUIRED_LANDMARKS) return false
        val palm = palmLength(hand)
        if (palm < 1e-3f) return false

        val index = fingerExtension(hand, INDEX_TIP, INDEX_MCP, palm)
        if (index.isNaN() || index < indexExtendedMin) return false

        // The index tip must also reach past its own middle joint, which rules
        // out a hooked finger that happens to measure long in 2D.
        if (hand.dist(INDEX_TIP, WRIST) < hand.dist(INDEX_PIP, WRIST)) return false

        val middle = fingerExtension(hand, MIDDLE_TIP, MIDDLE_MCP, palm)
        val ring = fingerExtension(hand, RING_TIP, RING_MCP, palm)
        if (middle.isNaN() || ring.isNaN()) return false
        if (middle > othersCurledMax || ring > othersCurledMax) return false

        // The index must stand out from the folded fingers rather than the
        // whole hand being foreshortened towards the lens.
        return index > middle + 0.18f && index > ring + 0.18f
    }

    /** Debug read-out of all four finger extensions, in palm lengths. */
    fun extensions(hand: Pose): FloatArray {
        if (hand.count < REQUIRED_LANDMARKS) return floatArrayOf(0f, 0f, 0f, 0f)
        val palm = palmLength(hand)
        return floatArrayOf(
            fingerExtension(hand, INDEX_TIP, INDEX_MCP, palm),
            fingerExtension(hand, MIDDLE_TIP, MIDDLE_MCP, palm),
            fingerExtension(hand, RING_TIP, RING_MCP, palm),
            fingerExtension(hand, PINKY_TIP, PINKY_MCP, palm),
        )
    }
}
