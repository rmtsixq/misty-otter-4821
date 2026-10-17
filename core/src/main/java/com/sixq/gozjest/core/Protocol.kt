package com.sixq.gozjest.core

/**
 * Wire contract between the main (display) device and the camera device.
 *
 * The main device is normally the Wi-Fi hotspot, so it acts as the server and
 * the camera device is the client. The camera device holds a long-poll request
 * open; the moment the main device queues a command the response is written,
 * which keeps the gesture-to-shutter latency down to the network round trip.
 */
object Protocol {

    /** TCP port of the command/upload server on the main device. */
    const val HTTP_PORT = 8787

    /** UDP port the main device answers discovery probes on. */
    const val DISCOVERY_PORT = 8788

    const val DISCOVERY_REQUEST = "GOZJEST_DISCOVER_V1"
    const val DISCOVERY_REPLY_PREFIX = "GOZJEST_MAIN_V1"

    const val PATH_PING = "/ping"
    const val PATH_POLL = "/poll"
    const val PATH_PHOTO = "/photo"
    const val PATH_EVENT = "/event"

    /** No command pending; the camera device should poll again immediately. */
    const val CMD_NONE = "NONE"

    /** Take one photo with the rear camera and upload it. */
    const val CMD_CAPTURE = "CAPTURE"

    /** Drop every locally cached photo (the main device started a new batch). */
    const val CMD_RESET = "RESET"

    /** How long the server holds a poll open before answering [CMD_NONE]. */
    const val POLL_HOLD_MS = 25_000L

    /** Clients are considered offline after this long without a poll. */
    const val CLIENT_TIMEOUT_MS = 12_000L

    const val MIME_JPEG = "image/jpeg"
}
