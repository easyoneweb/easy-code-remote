package com.easycoderemote.ui.viewmodel

import com.easycoderemote.data.model.SessionDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Windowed/archived-filter paging semantics of the sessions list (plan D1). */
class SessionsPagingTest {

    private fun sessions(n: Int, archived: Boolean = false): List<SessionDto> =
        (1..n).map { SessionDto(id = "s$it", title = "S$it", archived = archived) }

    @Test
    fun filterArchivedHidesArchivedSessionsByDefault() {
        val all = sessions(3) + sessions(2, archived = true)
        val hidden = SessionsPaging.filterArchived(all, showArchived = false)
        assertThat(hidden.map { it.id }).containsExactly("s1", "s2", "s3").inOrder()
        assertThat(SessionsPaging.filterArchived(all, showArchived = true)).hasSize(5)
    }

    @Test
    fun windowSessionsTakesFirstVisibleRowsInOrder() {
        val all = sessions(100)
        val window = SessionsPaging.windowSessions(all, 30)
        assertThat(window).hasSize(30)
        assertThat(window.first().id).isEqualTo("s1")
        assertThat(window.last().id).isEqualTo("s30")
    }

    @Test
    fun windowSessionsHandlesEmptyShortAndNonPositive() {
        assertThat(SessionsPaging.windowSessions(emptyList(), 30)).isEmpty()
        assertThat(SessionsPaging.windowSessions(sessions(2), 30)).hasSize(2)
        assertThat(SessionsPaging.windowSessions(sessions(5), 0)).isEmpty()
        assertThat(SessionsPaging.windowSessions(sessions(5), -1)).isEmpty()
    }

    @Test
    fun hasMoreReflectsWindowBoundary() {
        assertThat(SessionsPaging.hasMore(visible = 30, total = 100)).isTrue()
        assertThat(SessionsPaging.hasMore(visible = 100, total = 100)).isFalse()
        assertThat(SessionsPaging.hasMore(visible = 0, total = 0)).isFalse()
    }

    @Test
    fun totalCountIsPostFilter() {
        // SessionsViewModel.totalCount = filterArchived(all, showArchived).size.
        val all = sessions(3) + sessions(2, archived = true)
        assertThat(SessionsPaging.filterArchived(all, showArchived = false)).hasSize(3)
        assertThat(SessionsPaging.filterArchived(all, showArchived = true)).hasSize(5)
    }
}