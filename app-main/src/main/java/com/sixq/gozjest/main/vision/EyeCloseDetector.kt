package com.sixq.gozjest.main.vision

/**
 * Watches both eyelids and emits one [Outcome.Toggle] every time they stay
 * shut for [Tuning.blackoutHoldMs].
 *
 * The guards that keep an ordinary blink — or a camera hiccup — from flipping
 * the screen:
 *
 *  * the hold is five seconds long, two orders of magnitude above a blink;
 *  * the *more open* of the two eyes is measured, so a wink never counts;
 *  * a sub-[Tuning.eyeJitterGraceMs] flicker to "open" keeps the timer alive,
 *    but at least [MIN_CLOSED_RATIO] of the sampled frames must have been
 *    closed, so an eyelid hovering on the threshold cannot creep to five
 *    seconds;
 *  * losing the face freezes the timer rather than advancing it, so covering
 *    the lens is not a shortcut;
 *  * after each toggle the eyes must be open for [Tuning.reopenMs] before a
 *    new hold may begin — this is what makes "open, then hold again" the way
 *    back out of the blackout.
 */
class EyeCloseDetector(@Volatile var tuning: Tuning = Tuning.DEFAULT) {

    sealed interface Outcome {
        /** Nothing happening; no hold in progress. */
        data object Idle : Outcome

        /** Eyes are shut and the timer is running. */
        data class Holding(val progress: Float, val remainingMs: Long) : Outcome

        /** Five seconds reached — flip the screen. */
        data object Toggle : Outcome

        /** A face is visible but the detector has not armed yet. */
        data class Arming(val progress: Float) : Outcome

        /** Eyes must be opened before another hold can start. */
        data object NeedsReopen : Outcome
    }

    @Volatile
    var lastEyeAspectRatio: Float = Float.NaN
        private set

    @Volatile
    var lastBlinkScore: Float = Float.NaN
        private set

    @Volatile
    var eyesClosed: Boolean = false
        private set

    private var faceFirstSeenAt = 0L
    private var faceLastSeenAt = 0L
    private var closedSince = 0L
    private var closedLastAt = 0L
    private var openSince = 0L
    private var closedSamples = 0
    private var totalSamples = 0
    private var wasClosed = false
    private var needsReopen = false

    fun reset() {
        faceFirstSeenAt = 0L
        faceLastSeenAt = 0L
        clearHold()
        openSince = 0L
        wasClosed = false
        needsReopen = false
        eyesClosed = false
    }

    /**
     * @param face landmarks for this frame, or `null` if no face was found.
     * @param blinkScore the lower of the two `eyeBlink*` blendshape scores,
     *   or `NaN` when the model did not supply blendshapes.
     */
    fun update(face: Pose?, blinkScore: Float, nowMs: Long): Outcome {
        val config = tuning

        if (face == null || face.count < FaceGeometry.REQUIRED_LANDMARKS) {
            val hadFace = faceLastSeenAt != 0L
            if (hadFace && nowMs - faceLastSeenAt <= config.faceLossGraceMs) {
                // Short tracking dropout: freeze, neither advance nor reset.
                return if (closedSince != 0L) holding(config, nowMs) else Outcome.Idle
            }
            reset()
            return Outcome.Idle
        }

        if (faceFirstSeenAt == 0L) faceFirstSeenAt = nowMs
        faceLastSeenAt = nowMs

        val armedFor = nowMs - faceFirstSeenAt
        if (armedFor < config.armDelayMs) {
            clearHold()
            return Outcome.Arming((armedFor.toFloat() / config.armDelayMs).coerceIn(0f, 1f))
        }

        val closed = evaluateClosed(face, blinkScore, config)
        eyesClosed = closed

        if (needsReopen) {
            if (closed) {
                openSince = 0L
            } else {
                if (openSince == 0L) openSince = nowMs
                if (nowMs - openSince >= config.reopenMs) {
                    needsReopen = false
                    openSince = 0L
                }
            }
            clearHold()
            return if (needsReopen) Outcome.NeedsReopen else Outcome.Idle
        }

        if (closed) {
            if (closedSince == 0L) {
                closedSince = nowMs
                closedSamples = 0
                totalSamples = 0
            }
            closedLastAt = nowMs
            closedSamples++
            totalSamples++

            val elapsed = nowMs - closedSince
            val closedRatio = closedSamples.toFloat() / totalSamples.coerceAtLeast(1)
            if (elapsed >= config.blackoutHoldMs && closedRatio >= MIN_CLOSED_RATIO) {
                clearHold()
                needsReopen = true
                openSince = 0L
                return Outcome.Toggle
            }
            return holding(config, nowMs)
        }

        // Eyes read as open.
        if (closedSince != 0L && nowMs - closedLastAt <= config.eyeJitterGraceMs) {
            totalSamples++
            return holding(config, nowMs)
        }
        clearHold()
        return Outcome.Idle
    }

    private fun holding(config: Tuning, nowMs: Long): Outcome.Holding {
        val elapsed = (nowMs - closedSince).coerceAtLeast(0L)
        val remaining = (config.blackoutHoldMs - elapsed).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / config.blackoutHoldMs).coerceIn(0f, 1f)
        return Outcome.Holding(progress, remaining)
    }

    private fun evaluateClosed(face: Pose, blinkScore: Float, config: Tuning): Boolean {
        val ear = FaceGeometry.maxEyeAspectRatio(face)
        lastEyeAspectRatio = ear
        lastBlinkScore = blinkScore

        val closed = if (!blinkScore.isNaN()) {
            // Blendshapes are the stronger signal when the model provides them.
            if (wasClosed) {
                blinkScore > config.blinkScore - BLINK_HYSTERESIS
            } else {
                blinkScore > config.blinkScore
            }
        } else if (!ear.isNaN()) {
            if (wasClosed) ear < config.eyeOpenEar else ear < config.eyeClosedEar
        } else {
            false
        }

        wasClosed = closed
        return closed
    }

    private fun clearHold() {
        closedSince = 0L
        closedLastAt = 0L
        closedSamples = 0
        totalSamples = 0
    }

    private companion object {
        /** Share of sampled frames that must read "closed" for a valid hold. */
        const val MIN_CLOSED_RATIO = 0.75f

        const val BLINK_HYSTERESIS = 0.12f
    }
}
