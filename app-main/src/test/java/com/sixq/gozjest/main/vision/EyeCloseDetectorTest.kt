package com.sixq.gozjest.main.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the blackout toggle with synthetic eyelids.
 *
 * Frames arrive every 50 ms, so the detector's one-second arming window is
 * 20 frames and the five-second hold is another 100.
 */
class EyeCloseDetectorTest {

    /** Returns how many times the screen would have flipped. */
    private fun feed(
        detector: EyeCloseDetector = EyeCloseDetector(),
        frames: Int,
        face: (Int) -> Pose? = { Synthetic.face() },
        blink: (Int) -> Float,
    ): Int {
        var toggles = 0
        for (i in 0 until frames) {
            val outcome = detector.update(face(i), blink(i), START_MS + i * 50L)
            if (outcome is EyeCloseDetector.Outcome.Toggle) toggles++
        }
        return toggles
    }

    private val openEyes = Synthetic.face(eyeOpening = 8f)
    private val shutEyes = Synthetic.face(eyeOpening = 1f)

    @Test
    fun `eye aspect ratio separates open from shut`() {
        assertTrue(FaceGeometry.maxEyeAspectRatio(openEyes) > Tuning.DEFAULT.eyeOpenEar)
        assertTrue(FaceGeometry.maxEyeAspectRatio(shutEyes) < Tuning.DEFAULT.eyeClosedEar)
    }

    @Test
    fun `five seconds shut flips the screen once`() {
        assertEquals(1, feed(frames = 160) { if (it < ARMING) 0f else 0.9f })
    }

    @Test
    fun `the aspect-ratio fallback works when blendshapes are missing`() {
        assertEquals(
            1,
            feed(
                frames = 160,
                face = { if (it < ARMING) openEyes else shutEyes },
                blink = { Float.NaN },
            ),
        )
    }

    @Test
    fun `ordinary blinking never flips the screen`() {
        // 300 ms shut every 3 s, repeatedly.
        assertEquals(0, feed(frames = 200) { if ((it / 6) % 10 == 0) 0.9f else 0f })
    }

    @Test
    fun `a wink never flips the screen`() {
        // Only the subject's right eye closes; the detector reads the more
        // open of the two.
        val winking = Synthetic.face(eyeOpening = 8f, rightEyeOpening = 1f)
        assertEquals(
            0,
            feed(
                frames = 200,
                face = { if (it < ARMING) openEyes else winking },
                blink = { Float.NaN },
            ),
        )
    }

    @Test
    fun `an eyelid flickering on the threshold cannot creep to five seconds`() {
        assertEquals(
            0,
            feed(frames = 260) {
                when {
                    it < ARMING -> 0f
                    it % 2 == 0 -> 0.9f
                    else -> 0.1f
                }
            },
        )
    }

    @Test
    fun `staying shut past the hold still only flips once`() {
        assertEquals(1, feed(frames = 300) { if (it < ARMING) 0f else 0.9f })
    }

    @Test
    fun `shut, open, shut again flips twice`() {
        val toggles = feed(frames = 360) {
            when {
                it < ARMING -> 0f      // arming
                it < 134 -> 0.9f       // 5.5 s shut -> flip to black
                it < 174 -> 0f         // 2 s open   -> re-arm
                else -> 0.9f           // 5+ s shut  -> flip back
            }
        }
        assertEquals(2, toggles)
    }

    @Test
    fun `losing the face freezes the timer instead of advancing it`() {
        // Covering the lens must not be a shortcut to the blackout.
        assertEquals(
            0,
            feed(
                frames = 300,
                face = { if (it in ARMING..280) null else Synthetic.face() },
                blink = { if (it < ARMING) 0f else 0.9f },
            ),
        )
    }

    @Test
    fun `eyes already shut at startup wait for the arming window`() {
        assertEquals(0, feed(frames = 110) { 0.9f })
    }

    @Test
    fun `the hold duration is configurable`() {
        assertEquals(
            1,
            feed(
                EyeCloseDetector(Tuning.DEFAULT.copy(blackoutHoldMs = 3_000L)),
                frames = 110,
                blink = { if (it < ARMING) 0f else 0.9f },
            ),
        )
    }

    private companion object {
        const val START_MS = 10_000L

        /** Frames of the one-second arming window at 50 ms each, plus slack. */
        const val ARMING = 24
    }
}
