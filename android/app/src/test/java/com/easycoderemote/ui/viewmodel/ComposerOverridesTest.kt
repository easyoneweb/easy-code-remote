package com.easycoderemote.ui.viewmodel

import com.easycoderemote.data.model.ModelEntryDto
import com.easycoderemote.data.model.SessionDto
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

/** Pure composer-override semantics (plan §5). */
class ComposerOverridesTest {

    @Test
    fun modelOverrideJsonSendsIdWithOptionalProvider() {
        val withProvider = ComposerOverrides.modelOverrideJson("openrouter", "deepseek-v4-flash-0731")
        assertThat(withProvider["id"]?.jsonPrimitive?.content).isEqualTo("deepseek-v4-flash-0731")
        assertThat(withProvider["providerID"]?.jsonPrimitive?.content).isEqualTo("openrouter")

        val bare = ComposerOverrides.modelOverrideJson(null, "m1")
        assertThat(bare["id"]?.jsonPrimitive?.content).isEqualTo("m1")
        assertThat(bare["providerID"]).isNull()
    }

    @Test
    fun resolveAgentPreferOverrideOverSession() {
        val session = SessionDto(id = "s", agent = "code")
        assertThat(ComposerOverrides.resolveAgent("implementer", session)).isEqualTo("implementer")
        assertThat(ComposerOverrides.resolveAgent(null, session)).isEqualTo("code")
        assertThat(ComposerOverrides.resolveAgent("  ", null)).isNull()
    }

    @Test
    fun resolveModelLabelPreferOverrideThenSessionThenAuto() {
        val session = SessionDto(
            id = "s",
            model = buildJsonObject {
                put("id", "m1")
                put("providerID", "openrouter")
            },
        )
        assertThat(ComposerOverrides.resolveModelLabel("My Model", session)).isEqualTo("My Model")
        assertThat(ComposerOverrides.resolveModelLabel(null, session)).isEqualTo("openrouter · m1")
        val plain = SessionDto(id = "s", model = JsonPrimitive("m1"))
        assertThat(ComposerOverrides.resolveModelLabel(null, plain)).isEqualTo("m1")
        assertThat(ComposerOverrides.resolveModelLabel(null, SessionDto(id = "s"))).isEqualTo("auto")
    }

    @Test
    fun resolveVariantPreferOverrideWhenValidForChosenModel() {
        val model = ModelEntryDto(
            id = "m1",
            variants = buildJsonObject {
                putJsonObject("default") { put("label", "d") }
                putJsonObject("high") { put("label", "h") }
            },
        )
        val session = SessionDto(id = "s", model = buildJsonObject { put("id", "m1"); put("variant", "high") })
        assertThat(ComposerOverrides.resolveVariant("high", session, model)).isEqualTo("high")
        // Stale variant for a model that does not declare it → falls back to session.
        assertThat(ComposerOverrides.resolveVariant("medium", session, model)).isEqualTo("high")
        assertThat(ComposerOverrides.resolveVariant(null, session, model)).isEqualTo("high")
        assertThat(ComposerOverrides.resolveVariant(null, SessionDto(id = "s"), null)).isEqualTo("default")
    }

    @Test
    fun clearAfterSendClearsOnlyOnSuccess() {
        val model = JsonPrimitive("m")
        assertThat(ComposerOverrides.clearAfterSend(true, "a", model, "v")).isEqualTo(Triple(null, null, null))
        assertThat(ComposerOverrides.clearAfterSend(false, "a", model, "v")).isEqualTo(Triple("a", model, "v"))
    }

    @Test
    fun variantOptionsFollowTheChosenModel() {
        val model = ModelEntryDto(
            id = "m1",
            variants = buildJsonObject {
                putJsonObject("default") {}
                putJsonObject("turbo") {}
            },
        )
        assertThat(ComposerOverrides.variantOptions(model)).containsExactly("default", "turbo").inOrder()
        assertThat(ComposerOverrides.variantOptions(null))
            .containsExactly("default", "low", "medium", "high").inOrder()
    }
}