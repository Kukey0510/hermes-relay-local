package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ChatSession
import java.util.Locale

internal fun sourceGroupOf(source: String?): SessionSourceGroup =
    when (source?.trim()?.lowercase(Locale.ROOT)) {
        "qqbot" -> SessionSourceGroup.QQ
        "phone" -> SessionSourceGroup.Mobile
        "tui", "api_server", "web", "desktop" -> SessionSourceGroup.WebDesktop
        else -> SessionSourceGroup.Other
    }

internal fun filterSessions(
    sessions: List<ChatSession>,
    sources: Set<SessionSourceGroup>,
    tags: Set<String>,
    tagsOf: (String) -> List<String>,
): List<ChatSession> = sessions.filter { session ->
    (sources.isEmpty() || sourceGroupOf(session.source) in sources) &&
        (tags.isEmpty() || tagsOf(session.sessionId).any { it in tags })
}
