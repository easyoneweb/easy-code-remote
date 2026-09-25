package com.easycoderemote.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

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