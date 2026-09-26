package com.easycoderemote.ui.viewmodel

import com.easycoderemote.data.model.SessionDto

/**
 * Pure windowing/archived-filter helpers for the sessions list (plan D1). No
 * Android dependencies — fully unit-testable.
 *
 * "Lazy load" is progressive disclosure over the locally-cached Room data, NOT
 * server-side pagination: SSE `resync.required` ("re-fetch all of `/sessions`")
 * and `session.deleted`/status overlays require the full local set. Rendering is
 * windowed so composition/recomposition stays proportional to the visible rows.
 */
object SessionsPaging {

    /** Default/step size of the rendered window and of `loadMore()`. */
    const val PAGE_SIZE = 30

    /** Archived filter: archived sessions are hidden by default behind a toggle. */
    fun filterArchived(all: List<SessionDto>, showArchived: Boolean): List<SessionDto> =
        if (showArchived) all else all.filterNot { it.isArchived }

    /** Progressive-disclosure window: the first [visible] rows of [all]. */
    fun windowSessions(all: List<SessionDto>, visible: Int): List<SessionDto> =
        all.take(visible.coerceAtLeast(0))

    /** True when more rows exist beyond the currently rendered window. */
    fun hasMore(visible: Int, total: Int): Boolean = visible < total
}