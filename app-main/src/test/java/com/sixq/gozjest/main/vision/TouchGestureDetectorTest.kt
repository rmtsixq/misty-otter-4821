package com.sixq.gozjest.main.vision

import com.sixq.gozjest.main.vision.Synthetic.CHIN_Y
import com.sixq.gozjest.main.vision.Synthetic.IOD
import com.sixq.gozjest.main.vision.Synthetic.LIPS_POINT
import com.sixq.gozjest.main.vision.Synthetic.MID_EYE_X
import com.sixq.gozjest.main.vision.Synthetic.NOSE_TIP_POINT
import com.sixq.gozjest.main.vision.Synthetic.RIGHT_EYE_POINT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the gesture detector with synthetic landmarks.
 *
 * Roughly half of these are negative cases, because the thing that makes this
 * mechanism usable in front of an audience is not that it fires, it is that it
 * does not fire when nobody asked it to.
 */
class TouchGestureDetectorTest {

    /** Feeds [frames] frames at 50 ms intervals and returns everything fired. */
    private fun feed(
        detector: TouchGestureDetector = TouchGestureDetector(),
        frames: Int,
        face: (Int) -> Pose? = { Synthetic.face() },
        hands: (Int) -> List<Pose>,
    ): List<TouchTarget> {
        val fired = mutableListOf<TouchTarget>()
        for (i in 0 until frames) {
            val outcome = detector.update(face(i), hands(i), START_MS + i * 50L)
            if (outcome is TouchGestureDetector.Outcome.Fired) fired += outcome.target
        }
        return fired
    }

    private fun pointingAt(x: Float, y: Float) = listOf(Synthetic.hand(x, y, curl = 0.45f))

    private fun openPalmAt(x: Float, y: Float) = listOf(Synthetic.hand(x, y, curl = 1.05f))

    /* ----- the synthetic face itself is sane ---------------------------- */

    @Test
    fun `synthetic face has the geometry the thresholds assume`() {
        val face = Synthetic.face()
        assertEquals(IOD, FaceGeometry.interOcularDistance(face), 1f)
        assertTrue(FaceGeometry.isFrontalEnough(face))
        assertEquals(
            0.40f,
            FaceGeometry.verticalRatio(face, NOSE_TIP_POINT[0], NOSE_TIP_POINT[1]),
            0.03f,
        )
        assertEquals(
            0.66f,
            FaceGeometry.verticalRatio(face, LIPS_POINT[0], LIPS_POINT[1]),
            0.03f,
        )
    }

    @Test
    fun `pointing hand is recognised and an open palm is not`() {
        val pointing = Synthetic.hand(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1], curl = 0.45f)
        val palm = Synthetic.hand(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1], curl = 1.05f)
        assertTrue(
            HandGeometry.extensions(pointing).joinToString(),
            HandGeometry.isPointing(pointing),
        )
        assertTrue(
            HandGeometry.extensions(palm).joinToString(),
            !HandGeometry.isPointing(palm),
        )
    }

    /* ----- the three gestures ------------------------------------------- */

    @Test
    fun `touching the eye fires EYE`() {
        assertEquals(
            listOf(TouchTarget.EYE),
            feed(frames = 12) { pointingAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1]) },
        )
    }

    @Test
    fun `touching the nose tip fires NOSE`() {
        assertEquals(
            listOf(TouchTarget.NOSE),
            feed(frames = 12) { pointingAt(NOSE_TIP_POINT[0], NOSE_TIP_POINT[1]) },
        )
    }

    @Test
    fun `touching the lips fires LIPS`() {
        assertEquals(
            listOf(TouchTarget.LIPS),
            feed(frames = 12) { pointingAt(LIPS_POINT[0], LIPS_POINT[1]) },
        )
    }

    /* ----- everything that must stay quiet ------------------------------ */

    @Test
    fun `an open palm on the eye never fires`() {
        assertEquals(
            emptyList<TouchTarget>(),
            feed(frames = 30) { openPalmAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1]) },
        )
    }

    @Test
    fun `a fingertip between nose and lips abstains instead of guessing`() {
        val between = (NOSE_TIP_POINT[1] + LIPS_POINT[1]) / 2f
        assertEquals(
            emptyList<TouchTarget>(),
            feed(frames = 30) { pointingAt(NOSE_TIP_POINT[0], between) },
        )
    }

    @Test
    fun `a finger away from the face never fires`() {
        assertEquals(emptyList<TouchTarget>(), feed(frames = 30) { pointingAt(420f, 230f) })
    }

    @Test
    fun `no face means no gesture`() {
        assertEquals(
            emptyList<TouchTarget>(),
            feed(
                frames = 30,
                face = { null },
                hands = { pointingAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1]) },
            ),
        )
    }

    @Test
    fun `forehead and chin are outside every band`() {
        assertEquals(
            emptyList<TouchTarget>(),
            feed(frames = 30) { pointingAt(MID_EYE_X, Synthetic.verticalToY(-0.5f)) },
        )
        assertEquals(
            emptyList<TouchTarget>(),
            feed(frames = 30) { pointingAt(MID_EYE_X, CHIN_Y) },
        )
    }

    @Test
    fun `three frames is below the stability threshold`() {
        assertEquals(
            emptyList<TouchTarget>(),
            feed(frames = 3) { pointingAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1]) },
        )
    }

    /* ----- release before re-arm ---------------------------------------- */

    @Test
    fun `holding the finger still fires exactly once`() {
        assertEquals(
            1,
            feed(frames = 80) { pointingAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1]) }.size,
        )
    }

    @Test
    fun `touch, withdraw, touch fires twice`() {
        val fired = feed(frames = 80) { frame ->
            when {
                frame < 12 -> pointingAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1])
                frame < 30 -> pointingAt(460f, 600f)
                else -> pointingAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1])
            }
        }
        assertEquals(listOf(TouchTarget.EYE, TouchTarget.EYE), fired)
    }

    @Test
    fun `sliding from eye to lips without leaving the face fires once`() {
        val fired = feed(frames = 80) { frame ->
            if (frame < 25) pointingAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1])
            else pointingAt(LIPS_POINT[0], LIPS_POINT[1])
        }
        assertEquals(listOf(TouchTarget.EYE), fired)
    }

    /* ----- configuration ------------------------------------------------- */

    @Test
    fun `with strict pointing off an open palm does fire`() {
        val lenient = TouchGestureDetector(Tuning.DEFAULT.copy(strictPointing = false))
        assertEquals(
            listOf(TouchTarget.EYE),
            feed(lenient, frames = 12) { openPalmAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1]) },
        )
    }

    @Test
    fun `thresholds scale with hand size`() {
        // Same gesture with a much smaller hand: every length in the posture
        // test is divided by palm length, so the verdict must not change.
        val fired = feed(frames = 12) {
            listOf(
                Synthetic.hand(
                    RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1],
                    curl = 0.45f, palm = 55f,
                )
            )
        }
        assertEquals(listOf(TouchTarget.EYE), fired)
    }

    @Test
    fun `a profile face is rejected`() {
        // A yawed head projects the nose tip up against one eye; the frontal
        // check compares nose-to-eye distances and bails out.
        val profile = Synthetic.face().also {
            it.xs[1] = Synthetic.RIGHT_EYE_X - 5f
            it.ys[1] = Synthetic.EYE_Y + 20f
        }
        assertEquals(
            emptyList<TouchTarget>(),
            feed(
                frames = 30,
                face = { profile },
                hands = { pointingAt(RIGHT_EYE_POINT[0], RIGHT_EYE_POINT[1]) },
            ),
        )
    }

    private companion object {
        const val START_MS = 10_000L
    }
}
