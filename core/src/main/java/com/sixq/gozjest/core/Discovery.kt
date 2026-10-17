package com.sixq.gozjest.core

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

private const val TAG = "Discovery"

/**
 * Runs on the main device: answers UDP probes with its own port so the camera
 * device can find it without the user typing an IP address.
 */
class DiscoveryResponder {

    private var socket: DatagramSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread(name = "discovery-responder", isDaemon = true) {
            try {
                val server = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                    soTimeout = 1_000
                }
                socket = server
                val buffer = ByteArray(256)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        server.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val payload = String(packet.data, packet.offset, packet.length).trim()
                    if (payload != Protocol.DISCOVERY_REQUEST) continue

                    val reply = "${Protocol.DISCOVERY_REPLY_PREFIX}:${Protocol.HTTP_PORT}"
                        .toByteArray()
                    runCatching {
                        server.send(
                            DatagramPacket(reply, reply.size, packet.address, packet.port)
                        )
                    }.onFailure { Log.w(TAG, "Could not answer probe", it) }
                }
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "Responder stopped", t)
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }
}

/** Runs on the camera device: broadcasts a probe and returns whoever answers. */
object DiscoveryClient {

    data class Found(val host: String, val port: Int)

    /**
     * Sends a probe to every reachable broadcast address and waits up to
     * [timeoutMs] for the first valid answer.
     */
    fun probe(timeoutMs: Int = 1_200): Found? {
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(0))
                soTimeout = timeoutMs
            }
        }.getOrElse {
            Log.w(TAG, "Could not open probe socket", it)
            return null
        }

        return try {
            val request = Protocol.DISCOVERY_REQUEST.toByteArray()
            NetUtils.broadcastAddresses().forEach { target ->
                runCatching {
                    socket.send(
                        DatagramPacket(request, request.size, target, Protocol.DISCOVERY_PORT)
                    )
                }
            }

            val buffer = ByteArray(256)
            val deadline = System.currentTimeMillis() + timeoutMs
            var answer: Found? = null
            while (answer == null && System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val payload = String(packet.data, packet.offset, packet.length).trim()
                if (!payload.startsWith(Protocol.DISCOVERY_REPLY_PREFIX)) continue
                val host = packet.address?.hostAddress ?: continue
                val port = payload.substringAfter(':', "").toIntOrNull() ?: Protocol.HTTP_PORT
                answer = Found(host, port)
            }
            answer
        } catch (t: Throwable) {
            Log.w(TAG, "Probe failed", t)
            null
        } finally {
            runCatching { socket.close() }
        }
    }
}
