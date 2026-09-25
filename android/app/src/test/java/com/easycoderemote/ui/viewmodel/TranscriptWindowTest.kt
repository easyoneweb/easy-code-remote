package com.easycoderemote.ui.viewmodel

import com.easycoderemote.data.local.MessageEntity
import com.easycoderemote.data.local.PartEntity
import com.easycoderemote.data.repo.TranscriptMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Plan §10: windowing logic — page merge by message id, dedup, anchor drift with
 * new messages while scrolled up, eviction, part removal/compaction.
 */
class TranscriptWindowTest {

    private fun msg(id: String, seq: Long, body: String = "body of $id"): TranscriptMessage {
        val m = MessageEntity(
            id = id, profileId = "p", sessionId = "s", role = "assistant", seq = seq, rawJson = "{}",
            timeCreated = seq, // tests mirror real data: seq asc == timeCreated asc
        )
        val part = PartEntity(
            id = "${id}_p", profileId = "p", sessionId = "s", messageId = id,
            type = "text", text = body, tool = null, state = null, seq = 1, rawJson = "",
        )
        return TranscriptMessage(m, listOf(part))
    }

    private fun msgAt(id: String, timeCreated: Long, seq: Long = timeCreated): TranscriptMessage {
        val m = MessageEntity(
            id = id, profileId = "p", sessionId = "s", role = "assistant", seq = seq, rawJson = "{}",
            timeCreated = timeCreated,
        )
        val part = PartEntity(
            id = "${id}_p", profileId = "p", sessionId = "s", messageId = id,
            type = "text", text = "body of $id", tool = null, state = null, seq = 1, rawJson = "",
        )
        return TranscriptMessage(m, listOf(part))
    }

    private fun ids(out: List<TranscriptMessage>): List<String> = out.map { it.message.id }

    @Test
    fun firstFillKeepsNewestMaxSize() {
        val w = TranscriptWindow(maxSize = 5)
        val room = (1L..10L).map { msg("m$it", it) }
        val out = w.onRoom(room)
        assertThat(ids(out)).containsExactly("m10", "m9", "m8", "m7", "m6").inOrder()
    }

    @Test
    fun liveSseMessagesAppearAtTheLiveEdge() {
        val w = TranscriptWindow(maxSize = 10)
        w.onRoom((1L..3L).map { msg("m$it", it) })
        val out = w.onRoom((1L..5L).map { msg("m$it", it) })
        assertThat(ids(out)).containsExactly("m5", "m4", "m3", "m2", "m1").inOrder()
    }

    @Test
    fun paginationOlderPageAppendsAtTheTail() {
        val w = TranscriptWindow(maxSize = 10)
        w.onRoom((3L..5L).map { msg("m$it", it) })
        val older = (1L..2L).map { msg("m$it", it) }
        val out = w.onRoom(older + (3L..5L).map { msg("m$it", it) })
        assertThat(ids(out)).containsExactly("m5", "m4", "m3", "m2", "m1").inOrder()
    }

    @Test
    fun partsAreRefreshedInPlaceForKnownIds() {
        val w = TranscriptWindow(maxSize = 10)
        w.onRoom(listOf(msg("m1", 1)))
        val out = w.onRoom(listOf(msg("m1", 1, body = "updated body")))
        assertThat(out.single().parts.single().text).isEqualTo("updated body")
    }

    @Test
    fun removedMessagesLeaveTheWindow() {
        val w = TranscriptWindow(maxSize = 10)
        w.onRoom((1L..3L).map { msg("m$it", it) })
        // m1 removed (message.removed / retraction).
        val out = w.onRoom((2L..3L).map { msg("m$it", it) })
        assertThat(ids(out)).containsExactly("m3", "m2").inOrder()
    }

    @Test
    fun evictionDropsOldestBeyondCap() {
        val w = TranscriptWindow(maxSize = 3)
        val out = w.onRoom((1L..6L).map { msg("m$it", it) })
        assertThat(out).hasSize(3)
        assertThat(ids(out)).containsExactly("m6", "m5", "m4").inOrder()
    }

    @Test
    fun emptyRoomClearsTheWindow() {
        val w = TranscriptWindow(maxSize = 10)
        w.onRoom(listOf(msg("m1", 1)))
        assertThat(w.onRoom(emptyList())).isEmpty()
    }

    @Test
    fun paginationRaceNeverDuplicates() {
        // Plan §11: a live message arrives while an older page is being merged —
        // ids must never duplicate and order stays newest-first.
        val w = TranscriptWindow(maxSize = 10)
        w.onRoom(listOf(msg("m5", 5)))
        val out = w.onRoom((1L..6L).map { msg("m$it", it) })
        assertThat(ids(out)).containsExactly("m6", "m5", "m4", "m3", "m2", "m1").inOrder()
        assertThat(out.map { it.message.id }.distinct()).hasSize(out.size)
    }

    @Test
    fun paginationExtendsBeyondCap() {
        // Back-paging must never be a no-op once the window is full: older pages
        // extend the window freely (eviction only bounds live-edge growth).
        val w = TranscriptWindow(maxSize = 3)
        w.onRoom(listOf(msg("m4", 4), msg("m5", 5)))
        val out = w.onRoom((1L..5L).map { msg("m$it", it) })
        assertThat(ids(out)).containsExactly("m5", "m4", "m3", "m2", "m1").inOrder()
    }

    @Test
    fun liveGrowthStillEvictsAtCap() {
        // Newest SSE messages bound the window: once the cap is hit, the oldest
        // items (still in Room) leave the window to make room.
        val w = TranscriptWindow(maxSize = 3)
        w.onRoom(listOf(msg("m4", 4), msg("m5", 5)))
        val out = w.onRoom((1L..6L).map { msg("m$it", it) })
        assertThat(ids(out)).containsExactly("m6", "m5", "m4").inOrder()
    }

    @Test
    fun refreshWithIdenticalHistoryKeepsWindowStable() {
        val w = TranscriptWindow(maxSize = 10)
        w.onRoom((1L..5L).map { msg("m$it", it) })
        // Same history re-emitted (refetch) — no churn, same order.
        val out = w.onRoom((1L..5L).map { msg("m$it", it) })
        assertThat(ids(out)).containsExactly("m5", "m4", "m3", "m2", "m1").inOrder()
    }

    @Test
    fun orderingFollowsCreationTimeNotSeq() {
        // History stored before the creation-time column existed can have an
        // INVERTED seq (newest got the lowest seq). Ordering must follow the
        // server creation time, with seq only as a tiebreak.
        val w = TranscriptWindow(maxSize = 10)
        val out = w.onRoom(
            listOf(
                msgAt("m1", timeCreated = 100, seq = 5), // oldest, but highest seq
                msgAt("m2", timeCreated = 200, seq = 4),
                msgAt("m3", timeCreated = 300, seq = 3),
                msgAt("m4", timeCreated = 400, seq = 2),
                msgAt("m5", timeCreated = 500, seq = 1), // newest, but lowest seq
            ),
        )
        assertThat(ids(out)).containsExactly("m5", "m4", "m3", "m2", "m1").inOrder()
    }

    @Test
    fun liveEdgeAdmissionUsesCreationTime() {
        // A live message whose seq is LOWER than the window's (inverted-seq era)
        // must still be admitted at the live edge based on its creation time.
        val w = TranscriptWindow(maxSize = 10)
        w.onRoom(listOf(msgAt("m3", timeCreated = 300, seq = 1), msgAt("m4", timeCreated = 400, seq = 0)))
        val out = w.onRoom(
            listOf(
                msgAt("m5", timeCreated = 500, seq = -1), // newest by time, lowest by seq
                msgAt("m3", timeCreated = 300, seq = 1),
                msgAt("m4", timeCreated = 400, seq = 0),
            ),
        )
        assertThat(ids(out)).containsExactly("m5", "m4", "m3").inOrder()
    }
}