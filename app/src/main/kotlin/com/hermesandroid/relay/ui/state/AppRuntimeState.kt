package com.hermesandroid.relay.ui.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.auth.PairedSession
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.SupervisedModePolicy
import com.hermesandroid.relay.data.SupervisedModeStore
import com.hermesandroid.relay.ui.isRelayNavigationHydrated
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability

/**
 * App 根节点长期读取的运行时快照。
 *
 * 这里仅收集和组合状态；导航、界面绘制以及把策略写回 ViewModel 的副作用仍由
 * `RelayApp` 负责。这样后续页面只需读取同一份快照，不会各自重复订阅状态流。
 */
internal data class AppRuntimeState(
    /** 冷启动与配对入口用于判断当前鉴权阶段。 */
    val coldStartAuthState: AuthState,
    /** 配对、设置和状态条共同展示的当前配对会话。 */
    val currentPairedSession: PairedSession?,
    /** 聊天、监管模式和资料页使用的当前 Profile。 */
    val selectedProfile: Profile?,
    /** 插件、Git 与管理页请求使用的有效 Profile 名。 */
    val effectiveSessionProfileName: String?,
    /** 插件请求和 Git 工作区绑定使用的当前聊天会话 id。 */
    val currentChatSessionId: String?,
    /** 聊天页和状态条展示使用的有效 Profile。 */
    val effectiveDisplayProfile: Profile?,
    /** 监管模式确认固定 Profile 前用于避免抢跑。 */
    val profileSelectionSettled: Boolean,
    /** 监管模式解析固定 Profile、设置页展示可选项时使用。 */
    val agentProfiles: List<Profile>,
    /** 监管策略归属、连接切换和各服务配置使用的活动连接 id。 */
    val activeConnectionId: String?,
    /** 状态条、能力判断和连接设置使用的活动连接。 */
    val activeConnection: Connection?,
    /** 导航与配对入口等待连接仓库完成首帧恢复。 */
    val connectionStoreHydrated: Boolean,
    /** 设置页持久化监管策略时使用的、按连接隔离的策略仓库。 */
    val supervisedModeStore: SupervisedModeStore,
    /** 新建连接点击时读取最新归属，避免闭包使用过期监管策略。 */
    val supervisedPolicyState: State<Pair<String?, SupervisedModePolicy>?>,
    /** 导航在连接和对应监管策略均恢复后才放行。 */
    val relayNavigationHydrated: Boolean,
    /** 全局路由拦截、主题锁定和聊天约束使用的监管策略。 */
    val supervisedPolicy: SupervisedModePolicy,
    /** 监管模式自动选中并锁定的 Profile。 */
    val supervisedPinnedProfile: Profile?,
    /** 聊天层使用的策略；固定 Profile 未确认时暂不下发其名称。 */
    val chatSupervisedPolicy: SupervisedModePolicy,
    /** 家长解锁由 RelayApp 的路由和生命周期副作用读写。 */
    val parentAccessUnlocked: MutableState<Boolean>,
    /** 连接列表、切换器与配对流程使用的完整连接快照。 */
    val connections: List<Connection>,
    /** 语音入口用于判断标准 Dashboard 语音是否可用。 */
    val standardVoiceAvailability: StandardVoiceAvailability,
    /** 语音入口用于判断 Relay 语音通道是否就绪。 */
    val relayVoiceReady: Boolean,
)

/** 收集 RelayApp 所需的长期状态，不创建界面、不改变任何业务状态。 */
@Composable
internal fun rememberAppRuntimeState(
    applicationContext: Context,
    connectionViewModel: ConnectionViewModel,
    chatViewModel: ChatViewModel,
): AppRuntimeState {
    val coldStartAuthState by connectionViewModel.authState.collectAsState()
    val currentPairedSession by connectionViewModel.currentPairedSession.collectAsState()
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    val effectiveSessionProfileName by connectionViewModel.effectiveSessionProfileName.collectAsState()
    val currentChatSessionId by chatViewModel.currentSessionId.collectAsState()
    val effectiveDisplayProfile by connectionViewModel.effectiveDisplayProfile.collectAsState()
    val profileSelectionSettled by connectionViewModel.profileSelectionSettled.collectAsState()
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val activeConnectionId by connectionViewModel.activeConnectionId.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val connectionStoreHydrated by connectionViewModel.connectionStore.isHydrated.collectAsState()
    val supervisedModeStore = remember(applicationContext) { SupervisedModeStore(applicationContext) }
    val supervisedPolicyState = produceState<Pair<String?, SupervisedModePolicy>?>(
        initialValue = null,
        key1 = activeConnectionId,
        key2 = supervisedModeStore,
    ) {
        val connectionId = activeConnectionId
        if (connectionId == null) {
            value = null to SupervisedModePolicy()
        } else {
            supervisedModeStore.policyFlow(connectionId).collect { policy ->
                value = connectionId to policy
            }
        }
    }
    val ownedSupervisedPolicyState = supervisedPolicyState.value
        ?.takeIf { (ownerConnectionId, _) -> ownerConnectionId == activeConnectionId }
    val relayNavigationHydrated = isRelayNavigationHydrated(
        connectionStoreHydrated = connectionStoreHydrated,
        activeConnectionId = activeConnectionId,
        supervisedPolicyHydrated = ownedSupervisedPolicyState != null,
    )
    val supervisedPolicy = ownedSupervisedPolicyState?.second ?: SupervisedModePolicy()
    val supervisedPinnedProfile = supervisedPolicy.pinnedProfileName?.let { name ->
        agentProfiles.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
    val supervisedProfileConfirmed = !supervisedPolicy.enabled || (
        profileSelectionSettled &&
            supervisedPinnedProfile != null &&
            selectedProfile?.name.equals(supervisedPinnedProfile.name, ignoreCase = true)
        )
    val chatSupervisedPolicy = if (supervisedPolicy.enabled && !supervisedProfileConfirmed) {
        supervisedPolicy.copy(pinnedProfileName = null)
    } else {
        supervisedPolicy
    }
    val parentAccessUnlocked = remember(activeConnectionId) { mutableStateOf(false) }
    val connections by connectionViewModel.connections.collectAsState()
    val standardVoiceAvailability by connectionViewModel.standardVoiceAvailability.collectAsState()
    val relayVoiceReady by connectionViewModel.relayVoiceReady.collectAsState()

    return AppRuntimeState(
        coldStartAuthState = coldStartAuthState,
        currentPairedSession = currentPairedSession,
        selectedProfile = selectedProfile,
        effectiveSessionProfileName = effectiveSessionProfileName,
        currentChatSessionId = currentChatSessionId,
        effectiveDisplayProfile = effectiveDisplayProfile,
        profileSelectionSettled = profileSelectionSettled,
        agentProfiles = agentProfiles,
        activeConnectionId = activeConnectionId,
        activeConnection = activeConnection,
        connectionStoreHydrated = connectionStoreHydrated,
        supervisedModeStore = supervisedModeStore,
        supervisedPolicyState = supervisedPolicyState,
        relayNavigationHydrated = relayNavigationHydrated,
        supervisedPolicy = supervisedPolicy,
        supervisedPinnedProfile = supervisedPinnedProfile,
        chatSupervisedPolicy = chatSupervisedPolicy,
        parentAccessUnlocked = parentAccessUnlocked,
        connections = connections,
        standardVoiceAvailability = standardVoiceAvailability,
        relayVoiceReady = relayVoiceReady,
    )
}
