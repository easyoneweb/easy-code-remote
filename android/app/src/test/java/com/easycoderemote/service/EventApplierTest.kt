package com.easycoderemote.service

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.easycoderemote.data.local.AppDatabase
import com.easycoderemote.data.local.MessageEntity
import com.easycoderemote.data.model.AppEvent
import com.easycoderemote.data.model.MessageInfoDto
import com.easycoderemote.data.model.PartDto
import com.easycoderemote.data.model.SessionDto
import com.easycoderemote.data.model.SessionMessageDto
import com.easycoderemote.util.APP_JSON
import com.easycoderemote.util.decodeTolerantArray
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
    fun staleEmptyFullPartNeverRegressesStreamingText() = runTest {
        // Kilo interleaves live deltas (append) with persisted full-part snapshots
        // whose text is EMPTY or stale mid-stream (plan §5.6). A replace with
        // shorter/empty text must not wipe the accumulated text.
        applier.apply(AppEvent.SessionCreated("ses_1", SessionDto(id = "ses_1"), null, 1))
        applier.apply(AppEvent.MessageUpdated("ses_1", "msg_1", null, 2))
        applier.apply(AppEvent.PartUpdated("ses_1", "msg_1", "prt_1", PartDto(id = "prt_1"), deltaText = "Hello", 3))
        applier.apply(AppEvent.PartUpdated("ses_1", "msg_1", "prt_1", PartDto(id = "prt_1"), deltaText = " world", 4))

        // Stale snapshot with empty text (the persisted part-created event).
        applier.apply(
            AppEvent.PartUpdated("ses_1", "msg_1", "prt_1", PartDto(id = "prt_1", type = "text", text = ""), null, 5),
        )
        var parts = db.partDao().observeParts("prof_1", "ses_1", "msg_1").first()
        assertThat(parts.first().text).isEqualTo("Hello world")

        // Stale snapshot with partial/shorter text.
        applier.apply(
            AppEvent.PartUpdated("ses_1", "msg_1", "prt_1", PartDto(id = "prt_1", type = "text", text = "Hello"), null, 6),
        )
        parts = db.partDao().observeParts("prof_1", "ses_1", "msg_1").first()
        assertThat(parts.first().text).isEqualTo("Hello world")

        // Deltas keep appending after the stale replaces.
        applier.apply(AppEvent.PartUpdated("ses_1", "msg_1", "prt_1", PartDto(id = "prt_1"), deltaText = "!", 7))
        parts = db.partDao().observeParts("prof_1", "ses_1", "msg_1").first()
        assertThat(parts.first().text).isEqualTo("Hello world!")

        // A genuinely longer snapshot still replaces (e.g. the final committed text).
        applier.apply(
            AppEvent.PartUpdated(
                "ses_1", "msg_1", "prt_1",
                PartDto(id = "prt_1", type = "text", text = "Hello world! Final"), null, 8,
            ),
        )
        parts = db.partDao().observeParts("prof_1", "ses_1", "msg_1").first()
        assertThat(parts.first().text).isEqualTo("Hello world! Final")
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
    fun storeHistoryKeepsMessageWithObjectStateToolPart() = runTest {
        // Kilo `question` tool parts carry `state` as `{status, input}`. Before the
        // tolerant-decode fix this threw in PartDto and decodeTolerantArray silently
        // dropped the whole message (user's reply vanished from the transcript).
        val raw = """
            [{
              "info": {"id": "msg_1", "sessionID": "ses_1", "role": "assistant", "agent": "plan",
                       "modelID": "deepseek-v4-flash-0731", "providerID": "router_ai",
                       "time": {"created": 1000}, "parentID": "msg_0"},
              "parts": [
                {"id": "p1", "type": "step-start", "sessionID": "ses_1", "messageID": "msg_1",
                 "snapshot": {}, "time": {"created": 1001}},
                {"id": "p2", "type": "text", "sessionID": "ses_1", "messageID": "msg_1",
                 "text": "Plain-text streaming it is. Next decision.",
                 "time": {"created": 1002}},
                {"id": "p3", "type": "tool", "sessionID": "ses_1", "messageID": "msg_1",
                 "tool": "question", "callID": "call_1",
                 "state": {"status": "completed", "input": {"questions": []}},
                 "time": {"created": 1003}}
              ]
            }]
        """.trimIndent()
        val msgs = decodeTolerantArray(raw, SessionMessageDto.serializer())
        assertThat(msgs).hasSize(1)

        applier.storeHistory("ses_1", msgs, olderPage = false)
        val stored = db.messageDao().observeMessages("prof_1", "ses_1").first()
        assertThat(stored).hasSize(1)
        assertThat(stored.first().id).isEqualTo("msg_1")
        assertThat(stored.first().agent).isEqualTo("plan")
        assertThat(stored.first().providerID).isEqualTo("router_ai")
        assertThat(stored.first().modelID).isEqualTo("deepseek-v4-flash-0731")
        val parts = db.partDao().observeParts("prof_1", "ses_1", "msg_1").first()
        assertThat(parts.map { it.type }).containsExactly("step-start", "text", "tool")
        assertThat(parts.first { it.type == "tool" }.state).isEqualTo("completed")
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
    fun sessionIdleRefreshesRunningStatusToIdle() = runTest {
        // A raw snapshot puts the session in `busy` (derived → `running`); the
        // protocol-defined `session.idle` (run finished) event must flip it to
        // `idle` live instead of staying "running" until a full refetch.
        applier.apply(AppEvent.SessionCreated("ses_1", SessionDto(id = "ses_1", status = "busy"), null, 1))
        assertThat(db.sessionDao().observeSession("prof_1", "ses_1").first()?.status).isEqualTo("running")

        applier.apply(AppEvent.SessionIdle("ses_1", 2))
        assertThat(db.sessionDao().observeSession("prof_1", "ses_1").first()?.status).isEqualTo("idle")
    }

    @Test
    fun replaceAllSessionsRemovesStale() = runTest {
        applier.apply(AppEvent.SessionCreated("ses_old", SessionDto(id = "ses_old"), null, 1))
        applier.replaceAllSessions(listOf(SessionDto(id = "ses_new", title = "N")))
        assertThat(db.sessionDao().observeSession("prof_1", "ses_old").first()).isNull()
        assertThat(db.sessionDao().observeSession("prof_1", "ses_new").first()?.title).isEqualTo("N")
    }

    @Test
    fun messageUpdatedPopulatesBadgeFields() = runTest {
        val data = buildJsonObject {
            putJsonObject("info") {
                put("id", "msg_1")
                put("sessionID", "ses_1")
                put("role", "assistant")
                put("agent", "implementer")
                put("providerID", "openrouter")
                put("modelID", "deepseek-v4-flash-0731")
            }
        }
        applier.apply(AppEvent.MessageUpdated("ses_1", "msg_1", data, 2))
        val m = db.messageDao().observeMessages("prof_1", "ses_1").first().single()
        assertThat(m.agent).isEqualTo("implementer")
        assertThat(m.providerID).isEqualTo("openrouter")
        assertThat(m.modelID).isEqualTo("deepseek-v4-flash-0731")
    }

    @Test
    fun messageUpdatedDerivesProviderModelFromNestedModelObject() = runTest {
        val data = buildJsonObject {
            putJsonObject("info") {
                put("id", "msg_1")
                put("role", "assistant")
                put("agent", "implementer")
                putJsonObject("model") {
                    put("id", "claude-opus-4")
                    put("providerID", "anthropic")
                    put("variant", "default")
                }
            }
        }
        applier.apply(AppEvent.MessageUpdated("ses_1", "msg_1", data, 2))
        val m = db.messageDao().observeMessages("prof_1", "ses_1").first().single()
        assertThat(m.agent).isEqualTo("implementer")
        assertThat(m.providerID).isEqualTo("anthropic")
        assertThat(m.modelID).isEqualTo("claude-opus-4")
    }

    @Test
    fun storeHistoryPopulatesBadgeFields() = runTest {
        val dto = SessionMessageDto(
            info = MessageInfoDto(
                id = "msg_h",
                role = "assistant",
                agent = "plan",
                providerID = "anthropic",
                modelID = "claude-sonnet-4",
            ),
            parts = emptyList(),
        )
        applier.storeHistory("ses_1", listOf(dto), olderPage = false)
        val m = db.messageDao().observeMessages("prof_1", "ses_1").first().single()
        assertThat(m.agent).isEqualTo("plan")
        assertThat(m.providerID).isEqualTo("anthropic")
        assertThat(m.modelID).isEqualTo("claude-sonnet-4")
    }

    @Test
    fun migration2To3PreservesExistingRowsAndAddsBadgeColumns() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("ecr-migration-test.db")
        // Build a v2 schema + seed data by hand (Room's v2 exported schema).
        val path = context.getDatabasePath("ecr-migration-test.db").path
        val v2 = SQLiteDatabase.openOrCreateDatabase(path, null)
        v2.execSQL(
            "CREATE TABLE sessions (id TEXT NOT NULL, profileId TEXT NOT NULL, title TEXT NOT NULL, " +
                "agent TEXT, modelLabel TEXT, directory TEXT, status TEXT NOT NULL, waitingReason TEXT, " +
                "archived INTEGER NOT NULL, lastUpdated INTEGER NOT NULL, rawJson TEXT NOT NULL, PRIMARY KEY(id))",
        )
        v2.execSQL(
            "CREATE TABLE messages (id TEXT NOT NULL, profileId TEXT NOT NULL, sessionId TEXT NOT NULL, " +
                "role TEXT NOT NULL, seq INTEGER NOT NULL, rawJson TEXT NOT NULL, timeCreated INTEGER NOT NULL, " +
                "PRIMARY KEY(id))",
        )
        v2.execSQL(
            "CREATE TABLE parts (id TEXT NOT NULL, profileId TEXT NOT NULL, sessionId TEXT NOT NULL, " +
                "messageId TEXT NOT NULL, type TEXT NOT NULL, text TEXT NOT NULL, tool TEXT, state TEXT, " +
                "seq INTEGER NOT NULL, rawJson TEXT NOT NULL, PRIMARY KEY(id))",
        )
        v2.execSQL(
            "CREATE TABLE pending_items (id TEXT NOT NULL, profileId TEXT NOT NULL, sessionId TEXT NOT NULL, " +
                "kind TEXT NOT NULL, rawJson TEXT NOT NULL, receivedAt INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        v2.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        v2.execSQL("INSERT INTO room_master_table (id,identity_hash) VALUES(42, 'placeholder')")
        v2.execSQL("INSERT INTO messages (id,profileId,sessionId,role,seq,rawJson,timeCreated) " +
            "VALUES ('msg_old','prof_1','ses_1','assistant',1,'{}',1000)")
        v2.version = 2
        v2.close()

        val migrated = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ecr-migration-test.db",
        ).addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            assertThat(migrated.openHelper.writableDatabase.version).isEqualTo(3)
            val m = migrated.messageDao().observeMessages("prof_1", "ses_1").first().single()
            assertThat(m.id).isEqualTo("msg_old")
            assertThat(m.role).isEqualTo("assistant")
            assertThat(m.agent).isNull()
            assertThat(m.providerID).isNull()
            assertThat(m.modelID).isNull()
            // Writing rows with the new badge columns must work on the migrated DB.
            migrated.messageDao().upsert(
                MessageEntity(
                    id = "msg_new",
                    profileId = "prof_1",
                    sessionId = "ses_1",
                    role = "user",
                    seq = 2,
                    rawJson = "{}",
                    timeCreated = 2000,
                    agent = "code",
                    providerID = "openrouter",
                    modelID = "m1",
                ),
            )
            val inserted = migrated.messageDao().observeMessages("prof_1", "ses_1").first().first { it.id == "msg_new" }
            assertThat(inserted.agent).isEqualTo("code")
            assertThat(inserted.providerID).isEqualTo("openrouter")
            assertThat(inserted.modelID).isEqualTo("m1")
        } finally {
            migrated.close()
            context.deleteDatabase("ecr-migration-test.db")
        }
    }
}