package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.SessionOrganization
import com.hermesandroid.relay.data.SessionOrganizationStore
import com.hermesandroid.relay.data.SupervisedModePolicy
import com.hermesandroid.relay.data.SupervisedSessionAction
import com.hermesandroid.relay.data.allowsSessionAction
import com.hermesandroid.relay.ui.components.SessionListBody
import com.hermesandroid.relay.viewmodel.ChatViewModel
import java.io.IOException
import kotlinx.coroutines.launch

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

    val context = LocalContext.current.applicationContext
    val organizationStore = remember(context) { SessionOrganizationStore(context) }
    val organization by organizationStore.state.collectAsState(initial = SessionOrganization())
    val scope = rememberCoroutineScope()
    var storageFailed by remember { mutableStateOf(false) }
    fun persist(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (_: IOException) {
                storageFailed = true
            }
        }
    }
    if (storageFailed) {
        AlertDialog(
            onDismissRequest = { storageFailed = false },
            text = { Text(stringResource(R.string.session_tags_storage_failed)) },
            confirmButton = {
                TextButton(onClick = { storageFailed = false }) {
                    Text(stringResource(R.string.session_tags_done))
                }
            },
        )
    }

    SessionListBody(
        organization = organization,
        onSaveSessionTags = { id, tags -> persist { organizationStore.setSessionTags(id, tags) } },
        onDeleteTag = { tag -> persist { organizationStore.deleteTag(tag) } },
        onToggleGroup = { key -> persist { organizationStore.toggleGroup(key) } },
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
