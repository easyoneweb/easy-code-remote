package com.easycoderemote.data.local

import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.util.APP_JSON
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import org.junit.Test

/** SessionEntity → SessionDto mapping with the derived-status overlay (plan D3a). */
class SessionEntityMappingTest {

    private fun entity(raw: SessionDto, status: String = "idle", waitingReason: String? = null) = SessionEntity(
        id = raw.id,
        profileId = "p",
        title = raw.title,
        agent = raw.agent,
        modelLabel = null,
        directory = raw.directory,
        status = status,
        waitingReason = waitingReason,
        archived = raw.isArchived,
        lastUpdated = 0,
        rawJson = APP_JSON.encodeToString(SessionDto.serializer(), raw),
    )

    @Test
    fun toDtoOverlaysEntityStatusColumnsOverRawJson() {
        // The snapshot says `running` (rawJson), but the entity column — maintained
        // by EventApplier from status-only SSE events — says `idle`: the column wins.
        val raw = SessionDto(id = "ses_1", title = "T", status = "running")
        val dto = entity(raw, status = "idle", waitingReason = null).toDto()
        assertThat(dto).isNotNull()
        assertThat(dto?.id).isEqualTo("ses_1")
        assertThat(dto?.title).isEqualTo("T")
        assertThat(dto?.status).isEqualTo("idle")
        assertThat(dto?.waitingReason).isNull()
    }

    @Test
    fun toDtoOverlaysWaitingStatusAndReason() {
        val raw = SessionDto(id = "ses_1", status = "idle")
        val dto = entity(raw, status = "waiting", waitingReason = "permission").toDto()
        assertThat(dto?.status).isEqualTo("waiting")
        assertThat(dto?.waitingReason).isEqualTo("permission")
    }

    @Test
    fun toDtoPassesThroughOtherFieldsAndArchived() {
        val raw = SessionDto(id = "ses_1", title = "T", agent = "code", directory = "/home/user/proj", archived = true)
        val dto = entity(raw, status = "idle").toDto()
        assertThat(dto?.agent).isEqualTo("code")
        assertThat(dto?.directory).isEqualTo("/home/user/proj")
        assertThat(dto?.isArchived).isTrue()
    }

    @Test
    fun blankRawJsonReturnsNullGracefully() {
        val e = SessionEntity(
            id = "ses_1",
            profileId = "p",
            title = "T",
            agent = null,
            modelLabel = null,
            directory = null,
            status = "idle",
            waitingReason = null,
            archived = false,
            lastUpdated = 0,
            rawJson = "",
        )
        assertThat(e.toDto()).isNull()
    }
}