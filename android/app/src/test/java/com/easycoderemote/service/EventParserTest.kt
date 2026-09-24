package com.easycoderemote.service

import com.easycoderemote.data.model.Envelope
import com.easycoderemote.util.APP_JSON
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import org.junit.Test

/** Parser fixtures use the real envelope shapes from docs/protocol.md. */
class EventParserTest {

    private fun envelope(json: String): Envelope = APP_JSON.decodeFromString(json)

    @Test
    fun serverConnected() {
        val e = envelope("""{"type":"server.connected","data":{"kiloVersion":"7.7.9"},"ts":1,"cursor":42}""")
        val event = EventParser.parse(e)
        assertThat(event).isInstanceOf(AppEvent.ServerConnected::class.java)
        val s = event as AppEvent.ServerConnected
        assertThat(s.kiloVersion).isEqualTo("7.7.9")
        assertThat(s.cursor).isEqualTo(42)
    }

    @Test
    fun resyncRequired() {
        val e = envelope("""{"type":"resync.required","data":{"reason":"buffer rolled over"},"cursor":7}""")
        val event = EventParser.parse(e)
        assertThat((event as AppEvent.ResyncRequired).reason).contains("rolled over")
    }

    @Test
    fun sessionUpdatedParsesSession() {
        val json = """{"type":"session.updated","sessionID":"ses_1","data":{"info":{
            "id":"ses_1","title":"My session","status":"running",
            "model":{"id":"deepseek/deepseek-v4-flash-0731","providerID":"router_ai","variant":"default"}
        }},"cursor":3}"""
        val event = EventParser.parse(envelope(json))
        assertThat(event).isInstanceOf(AppEvent.SessionUpdated::class.java)
        val s = event as AppEvent.SessionUpdated
        assertThat(s.sessionID).isEqualTo("ses_1")
        assertThat(s.session?.title).isEqualTo("My session")
        assertThat(s.session?.modelLabel()).contains("deepseek/deepseek-v4-flash-0731")
    }

    @Test
    fun partDeltaIsAppendSemantics() {
        // Server maps kilo message.part.delta → message.part.updated with data.delta.textDelta.
        val json = """{"type":"message.part.updated","sessionID":"ses_1","messageID":"msg_1",
            "partID":"prt_1","data":{"part":{"id":"prt_1","type":"text","text":"The"},
            "delta":{"type":"text-delta","textDelta":"The"}},"cursor":5}"""
        val event = EventParser.parse(envelope(json))
        assertThat(event).isInstanceOf(AppEvent.PartUpdated::class.java)
        val p = event as AppEvent.PartUpdated
        assertThat(p.partID).isEqualTo("prt_1")
        assertThat(p.deltaText).isEqualTo("The")
        assertThat(p.part?.text).isEqualTo("The")
    }

    @Test
    fun partFullIsReplaceSemantics() {
        val json = """{"type":"message.part.updated","sessionID":"ses_1","messageID":"msg_1",
            "partID":"prt_1","data":{"part":{"id":"prt_1","type":"text","text":"Full text"}},"cursor":6}"""
        val event = EventParser.parse(envelope(json))
        assertThat(event).isInstanceOf(AppEvent.PartUpdated::class.java)
        val p = event as AppEvent.PartUpdated
        assertThat(p.deltaText).isNull()
        assertThat(p.part?.text).isEqualTo("Full text")
    }

    @Test
    fun rawDeltaTypeAlsoParses() {
        val json = """{"type":"message.part.delta","sessionID":"ses_1","messageID":"msg_1",
            "partID":"prt_1","data":{"part":{"id":"prt_1","type":"text","text":"chunk"}},"cursor":6}"""
        val event = EventParser.parse(envelope(json))
        val p = event as AppEvent.PartUpdated
        assertThat(p.deltaText).isEqualTo("chunk")
    }

    @Test
    fun permissionAskedPassthrough() {
        val json = """{"type":"permission.asked","sessionID":"ses_1",
            "data":{"id":"perm_1","sessionID":"ses_1","permission":"bash","pattern":"git status"},"cursor":8}"""
        val event = EventParser.parse(envelope(json))
        assertThat(event).isInstanceOf(AppEvent.PermissionAsked::class.java)
        assertThat((event as AppEvent.PermissionAsked).data?.str("permission")).isEqualTo("bash")
    }

    @Test
    fun questionRejected() {
        val json = """{"type":"question.rejected","sessionID":"ses_1","cursor":9}"""
        assertThat(EventParser.parse(envelope(json))).isInstanceOf(AppEvent.QuestionRejected::class.java)
    }

    @Test
    fun unknownTypeIgnored() {
        val json = """{"type":"something.else","cursor":1}"""
        assertThat(EventParser.parse(envelope(json))).isNull()
    }

    @Test
    fun sessionStatusExtractsType() {
        val json = """{"type":"session.status","sessionID":"ses_1","data":{"status":{"type":"busy"}},"cursor":10}"""
        val event = EventParser.parse(envelope(json))
        assertThat((event as AppEvent.SessionStatus).status).isEqualTo("busy")
    }
}