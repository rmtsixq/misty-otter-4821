package com.sixq.gozjest.main.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

private const val TAG = "PhotoCache"

/**
 * Holds the photos taken since the last question, on disk so a few megabytes
 * of JPEG never sit in the heap.
 *
 * Every eye/nose touch appends one frame; the lip touch hands the whole batch
 * to Gemini and then (by default) empties it again.
 */
class PhotoCache(context: Context, private val maxPhotos: Int = DEFAULT_MAX) {

    private val directory = File(context.cacheDir, "batch").apply { mkdirs() }
    private val lock = Any()
    private var sequence = 0

    init {
        clear()
    }

    /** Appends one JPEG. Returns the batch size afterwards. */
    fun add(bytes: ByteArray): Int = synchronized(lock) {
        val file = File(directory, "%04d.jpg".format(sequence++))
        runCatching { file.writeBytes(bytes) }
            .onFailure { Log.e(TAG, "Could not cache photo", it) }
        trimLocked()
        filesLocked().size
    }

    fun count(): Int = synchronized(lock) { filesLocked().size }

    /** The current batch, oldest first. */
    fun snapshot(): List<File> = synchronized(lock) { filesLocked() }

    fun clear() = synchronized(lock) {
        filesLocked().forEach { runCatching { it.delete() } }
        sequence = 0
    }

    /** Small preview of the newest photo for the main device's status card. */
    fun latestThumbnail(maxEdge: Int = 240): Bitmap? = synchronized(lock) {
        val newest = filesLocked().lastOrNull() ?: return null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(newest.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var scale = 1
            while (longest / (scale * 2) >= maxEdge) scale *= 2
            BitmapFactory.decodeFile(
                newest.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = scale },
            )
        }.getOrNull()
    }

    private fun filesLocked(): List<File> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Keeps the batch bounded; Gemini rejects oversized inline payloads. */
    private fun trimLocked() {
        val files = filesLocked()
        if (files.size <= maxPhotos) return
        files.take(files.size - maxPhotos).forEach { runCatching { it.delete() } }
    }

    companion object {
        const val DEFAULT_MAX = 20
    }
}
