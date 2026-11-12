package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.SupervisedModePolicy
import com.hermesandroid.relay.data.SupervisedSessionAction
import com.hermesandroid.relay.data.allowsSessionAction
import com.hermesandroid.relay.ui.components.SessionListBody
import com.hermesandroid.relay.viewmodel.ChatViewModel

/** The primary Chat tab: a full-page projection of the same session body used by the drawer. */
@Composable
fun SessionListScreen(
    chatViewModel: ChatViewModel,
    supervisedPolicy: SupervisedModePolicy,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
) {
    val sessions by chatViewModel.sessions.collectAsState()
    val currentSessionId by chatViewModel.currentSessionId.collectAsState()
    val isLoading by chatViewModel.isLoadingSessions.collectAsState()
    val loadFailed by chatViewModel.sessionListUnavailable.collectAsState()
    val isLoadingMore by chatViewModel.isLoadingMoreSessions.collectAsState()
    val hasMore by chatViewModel.hasMoreSessions.collectAsState()
    val loadMoreFailed by chatViewModel.sessionPageLoadFailed.collectAsState()
    val activityStates by chatViewModel.backgroundSessionActivityStates.collectAsState()
    val completionReceipts by chatViewModel.completionReceipts.collectAsState()
    val autoTitlesSupported by chatViewModel.serverAutoTitles.collectAsState()
    val archiveSupported by chatViewModel.sessionArchivingSupported.collectAsState()

    SessionListBody(
        sessions = if (
            supervisedPolicy.enabled && !supervisedPolicy.capabilities.conversationHistory
        ) emptyList() else sessions,
        currentSessionId = currentSessionId,
        scopeTitle = stringResource(R.string.session_list_title),
        isLoading = isLoading,
        loadFailed = loadFailed,
        isLoadingMore = isLoadingMore,
        hasMore = hasMore,
        loadMoreFailed = loadMoreFailed,
        activityStates = activityStates,
        unreadSessionIds = completionReceipts
            .asSequence()
            .filter { it.unread }
            .mapTo(mutableSetOf()) { it.sessionId },
        autoTitlesSupported = autoTitlesSupported,
        archiveSupported = archiveSupported,
        supervisedSessionActions = supervisedPolicy.capabilities.sessionActions
            .takeIf { supervisedPolicy.enabled },
        newChatEnabled = !supervisedPolicy.enabled || supervisedPolicy.capabilities.newChat,
        onRefresh = chatViewModel::refreshSessions,
        onLoadMore = chatViewModel::loadMoreSessions,
        onRetryLoadMore = chatViewModel::retryLoadMoreSessions,
        onNewChat = {
            chatViewModel.createNewChat()
            onNewSession()
        },
        onSelectSession = { sessionId ->
            chatViewModel.switchSession(sessionId)
            onOpenSession(sessionId)
        },
        onDeleteSession = { sessionId ->
            if (!supervisedPolicy.enabled ||
                supervisedPolicy.allowsSessionAction(SupervisedSessionAction.Delete)
            ) chatViewModel.deleteSession(sessionId)
        },
        onRenameSession = { sessionId, title ->
            if (!supervisedPolicy.enabled ||
                supervisedPolicy.allowsSessionAction(SupervisedSessionAction.Rename)
            ) chatViewModel.renameSession(sessionId, title)
        },
        onSetSessionPinned = { sessionId, pinned ->
            if (!supervisedPolicy.enabled ||
                supervisedPolicy.allowsSessionAction(SupervisedSessionAction.Pin)
            ) chatViewModel.setSessionPinned(sessionId, pinned)
        },
        onSetSessionArchived = { sessionId, archived ->
            if (!supervisedPolicy.enabled ||
                supervisedPolicy.allowsSessionAction(SupervisedSessionAction.Archive)
            ) chatViewModel.setSessionArchived(sessionId, archived)
        },
        asFullPage = true,
        modifier = Modifier.fillMaxSize().testTag("sessionList.root"),
        itemTestTag = "sessionList.item",
        newButtonTestTag = "sessionList.new",
    )
}
