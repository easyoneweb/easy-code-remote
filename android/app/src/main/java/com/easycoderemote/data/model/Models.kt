package com.easycoderemote.data.model

import com.easycoderemote.util.APP_JSON
import com.easycoderemote.util.asStringOrNull
import com.easycoderemote.util.str
import com.easycoderemote.util.sumNumbers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** GET /health */
@Serializable
data class HealthDto(
    val status: String = "",
    val serverVersion: String = "",
    val kiloVersion: String = "",
    val engine: String = "down",
    val sessions: Int = 0,
) {
    val engineUp: Boolean get() = engine == "up"
}

/** GET /api/v1/config — flattened, tolerant shape. */
@Serializable
data class ServerConfigDto(
    val agents: List<JsonElement> = emptyList(),
    val skills: List<JsonElement> = emptyList(),
    val commands: List<JsonElement> = emptyList(),
    val mcps: JsonElement? = null,
    val providers: List<JsonElement> = emptyList(),
    val models: List<ModelEntryDto> = emptyList(),
)

@Serializable
data class ModelEntryDto(
    val id: String = "",
    val providerID: String? = null,
    val name: String? = null,
    /** Free-form variant map of this model (keys are variant names). Untyped on purpose. */
    val variants: JsonElement? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id

    /** Variant options for this model: the keys of `variants`, sorted; common fallback when missing. */
    fun variantNames(): List<String> {
        val obj = variants as? JsonObject ?: return COMMON_VARIANTS
        val keys = obj.keys.filter { it.isNotBlank() }.sorted()
        return keys.ifEmpty { COMMON_VARIANTS }
    }
}

/** Model variants understood when a model does not declare its own list. */
val COMMON_VARIANTS = listOf("default", "low", "medium", "high")

/** One provider from `/config` `providers` (tolerant: object or plain string id). */
@Serializable
data class ProviderEntryDto(
    val id: String = "",
    val name: String? = null,
    val displayName: String = "",
) {
    /** Best human label: raw `displayName`, else `name`, else the id. */
    val label: String
        get() = displayName.ifBlank { name?.takeIf { it.isNotBlank() } ?: id }
}

/** Tolerant parser over the raw `providers` JSON elements of `/config`. */
fun parseProviders(raw: List<JsonElement>): List<ProviderEntryDto> = raw.mapNotNull { el ->
    when (el) {
        is JsonPrimitive -> ProviderEntryDto(id = el.content)
        is JsonObject -> ProviderEntryDto(
            id = el.str("id").ifBlank { el.str("name") },
            name = el.str("name"),
            displayName = el.str("displayName"),
        )
        else -> null
    }
}

/** Display name for a provider id: favors the raw `name`/`displayName`, else the id. */
fun providerDisplayName(providerID: String, providers: List<JsonElement>): String {
    val key = providerID.takeIf { it.isNotBlank() } ?: return "unknown"
    return parseProviders(providers).firstOrNull { it.id == key }?.label?.takeIf { it.isNotBlank() } ?: key
}

/**
 * Models grouped by provider id; a blank/missing provider groups under "unknown"
 * and keys are sorted. Shared by the composer model picker sheet and the config
 * Providers → provider → models screens.
 */
fun groupModelsByProvider(models: List<ModelEntryDto>): Map<String, List<ModelEntryDto>> =
    models.groupBy { it.providerID?.takeIf { p -> p.isNotBlank() } ?: "unknown" }.toSortedMap()

/**
 * Tolerant parse of the `POST /message` response — the created kilo message in
 * the `{info, parts}` shape (docs/protocol.md). Also accepts a `{"message": ...}`
 * wrapper. Returns null for empty/malformed bodies.
 */
fun parseMessageResponse(body: String): SessionMessageDto? {
    if (body.isBlank()) return null
    val el = runCatching { APP_JSON.parseToJsonElement(body) }.getOrNull() ?: return null
    // Some servers frame the created message under a `message` key; unwrap it when
    // the object itself does not already carry message fields. A direct decode
    // would silently succeed (ignoreUnknownKeys) with empty defaults, so detect
    // the wrapper BEFORE decoding rather than falling back after.
    val message = (el as? JsonObject)?.let { obj ->
        if (obj.containsKey("info") || obj.containsKey("parts")) obj else obj["message"]
    } ?: el
    return runCatching { APP_JSON.decodeFromJsonElement(SessionMessageDto.serializer(), message) }.getOrNull()
        // A "message" without a usable id is not an echoable message (e.g. an
        // unexpected `{"status":"ok"}` body): treat it as "no echo" so the
        // repository never stores a junk empty-id row.
        ?.takeIf { it.info.id.isNotBlank() }
}

/** One session from GET /api/v1/sessions. model is tolerant (object or string). */
@Serializable
data class SessionDto(
    val id: String = "",
    val slug: String? = null,
    val title: String = "",
    val agent: String? = null,
    val model: JsonElement? = null,
    val directory: String? = null,
    val path: String? = null,
    val projectID: String? = null,
    val summary: JsonElement? = null,
    val tokens: JsonElement? = null,
    val cost: Double? = null,
    val time: TimeDto? = null,
    val status: String = "idle",
    val waitingReason: String? = null,
    val archived: Boolean? = null,
) {
    val isArchived: Boolean get() = archived == true
    val lastUpdatedMillis: Long get() = (time?.updated ?: 0L) * 1000L

    fun modelLabel(): String {
        val m = model ?: return agent.orEmpty()
        return when (m) {
            is JsonPrimitive -> m.content.ifBlank { agent.orEmpty() }
            is JsonObject -> {
                val id = m["id"]?.jsonPrimitive?.contentOrNull ?: return agent.orEmpty()
                val v = m["variant"]?.jsonPrimitive?.contentOrNull
                if (!v.isNullOrBlank() && v != "default") "$id ($v)" else id
            }
            else -> agent.orEmpty()
        }
    }

    /** Provider of the session's active model (object form only; string id → null). */
    fun sessionModelProvider(): String? {
        val m = model as? JsonObject ?: return null
        return m["providerID"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** Model id of the session's active model (object `id` or plain string). */
    fun sessionModelId(): String? {
        val m = model ?: return null
        return when (m) {
            is JsonObject -> m["id"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> m.content.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** Variant of the session's active model (object form only). */
    fun sessionModelVariant(): String? {
        val m = model as? JsonObject ?: return null
        return m["variant"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    val tokenCount: Long get() = tokens.sumNumbers()
}

@Serializable
data class TimeDto(
    val created: Long? = null,
    val updated: Long? = null,
)

/** One message from GET /api/v1/sessions/{id}/messages. */
@Serializable
data class SessionMessageDto(
    val info: MessageInfoDto = MessageInfoDto(),
    val parts: List<PartDto> = emptyList(),
)

/** `providerID · id` canonical label; each part optional, null when both blank. */
fun providerModelLabel(provider: String?, modelId: String?): String? {
    val p = provider?.trim().orEmpty()
    val m = modelId?.trim().orEmpty()
    return when {
        p.isEmpty() && m.isEmpty() -> null
        p.isEmpty() -> m
        m.isEmpty() -> p
        else -> "$p · $m"
    }
}

/** `agent · provider · id` badge caption with blank parts dropped; null when nothing present. */
fun badgeLabel(agent: String?, provider: String?, modelId: String?): String? {
    val parts = listOf(agent, provider, modelId).mapNotNull { it?.trim()?.takeIf { v -> v.isNotEmpty() } }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

@Serializable
data class MessageInfoDto(
    val id: String = "",
    val sessionID: String? = null,
    val role: String? = null,
    val time: JsonElement? = null,
    val model: JsonElement? = null,
    /** Message-local agent/model of the producing run (flat form; server also
     *  nests them under `model` as `{id, providerID, variant}`). */
    val agent: String? = null,
    val providerID: String? = null,
    val modelID: String? = null,
) {
    val roleLabel: String get() = role ?: "assistant"

    /** Server creation time (ms epoch); authoritative for transcript ordering. */
    val createdMs: Long
        get() = (time as? JsonObject)?.get("created")?.jsonPrimitive?.longOrNull ?: 0L

    /** Provider of this message: flat `providerID`, else from the nested `model` object. */
    fun effectiveProviderID(): String? =
        providerID?.trim()?.takeIf { it.isNotEmpty() }
            ?: (model as? JsonObject)?.get("providerID")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** Model id of this message: flat `modelID`, else from the nested `model` object. */
    fun effectiveModelID(): String? =
        modelID?.trim()?.takeIf { it.isNotEmpty() }
            ?: (model as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

/** A part (text or tool) inside a message. Tool payloads stay raw JsonElement. */
@Serializable
data class PartDto(
    val id: String = "",
    val type: String = "text",
    val text: String? = null,
    val tool: String? = null,
    val name: String? = null,
    /** Tolerant of both a flat string and a `{status, ...}` object that kilo emits
     *  on question/permission tool parts. */
    val state: JsonElement? = null,
    val status: String? = null,
    val title: String? = null,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
) {
    val isTool: Boolean get() = type == "tool" || tool != null

    /** UI string for the part state: the found `state`/`status` value. */
    val stateLabel: String
        get() {
            val fromState = when (val s = state) {
                is JsonPrimitive -> s.content.takeIf { it.isNotBlank() }
                is JsonObject -> s["status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: s["state"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                else -> null
            }
            return fromState ?: status ?: ""
        }

    val textValue: String get() = text ?: ""
}

/** GET /api/v1/sessions/{id}/pending */
@Serializable
data class PendingDto(
    val permissions: List<JsonElement> = emptyList(),
    val questions: List<JsonElement> = emptyList(),
)

/** Stable server error body {"error":{"code","message","retryable"?}} */
@Serializable
data class ErrorBody(
    val error: ErrorInfo = ErrorInfo(),
)

@Serializable
data class ErrorInfo(
    val code: String = "",
    val message: String = "",
    val retryable: Boolean? = null,
)

/** Extract the permission id from a raw permission payload (tolerant probe). */
fun JsonElement?.permissionId(): String {
    return when (this) {
        is JsonObject -> str("permissionID").ifBlank { str("id") }
        is JsonPrimitive -> content
        else -> ""
    }
}

/** Extract the question id from a raw question payload (tolerant probe). */
fun JsonElement?.questionId(): String {
    return when (this) {
        is JsonObject -> str("questionID").ifBlank { str("id") }
        is JsonPrimitive -> content
        else -> ""
    }
}

/** Human text for a permission payload, tolerant across kilo shapes. */
fun JsonElement?.permissionSummary(): String {
    return when (this) {
        is JsonObject -> {
            val permission = str("permission").ifBlank { str("title").ifBlank { "unknown permission" } }
            val pattern = str("pattern").takeIf { it.isNotBlank() }
            val path = str("path").takeIf { it.isNotBlank() }
            val directory = str("directory").takeIf { it.isNotBlank() }
            listOfNotNull(permission, pattern, path, directory).joinToString(" · ")
        }
        else -> this.asStringOrNull() ?: "unknown permission"
    }
}

/** Human text for a question payload. */
fun JsonElement?.questionSummary(): String {
    return when (this) {
        is JsonObject -> {
            // Kilo wraps questions in a `questions` array; also accept flat shapes.
            val nested = this.firstQuestion()?.str("question")
            listOf(nested, str("question"), str("message"), str("title"))
                .firstOrNull { !it.isNullOrBlank() } ?: "Question"
        }
        else -> this.asStringOrNull() ?: "Question"
    }
}

/** One selectable answer option of a kilo question. */
data class QuestionOption(val label: String, val description: String)

/** Header of the first question in a payload (kilo wraps questions in an array). */
fun JsonElement?.questionHeader(): String {
    val first = this?.firstQuestion()
    return first?.str("header")
        ?.takeIf { it.isNotBlank() }
        ?: this.questionSummary()
}

/** Body text of the first question in a payload. */
fun JsonElement?.questionText(): String {
    val first = this?.firstQuestion()
    return first?.str("question")
        ?.takeIf { it.isNotBlank() }
        ?: this.questionSummary()
}

/** Options of the first question in a payload, in order. */
fun JsonElement?.questionOptions(): List<QuestionOption> {
    val first = this?.firstQuestion() ?: return emptyList()
    val arr = first["options"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val label = obj.str("label")
        if (label.isBlank()) null else QuestionOption(label, obj.str("description"))
    }
}

/** First question object of a kilo question payload (tolerant). */
private fun JsonElement.firstQuestion(): JsonObject? {
    val obj = this as? JsonObject
    val list = obj?.get("questions") as? JsonArray
    if (!list.isNullOrEmpty()) {
        val first = list.firstOrNull() as? JsonObject
        if (first != null) return first
    }
    return obj
}