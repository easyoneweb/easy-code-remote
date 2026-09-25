package com.easycoderemote.data.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * Plan §9: kilo question payloads wrap the question(s) in a `questions` array
 * with selectable `options`. The phone must render the header, body and options
 * instead of dumping raw JSON.
 */
class QuestionPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(raw: String) = json.parseToJsonElement(raw).jsonObject

    private val kiloShape = payload(
        """{
          "id": "que_1", "sessionID": "ses_1",
          "questions": [{
            "question": "How should the pricing block be built?",
            "header": "Pricing block scope",
            "options": [
              {"label": "Shared data + compact-only (Recommended)", "description": "Low risk"},
              {"label": "Full rewrite", "description": "Higher risk"}
            ]
          }]
        }""",
    )

    @Test
    fun headerAndBodyComeFromTheQuestionsArray() {
        assertThat(kiloShape.questionHeader()).isEqualTo("Pricing block scope")
        assertThat(kiloShape.questionText()).isEqualTo("How should the pricing block be built?")
        assertThat(kiloShape.questionSummary()).isEqualTo("How should the pricing block be built?")
    }

    @Test
    fun optionsAreParsedInOrderWithLabels() {
        val options = kiloShape.questionOptions()
        assertThat(options).hasSize(2)
        assertThat(options[0].label).isEqualTo("Shared data + compact-only (Recommended)")
        assertThat(options[0].description).isEqualTo("Low risk")
        assertThat(options[1].label).isEqualTo("Full rewrite")
    }

    @Test
    fun flatPayloadWithoutArrayStillParses() {
        val flat = payload(
            """{"question":"Pick one","options":[{"label":"A"},{"label":"B","description":"bee"}]}""",
        )
        assertThat(flat.questionText()).isEqualTo("Pick one")
        assertThat(flat.questionHeader()).isEqualTo("Pick one") // falls back to summary
        val options = flat.questionOptions()
        assertThat(options.map { it.label }).containsExactly("A", "B").inOrder()
        assertThat(options[1].description).isEqualTo("bee")
    }

    @Test
    fun emptyPayloadYieldsNoOptions() {
        assertThat(payload("{}").questionOptions()).isEmpty()
        assertThat(payload("{}").questionText()).isEqualTo("Question")
        assertThat(payload("{}").questionHeader()).isEqualTo("Question")
    }

    @Test
    fun missingLabelOptionsAreSkipped() {
        val p = payload("""{"questions":[{"question":"q","options":[{"description":"no label"},{"label":"ok"}]}]}""")
        val options = p.questionOptions()
        assertThat(options.map { it.label }).containsExactly("ok")
    }
}