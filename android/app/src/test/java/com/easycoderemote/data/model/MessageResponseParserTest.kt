package com.easycoderemote.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tolerant decode of the `POST /message` response (plan D6 — optimistic echo). */
class MessageResponseParserTest {

    @Test
    fun parsesKiloMessageShape() {
        val body = """
            {
              "info": {"id": "msg_1", "sessionID": "ses_1", "role": "user", "time": {"created": 1000}},
              "parts": [{"id": "p1", "type": "text", "text": "hello"}]
            }
        """.trimIndent()
        val dto = parseMessageResponse(body)
        assertThat(dto).isNotNull()
        assertThat(dto?.info?.id).isEqualTo("msg_1")
        assertThat(dto?.info?.role).isEqualTo("user")
        assertThat(dto?.parts).hasSize(1)
        assertThat(dto?.parts?.first()?.text).isEqualTo("hello")
    }

    @Test
    fun acceptsWrappedMessageObject() {
        val body = """{"message": {"info": {"id": "msg_2", "role": "user"}, "parts": []}}"""
        val dto = parseMessageResponse(body)
        assertThat(dto?.info?.id).isEqualTo("msg_2")
        assertThat(dto?.parts).isEmpty()
    }

    @Test
    fun malformedAndEmptyBodiesReturnNull() {
        assertThat(parseMessageResponse("")).isNull()
        assertThat(parseMessageResponse("   ")).isNull()
        assertThat(parseMessageResponse("{not json")).isNull()
        // An array or primitive body is not a message.
        assertThat(parseMessageResponse("[]")).isNull()
        assertThat(parseMessageResponse("null")).isNull()
    }

    @Test
    fun unrelatedBodyWithoutUsableMessageIdReturnsNull() {
        // A body that decodes with empty defaults (e.g. `{"status":"ok"}`) is not
        // an echoable message and must not produce a junk empty-id row.
        assertThat(parseMessageResponse("""{"status":"ok"}""")).isNull()
        assertThat(parseMessageResponse("""{"message": {"parts": []}}""")).isNull()
    }
}