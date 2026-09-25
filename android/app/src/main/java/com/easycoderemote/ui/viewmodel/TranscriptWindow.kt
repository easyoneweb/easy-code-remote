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
 *   keeps items newest-first.
 * - Known ids get fresh parts on every emission → in-place streaming updates and
 *   stable ordering across history re-fetches.
 * - Unknown ids are placed by seq: newer than the window's newest are live SSE
 *   messages (live edge); older than the window's oldest are pagination pages
 *   (tail). Nothing else is admitted, so a re-fetch can never duplicate or
 *   reorder the window.
 * - Messages that disappear from Room (retraction / `message.removed`) leave the
 *   window automatically.
 * - Eviction bounds only live-edge growth: the newest SSE stream cannot grow the
 *   window past [maxSize], while older pages extend it freely. Evicting on
 *   pagination instead would make back-paging a no-op once the window is full
 *   (the just-fetched page would be trimmed away immediately).
 */
class TranscriptWindow(private val maxSize: Int = 2000) {

    var items: List<TranscriptMessage> = emptyList()
        private set

    fun onRoom(room: List<TranscriptMessage>): List<TranscriptMessage> {
        if (room.isEmpty()) {
            items = emptyList()
            return items
        }
        val inWindow = itemsById
        // Ordering is keyed on the server creation time (message.seq is only a
        // local tiebreak and can be unreliable for history fetched before the
        // creation-time column existed).
        val newestTs = items.firstOrNull()?.message?.timeCreated
        val oldestTs = items.lastOrNull()?.message?.timeCreated

        var grewAtLiveEdge = newestTs == null // first fill == live growth
        val merged = LinkedHashMap<String, TranscriptMessage>(room.size * 2)
        for (tm in room) {
            when {
                inWindow[tm.message.id] != null -> merged[tm.message.id] = tm
                newestTs == null -> {
                    merged[tm.message.id] = tm
                    grewAtLiveEdge = true
                }
                tm.message.timeCreated > newestTs -> {
                    merged[tm.message.id] = tm
                    grewAtLiveEdge = true
                }
                oldestTs != null && tm.message.timeCreated < oldestTs -> merged[tm.message.id] = tm // pagination
            }
        }

        val sorted = merged.values.sortedWith(
            compareByDescending<TranscriptMessage> { it.message.timeCreated }
                .thenByDescending { it.message.seq },
        )
        val windowed = if (grewAtLiveEdge && sorted.size > maxSize) sorted.take(maxSize) else sorted
        items = windowed
        itemsById = windowed.associateByTo(LinkedHashMap()) { it.message.id }
        return windowed
    }

    private var itemsById: Map<String, TranscriptMessage> = emptyMap()
}