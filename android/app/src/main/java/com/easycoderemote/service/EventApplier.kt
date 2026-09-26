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
import com.easycoderemote.data.model.MessageInfoDto
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
            is AppEvent.SessionCreated -> upsertSession(event.sessionID, event.session, event.data)
            is AppEvent.SessionUpdated -> upsertSession(event.sessionID, event.session, event.data)
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
            is AppEvent.QuestionReplied -> {
                statusComputer.onQuestionReplied(event.sessionID)
                clearPending("question", event.sessionID)
                refreshSessionStatus(event.sessionID)
            }
            is AppEvent.QuestionRejected -> {
                statusComputer.onQuestionReplied(event.sessionID)
                clearPending("question", event.sessionID)
                refreshSessionStatus(event.sessionID)
            }
            is AppEvent.SessionIdle -> {
                // The protocol-defined "run finished" event. A raw status snapshot
                // may never arrive, so without this a running→idle session stays
                // "running" until a full refetch/re-snapshot.
                statusComputer.onRawStatus(event.sessionID, "idle")
                refreshSessionStatus(event.sessionID)
            }
            is AppEvent.ServerConnected, is AppEvent.ResyncRequired,
            is AppEvent.EngineConnected, is AppEvent.EngineDisconnected,
            is AppEvent.SessionError,
            is AppEvent.SessionCompacting -> Unit
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
        val created = data?.jsonObject("info")?.jsonObject("time")?.get("created")?.jsonPrimitive?.longOrNull ?: 0L
        // Badge fields live in `data.info` (SSE/messages shape); tolerate a flat
        // message-like `data` shape the same way `role` is read above.
        val info = (data?.jsonObject("info") ?: data)?.let {
            runCatching { APP_JSON.decodeFromJsonElement(MessageInfoDto.serializer(), it) }.getOrNull()
        }
        messageDao.upsert(
            MessageEntity(
                id = messageId,
                profileId = profileId,
                sessionId = sessionId,
                role = role,
                seq = seq,
                rawJson = data?.toString() ?: "",
                timeCreated = created,
                agent = info?.agent?.takeIf { it.isNotBlank() },
                providerID = info?.effectiveProviderID(),
                modelID = info?.effectiveModelID(),
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
        // Kilo interleaves live text deltas (`message.part.delta`, append) with
        // persisted full-part snapshots (`message.part.updated`, replace) whose
        // text is often EMPTY or stale mid-stream. Never let a replace shrink the
        // accumulated text, or streaming would wipe the earlier content and keep
        // appending from the truncated base.
        text = if (part.textValue.length >= base.text.length) part.textValue else base.text,
        tool = part.tool ?: part.name ?: base.tool,
        state = part.stateLabel.ifBlank { base.state },
        rawJson = "",
    )

    private suspend fun deletePart(sessionId: String, messageId: String?, partId: String) {
        if (partId.isBlank()) return
        partDao.delete(profileId, sessionId, messageId.orEmpty(), partId)
    }

    /**
     * History fetch: store messages and their embedded parts in server order.
     *
     * The server returns pages oldest-first (chronological). Seq assignment keeps
     * ascending Room order == chronological order (plan §5.8):
     * - Newest page (`olderPage = false`, i.e. `?before=` unset): new messages get
     *   seq above the current max, in page order — so a refresh after a stale
     *   cache still sorts correctly.
     * - Older page (`olderPage = true`, pagination): new messages get seq below
     *   the current min, in page order — they land before everything stored.
     * - Existing messages keep their seq (re-fetch must not renumber them, which
     *   could collide with concurrently arriving SSE).
     */
    suspend fun storeHistory(
        sessionId: String,
        messages: List<com.easycoderemote.data.model.SessionMessageDto>,
        olderPage: Boolean,
    ) {
        val maxSeq = messageDao.maxSeq(profileId, sessionId)
        val minSeq = messageDao.minSeq(profileId, sessionId)
        for ((index, m) in messages.withIndex()) {
            // Keep the existing seq for messages already stored.
            val existing = messageDao.observeMessage(profileId, sessionId, m.info.id).first()
            val effectiveSeq = existing?.seq ?: if (olderPage) {
                // Pagination: prepend below the current oldest, chronological.
                (minSeq ?: 0L) - (messages.size - index).toLong()
            } else {
                // Newest page: append above the current newest (or seed 1..N), chronological.
                if (maxSeq == null) (index + 1).toLong() else maxSeq + (index + 1).toLong()
            }
            messageDao.upsert(
                MessageEntity(
                    id = m.info.id,
                    profileId = profileId,
                    sessionId = sessionId,
                    role = m.info.roleLabel,
                    seq = effectiveSeq,
                    rawJson = m.info.toString(),
                    timeCreated = m.info.createdMs,
                    agent = m.info.agent?.takeIf { it.isNotBlank() },
                    providerID = m.info.effectiveProviderID(),
                    modelID = m.info.effectiveModelID(),
                ),
            )
            var pseq = 0L
            for (p in m.parts) {
                pseq += 1
                // Keep the more advanced text: a history snapshot can lag the live
                // stream (or carry empty text for in-flight parts), so never let a
                // shorter snapshot overwrite the accumulated text.
                val existingPart = partDao.observePart(profileId, sessionId, m.info.id, p.id).first()
                val text = if (p.textValue.length >= (existingPart?.text?.length ?: 0)) {
                    p.textValue
                } else {
                    existingPart?.text ?: p.textValue
                }
                partDao.upsert(
                    PartEntity(
                        id = p.id,
                        profileId = profileId,
                        sessionId = sessionId,
                        messageId = m.info.id,
                        type = p.type.ifBlank { "text" },
                        text = text,
                        tool = p.tool ?: p.name,
                        state = p.stateLabel,
                        seq = pseq,
                        rawJson = p.toString(),
                    ),
                )
            }
        }
        // Retention: per-session storage bound (5000). Generous so paginated history
        // survives and older pages stay cacheable (plan §5.8: Room retains full
        // history for back-paging; the archived-session purge bounds total growth).
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