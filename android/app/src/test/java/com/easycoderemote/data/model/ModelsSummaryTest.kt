package com.easycoderemote.data.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** Notification-body summaries must be readable text, never raw JSON (plan D6). */
class ModelsSummaryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(raw: String) = json.parseToJsonElement(raw).jsonObject

    @Test
    fun permissionSummaryIsReadableNotJson() {
        val p = payload(
            """{"permission": "bash", "pattern": "*", "path": "/tmp/file.sh", "directory": "/home/user/proj"}""",
        )
        val summary = p.permissionSummary()
        assertThat(summary).isEqualTo("bash · * · /tmp/file.sh · /home/user/proj")
        assertThat(summary).doesNotContain("{")
    }

    @Test
    fun permissionSummaryFallsBackOnUnknownShape() {
        assertThat(payload("""{"id": "perm_1"}""").permissionSummary()).isEqualTo("unknown permission")
        assertThat(payload("""{}""").permissionSummary()).isEqualTo("unknown permission")
    }

    @Test
    fun questionTextUsesWrappedQuestionBody() {
        val p = payload("""{"id":"q1","questions":[{"question":"Continue the refactor?","options":[]}]}""")
        assertThat(p.questionText()).isEqualTo("Continue the refactor?")
        // Never the raw JSON id or braces.
        assertThat(p.questionText()).doesNotContain("q1")
        assertThat(p.questionText()).doesNotContain("{")
    }

    @Test
    fun questionTextFallsBackToSummaryWhenBodyMissing() {
        val p = payload("""{"id":"q2","questions":[{"header":"Wide","options":[]}]}""")
        assertThat(p.questionText()).isEqualTo("Question")
        assertThat(p.questionSummary()).isEqualTo("Question")
    }

    @Test
    fun questionSummaryPrefersWrapperQuestion() {
        val p = payload("""{"questions":[{"question":"A","options":[]},{"question":"B","options":[]}]}""")
        assertThat(p.questionSummary()).isEqualTo("A")
    }
}