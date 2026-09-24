package com.easycoderemote.service

import com.easycoderemote.data.model.AppEvent
import com.easycoderemote.data.model.Envelope
import com.easycoderemote.data.model.PartDto
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.util.APP_JSON
import com.easycoderemote.util.jsonObject
import com.easycoderemote.util.str
import com.easycoderemote.util.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Pure envelope → [AppEvent] mapping. No Android dependencies so it is fully
 * unit-testable with the real envelope shapes from docs/protocol.md.
 */
object EventParser {

    fun parse(envelope: Envelope): AppEvent? {
        val d = envelope.data
        val sid = envelope.sessionID ?: d?.string("sessionID").orEmpty()
        val mid = envelope.messageID ?: d?.string("messageID").orEmpty()
        return when (envelope.type) {
            "server.connected" -> AppEvent.ServerConnected(d?.string("kiloVersion"), envelope.cursor)
            "resync.required" -> AppEvent.ResyncRequired(d?.string("reason"), envelope.cursor)
            "engine.connected" -> AppEvent.EngineConnected(d?.string("kiloVersion"), envelope.cursor)
            "engine.disconnected" -> AppEvent.EngineDisconnected(d?.string("reason"), envelope.cursor)

            "session.created" -> AppEvent.SessionCreated(sid, sessionFrom(d), d, envelope.cursor)
            "session.updated" -> AppEvent.SessionUpdated(sid, sessionFrom(d), d, envelope.cursor)
            "session.deleted" -> AppEvent.SessionDeleted(sid, envelope.cursor)
            "session.status" -> AppEvent.SessionStatus(sid, statusFrom(d), envelope.cursor)
            "session.error" -> AppEvent.SessionError(
                sid, d?.string("error") ?: d?.string("message"), envelope.cursor,
            )
            "session.idle" -> AppEvent.SessionIdle(sid, envelope.cursor)

            "message.updated" -> AppEvent.MessageUpdated(sid, mid, d, envelope.cursor)
            "message.removed" -> AppEvent.MessageRemoved(sid, mid, envelope.cursor)

            "message.part.updated", "message.part.delta" -> {
                val partObject = d?.jsonObject("part")
                val part = partObject?.let {
                    runCatching { APP_JSON.decodeFromJsonElement(PartDto.serializer(), it) }.getOrNull()
                }
                val delta = d?.jsonObject("delta")?.string("textDelta")
                    ?: partObject?.string("text")?.takeIf { part?.type == "text" }
                val partId = envelope.partID ?: d?.string("partID").orEmpty()
                if (partId.isBlank()) null else AppEvent.PartUpdated(
                    sessionID = sid,
                    messageID = mid,
                    partID = partId,
                    part = part,
                    deltaText = delta,
                    cursor = envelope.cursor,
                )
            }

            "message.part.removed" -> AppEvent.PartRemoved(
                sid, mid, envelope.partID ?: d?.string("partID").orEmpty(), envelope.cursor,
            )

            "permission.asked" -> AppEvent.PermissionAsked(sid, d, envelope.cursor)
            "permission.replied" -> AppEvent.PermissionReplied(sid, envelope.cursor)
            "question.asked" -> AppEvent.QuestionAsked(sid, d, envelope.cursor)
            "question.replied" -> AppEvent.QuestionReplied(sid, envelope.cursor)
            "question.rejected" -> AppEvent.QuestionRejected(sid, envelope.cursor)

            else -> null
        }
    }

    private fun statusFrom(d: JsonObject?): String {
        val st = d?.jsonObject("status") ?: return d?.str("type").orEmpty()
        return st.str("type")
    }

    private fun sessionFrom(d: JsonObject?): SessionDto? {
        val info = d?.jsonObject("info") ?: d ?: return null
        return runCatching { APP_JSON.decodeFromJsonElement(SessionDto.serializer(), info) }.getOrNull()
    }
}