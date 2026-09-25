package com.easycoderemote.ui.viewmodel

import com.easycoderemote.data.repo.TranscriptMessage

/** Live-edge transcript window state exposed to the detail screen (plan §5.8). */
data class SessionWindow(
    val items: List<TranscriptMessage> = emptyList(), // newest-first
    val hasMore: Boolean = true,
    val loadingOlder: Boolean = false,
)

/**
 * Pure in-memory windowing over Room transcript emissions for one session
 * (plan §5.8). No Android dependencies, fully unit-testable.
 *
 * - Room emits the full transcript (oldest-first, unique message ids); the window
 *   keeps the newest ~[maxSize] items, ordered newest-first.
 * - Known ids get fresh parts on every emission → in-place streaming updates and
 *   stable seq across history re-fetches.
 * - Unknown ids are placed by seq: newer than the window's newest are live SSE
 *   messages (they land at the live edge); older than the window's oldest are
 *   pagination pages (they land at the tail). Nothing else is admitted, so a
 *   re-fetch can never duplicate or reorder the window.
 * - Messages that disappear from Room (retraction / `message.removed`) leave the
 *   window automatically.
 * - Eviction: when the window exceeds [maxSize], the oldest items drop out — they
 *   stay in Room and are re-fetchable by pagination.
 */
class TranscriptWindow(private val maxSize: Int = 500) {

    var items: List<TranscriptMessage> = emptyList()
        private set

    fun onRoom(room: List<TranscriptMessage>): List<TranscriptMessage> {
        if (room.isEmpty()) {
            items = emptyList()
            return items
        }
        val inWindow = itemsById
        val newestSeq = items.firstOrNull()?.message?.seq
        val oldestSeq = items.lastOrNull()?.message?.seq

        val merged = LinkedHashMap<String, TranscriptMessage>(room.size * 2)
        for (tm in room) {
            when {
                inWindow[tm.message.id] != null -> merged[tm.message.id] = tm
                newestSeq == null -> merged[tm.message.id] = tm // first fill
                tm.message.seq > newestSeq -> merged[tm.message.id] = tm // live SSE
                oldestSeq != null && tm.message.seq < oldestSeq -> merged[tm.message.id] = tm // pagination
            }
        }

        val sorted = merged.values.sortedByDescending { it.message.seq }
        val windowed = if (sorted.size > maxSize) sorted.take(maxSize) else sorted
        items = windowed
        itemsById = windowed.associateByTo(LinkedHashMap()) { it.message.id }
        return windowed
    }

    private var itemsById: Map<String, TranscriptMessage> = emptyMap()
}