package com.sixq.gozjest.main.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short vibration patterns.
 *
 * While the screen is blacked out these are the *only* feedback the user gets,
 * so each event has its own recognisable rhythm.
 */
class Haptics(context: Context) {

    var enabled: Boolean = true

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** One short tap: a photo was requested. */
    fun capture() = play(longArrayOf(0, 35))

    /** Two taps: the batch went to Gemini. */
    fun asking() = play(longArrayOf(0, 30, 70, 30))

    /** Three quick taps: the answer is on screen. */
    fun success() = play(longArrayOf(0, 25, 60, 25, 60, 25))

    /** One long buzz: something went wrong. */
    fun error() = play(longArrayOf(0, 220))

    /** Soft tick: the screen flipped. */
    fun toggle() = play(longArrayOf(0, 20))

    private fun play(pattern: LongArray) {
        if (!enabled) return
        val device = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            device.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
