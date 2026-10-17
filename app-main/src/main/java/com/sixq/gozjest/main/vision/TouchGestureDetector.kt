package com.sixq.gozjest.main.vision

/** The three places on the face that mean something to this app. */
enum class TouchTarget { EYE, NOSE, LIPS }

/**
 * Turns per-frame landmarks into discrete "the user deliberately touched X"
 * events.
 *
 * Five independent filters have to agree before anything fires:
 *
 *  1. **Posture** — the hand must be pointing (see [HandGeometry.isPointing]).
 *  2. **Proximity** — the index tip must be within [Tuning.touchRadius] of the
 *     target region, measured in inter-ocular distances so it scales.
 *  3. **Margin** — the winning region must beat the runner-up by
 *     [Tuning.marginRatio]; a finger halfway between nose and lip abstains
 *     instead of guessing.
 *  4. **Anatomy** — the tip must sit in the right band of the face-local
 *     vertical axis, so a point near the jaw can never register as an eye.
 *  5. **Time** — the same answer must repeat for [Tuning.stabilityFrames]
 *     frames, and the finger must then leave the face before the next event.
 */
class TouchGestureDetector(@Volatile var tuning: Tuning = Tuning.DEFAULT) {

    sealed interface Outcome {
        /** Nothing of interest this frame. */
        data object Idle : Outcome

        /** A target is winning but has not held long enough yet. */
        data class Tracking(val target: TouchTarget, val frames: Int, val needed: Int) : Outcome

        /** A deliberate touch just completed. */
        data class Fired(val target: TouchTarget) : Outcome
    }

    /** Last frame's internals, for the on-screen debug overlay. */
    data class Debug(
        val handSeen: Boolean = false,
        val pointing: Boolean = false,
        val frontal: Boolean = false,
        val armed: Boolean = true,
        val eyeDistance: Float = Float.NaN,
        val noseDistance: Float = Float.NaN,
        val lipsDistance: Float = Float.NaN,
        val verticalRatio: Float = Float.NaN,
        val rejection: String? = null,
    )

    @Volatile
    var debug: Debug = Debug()
        private set

    private var candidate: TouchTarget? = null
    private var candidateFrames = 0
    private var armed = true
    private var releaseFrames = 0
    private var lastFireAt = 0L

    fun reset() {
        candidate = null
        candidateFrames = 0
        armed = true
        releaseFrames = 0
        debug = Debug()
    }

    fun update(face: Pose?, hands: List<Pose>, nowMs: Long): Outcome {
        val config = tuning

        if (face == null || face.count < FaceGeometry.REQUIRED_LANDMARKS) {
            candidate = null
            candidateFrames = 0
            noteRelease(config, released = true)
            debug = Debug(armed = armed, rejection = "yüz yok")
            return Outcome.Idle
        }

        val iod = FaceGeometry.interOcularDistance(face)
        if (iod < 8f) {
            candidate = null
            candidateFrames = 0
            debug = Debug(armed = armed, frontal = false, rejection = "yüz çok küçük")
            return Outcome.Idle
        }

        val frontal = FaceGeometry.isFrontalEnough(face)
        if (!frontal) {
            candidate = null
            candidateFrames = 0
            debug = Debug(armed = armed, handSeen = hands.isNotEmpty(), rejection = "yüz yan dönük")
            return Outcome.Idle
        }

        var bestTarget: TouchTarget? = null
        var bestDistance = Float.MAX_VALUE
        var closestAnything = Float.MAX_VALUE
        var anyPointing = false
        var rejection: String? = null
        var debugEye = Float.NaN
        var debugNose = Float.NaN
        var debugLips = Float.NaN
        var debugVertical = Float.NaN

        for (hand in hands) {
            if (hand.count < HandGeometry.REQUIRED_LANDMARKS) continue

            val palm = HandGeometry.palmLength(hand)
            if (palm < 0.35f * iod) {
                rejection = rejection ?: "el çok uzak"
                continue
            }

            val pointing = HandGeometry.isPointing(hand)
            if (pointing) anyPointing = true
            if (config.strictPointing && !pointing) {
                rejection = rejection ?: "işaret şekli yok"
                continue
            }

            val tipX = hand.xs[HandGeometry.INDEX_TIP]
            val tipY = hand.ys[HandGeometry.INDEX_TIP]

            val eyeDistance = minOf(
                face.minDistToPoint(FaceGeometry.EYE_RIGHT, tipX, tipY),
                face.minDistToPoint(FaceGeometry.EYE_LEFT, tipX, tipY),
            ) / iod
            val noseDistance = face.minDistToPoint(FaceGeometry.NOSE_TIP, tipX, tipY) / iod
            val lipsDistance = face.minDistToPoint(FaceGeometry.LIPS, tipX, tipY) / iod
            val vertical = FaceGeometry.verticalRatio(face, tipX, tipY)

            // Keep the debug read-out for whichever hand is nearest the face.
            val handMinimum = minOf(eyeDistance, noseDistance, lipsDistance)
            if (handMinimum < closestAnything) {
                debugEye = eyeDistance
                debugNose = noseDistance
                debugLips = lipsDistance
                debugVertical = vertical
                closestAnything = handMinimum
            }

            val ranked = listOf(
                TouchTarget.EYE to eyeDistance,
                TouchTarget.NOSE to noseDistance,
                TouchTarget.LIPS to lipsDistance,
            ).sortedBy { it.second }

            val (winner, winnerDistance) = ranked[0]
            val runnerUpDistance = ranked[1].second

            if (winnerDistance > config.touchRadius) {
                rejection = rejection ?: "parmak uzak"
                continue
            }
            if (winnerDistance > config.marginRatio * runnerUpDistance) {
                rejection = "belirsiz (${winner.name.lowercase()}/${ranked[1].first.name.lowercase()})"
                continue
            }
            if (!isAnatomicallyPlausible(winner, vertical)) {
                rejection = "yanlış bölge"
                continue
            }

            if (winnerDistance < bestDistance) {
                bestDistance = winnerDistance
                bestTarget = winner
            }
        }

        val touchingSomething = closestAnything <= config.releaseRadius
        noteRelease(config, released = !touchingSomething)

        debug = Debug(
            handSeen = hands.isNotEmpty(),
            pointing = anyPointing,
            frontal = true,
            armed = armed,
            eyeDistance = debugEye,
            noseDistance = debugNose,
            lipsDistance = debugLips,
            verticalRatio = debugVertical,
            rejection = if (bestTarget == null) rejection else null,
        )

        if (!armed) {
            candidate = null
            candidateFrames = 0
            return Outcome.Idle
        }

        if (bestTarget == null) {
            candidate = null
            candidateFrames = 0
            return Outcome.Idle
        }

        if (bestTarget != candidate) {
            candidate = bestTarget
            candidateFrames = 1
        } else {
            candidateFrames++
        }

        val ready = candidateFrames >= config.stabilityFrames &&
            nowMs - lastFireAt >= config.cooldownMs
        if (!ready) {
            return Outcome.Tracking(bestTarget, candidateFrames, config.stabilityFrames)
        }

        lastFireAt = nowMs
        armed = false
        releaseFrames = 0
        candidate = null
        candidateFrames = 0
        return Outcome.Fired(bestTarget)
    }

    /** Re-arms only after the finger has clearly left the face for a few frames. */
    private fun noteRelease(config: Tuning, released: Boolean) {
        if (armed) {
            releaseFrames = 0
            return
        }
        if (released) {
            releaseFrames++
            if (releaseFrames >= RELEASE_FRAMES) {
                armed = true
                releaseFrames = 0
            }
        } else {
            releaseFrames = 0
        }
    }

    private fun isAnatomicallyPlausible(target: TouchTarget, vertical: Float): Boolean {
        if (vertical.isNaN()) return true
        return when (target) {
            TouchTarget.EYE -> vertical in EYE_BAND
            TouchTarget.NOSE -> vertical in NOSE_BAND
            TouchTarget.LIPS -> vertical in LIPS_BAND
        }
    }

    private companion object {
        /** Frames of clear separation required before the next trigger can arm. */
        const val RELEASE_FRAMES = 3

        /*
         * Bands along the face-local vertical axis, where 0.0 is the eye line,
         * ~0.40 the nose tip, ~0.66 the mouth and 1.0 the chin. They overlap on
         * purpose; the margin rule resolves anything that lands in the overlap.
         */
        val EYE_BAND = -0.30f..0.30f
        val NOSE_BAND = 0.14f..0.60f
        val LIPS_BAND = 0.46f..0.95f
    }
}
