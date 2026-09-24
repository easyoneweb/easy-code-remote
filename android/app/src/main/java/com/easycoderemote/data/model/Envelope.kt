package com.easycoderemote.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One SSE envelope from GET /api/v1/events. */
@Serializable
data class Envelope(
    val type: String = "",
    val sessionID: String? = null,
    val messageID: String? = null,
    val partID: String? = null,
    val data: JsonObject? = null,
    val ts: Long = 0,
    val cursor: Long = 0,
)