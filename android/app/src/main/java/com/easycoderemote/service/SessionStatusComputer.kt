package com.easycoderemote.service

import com.easycoderemote.data.model.PendingDto

/**
 * Phone-side mirror of the server's derived-status rules (docs/protocol.md):
 * `waiting` wins over `running`/`idle`; pending state comes from SSE
 * permission./question. events and the `/pending` refresh.
 */
class SessionStatusComputer {
    private val pendingPerm = HashSet<String>()
    private val pendingQ = HashSet<String>()
    private val rawStatus = HashMap<String, String>()

    fun reset() {
        pendingPerm.clear()
        pendingQ.clear()
        rawStatus.clear()
    }

    fun onRawStatus(sessionId: String, status: String) {
        if (status.isNotBlank()) rawStatus[sessionId] = status
    }

    fun onSessionSnapshot(sessionId: String, status: String, waitingReason: String?) {
        rawStatus[sessionId] = if (status == "waiting") "idle" else status
        if (waitingReason == "permission") pendingPerm.add(sessionId) else pendingPerm.remove(sessionId)
        if (waitingReason == "question") pendingQ.add(sessionId) else pendingQ.remove(sessionId)
    }

    fun onPermissionAsked(sessionId: String) {
        pendingPerm.add(sessionId)
    }

    fun onPermissionReplied(sessionId: String) {
        pendingPerm.remove(sessionId)
    }

    fun onQuestionAsked(sessionId: String) {
        pendingQ.add(sessionId)
    }

    fun onQuestionReplied(sessionId: String) {
        pendingQ.remove(sessionId)
    }

    fun onPendingList(sessionId: String, pending: PendingDto) {
        if (pending.permissions.isNotEmpty()) pendingPerm.add(sessionId) else pendingPerm.remove(sessionId)
        if (pending.questions.isNotEmpty()) pendingQ.add(sessionId) else pendingQ.remove(sessionId)
    }

    /** True when this computer holds authoritative state for the session. */
    fun knows(sessionId: String): Boolean =
        sessionId in rawStatus || sessionId in pendingPerm || sessionId in pendingQ

    /** Returns (derived status, waitingReason). */
    fun status(sessionId: String, raw: String? = null): Pair<String, String?> {
        raw?.takeIf { it.isNotBlank() }?.let { rawStatus[sessionId] = it }
        if (sessionId in pendingPerm) return "waiting" to "permission"
        if (sessionId in pendingQ) return "waiting" to "question"
        return when (rawStatus[sessionId]) {
            "busy", "retry" -> "running" to null
            else -> "idle" to null
        }
    }
}