package com.easycoderemote.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared tolerant JSON. Unknown keys are ignored and values are parsed leniently
 * so a single unexpected field shape cannot take the whole payload down.
 */
val APP_JSON: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/** Decodes a JSON array tolerantly, element by element, dropping bad entries. */
inline fun <reified T> decodeTolerantArray(body: String, serializer: KSerializer<T>): List<T> {
    val elements = APP_JSON.decodeFromString<List<JsonElement>>(body)
    return elements.mapNotNull { el ->
        runCatching { APP_JSON.decodeFromJsonElement(serializer, el) }.getOrNull()
    }
}

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""

fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.let { it.content.toLongOrNull() }

fun JsonObject.jsonObject(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Best-effort string extraction from a JsonElement (object/array/primitives). */
fun JsonElement?.asStringOrNull(): String? {
    return when (this) {
        is JsonPrimitive -> if (this is JsonNull) null else content
        is JsonObject -> {
            str("text").takeIf { it.isNotBlank() }
                ?: str("content").takeIf { it.isNotBlank() }
                ?: str("title").takeIf { it.isNotBlank() }
        }
        is JsonArray -> firstOrNull()?.let { it.asStringOrNull() }
        else -> null
    }
}

/** Sum of all numeric leaves under a JsonElement (e.g. token counters). */
fun JsonElement?.sumNumbers(): Long {
    var total = 0L
    fun walk(e: JsonElement?) {
        when (e) {
            is JsonPrimitive -> if (e !is JsonNull) e.content.toLongOrNull()?.let { total += it }
            is JsonObject -> e.values.forEach { walk(it.value) }
            is JsonArray -> e.forEach { walk(it) }
            else -> Unit
        }
    }
    walk(this)
    return total
}