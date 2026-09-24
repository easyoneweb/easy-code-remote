package com.easycoderemote.service

import com.easycoderemote.data.local.MessageDao
import com.easycoderemote.data.local.MessageEntity
import com.easycoderemote.data.local.PartDao
import com.easycoderemote.data.local.PartEntity
import com.easycoderemote.data.local.PendingItemDao
import com.easycoderemote.data.local.PendingItemEntity
import com.easycoderemote.data.local.SessionDao
import com.easycoderemote.data.local.SessionEntity
import com.easycoderemote.data.model.AppEvent
import com.easycoderemote.data.model.PartDto
import com.easycoderemote.data.model.PendingDto
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.model.permissionId
import com.easycoderemote.data.model.questionId
import com.easycoderemote.util.APP_JSON
import com.easycoderemote.util.jsonObject
import com.easycoderemote.util.str
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Applies app events to Room for one profile. Append/replace semantics for text
 * parts follow plan §5.6: delta → append, full part → replace.
 */
class EventApplier(
    private val profileId: String,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val partDao: PartDao,
    private val pendingItemDao: PendingItemDao,
) {
    private val statusComputer = SessionStatusComputer()

    suspend fun apply(event: AppEvent) {
        when (event) {
            is AppEvent.SessionCreated, is AppEvent.SessionUpdated -> upsertSession(event.sessionID, event.session, event.data)
            is AppEvent.SessionDeleted -> deleteSession(event.sessionID)
            is AppEvent.SessionStatus -> {
                statusComputer.onRawStatus(event.sessionID, event.status)
                refreshSessionStatus(event.sessionID)
            }
            is AppEvent.MessageUpdated -> upsertMessage(event.sessionID, event.messageID, event.data)
            is AppEvent.MessageRemoved -> deleteMessage(event.sessionID, event.messageID)
            is AppEvent.PartUpdated -> upsertPart(event)
            is AppEvent.PartRemoved -> deletePart(event.sessionID, event.messageID, event.partID)
            is AppEvent.PermissionAsked -> {
                statusComputer.onPermissionAsked(event.sessionID)
                persistPending("permission", event.sessionID, event.data)
                refreshSessionStatus(event.sessionID)
            }
            is AppEvent.PermissionReplied -> {
                statusComputer.onPermissionReplied(event.sessionID)
                clearPending("permission", event.sessionID)
                refreshSessionStatus(event.sessionID)
            }
            is AppEvent.QuestionAsked -> {
                statusComputer.onQuestionAsked(event.sessionID)
                persistPending("question", event.sessionID, event.data)
                refreshSessionStatus(event.sessionID)
            }
            is AppEvent.QuestionReplied, is AppEvent.QuestionRejected -> {
                statusComputer.onQuestionReplied(event.sessionID)
                clearPending("question", event.sessionID)
                refreshSessionStatus(event.sessionID)
            }
            is AppEvent.ServerConnected, is AppEvent.ResyncRequired,
            is AppEvent.EngineConnected, is AppEvent.EngineDisconnected,
            is AppEvent.SessionError, is AppEvent.SessionIdle -> Unit
        }
    }

    /** Full resync: replace the session set from a fresh `/sessions` fetch. */
    suspend fun replaceAllSessions(sessions: List<SessionDto>) {
        statusComputer.reset()
        val known = sessionDao.ids(profileId)
        val incoming = sessions.map { it.id }.toSet()
        val toDelete = known.filterNot { it in incoming }
        if (toDelete.isNotEmpty()) sessionDao.deleteAll(profileId, toDelete)
        sessions.forEach { dto ->
            statusComputer.onSessionSnapshot(dto.id, dto.status, dto.waitingReason)
            upsertSession(dto.id, dto, null)
        }
        // Retention: keep at most 50 archived sessions per profile.
        sessionDao.archivedBeyond(profileId).forEach { staleArchivedId ->
            deleteSession(staleArchivedId)
        }
    }

    /** Applies a `/pending` fetch for one session (approval screen / resync). */
    suspend fun applyPending(sessionId: String, pending: PendingDto) {
        statusComputer.onPendingList(sessionId, pending)
        pendingItemDao.clearSession(profileId, sessionId)
        pending.permissions.forEach {
            val obj = it as? JsonObject ?: return@forEach
            persistPending("permission", sessionId, obj)
        }
        pending.questions.forEach {
            val obj = it as? JsonObject ?: return@forEach
            persistPending("question", sessionId, obj)
        }
        refreshSessionStatus(sessionId)
    }

    // -- session bookkeeping --

    private suspend fun upsertSession(sessionID: String, dto: SessionDto?, raw: JsonObject?) {
        val safe = dto ?: raw?.let { runCatching { APP_JSON.decodeFromJsonElement(SessionDto.serializer(), it) }.getOrNull() }
            ?: return
        val (status, reason) = statusComputer.status(sessionID, safe.status)
        sessionDao.upsert(
            SessionEntity(
                id = safe.id,
                profileId = profileId,
                title = safe.title.ifBlank { safe.slug ?: safe.id },
                agent = safe.agent,
                modelLabel = safe.modelLabel().ifBlank { null },
                directory = safe.directory ?: safe.path,
                status = status,
                waitingReason = reason,
                archived = safe.isArchived,
                lastUpdated = safe.lastUpdatedMillis.takeIf { it > 0 } ?: System.currentTimeMillis(),
                rawJson = APP_JSON.encodeToString(SessionDto.serializer(), safe),
            ),
        )
    }

    private suspend fun deleteSession(sessionId: String) {
        sessionDao.delete(profileId, sessionId)
        messageDao.clearSession(profileId, sessionId)
        partDao.clearSession(profileId, sessionId)
        pendingItemDao.clearSession(profileId, sessionId)
    }

    private suspend fun refreshSessionStatus(sessionId: String) {
        val existing = sessionDao.observeSession(profileId, sessionId).first() ?: return
        // Only override the stored status when this applier has authoritative
        // state (raw status events or pending flags) for the session; a fresh
        // applier on the approval-screen path must not flip running→idle.
        if (!statusComputer.knows(sessionId)) return
        val (status, reason) = statusComputer.status(sessionId)
        sessionDao.upsert(existing.copy(status = status, waitingReason = reason))
    }

    // -- messages & parts --

    private suspend fun upsertMessage(sessionId: String, messageId: String, data: JsonObject?) {
        if (messageId.isBlank()) return
        val existing = messageDao.observeMessage(profileId, sessionId, messageId).first()
        val seq = existing?.seq ?: ((messageDao.maxSeq(profileId, sessionId) ?: 0L) + 1)
        val role = data?.jsonObject("info")?.str("role") ?: data?.str("role") ?: "assistant"
        messageDao.upsert(
            MessageEntity(
                id = messageId,
                profileId = profileId,
                sessionId = sessionId,
                role = role,
                seq = seq,
                rawJson = data?.toString() ?: "",
            ),
        )
    }

    private suspend fun deleteMessage(sessionId: String, messageId: String) {
        if (messageId.isBlank()) return
        messageDao.delete(profileId, sessionId, messageId)
        partDao.clearMessage(profileId, sessionId, messageId)
    }

    private suspend fun upsertPart(event: AppEvent.PartUpdated) {
        if (event.partID.isBlank()) return
        val existing = partDao.observePart(profileId, event.sessionID, event.messageID.orEmpty(), event.partID).first()
        val base = existing ?: PartEntity(
            id = event.partID,
            profileId = profileId,
            sessionId = event.sessionID,
            messageId = event.messageID.orEmpty(),
            type = event.part?.type ?: "text",
            text = "",
            tool = event.part?.tool ?: event.part?.name,
            state = event.part?.stateLabel,
            seq = (partDao.maxSeq(profileId, event.sessionID, event.messageID.orEmpty()) ?: 0L) + 1,
            rawJson = "",
        )
        val updated = if (event.deltaText != null) {
            base.copy(text = base.text + event.deltaText, type = "text", rawJson = "")
        } else if (event.part != null) {
            replaceFromPart(base, event.part)
        } else {
            base
        }
        partDao.upsert(updated)
    }

    private fun replaceFromPart(base: PartEntity, part: PartDto): PartEntity = base.copy(
        type = part.type.ifBlank { base.type },
        text = part.textValue,
        tool = part.tool ?: part.name ?: base.tool,
        state = part.stateLabel.ifBlank { base.state },
        rawJson = "",
    )

    private suspend fun deletePart(sessionId: String, messageId: String?, partId: String) {
        if (partId.isBlank()) return
        partDao.delete(profileId, sessionId, messageId.orEmpty(), partId)
    }

    /** History fetch: store messages and their embedded parts in server order. */
    suspend fun storeHistory(sessionId: String, messages: List<com.easycoderemote.data.model.SessionMessageDto>) {
        var seq = messageDao.maxSeq(profileId, sessionId) ?: 0L
        for (m in messages) {
            seq += 1
            messageDao.upsert(
                MessageEntity(
                    id = m.info.id,
                    profileId = profileId,
                    sessionId = sessionId,
                    role = m.info.roleLabel,
                    seq = seq,
                    rawJson = m.info.toString(),
                ),
            )
            var pseq = 0L
            for (p in m.parts) {
                pseq += 1
                partDao.upsert(
                    PartEntity(
                        id = p.id,
                        profileId = profileId,
                        sessionId = sessionId,
                        messageId = m.info.id,
                        type = p.type.ifBlank { "text" },
                        text = p.textValue,
                        tool = p.tool ?: p.name,
                        state = p.stateLabel,
                        seq = pseq,
                        rawJson = p.toString(),
                    ),
                )
            }
        }
        // Retention: keep the last 200 messages per session.
        messageDao.trimSession(profileId, sessionId)
    }

    // -- pending payload retention --

    private suspend fun persistPending(kind: String, sessionId: String, data: JsonObject?) {
        if (data == null) return
        val id = if (kind == "permission") data.permissionId() else data.questionId()
        if (id.isBlank()) return
        pendingItemDao.upsert(
            PendingItemEntity(
                id = id,
                profileId = profileId,
                sessionId = sessionId,
                kind = kind,
                rawJson = data.toString(),
                receivedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun clearPending(kind: String, sessionId: String) {
        pendingItemDao.clearKind(profileId, sessionId, kind)
    }
}