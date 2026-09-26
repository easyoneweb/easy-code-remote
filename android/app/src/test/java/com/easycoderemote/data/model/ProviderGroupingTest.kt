package com.easycoderemote.data.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** Provider parsing/grouping/display helpers (plan D2 / §config hierarchy). */
class ProviderGroupingTest {

    @Test
    fun groupModelsByProviderGroupsBlankUnderUnknownAndSortsKeys() {
        val models = listOf(
            ModelEntryDto(id = "m1", providerID = "zeta"),
            ModelEntryDto(id = "m2", providerID = null),
            ModelEntryDto(id = "m3", providerID = "alpha"),
            ModelEntryDto(id = "m4", providerID = "   "),
        )
        val grouped = groupModelsByProvider(models)
        assertThat(grouped.keys).containsExactly("alpha", "unknown", "zeta").inOrder()
        assertThat(grouped["unknown"]!!.map { it.id }).containsExactly("m2", "m4")
        assertThat(grouped["alpha"]!!.map { it.id }).containsExactly("m3")
    }

    @Test
    fun groupModelsByProviderCountsPerProvider() {
        val models = listOf(
            ModelEntryDto(id = "a1", providerID = "p"),
            ModelEntryDto(id = "a2", providerID = "p"),
            ModelEntryDto(id = "b1", providerID = "q"),
        )
        val grouped = groupModelsByProvider(models)
        assertThat(grouped["p"]).hasSize(2)
        assertThat(grouped["q"]).hasSize(1)
        assertThat(grouped["unknown"]).isNull()
    }

    @Test
    fun providerDisplayNameFavorsNameDisplayNameElseId() {
        val providers: List<JsonElement> = listOf(
            buildJsonObject { put("id", "openrouter"); put("name", "OpenRouter") },
            buildJsonObject { put("id", "anthropic"); put("displayName", "Anthropic Inc") },
            // id falls back to name when no explicit id is present.
            buildJsonObject { put("name", "Custom") },
        )
        assertThat(providerDisplayName("openrouter", providers)).isEqualTo("OpenRouter")
        assertThat(providerDisplayName("anthropic", providers)).isEqualTo("Anthropic Inc")
        assertThat(providerDisplayName("Custom", providers)).isEqualTo("Custom")
        assertThat(providerDisplayName("unknown-provider", providers)).isEqualTo("unknown-provider")
    }

    @Test
    fun providerDisplayNameBlankMapsToUnknown() {
        assertThat(providerDisplayName("", emptyList())).isEqualTo("unknown")
        assertThat(providerDisplayName("   ", emptyList())).isEqualTo("unknown")
    }

    @Test
    fun parseProvidersToleratesPrimitiveIds() {
        val raw: List<JsonElement> = listOf(
            buildJsonObject { put("id", "p1"); put("name", "P One") },
            kotlinx.serialization.json.JsonPrimitive("p2"),
            buildJsonObject { put("displayName", "P Three") }, // no id/name → blank id
        )
        val parsed = parseProviders(raw)
        assertThat(parsed[0].label).isEqualTo("P One")
        assertThat(parsed[1].id).isEqualTo("p2")
        assertThat(parsed[2].id).isEmpty()
    }
}