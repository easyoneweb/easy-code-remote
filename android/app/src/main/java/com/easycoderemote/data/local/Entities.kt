package com.easycoderemote.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.util.APP_JSON

/**
 * Decodes [SessionEntity.rawJson] into a [SessionDto] and overlays the derived
 * `status`/`waitingReason` columns (maintained by [com.easycoderemote.service.EventApplier]
 * via `statusComputer`) over the snapshot values. Status-only SSE events never
 * rewrite `rawJson`, so without this overlay the session list/detail would keep
 * showing the stale snapshot status until a full `/sessions` refetch.
 *
 * Returns null when the raw JSON cannot be decoded (blank/unknown shape), so
 * callers can drop the entity gracefully.
 */
fun SessionEntity.toDto(): SessionDto? {
    val decoded = runCatching { APP_JSON.decodeFromString(SessionDto.serializer(), rawJson) }.getOrNull()
        ?: return null
    return decoded.copy(status = status, waitingReason = waitingReason)
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val title: String,
    val agent: String?,
    val modelLabel: String?,
    val directory: String?,
    val status: String,
    val waitingReason: String?,
    val archived: Boolean,
    val lastUpdated: Long,
    val rawJson: String,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val sessionId: String,
    val role: String,
    val seq: Long,
    val rawJson: String,
    /** Server creation time (ms epoch); the authoritative transcript order key. */
    val timeCreated: Long = 0,
    /** Message-local agent/model shown in badges; filled on event/history ingest. */
    val agent: String? = null,
    val providerID: String? = null,
    val modelID: String? = null,
)

@Entity(tableName = "parts")
data class PartEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val sessionId: String,
    val messageId: String,
    val type: String,
    val text: String,
    val tool: String?,
    val state: String?,
    val seq: Long,
    val rawJson: String,
)

/** Pending permission/question payload retained for offline/notification flows. */
@Entity(tableName = "pending_items")
data class PendingItemEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val sessionId: String,
    val kind: String, // "permission" | "question"
    val rawJson: String,
    val receivedAt: Long,
)