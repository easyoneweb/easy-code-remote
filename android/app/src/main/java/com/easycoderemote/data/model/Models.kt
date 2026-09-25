package com.easycoderemote.data.model

import com.easycoderemote.util.asStringOrNull
import com.easycoderemote.util.str
import com.easycoderemote.util.sumNumbers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: id
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

@Serializable
data class MessageInfoDto(
    val id: String = "",
    val sessionID: String? = null,
    val role: String? = null,
    val time: JsonElement? = null,
    val model: JsonElement? = null,
) {
    val roleLabel: String get() = role ?: "assistant"

    /** Server creation time (ms epoch); authoritative for transcript ordering. */
    val createdMs: Long
        get() = (time as? JsonObject)?.get("created")?.jsonPrimitive?.longOrNull ?: 0L
}

/** A part (text or tool) inside a message. Tool payloads stay raw JsonElement. */
@Serializable
data class PartDto(
    val id: String = "",
    val type: String = "text",
    val text: String? = null,
    val tool: String? = null,
    val name: String? = null,
    val state: String? = null,
    val status: String? = null,
    val title: String? = null,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
) {
    val isTool: Boolean get() = type == "tool" || tool != null
    val stateLabel: String get() = state ?: status ?: ""
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