package com.sixq.gozjest.main.net

import android.util.Log
import com.sixq.gozjest.core.CameraEvent
import com.sixq.gozjest.core.Command
import com.sixq.gozjest.core.Protocol
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "MainServer"

/**
 * Command and upload server that runs on the main device.
 *
 * The camera device keeps one request parked on [Protocol.PATH_POLL]; as soon
 * as a gesture queues a command the parked response is written, so the shutter
 * fires a network round trip after the fingertip lands rather than after the
 * next poll interval.
 */
class MainServer(
    port: Int = Protocol.HTTP_PORT,
    private val callbacks: Callbacks,
) : NanoHTTPD("0.0.0.0", port) {

    interface Callbacks {
        fun onPhotoReceived(bytes: ByteArray, commandId: String)
        fun onClientEvent(event: CameraEvent)
        fun onClientStateChanged(online: Boolean)
    }

    private val commands = LinkedBlockingQueue<Command>()

    @Volatile
    private var lastPollAt = 0L

    @Volatile
    private var reportedOnline = false

    /** True while the camera device has polled recently. */
    val clientOnline: Boolean
        get() = lastPollAt != 0L &&
            System.currentTimeMillis() - lastPollAt < Protocol.CLIENT_TIMEOUT_MS

    /**
     * Queues one command for the camera device.
     *
     * @return false when no camera device is connected, so the caller can say
     *   so instead of silently banking commands that would all fire at once on
     *   reconnect.
     */
    fun enqueue(type: String): Boolean {
        if (!clientOnline) {
            refreshClientState()
            return false
        }
        commands.offer(Command(id = UUID.randomUUID().toString(), type = type))
        return true
    }

    fun clearPending() = commands.clear()

    /** Recomputes the online flag and notifies on a change. */
    fun refreshClientState() {
        val online = clientOnline
        if (online != reportedOnline) {
            reportedOnline = online
            callbacks.onClientStateChanged(online)
        }
    }

    override fun serve(session: IHTTPSession): Response = try {
        when (session.uri) {
            Protocol.PATH_PING -> servePing()
            Protocol.PATH_POLL -> servePoll()
            Protocol.PATH_PHOTO -> servePhoto(session)
            Protocol.PATH_EVENT -> serveEvent(session)
            else -> json(Response.Status.NOT_FOUND, """{"error":"unknown path"}""")
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Request failed: ${session.uri}", t)
        json(Response.Status.INTERNAL_ERROR, """{"error":"${t.message.orEmpty()}"}""")
    }

    private fun servePing(): Response {
        touchClient()
        return json(Response.Status.OK, """{"ok":true,"role":"main"}""")
    }

    private fun servePoll(): Response {
        touchClient()
        val command = commands.poll(Protocol.POLL_HOLD_MS, TimeUnit.MILLISECONDS)
        return json(Response.Status.OK, (command ?: Command.NONE).toJson())
    }

    private fun servePhoto(session: IHTTPSession): Response {
        touchClient()
        val commandId = session.parameters["id"]?.firstOrNull().orEmpty()
        val bytes = session.readBody()
        if (bytes.isEmpty()) {
            return json(Response.Status.BAD_REQUEST, """{"error":"empty body"}""")
        }
        callbacks.onPhotoReceived(bytes, commandId)
        return json(Response.Status.OK, """{"ok":true,"bytes":${bytes.size}}""")
    }

    private fun serveEvent(session: IHTTPSession): Response {
        touchClient()
        val raw = String(session.readBody(), Charsets.UTF_8)
        if (raw.isNotBlank()) {
            runCatching { CameraEvent.fromJson(raw) }
                .onSuccess { callbacks.onClientEvent(it) }
                .onFailure { Log.w(TAG, "Bad event payload", it) }
        }
        return json(Response.Status.OK, """{"ok":true}""")
    }

    private fun touchClient() {
        lastPollAt = System.currentTimeMillis()
        if (!reportedOnline) {
            reportedOnline = true
            callbacks.onClientStateChanged(true)
        }
    }

    /**
     * Reads the raw request body.
     *
     * NanoHTTPD's own `parseBody` decodes non-form bodies as UTF-8 text, which
     * would corrupt a JPEG, so the stream is drained by hand. By the time
     * `serve` runs the stream is positioned exactly at the first body byte.
     */
    private fun IHTTPSession.readBody(): ByteArray {
        val declared = headers["content-length"]?.toIntOrNull() ?: -1
        val stream = inputStream
        if (declared > 0) {
            val buffer = ByteArray(declared)
            var read = 0
            while (read < declared) {
                val count = stream.read(buffer, read, declared - read)
                if (count <= 0) break
                read += count
            }
            return if (read == declared) buffer else buffer.copyOf(read)
        }
        if (declared == 0) return ByteArray(0)

        // No Content-Length: read until the client stops sending.
        val sink = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(chunk)
            if (count <= 0) break
            sink.write(chunk, 0, count)
            if (sink.size() > MAX_BODY_BYTES) break
        }
        return sink.toByteArray()
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }

    private companion object {
        const val MAX_BODY_BYTES = 24 * 1024 * 1024
    }
}
