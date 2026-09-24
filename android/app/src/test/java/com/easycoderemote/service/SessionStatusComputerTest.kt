package com.easycoderemote.service

import com.easycoderemote.data.model.PendingDto
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class SessionStatusComputerTest {

    @Test
    fun waitingOverridesRunning() {
        val c = SessionStatusComputer()
        c.onRawStatus("ses_1", "busy")
        assertThat(c.status("ses_1")).isEqualTo("running" to null)

        c.onPermissionAsked("ses_1")
        assertThat(c.status("ses_1")).isEqualTo("waiting" to "permission")

        c.onPermissionReplied("ses_1")
        assertThat(c.status("ses_1")).isEqualTo("running" to null)
    }

    @Test
    fun questionPendingWinsOverIdle() {
        val c = SessionStatusComputer()
        c.onQuestionAsked("ses_1")
        assertThat(c.status("ses_1")).isEqualTo("waiting" to "question")
        c.onQuestionReplied("ses_1")
        assertThat(c.status("ses_1")).isEqualTo("idle" to null)
    }

    @Test
    fun snapshotReflectsServerDerivedStatus() {
        val c = SessionStatusComputer()
        c.onSessionSnapshot("ses_1", "waiting", "permission")
        assertThat(c.status("ses_1")).isEqualTo("waiting" to "permission")
        // Next snapshot without the reason clears it.
        c.onSessionSnapshot("ses_1", "idle", null)
        assertThat(c.status("ses_1")).isEqualTo("idle" to null)
    }

    @Test
    fun pendingListRefresh() {
        val c = SessionStatusComputer()
        c.onRawStatus("ses_1", "busy")
        c.onPendingList("ses_1", PendingDto(permissions = listOf(buildJsonObject {}), questions = emptyList()))
        assertThat(c.status("ses_1")).isEqualTo("waiting" to "permission")
        c.onPendingList("ses_1", PendingDto(permissions = emptyList(), questions = emptyList()))
        assertThat(c.status("ses_1")).isEqualTo("running" to null)
    }
}