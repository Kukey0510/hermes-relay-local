package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFiltersTest {
    @Test
    fun sourcesMapToFixedGroups() {
        assertEquals(SessionSourceGroup.QQ, sourceGroupOf("qqbot"))
        assertEquals(SessionSourceGroup.Mobile, sourceGroupOf("phone"))
        assertEquals(SessionSourceGroup.WebDesktop, sourceGroupOf("tui"))
        assertEquals(SessionSourceGroup.WebDesktop, sourceGroupOf("api_server"))
        assertEquals(SessionSourceGroup.Other, sourceGroupOf("discord"))
        assertEquals(SessionSourceGroup.Other, sourceGroupOf(null))
    }

    @Test
    fun filtersUseOrWithinGroupsAndAndBetweenGroups() {
        val sessions = listOf(
            session("qq", "qqbot"),
            session("phone", "phone"),
            session("web", "tui"),
            session("other", "discord"),
        )
        val tags = mapOf("qq" to listOf("work"), "phone" to listOf("home"), "web" to listOf("work"))
        val tagsOf: (String) -> List<String> = { tags[it].orEmpty() }

        assertEquals(listOf("qq"), filterSessions(sessions, setOf(SessionSourceGroup.QQ), emptySet(), tagsOf).ids())
        assertEquals(listOf("qq", "web"), filterSessions(sessions, emptySet(), setOf("work"), tagsOf).ids())
        assertEquals(listOf("qq"), filterSessions(sessions, setOf(SessionSourceGroup.QQ), setOf("work"), tagsOf).ids())
        assertEquals(sessions, filterSessions(sessions, emptySet(), emptySet(), tagsOf))
        assertEquals(
            listOf("qq", "phone"),
            filterSessions(sessions, setOf(SessionSourceGroup.QQ, SessionSourceGroup.Mobile), emptySet(), tagsOf).ids(),
        )
    }

    private fun session(id: String, source: String?) =
        ChatSession(sessionId = id, title = null, model = null, source = source)
    private fun List<ChatSession>.ids() = map(ChatSession::sessionId)
}
