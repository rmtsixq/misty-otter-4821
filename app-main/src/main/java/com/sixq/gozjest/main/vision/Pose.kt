package com.sixq.gozjest.main.vision

import kotlin.math.sqrt

/**
 * A set of landmarks already converted to pixel coordinates of the upright
 * analysis frame. Keeping x/y in parallel float arrays avoids allocating a
 * few hundred objects on every camera frame.
 */
class Pose(val xs: FloatArray, val ys: FloatArray) {

    val count: Int get() = xs.size

    fun has(index: Int): Boolean = index in 0 until count

    fun dist(a: Int, b: Int): Float = hypot(xs[a] - xs[b], ys[a] - ys[b])

    fun distToPoint(index: Int, px: Float, py: Float): Float =
        hypot(xs[index] - px, ys[index] - py)

    /** Smallest distance from the given point to any landmark in [indices]. */
    fun minDistToPoint(indices: IntArray, px: Float, py: Float): Float {
        var best = Float.MAX_VALUE
        for (index in indices) {
            if (!has(index)) continue
            val d = distToPoint(index, px, py)
            if (d < best) best = d
        }
        return best
    }

    /** Arithmetic mean of the given landmarks, as `[x, y]`. */
    fun centroid(indices: IntArray): FloatArray {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (index in indices) {
            if (!has(index)) continue
            sx += xs[index]
            sy += ys[index]
            n++
        }
        return if (n == 0) floatArrayOf(0f, 0f) else floatArrayOf(sx / n, sy / n)
    }

    companion object {
        fun hypot(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)
    }
}
