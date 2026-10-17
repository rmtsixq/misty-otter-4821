package com.sixq.gozjest.camera

import android.content.Context
import androidx.core.content.edit
import com.sixq.gozjest.core.Protocol

/** Persisted settings for the camera device. */
class CameraPrefs(context: Context) {

    private val store = context.getSharedPreferences("gozjest-camera", Context.MODE_PRIVATE)

    /** Blank means "find the main device automatically". */
    var manualHost: String
        get() = store.getString(KEY_HOST, "")!!
        set(value) = store.edit { putString(KEY_HOST, value.trim()) }

    var port: Int
        get() = store.getInt(KEY_PORT, Protocol.HTTP_PORT)
        set(value) = store.edit { putInt(KEY_PORT, value) }

    /** Longest edge of the uploaded JPEG, in pixels. */
    var maxEdge: Int
        get() = store.getInt(KEY_MAX_EDGE, DEFAULT_MAX_EDGE)
        set(value) = store.edit { putInt(KEY_MAX_EDGE, value) }

    var quality: Int
        get() = store.getInt(KEY_QUALITY, DEFAULT_QUALITY)
        set(value) = store.edit { putInt(KEY_QUALITY, value) }

    /** Keep a copy of every photo in this phone's own cache directory. */
    var keepLocalCopy: Boolean
        get() = store.getBoolean(KEY_LOCAL_COPY, true)
        set(value) = store.edit { putBoolean(KEY_LOCAL_COPY, value) }

    companion object {
        /**
         * 1600 px keeps printed text comfortably readable for Gemini while
         * holding a frame to roughly 300 KB, so a batch of twenty still fits
         * in one inline request.
         */
        const val DEFAULT_MAX_EDGE = 1600
        const val DEFAULT_QUALITY = 82

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_MAX_EDGE = "max_edge"
        private const val KEY_QUALITY = "quality"
        private const val KEY_LOCAL_COPY = "local_copy"
    }
}
