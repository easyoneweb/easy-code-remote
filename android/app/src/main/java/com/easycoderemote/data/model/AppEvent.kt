package com.easycoderemote.data.model

import kotlinx.serialization.json.JsonObject

/**
 * Typed app-level view of a server envelope. `cursor` is carried through so the
 * consumer can persist the resume point without keeping the raw envelope.
 */
sealed interface AppEvent {
    val cursor: Long

    data class ServerConnected(val kiloVersion: String?, override val cursor: Long) : AppEvent
    data class ResyncRequired(val reason: String?, override val cursor: Long) : AppEvent
    data class EngineConnected(val kiloVersion: String?, override val cursor: Long) : AppEvent
    data class EngineDisconnected(val reason: String?, override val cursor: Long) : AppEvent

    data class SessionCreated(
        val sessionID: String,
        val session: SessionDto?,
        val data: JsonObject?,
        override val cursor: Long,
    ) : AppEvent

    data class SessionUpdated(
        val sessionID: String,
        val session: SessionDto?,
        val data: JsonObject?,
        override val cursor: Long,
    ) : AppEvent

    data class SessionDeleted(val sessionID: String, override val cursor: Long) : AppEvent
    data class SessionStatus(val sessionID: String, val status: String, override val cursor: Long) : AppEvent
    data class SessionError(val sessionID: String, val message: String?, override val cursor: Long) : AppEvent
    data class SessionIdle(val sessionID: String, override val cursor: Long) : AppEvent

    data class MessageUpdated(
        val sessionID: String,
        val messageID: String,
        val data: JsonObject?,
        override val cursor: Long,
    ) : AppEvent

    data class MessageRemoved(val sessionID: String, val messageID: String, override val cursor: Long) : AppEvent

    /** Append text when deltaText != null, else replace the whole part. */
    data class PartUpdated(
        val sessionID: String,
        val messageID: String?,
        val partID: String,
        val part: PartDto?,
        val deltaText: String?,
        override val cursor: Long,
    ) : AppEvent

    data class PartRemoved(val sessionID: String, val messageID: String?, val partID: String, override val cursor: Long) : AppEvent

    data class PermissionAsked(val sessionID: String, val data: JsonObject?, override val cursor: Long) : AppEvent
    data class PermissionReplied(val sessionID: String, override val cursor: Long) : AppEvent
    data class QuestionAsked(val sessionID: String, val data: JsonObject?, override val cursor: Long) : AppEvent
    data class QuestionReplied(val sessionID: String, override val cursor: Long) : AppEvent
    data class QuestionRejected(val sessionID: String, override val cursor: Long) : AppEvent
}

/** UI-facing events produced by the app itself (never come from the server). */
sealed interface UiEvent {
    data class ReauthRequired(val profileId: String) : UiEvent
    data class CertChanged(val profileId: String, val fingerprint: String?) : UiEvent
    data class SessionGone(val sessionId: String) : UiEvent
    data class OperationResult(val ok: Boolean, val message: String?) : UiEvent
}