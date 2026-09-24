package com.easycoderemote.service

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.easycoderemote.data.local.AppDatabase
import com.easycoderemote.data.model.AppEvent
import com.easycoderemote.data.model.PartDto
import com.easycoderemote.data.model.SessionDto
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Room + append/replace semantics against an in-memory DB. */
@RunWith(RobolectricTestRunner::class)
class EventApplierTest {

    private lateinit var db: AppDatabase
    private lateinit var applier: EventApplier

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        applier = EventApplier("prof_1", db.sessionDao(), db.messageDao(), db.partDao(), db.pendingItemDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sessionCreatedUpsertsEntity() = runTest {
        applier.apply(AppEvent.SessionCreated("ses_1", SessionDto(id = "ses_1", title = "T"), null, 1))
        val s = db.sessionDao().observeSession("prof_1", "ses_1").first()
        assertThat(s?.title).isEqualTo("T")
    }

    @Test
    fun partDeltaAppendsAndFullPartReplaces() = runTest {
        applier.apply(AppEvent.SessionCreated("ses_1", SessionDto(id = "ses_1"), null, 1))
        applier.apply(AppEvent.MessageUpdated("ses_1", "msg_1", null, 2))
        applier.apply(AppEvent.PartUpdated("ses_1", "msg_1", "prt_1", PartDto(id = "prt_1"), deltaText = "Hel", 3))
        applier.apply(AppEvent.PartUpdated("ses_1", "msg_1", "prt_1", PartDto(id = "prt_1"), deltaText = "lo", 4))
        var parts = db.partDao().observeParts("prof_1", "ses_1", "msg_1").first()
        assertThat(parts.first().text).isEqualTo("Hello")

        applier.apply(
            AppEvent.PartUpdated("ses_1", "msg_1", "prt_1", PartDto(id = "prt_1", type = "text", text = "Replaced"), null, 5),
        )
        parts = db.partDao().observeParts("prof_1", "ses_1", "msg_1").first()
        assertThat(parts.first().text).isEqualTo("Replaced")
    }

    @Test
    fun pendingPayloadRetainedThenCleared() = runTest {
        applier.apply(
            AppEvent.PermissionAsked(
                "ses_1",
                buildJsonObject { put("id", "perm_1"); put("sessionID", "ses_1") },
                1,
            ),
        )
        var pending = db.pendingItemDao().observePending("prof_1", "ses_1").first()
        assertThat(pending).hasSize(1)
        assertThat(pending.first().kind).isEqualTo("permission")

        applier.apply(AppEvent.PermissionReplied("ses_1", 2))
        pending = db.pendingItemDao().observePending("prof_1", "ses_1").first()
        assertThat(pending).isEmpty()
    }

    @Test
    fun sessionDeletedClearsEverything() = runTest {
        applier.apply(AppEvent.SessionCreated("ses_1", SessionDto(id = "ses_1"), null, 1))
        applier.apply(AppEvent.MessageUpdated("ses_1", "msg_1", null, 2))
        applier.apply(
            AppEvent.PermissionAsked("ses_1", buildJsonObject { put("id", "perm_1"); put("sessionID", "ses_1") }, 3),
        )
        applier.apply(AppEvent.SessionDeleted("ses_1", 4))
        assertThat(db.sessionDao().observeSession("prof_1", "ses_1").first()).isNull()
        assertThat(db.messageDao().observeMessages("prof_1", "ses_1").first()).isEmpty()
        assertThat(db.pendingItemDao().observePending("prof_1", "ses_1").first()).isEmpty()
    }

    @Test
    fun replaceAllSessionsRemovesStale() = runTest {
        applier.apply(AppEvent.SessionCreated("ses_old", SessionDto(id = "ses_old"), null, 1))
        applier.replaceAllSessions(listOf(SessionDto(id = "ses_new", title = "N")))
        assertThat(db.sessionDao().observeSession("prof_1", "ses_old").first()).isNull()
        assertThat(db.sessionDao().observeSession("prof_1", "ses_new").first()?.title).isEqualTo("N")
    }
}