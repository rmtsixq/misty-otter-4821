package com.sixq.gozjest.main.vision

/**
 * Every threshold the detectors use, in one place so the settings screen can
 * expose the handful that are worth tweaking per device and per user.
 *
 * Distances are multiples of the inter-ocular distance, never pixels.
 */
data class Tuning(
    /** A fingertip closer than this to a target region counts as touching it. */
    val touchRadius: Float = 0.38f,

    /** The finger must get this far from every target before the next trigger. */
    val releaseRadius: Float = 0.75f,

    /** The winning target must beat the runner-up by this factor, or we abstain. */
    val marginRatio: Float = 0.80f,

    /** Consecutive frames the same target must win before a trigger fires. */
    val stabilityFrames: Int = 5,

    /** Minimum gap between two triggers. */
    val cooldownMs: Long = 1_200L,

    /** Require an index-pointing hand shape (off = any fingertip proximity). */
    val strictPointing: Boolean = true,

    /** Eye-aspect ratio below which a lid counts as shut. */
    val eyeClosedEar: Float = 0.17f,

    /** Eye-aspect ratio above which a lid counts as open again (hysteresis). */
    val eyeOpenEar: Float = 0.23f,

    /** Blendshape blink score above which a lid counts as shut. */
    val blinkScore: Float = 0.45f,

    /** How long both eyes must stay shut to flip the blackout. */
    val blackoutHoldMs: Long = 5_000L,

    /** Eyes must be open at least this long before a new hold can start. */
    val reopenMs: Long = 600L,

    /** A face must be tracked this long before the blackout arms at all. */
    val armDelayMs: Long = 1_000L,

    /** Dropped-face jitter shorter than this does not reset the hold timer. */
    val faceLossGraceMs: Long = 400L,

    /** Closed-eye jitter shorter than this does not reset the hold timer. */
    val eyeJitterGraceMs: Long = 150L,

    /** Face must span at least this fraction of the frame to be trusted. */
    val minFaceCoverage: Float = 0.16f,

    /** Buzz on capture / answer, the only feedback available while blacked out. */
    val hapticFeedback: Boolean = true,

    /** Keep reacting to finger gestures while the screen is blacked out. */
    val gesturesWhileBlack: Boolean = true,
) {
    companion object {
        val DEFAULT = Tuning()
    }
}
