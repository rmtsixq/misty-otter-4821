package com.sixq.gozjest.camera.net

import android.content.Context
import android.util.Log
import com.sixq.gozjest.camera.CameraPrefs
import com.sixq.gozjest.core.CameraEvent
import com.sixq.gozjest.core.Command
import com.sixq.gozjest.core.DiscoveryClient
import com.sixq.gozjest.core.NetUtils
import com.sixq.gozjest.core.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "MainClient"

/**
 * Talks to the main device.
 *
 * Finding the other phone is tried four ways, cheapest first, because a
 * hotspot hands out a different subnet on nearly every vendor's firmware:
 * a manually entered address, the default-route gateway (which *is* the
 * hotspot host), a UDP broadcast probe, and finally the `x.y.z.1` guess.
 */
class MainClient(
    private val context: Context,
    private val prefs: CameraPrefs,
) {

    data class Resolved(val host: String, val port: Int) {
        fun url(path: String) = "http://$host:$port$path"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Must outlast the server's long-poll hold, or every poll would abort.
        .readTimeout(Protocol.POLL_HOLD_MS / 1000 + 15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val probeHttp = http.newBuilder()
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cached: Resolved? = null

    /** Drops the cached address so the next call re-runs discovery. */
    fun invalidate() {
        cached = null
    }

    /** Blocking; returns the main device's address or null. */
    fun resolve(): Resolved? {
        cached?.let { return it }

        val port = prefs.port
        val candidates = buildList {
            prefs.manualHost.takeIf { it.isNotBlank() }?.let { add(it) }
            NetUtils.defaultGateway(context)?.let { add(it) }
            DiscoveryClient.probe()?.let { add(it.host) }
            addAll(NetUtils.guessedHotspotHosts())
        }.distinct()

        for (host in candidates) {
            val resolved = Resolved(host, port)
            if (ping(resolved)) {
                Log.i(TAG, "Main device at $host:$port")
                cached = resolved
                return resolved
            }
        }
        return null
    }

    private fun ping(target: Resolved): Boolean = runCatching {
        val request = Request.Builder().url(target.url(Protocol.PATH_PING)).get().build()
        probeHttp.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /**
     * Parks a request on the server until a command arrives or the hold
     * expires. Blocking; throws on any network problem so the caller can mark
     * the link down and re-resolve.
     */
    @Throws(Exception::class)
    fun poll(target: Resolved): Command {
        val request = Request.Builder().url(target.url(Protocol.PATH_POLL)).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("poll HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            return if (body.isBlank()) Command.NONE else Command.fromJson(body)
        }
    }

    /** Blocking upload of one JPEG. */
    fun uploadPhoto(target: Resolved, bytes: ByteArray, commandId: String): Boolean =
        runCatching {
            val request = Request.Builder()
                .url(target.url("${Protocol.PATH_PHOTO}?id=$commandId"))
                .post(bytes.toRequestBody(JPEG_MEDIA_TYPE))
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrElse {
            Log.w(TAG, "Upload failed", it)
            false
        }

    /** Best-effort status report; failures are not worth surfacing. */
    fun reportEvent(target: Resolved, event: CameraEvent) {
        runCatching {
            val request = Request.Builder()
                .url(target.url(Protocol.PATH_EVENT))
                .post(event.toJson().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            http.newCall(request).execute().close()
        }
    }

    private companion object {
        val JPEG_MEDIA_TYPE = Protocol.MIME_JPEG.toMediaType()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
