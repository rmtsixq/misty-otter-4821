package com.sixq.gozjest.core

import org.json.JSONObject

/** A single instruction travelling from the main device to the camera device. */
data class Command(
    val id: String,
    val type: String,
) {
    fun toJson(): String = JSONObject()
        .put("id", id)
        .put("cmd", type)
        .toString()

    companion object {
        val NONE = Command(id = "", type = Protocol.CMD_NONE)

        fun fromJson(raw: String): Command {
            val obj = JSONObject(raw)
            return Command(
                id = obj.optString("id", ""),
                type = obj.optString("cmd", Protocol.CMD_NONE),
            )
        }
    }
}

/** Status line the camera device reports back so the main device can show it. */
data class CameraEvent(
    val kind: String,
    val message: String,
) {
    fun toJson(): String = JSONObject()
        .put("kind", kind)
        .put("message", message)
        .toString()

    companion object {
        const val KIND_ERROR = "error"
        const val KIND_INFO = "info"

        fun fromJson(raw: String): CameraEvent {
            val obj = JSONObject(raw)
            return CameraEvent(
                kind = obj.optString("kind", KIND_INFO),
                message = obj.optString("message", ""),
            )
        }
    }
}
