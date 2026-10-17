package com.sixq.gozjest.main

import android.content.Context
import androidx.core.content.edit
import com.sixq.gozjest.main.vision.Tuning

/** Persisted settings for the main device. */
class Prefs(context: Context) {

    private val store = context.getSharedPreferences("gozjest-main", Context.MODE_PRIVATE)

    var apiKey: String
        get() = store.getString(KEY_API, null) ?: BuildConfig.GEMINI_API_KEY
        set(value) = store.edit { putString(KEY_API, value.trim()) }

    var model: String
        get() = store.getString(KEY_MODEL, DEFAULT_MODEL)!!.ifBlank { DEFAULT_MODEL }
        set(value) = store.edit { putString(KEY_MODEL, value.trim()) }

    var prompt: String
        get() = store.getString(KEY_PROMPT, DEFAULT_PROMPT)!!.ifBlank { DEFAULT_PROMPT }
        set(value) = store.edit { putString(KEY_PROMPT, value) }

    var touchRadius: Float
        get() = store.getFloat(KEY_TOUCH_RADIUS, Tuning.DEFAULT.touchRadius)
        set(value) = store.edit { putFloat(KEY_TOUCH_RADIUS, value) }

    var stabilityFrames: Int
        get() = store.getInt(KEY_STABILITY, Tuning.DEFAULT.stabilityFrames)
        set(value) = store.edit { putInt(KEY_STABILITY, value) }

    var blackoutHoldMs: Long
        get() = store.getLong(KEY_HOLD, Tuning.DEFAULT.blackoutHoldMs)
        set(value) = store.edit { putLong(KEY_HOLD, value) }

    var strictPointing: Boolean
        get() = store.getBoolean(KEY_STRICT, Tuning.DEFAULT.strictPointing)
        set(value) = store.edit { putBoolean(KEY_STRICT, value) }

    var hapticFeedback: Boolean
        get() = store.getBoolean(KEY_HAPTIC, Tuning.DEFAULT.hapticFeedback)
        set(value) = store.edit { putBoolean(KEY_HAPTIC, value) }

    var gesturesWhileBlack: Boolean
        get() = store.getBoolean(KEY_GESTURES_DARK, Tuning.DEFAULT.gesturesWhileBlack)
        set(value) = store.edit { putBoolean(KEY_GESTURES_DARK, value) }

    var clearAfterAnswer: Boolean
        get() = store.getBoolean(KEY_CLEAR_AFTER, true)
        set(value) = store.edit { putBoolean(KEY_CLEAR_AFTER, value) }

    var showDebug: Boolean
        get() = store.getBoolean(KEY_DEBUG, false)
        set(value) = store.edit { putBoolean(KEY_DEBUG, value) }

    /** Builds the detector configuration from the stored values. */
    fun tuning(): Tuning = Tuning.DEFAULT.copy(
        touchRadius = touchRadius,
        stabilityFrames = stabilityFrames,
        blackoutHoldMs = blackoutHoldMs,
        strictPointing = strictPointing,
        hapticFeedback = hapticFeedback,
        gesturesWhileBlack = gesturesWhileBlack,
    )

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        val DEFAULT_PROMPT = """
            Bu fotoğraflardaki soruları incele ve çöz.
            Her soru için önce doğru cevabı net bir şekilde yaz, sonra en fazla
            iki cümleyle kısa gerekçesini ekle. Çoktan seçmeli sorularda şıkkın
            harfini de belirt. Fotoğraflarda soru yoksa ne gördüğünü kısaca açıkla.
            Türkçe ve sade bir dille yanıt ver.
        """.trimIndent()

        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_PROMPT = "prompt"
        private const val KEY_TOUCH_RADIUS = "touch_radius"
        private const val KEY_STABILITY = "stability_frames"
        private const val KEY_HOLD = "blackout_hold_ms"
        private const val KEY_STRICT = "strict_pointing"
        private const val KEY_HAPTIC = "haptic"
        private const val KEY_GESTURES_DARK = "gestures_dark"
        private const val KEY_CLEAR_AFTER = "clear_after_answer"
        private const val KEY_DEBUG = "show_debug"
    }
}
