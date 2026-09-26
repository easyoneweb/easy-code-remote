package com.easycoderemote.ui.viewmodel

import com.easycoderemote.data.model.COMMON_VARIANTS
import com.easycoderemote.data.model.ModelEntryDto
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.model.providerModelLabel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure picker semantics for the composer's one-shot agent/model/variant overrides.
 * No Android dependencies — fully unit-testable.
 *
 * Overrides are per-send: they win over the session's active values for the next
 * message and clear after a successful send, so the session's active agent/model
 * always wins between messages.
 */
object ComposerOverrides {

    /** Serialized `model` payload for `/message`: `{id}` + optional `providerID`. */
    fun modelOverrideJson(providerID: String?, id: String): JsonObject = buildJsonObject {
        put("id", id)
        providerID?.takeIf { it.isNotBlank() }?.let { put("providerID", it) }
    }

    /** Agent shown on the chip: explicit override wins, else the session's active agent. */
    fun resolveAgent(overrideAgent: String?, sessionDto: SessionDto?): String? =
        overrideAgent?.takeIf { it.isNotBlank() } ?: sessionDto?.agent

    /**
     * Model label shown on the chip: the override's displayName/id, else the
     * session's active `provider · id`, else "auto".
     */
    fun resolveModelLabel(overrideLabel: String?, sessionDto: SessionDto?): String {
        overrideLabel?.takeIf { it.isNotBlank() }?.let { return it }
        return providerModelLabel(sessionDto?.sessionModelProvider(), sessionDto?.sessionModelId()) ?: "auto"
    }

    /**
     * Variant shown on the chip: the explicit override when valid for [chosenModel],
     * else the session's active variant, else "default".
     */
    fun resolveVariant(overrideVariant: String?, sessionDto: SessionDto?, chosenModel: ModelEntryDto?): String {
        overrideVariant?.takeIf { it.isNotBlank() }?.let { v ->
            // A variant list is per-model; a stale variant for a different model is invalid.
            if (chosenModel == null || v in chosenModel.variantNames()) return v
        }
        return sessionDto?.sessionModelVariant() ?: "default"
    }

    /**
     * Composer override values after a message send attempt: a successful send is
     * one-shot and clears them (null, null, null); a failed send keeps them so the
     * user can retry.
     */
    fun clearAfterSend(success: Boolean, agent: String?, model: JsonElement?, variant: String?): Triple<String?, JsonElement?, String?> =
        if (success) Triple(null, null, null) else Triple(agent, model, variant)

    /** Variant options for the model picker context: the chosen model's own list when known. */
    fun variantOptions(chosenModel: ModelEntryDto?): List<String> =
        chosenModel?.variantNames() ?: COMMON_VARIANTS
}
