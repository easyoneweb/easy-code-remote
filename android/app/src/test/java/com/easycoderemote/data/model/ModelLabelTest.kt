package com.easycoderemote.data.model

import com.easycoderemote.util.APP_JSON
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test

/** Label formatting + tolerant decode of agent/model fields (plan §1 helpers). */
class ModelLabelTest {

    // -- providerModelLabel / badgeLabel -----------------------------------

    @Test
    fun providerModelLabelCombinesBothWhenPresent() {
        assertThat(providerModelLabel("openrouter", "deepseek/deepseek-v4-flash-0731"))
            .isEqualTo("openrouter · deepseek/deepseek-v4-flash-0731")
    }

    @Test
    fun providerModelLabelToleratesEitherPartMissing() {
        assertThat(providerModelLabel(null, "deepseek-v4-flash-0731")).isEqualTo("deepseek-v4-flash-0731")
        assertThat(providerModelLabel("openrouter", null)).isEqualTo("openrouter")
        assertThat(providerModelLabel("", "  ")).isNull()
    }

    @Test
    fun badgeLabelDropsBlankPartsAndReturnsNullWhenNone() {
        assertThat(badgeLabel("implementer", "openrouter", "deepseek-v4-flash-0731"))
            .isEqualTo("implementer · openrouter · deepseek-v4-flash-0731")
        assertThat(badgeLabel(null, "openrouter", "m1")).isEqualTo("openrouter · m1")
        assertThat(badgeLabel("implementer", null, null)).isEqualTo("implementer")
        assertThat(badgeLabel("", " ", null)).isNull()
    }

    // -- ModelEntryDto variants -------------------------------------------

    @Test
    fun modelEntryDecodesVariantsAndExposesSortedNames() {
        val raw = """
            {
              "id": "anthropic/claude-opus-4",
              "providerID": "anthropic",
              "name": "Claude Opus 4",
              "variants": {"medium": {"label": "m"}, "default": {"label": "d"}, "high": {"label": "h"}}
            }
        """.trimIndent()
        val dto = APP_JSON.decodeFromString(ModelEntryDto.serializer(), raw)
        assertThat(dto.id).isEqualTo("anthropic/claude-opus-4")
        assertThat(dto.variantNames()).containsExactly("default", "high", "medium").inOrder()
    }

    @Test
    fun modelEntryWithoutVariantsFallsBackToCommonList() {
        val plain = ModelEntryDto(id = "m1", providerID = "p1")
        assertThat(plain.variantNames()).containsExactly("default", "low", "medium", "high").inOrder()
        val blank = APP_JSON.decodeFromString<ModelEntryDto>("""{"id":"x","variants":{}}""")
        assertThat(blank.variantNames()).containsExactly("default", "low", "medium", "high").inOrder()
    }

    // -- MessageInfoDto agent/model -----------------------------------------

    @Test
    fun messageInfoDecodesFlatAgentAndModelFields() {
        val raw = """
            {
              "id": "msg_1",
              "sessionID": "ses_1",
              "role": "assistant",
              "agent": "implementer",
              "providerID": "openrouter",
              "modelID": "deepseek-v4-flash-0731"
            }
        """.trimIndent()
        val dto = APP_JSON.decodeFromString(MessageInfoDto.serializer(), raw)
        assertThat(dto.agent).isEqualTo("implementer")
        assertThat(dto.effectiveProviderID()).isEqualTo("openrouter")
        assertThat(dto.effectiveModelID()).isEqualTo("deepseek-v4-flash-0731")
    }

    @Test
    fun messageInfoDerivesProviderModelFromNestedModelObject() {
        val raw = """
            {"id":"msg_1","role":"assistant","model":{"id":"m1","providerID":"anthropic","variant":"high"}}
        """.trimIndent()
        val dto = APP_JSON.decodeFromString(MessageInfoDto.serializer(), raw)
        assertThat(dto.effectiveProviderID()).isEqualTo("anthropic")
        assertThat(dto.effectiveModelID()).isEqualTo("m1")
        // No flat agent → null, badge stays valid.
        assertThat(dto.agent).isNull()
    }

    @Test
    fun messageInfoWithoutModelYieldsNulls() {
        val dto = MessageInfoDto(id = "msg_1")
        assertThat(dto.effectiveProviderID()).isNull()
        assertThat(dto.effectiveModelID()).isNull()
    }

    // -- SessionDto model parsing -------------------------------------------

    private fun sessionWithModel(model: JsonElement) = SessionDto(id = "ses_1", agent = "code", model = model)

    @Test
    fun sessionModelObjectExtractsProviderIdVariant() {
        val s = sessionWithModel(
            buildJsonObject {
                put("id", "m1")
                put("providerID", "p1")
                put("variant", "high")
            },
        )
        assertThat(s.sessionModelProvider()).isEqualTo("p1")
        assertThat(s.sessionModelId()).isEqualTo("m1")
        assertThat(s.sessionModelVariant()).isEqualTo("high")
    }

    @Test
    fun sessionModelPlainStringIsIdWithoutProvider() {
        val s = sessionWithModel(JsonPrimitive("openrouter/deepseek-v4-flash-0731"))
        assertThat(s.sessionModelId()).isEqualTo("openrouter/deepseek-v4-flash-0731")
        assertThat(s.sessionModelProvider()).isNull()
        assertThat(s.sessionModelVariant()).isNull()
    }

    @Test
    fun sessionWithoutModelYieldsNulls() {
        val s = SessionDto(id = "ses_1", agent = "code")
        assertThat(s.sessionModelId()).isNull()
        assertThat(s.sessionModelProvider()).isNull()
        assertThat(s.sessionModelVariant()).isNull()
    }

    @Test
    fun sessionModelParsingKeepsModelLabelWorking() {
        val s = sessionWithModel(buildJsonObject { put("id", "m1"); put("variant", "high") })
        assertThat(s.modelLabel()).isEqualTo("m1 (high)")
        val plain = sessionWithModel(JsonPrimitive("m1"))
        assertThat(plain.modelLabel()).isEqualTo("m1")
    }

    // -- PartDto tolerant state (kilo tool parts) ---------------------------

    @Test
    fun partDtoDecodesObjectStateWithoutLosingTheMessage() {
        // kilo question/permission tool parts carry `state` as `{status, input}`;
        // an object state used to fail String? decode and silently drop the part.
        val raw = """
            {
              "id": "prt_q",
              "type": "tool",
              "tool": "question",
              "state": {"status": "completed", "input": {"questions": []}}
            }
        """.trimIndent()
        val part = APP_JSON.decodeFromString<PartDto>(raw)
        assertThat(part.id).isEqualTo("prt_q")
        assertThat(part.stateLabel).isEqualTo("completed")
        assertThat(part.isTool).isTrue()
    }

    @Test
    fun partDtoAcceptsFlatStringStateStill() {
        val flat = """{"id":"prt_1","type":"tool","tool":"bash","state":"running"}"""
        val part = APP_JSON.decodeFromString<PartDto>(flat)
        assertThat(part.stateLabel).isEqualTo("running")
    }

    @Test
    fun sessionMessageWithObjectStateDecodesThroughTolerantArray() {
        val raw = """
            [
              {"info": {"id": "msg_1", "role": "assistant"},
               "parts": [
                 {"id": "prt_q", "type": "tool", "tool": "question",
                  "state": {"status": "completed", "input": {"questions": []}}}
               ]}
            ]
        """.trimIndent()
        val msgs = com.easycoderemote.util.decodeTolerantArray(raw, SessionMessageDto.serializer())
        assertThat(msgs).hasSize(1)
        assertThat(msgs[0].info.id).isEqualTo("msg_1")
        assertThat(msgs[0].parts.first().stateLabel).isEqualTo("completed")
    }

    @Test
    fun questionReplyBodyUsesNestedAnswerArrays() {
        // Kilo's schema (extracted from the 7.7.9 bundle) is
        // `QuestionReply = { answers: QuestionAnswer[] }` with each QuestionAnswer
        // an array of labels, one per question in order. A single-select reply is
        // therefore `answers:[["label"]]` — a flat `["label"]` is rejected by
        // kilo (`Expected QuestionAnswer, got ...`).
        val json = buildJsonObject {
            put("questionID", "q_1")
            putJsonArray("answers") { add(buildJsonArray { add(JsonPrimitive("DB-draft + fast poll (Recommended)")) }) }
        }
        val text = json.toString()
        assertThat(text).isEqualTo(
            """{"questionID":"q_1","answers":[["DB-draft + fast poll (Recommended)"]]}""",
        )
        val arr = APP_JSON.parseToJsonElement(text).jsonObject["answers"]!!.jsonArray
        assertThat(arr).hasSize(1)
        assertThat(arr[0].jsonArray.first().jsonPrimitive.content)
            .isEqualTo("DB-draft + fast poll (Recommended)")
    }
}