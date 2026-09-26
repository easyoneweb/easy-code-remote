package com.easycoderemote.data.remote

import com.easycoderemote.data.model.HealthDto
import com.easycoderemote.data.model.PendingDto
import com.easycoderemote.data.model.ServerConfigDto
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.model.SessionMessageDto
import com.easycoderemote.data.model.parseMessageResponse
import com.easycoderemote.util.APP_JSON
import com.easycoderemote.util.decodeTolerantArray
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for the companion server. One instance per (profile, token, pin)
 * tuple; the repository rebuilds it when the active profile changes.
 */
class ApiClient(
    baseUrl: String,
    token: String,
    pinnedFingerprint: String?,
    timeoutSeconds: Long = 30,
) {
    private val baseUrl: String = baseUrl.trimEnd('/')
    // Tokens are pasted; a stray newline/space would make OkHttp reject the header.
    private val token: String = token.trim()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Shared builder: TOFU-pinned TLS + hostname-verification bypass when pinned.
    private val client: OkHttpClient = OkHttpClients.build(baseUrl, pinnedFingerprint, timeoutSeconds)

    suspend fun health(): HealthDto =
        get("/health", auth = false, HealthDto.serializer())

    suspend fun sessions(): List<SessionDto> =
        decodeTolerantArray(getRaw(auth = true, path = "/api/v1/sessions"), SessionDto.serializer())

    suspend fun session(id: String): SessionDto? {
        val body = getRaw(auth = true, path = "/api/v1/sessions/${enc(id)}")
        return runCatching { APP_JSON.decodeFromString(SessionDto.serializer(), body) }.getOrNull()
    }

    suspend fun messages(sessionId: String, limit: Int, before: String?): List<SessionMessageDto> {
        val path = buildString {
            append("/api/v1/sessions/").append(enc(sessionId)).append("/messages?limit=").append(limit)
            before?.takeIf { it.isNotBlank() }?.let { append("&before=").append(enc(it)) }
        }
        return decodeTolerantArray(getRaw(auth = true, path = path), SessionMessageDto.serializer())
    }

    suspend fun pending(sessionId: String): PendingDto {
        val body = getRaw(auth = true, path = "/api/v1/sessions/${enc(sessionId)}/pending")
        return runCatching { APP_JSON.decodeFromString(PendingDto.serializer(), body) }
            .getOrDefault(PendingDto())
    }

    suspend fun config(): ServerConfigDto {
        val body = getRaw(auth = true, path = "/api/v1/config")
        return runCatching { APP_JSON.decodeFromString(ServerConfigDto.serializer(), body) }
            .getOrDefault(ServerConfigDto())
    }

    suspend fun diff(sessionId: String): String =
        getRaw(auth = true, path = "/api/v1/sessions/${enc(sessionId)}/diff")

    suspend fun sendMessage(
        sessionId: String,
        text: String,
        agent: String?,
        model: JsonElement?,
        variant: String?,
        messageID: String?,
        queued: Boolean,
    ): SessionMessageDto? {
        val json = buildJsonObject {
            putJsonArray("parts") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            messageID?.takeIf { it.isNotBlank() }?.let { put("messageID", it) }
            agent?.takeIf { it.isNotBlank() }?.let { put("agent", it) }
            model?.let { put("model", it) }
            variant?.takeIf { it.isNotBlank() }?.let { put("variant", it) }
            if (queued) put("queued", true)
        }
        // POST /message returns the created kilo message (docs/protocol.md); before
        // this the response body was discarded, so the sent message never showed in
        // the transcript until SSE echoed it.
        val body = post(auth = true, path = "/api/v1/sessions/${enc(sessionId)}/message", jsonBody = json.toString())
        return parseMessageResponse(body)
    }

    suspend fun abort(sessionId: String) {
        post(auth = true, path = "/api/v1/sessions/${enc(sessionId)}/abort", jsonBody = "{}")
    }

    suspend fun runCommand(sessionId: String, command: String, arguments: String?) {
        val json = buildJsonObject {
            put("command", command)
            arguments?.takeIf { it.isNotBlank() }?.let { put("arguments", it) }
        }
        post(auth = true, path = "/api/v1/sessions/${enc(sessionId)}/command", jsonBody = json.toString())
    }

    suspend fun permissionReply(sessionId: String, permissionID: String, action: String, always: Boolean) {
        val json = buildJsonObject {
            put("permissionID", permissionID)
            put("action", action)
            if (always) put("always", true)
        }
        post(auth = true, path = "/api/v1/sessions/${enc(sessionId)}/permission", jsonBody = json.toString())
    }

    suspend fun questionReply(sessionId: String, questionID: String, answers: List<String>) {
        val json = buildJsonObject {
            put("questionID", questionID)
            // Kilo's schema: `QuestionReply = { answers: QuestionAnswer[] }` where
            // each QuestionAnswer is an array of selected labels, one per asked
            // question in order. A single-select reply is `answers:[["label"]]`.
            putJsonArray("answers") {
                addJsonArray { answers.forEach { add(JsonPrimitive(it)) } }
            }
        }
        post(auth = true, path = "/api/v1/sessions/${enc(sessionId)}/question", jsonBody = json.toString())
    }

    suspend fun questionReject(sessionId: String, questionID: String) {
        val json = buildJsonObject {
            put("questionID", questionID)
            put("action", "reject")
        }
        post(auth = true, path = "/api/v1/sessions/${enc(sessionId)}/question", jsonBody = json.toString())
    }

    // -- plumbing below --

    private suspend fun <T> get(path: String, auth: Boolean, serializer: KSerializer<T>): T {
        val body = getRaw(auth = auth, path = path)
        return runCatching { APP_JSON.decodeFromString(serializer, body) }
            .getOrElse { throw ApiException("bad_response", "Unexpected server response: ${it.message}", false, 0) }
    }

    private suspend fun getRaw(auth: Boolean, path: String): String = withContext(Dispatchers.IO) {
        execute(auth = auth, path = path, body = null)
    }

    private suspend fun post(auth: Boolean, path: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        execute(auth = auth, path = path, body = jsonBody)
    }

    private fun execute(auth: Boolean, path: String, body: String?): String {
        val requestBuilder = Request.Builder()
            .url(baseUrl + path)
            .apply { if (auth) header("Authorization", "Bearer $token") }
        if (body != null) requestBuilder.post(body.toRequestBody(jsonMediaType))
        val response = try {
            client.newCall(requestBuilder.build()).execute()
        } catch (e: IOException) {
            throw ErrorMapper.fromTransport(e)
        }
        return response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ErrorMapper.fromResponse(text, resp.code)
            text
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}