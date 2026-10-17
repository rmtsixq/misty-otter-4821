package com.sixq.gozjest.camera.cam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "PhotoEncoder"

/**
 * Shrinks a full-resolution capture to something worth sending.
 *
 * Two things matter for the answer quality on the other end: the image must be
 * upright (a sideways photo of a printed page costs the model accuracy) and it
 * must be small enough that twenty of them still fit in one inline request.
 */
object PhotoEncoder {

    fun encode(image: ImageProxy, maxEdge: Int, quality: Int): ByteArray? {
        val raw = image.readJpegBytes() ?: return null
        val rotation = image.imageInfo.rotationDegrees

        // Nothing to do if it is already small and upright.
        var bitmap = decodeDownsampled(raw, maxEdge) ?: return if (rotation == 0) raw else null
        bitmap = scaleToFit(bitmap, maxEdge)
        bitmap = rotate(bitmap, rotation)

        return try {
            ByteArrayOutputStream().use { sink ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, sink)
                sink.toByteArray()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Re-compression failed", t)
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun ImageProxy.readJpegBytes(): ByteArray? = runCatching {
        val buffer = planes[0].buffer
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    }.getOrElse {
        Log.e(TAG, "Could not read capture buffer", it)
        null
    }

    /** Uses `inSampleSize` so the full-size bitmap never reaches the heap. */
    private fun decodeDownsampled(raw: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2

        return runCatching {
            BitmapFactory.decodeByteArray(
                raw, 0, raw.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrElse {
            Log.e(TAG, "Decode failed", it)
            null
        }
    }

    private fun scaleToFit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val factor = maxEdge.toFloat() / longest
        val width = (bitmap.width * factor).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * factor).roundToInt().coerceAtLeast(1)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }.getOrNull() ?: return bitmap
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
