package com.sixq.gozjest.main.gemini

import android.util.Base64
import android.util.Log
import com.sixq.gozjest.core.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiClient"
private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"

/** Sends the cached photo batch to Gemini and returns the answer text. */
class GeminiClient {

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Failure(val message: String) : Result
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Blocking; call from a background dispatcher. */
    fun ask(
        apiKey: String,
        model: String,
        prompt: String,
        photos: List<File>,
    ): Result {
        if (apiKey.isBlank()) {
            return Result.Failure("Gemini API anahtarı girilmemiş (Ayarlar → API anahtarı).")
        }
        if (photos.isEmpty()) {
            return Result.Failure("Önbellekte fotoğraf yok.")
        }

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        var totalBytes = 0L
        for (photo in photos) {
            val bytes = runCatching { photo.readBytes() }.getOrNull() ?: continue
            if (bytes.isEmpty()) continue
            totalBytes += bytes.size
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", Protocol.MIME_JPEG)
                        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                )
            )
        }
        if (totalBytes == 0L) return Result.Failure("Fotoğraflar okunamadı.")
        if (totalBytes > MAX_INLINE_BYTES) {
            return Result.Failure(
                "Toplu boyut çok büyük (${totalBytes / (1024 * 1024)} MB). " +
                    "Daha az fotoğraf çekin veya çözünürlüğü düşürün."
            )
        }

        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 2048)
            )
            .toString()

        val request = Request.Builder()
            .url("$ENDPOINT/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.Failure(describeHttpError(response.code, body))
                } else {
                    parseAnswer(body)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Gemini call failed", t)
            Result.Failure("Ağ hatası: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun parseAnswer(body: String): Result = runCatching {
        val root = JSONObject(body)

        root.optJSONObject("promptFeedback")?.optString("blockReason")?.let { reason ->
            if (reason.isNotBlank()) {
                return Result.Failure("İstek Gemini tarafından engellendi ($reason).")
            }
        }

        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            return Result.Failure("Gemini boş yanıt döndü.")
        }

        val candidate = candidates.getJSONObject(0)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            for (i in 0 until (parts?.length() ?: 0)) {
                parts?.optJSONObject(i)?.optString("text")?.let { chunk ->
                    if (chunk.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(chunk)
                    }
                }
            }
        }

        if (text.isBlank()) {
            val finishReason = candidate.optString("finishReason", "")
            Result.Failure(
                if (finishReason.isBlank()) "Gemini metin döndürmedi."
                else "Gemini yanıtı kesildi ($finishReason)."
            )
        } else {
            Result.Ok(text.trim())
        }
    }.getOrElse {
        Log.e(TAG, "Could not parse response: ${body.take(400)}", it)
        Result.Failure("Yanıt ayrıştırılamadı.")
    }

    private fun describeHttpError(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val hint = when (code) {
            400 -> "İstek geçersiz. Model adını kontrol edin."
            401, 403 -> "API anahtarı reddedildi. Anahtarı ve Generative Language API iznini kontrol edin."
            404 -> "Model bulunamadı. Ayarlardaki model adını kontrol edin."
            429 -> "Kota aşıldı, biraz bekleyip tekrar deneyin."
            in 500..599 -> "Gemini sunucu hatası, tekrar deneyin."
            else -> "HTTP $code"
        }
        return if (detail.isBlank()) hint else "$hint ($detail)"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Inline payloads must stay well under the request ceiling. */
        const val MAX_INLINE_BYTES = 18L * 1024 * 1024
    }
}
