package com.hermesandroid.relay.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import com.hermesandroid.relay.ui.components.ThemedMessageHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermesandroid.relay.R
import com.hermesandroid.relay.HermesRelayApp
import com.hermesandroid.relay.ui.components.CrashReportGate
import com.hermesandroid.relay.ui.components.CandidateBuildBanner
import com.hermesandroid.relay.ui.components.DemoModeBanner
import com.hermesandroid.relay.ui.components.DemoUnavailableContent
import com.hermesandroid.relay.ui.components.MessageBannerHost
import com.hermesandroid.relay.ui.components.newestPetAssistantIsSettled
import com.hermesandroid.relay.ui.components.newestPetVisitTargetUiKey
import com.hermesandroid.relay.ui.components.LocalAgentIconPath
import com.hermesandroid.relay.ui.components.LocalAvailableSphereSkins
import com.hermesandroid.relay.ui.components.LocalSphereSkin
import com.hermesandroid.relay.ui.components.SphereRegistry
import com.hermesandroid.relay.ui.components.SphereSkinLoader
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.components.avatar.LocalAvailableAvatars
import com.hermesandroid.relay.ui.components.avatar.LocalAvailablePets
import com.hermesandroid.relay.ui.components.avatar.LocalBackgroundVisualizationEnabled
import com.hermesandroid.relay.ui.components.avatar.LocalFloatingPet
import com.hermesandroid.relay.ui.components.avatar.LocalPetPlaybackSpeed
import com.hermesandroid.relay.ui.components.avatar.LocalPetStabilize
import com.hermesandroid.relay.ui.components.avatar.PetLoader
import com.hermesandroid.relay.ui.components.avatar.toAvatar
import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import com.hermesandroid.relay.ui.components.avatar.resolveBackgroundAvatar
import com.hermesandroid.relay.ui.components.FloatingPetCompanion
import com.hermesandroid.relay.ui.components.shouldCompactFloatingPet
import com.hermesandroid.relay.ui.components.pet.LocalPetCompanionCoordinator
import com.hermesandroid.relay.ui.components.pet.LocalPetSafeAreaRegistry
import com.hermesandroid.relay.ui.components.pet.PetCompanionCoordinator
import com.hermesandroid.relay.ui.components.pet.PetInteractionLayer
import com.hermesandroid.relay.ui.components.pet.PetSafeAreaRegistry
import com.hermesandroid.relay.ui.components.pet.petPerchSurface
import com.hermesandroid.relay.ui.components.pet.platformModalOwnsPetLayer
import com.hermesandroid.relay.ui.components.ConnectionSwitcherSheet
import com.hermesandroid.relay.ui.components.ChatMediaViewerHost
import com.hermesandroid.relay.ui.components.ChatTransportStatusBadge
import com.hermesandroid.relay.ui.components.ChatTransportTier
import com.hermesandroid.relay.ui.components.ConnectionSecurityGlyph
import com.hermesandroid.relay.ui.components.PowerFeatureGateScreen
import com.hermesandroid.relay.ui.components.PowerFeatureGateStatus
import com.hermesandroid.relay.ui.components.RelayStatusStrip
import com.hermesandroid.relay.ui.components.UnattendedGlobalBanner
import com.hermesandroid.relay.ui.components.UpdateAvailableBanner
import com.hermesandroid.relay.ui.components.HostResourcePressureBanner
import com.hermesandroid.relay.ui.components.rememberUpdateAvailability
import com.hermesandroid.relay.ui.components.resolveChatTransportStatus
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.ui.components.WhatsNewToast
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BridgePreferencesRepository
import com.hermesandroid.relay.data.BridgeSafetyPreferencesRepository
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.CandidateBuild
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.SessionTransport
import com.hermesandroid.relay.data.chatTransportForPreference
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.data.SupervisedModePolicy
import com.hermesandroid.relay.data.SupervisedModeStore
import com.hermesandroid.relay.data.VoicePresentationMode
import com.hermesandroid.relay.data.capabilities
import com.hermesandroid.relay.data.displayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hermesandroid.relay.util.HumanError
import kotlinx.coroutines.delay
import com.hermesandroid.relay.ui.onboarding.OnboardingScreen
import com.hermesandroid.relay.ui.screens.AboutScreen
import com.hermesandroid.relay.ui.screens.AdvancedSettingsScreen
import com.hermesandroid.relay.ui.screens.AnalyticsScreen
import com.hermesandroid.relay.ui.screens.AppearanceSettingsScreen
import com.hermesandroid.relay.ui.screens.CustomThemeScreen
import com.hermesandroid.relay.ui.screens.CustomPetGuideScreen
import com.hermesandroid.relay.ui.screens.PetdexBrowseScreen
import com.hermesandroid.relay.ui.screens.BridgeCoreScreen
import com.hermesandroid.relay.ui.screens.DiagnosticsScreen
import com.hermesandroid.relay.ui.screens.BridgeScreen
// === PHASE3-safety-rails: bridge safety route ===
import com.hermesandroid.relay.ui.screens.BridgeSafetySettingsScreen
import com.hermesandroid.relay.ui.screens.BotGroupDetailScreen
import com.hermesandroid.relay.ui.screens.BotChatScreen
import com.hermesandroid.relay.ui.screens.BotModeScreen
// === END PHASE3-safety-rails ===
import com.hermesandroid.relay.ui.screens.ChatScreen
import com.hermesandroid.relay.ui.screens.ChatSettingsScreen
import com.hermesandroid.relay.ui.screens.ChangelogScreen
import com.hermesandroid.relay.ui.screens.DashboardManagementScreen
import com.hermesandroid.relay.ui.screens.DashboardSignInScreen
import com.hermesandroid.relay.ui.screens.DeveloperSettingsScreen
import com.hermesandroid.relay.ui.screens.MediaSettingsScreen
import com.hermesandroid.relay.ui.screens.PairedDevicesScreen
import com.hermesandroid.relay.ui.screens.ConnectionsSettingsScreen
import com.hermesandroid.relay.ui.screens.PermissionsStatusScreen
import com.hermesandroid.relay.ui.screens.ProfileInspectorScreen
import com.hermesandroid.relay.ui.screens.RealtimeVoiceTestScreen
import com.hermesandroid.relay.ui.screens.SettingsScreen
import com.hermesandroid.relay.ui.screens.SupervisedControlsScreen
import com.hermesandroid.relay.ui.screens.SupervisedAppearanceSettingsScreen
import com.hermesandroid.relay.ui.screens.UsageLimitsScreen
import com.hermesandroid.relay.ui.screens.PluginsScreen
import com.hermesandroid.relay.ui.screens.PluginPageScreen
import com.hermesandroid.relay.ui.screens.GitStateScreen
import com.hermesandroid.relay.viewmodel.GitStateViewModel
import com.hermesandroid.relay.viewmodel.GitStateUiState
import com.hermesandroid.relay.ui.screens.TerminalScreen
import com.hermesandroid.relay.ui.screens.NotificationCompanionSettingsScreen
import com.hermesandroid.relay.ui.screens.ProactiveSettingsScreen
import com.hermesandroid.relay.ui.screens.VoiceSettingsScreen
import com.hermesandroid.relay.ui.screens.prewarmDashboardManage
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayGridTexture
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.relay.RelayProfileInspectorClient
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.viewmodel.ChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.ChatTransportPath
import com.hermesandroid.relay.viewmodel.ChatTransportReadiness
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.plugins.runtime.PLUGIN_API_WRITE_CAPABILITY
import com.hermesandroid.relay.viewmodel.PluginsViewModel
import com.hermesandroid.relay.viewmodel.PluginsHubState
import com.hermesandroid.relay.viewmodel.ProfileInspectorViewModel
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import com.hermesandroid.relay.viewmodel.resolveChatRuntimeStatus
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.runtime.HermesRuntimeInitializationState
import com.hermesandroid.relay.ui.state.rememberAppRuntimeState

// Global snackbar host so any screen can surface a HumanError without
// plumbing a host through every ViewModel. Provided by RelayApp below.
val LocalSnackbarHost = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalSnackbarHost not provided — wrap your UI in RelayApp's CompositionLocalProvider")
}

// Short-lived snackbar by default; retryable errors get Long so users have
// time to tap the action before it auto-dismisses.
suspend fun SnackbarHostState.showHumanError(err: HumanError): SnackbarResult {
    return showSnackbar(com.hermesandroid.relay.ui.components.HumanErrorVisuals(err))
}

/** Startup chrome should wait for either standard chat surface, not Relay. */
internal fun hasConfiguredStartupChat(connection: Connection?): Boolean =
    connection?.capabilities?.chatConfigured == true

/**
 * A Pair route is allowed to start once its target connection is active and
 * persisted. Duplicate Renew may authorize one explicit existing-connection
 * handoff; arbitrary active-id mismatches remain blocked so restored state
 * cannot bypass connection/auth hydration.
 */
internal fun resolvePairSetupReady(
    storeHydrated: Boolean,
    connectionId: String?,
    authorizedHandoffId: String?,
    activeConnectionId: String?,
    connectionIds: Set<String>,
    draftConnectionId: String? = null,
): Boolean = connectionId == null || connectionId == draftConnectionId ||
    storeHydrated && activeConnectionId != null && (
        activeConnectionId == connectionId ||
            activeConnectionId == authorizedHandoffId && activeConnectionId in connectionIds
        )

/** A user retry replaces even a still-active preparation attempt. */
internal fun shouldStartPairPreparation(hasActiveJob: Boolean, retryRequested: Boolean): Boolean =
    retryRequested || !hasActiveJob

internal fun shouldCommitPairDraftBeforeDashboardSignIn(
    connectionId: String?,
    draftConnectionId: String?,
): Boolean = connectionId != null && connectionId == draftConnectionId

/** Pair-origin sign-in must finish the staged Dashboard-ingress Relay handshake. */
internal fun shouldResumePairingAfterDashboardAuthentication(source: String): Boolean =
    source == Screen.DashboardSignIn.SOURCE_PAIR ||
        source == Screen.DashboardSignIn.SOURCE_ONBOARDING

/** A replaced/canceled attempt must not evict the newer job from the route map. */
internal fun isCurrentPairPreparation(mappedJob: Any?, completingJob: Any): Boolean =
    mappedJob === completingJob

/**
 * App-root chat health derived only from the two transports that can carry a
 * conversation. Optional Relay state is deliberately absent.
 */
internal fun resolveAppChatRuntimeStatus(
    connection: Connection?,
    gatewayAvailability: GatewayAvailability,
    apiHealth: ConnectionViewModel.HealthStatus,
    streamingEndpoint: String = "auto",
    conversationOwner: SessionTransport? = null,
): ChatRuntimeStatus {
    val capabilities = connection?.capabilities
    val gateway = when {
        capabilities?.dashboardGatewayConfigured != true -> ChatTransportReadiness.NotConfigured
        gatewayAvailability == GatewayAvailability.Ready -> ChatTransportReadiness.Ready
        gatewayAvailability == GatewayAvailability.Unknown -> ChatTransportReadiness.Connecting
        else -> ChatTransportReadiness.Unavailable
    }
    val api = when {
        capabilities?.apiServerConfigured != true -> ChatTransportReadiness.NotConfigured
        apiHealth == ConnectionViewModel.HealthStatus.Reachable -> ChatTransportReadiness.Ready
        apiHealth == ConnectionViewModel.HealthStatus.Unknown ||
            apiHealth == ConnectionViewModel.HealthStatus.Probing -> ChatTransportReadiness.Connecting
        else -> ChatTransportReadiness.Unavailable
    }
    val owner = when (conversationOwner ?: connection?.chatTransportForPreference(streamingEndpoint)) {
        SessionTransport.SSE -> ChatTransportPath.ApiSse
        else -> ChatTransportPath.Gateway
    }
    return resolveChatRuntimeStatus(gateway = gateway, apiSse = api, owner = owner)
}

internal fun shouldSettleStartupUnreachable(
    hasConfiguredChat: Boolean,
    runtimeStatus: ChatRuntimeStatus,
): Boolean =
    hasConfiguredChat &&
        runtimeStatus is ChatRuntimeStatus.Unavailable

internal fun startupShellCanRender(
    appReady: Boolean,
    hasStartupConnection: Boolean,
    endpointSelected: Boolean,
    chatUp: Boolean,
    narrationStage: Int,
    unreachableConfirmed: Boolean,
    timedOut: Boolean,
): Boolean = appReady && (
    !hasStartupConnection ||
        ((endpointSelected || chatUp) && narrationStage >= 2) ||
        unreachableConfirmed ||
        timedOut
    )

/** Route represented by the app footer's currently usable chat transport. */
internal fun resolveFooterRouteCandidate(
    runtimeStatus: ChatRuntimeStatus,
    activeEndpoint: EndpointCandidate?,
    connection: Connection?,
    effectiveDashboardUrl: String,
): EndpointCandidate? {
    val connected = runtimeStatus as? ChatRuntimeStatus.Connected ?: return null
    return when (connected.transport) {
        ChatTransportPath.Gateway -> {
            val dashboardUrl = effectiveDashboardUrl.trim().trimEnd('/')
                .ifBlank { connection?.resolvedDashboardUrl.orEmpty() }
            if (dashboardUrl.isBlank()) {
                null
            } else {
                val activeDashboardUrl = activeEndpoint?.dashboard?.url
                    ?.trim()
                    ?.trimEnd('/')
                activeEndpoint?.takeIf { activeDashboardUrl == dashboardUrl }
                    ?: Connection.endpointCandidateFromDashboardUrl(
                        role = Connection.inferRouteRole(dashboardUrl),
                        priority = activeEndpoint?.priority ?: 0,
                        dashboardUrl = dashboardUrl,
                    )
            }
        }

        ChatTransportPath.ApiSse -> activeEndpoint?.takeIf { it.api != null }
            ?: connection?.routeCandidates?.firstOrNull { it.api != null }
    }
}

/**
 * Compact, surface-aware label for the persistent chat footer.
 *
 * Endpoint roles are operator and wire metadata, so an internal role such as
 * `authenticated_dashboard` must never leak into this constrained surface.
 * Gateway labels describe how the Dashboard is reached; Direct API keeps
 * the route's ordinary transport label.
 */
internal fun resolveFooterRouteLabel(
    runtimeStatus: ChatRuntimeStatus,
    route: EndpointCandidate?,
    fallbackLabel: String,
): String {
    val connected = runtimeStatus as? ChatRuntimeStatus.Connected ?: return ""
    if (route == null) return fallbackLabel
    if (connected.transport == ChatTransportPath.ApiSse) return route.displayLabel()

    return when (route.role.trim().lowercase()) {
        "lan" -> "LAN"
        "tailscale" -> "Tailscale"
        else -> if (
            route.dashboard?.url?.startsWith("https://", ignoreCase = true) == true
        ) {
            "HTTP"
        } else {
            route.displayLabel()
        }
    }
}

/** Keep the footer's model identity compact; context-window suffixes belong in model details. */
internal fun compactFooterModelLabel(model: String): String =
    model.substringAfterLast('/').replace(Regex("-\\d+[kKmM]$"), "")

/**
 * Conversation voice remains part of chat, so its persistent connection
 * footer stays visible. Focus voice is the only presentation that suppresses
 * the surrounding chat chrome.
 */
internal fun shouldShowConnectionFooter(
    voiceMode: Boolean,
    presentationMode: VoicePresentationMode,
): Boolean = !voiceMode || presentationMode == VoicePresentationMode.Conversation

/**
 * Full-screen setup owns the whole window. Keeping connection-dependent app
 * chrome composed behind it makes that chrome rebuild while Add connection
 * swaps to its placeholder, visibly resizing the entering setup surface.
 */
internal fun shouldSuppressGlobalChrome(
    onboardingCompleted: Boolean,
    isDemoMode: Boolean,
    currentRoute: String?,
): Boolean =
    (!onboardingCompleted && !isDemoMode) ||
        currentRoute == Screen.Onboarding.route ||
        currentRoute == Screen.Pair.route

internal const val APP_STATUS_PET_WALK_REGION = "app-status-footer"

/**
 * Routes where the persistent status chrome is deliberately exposed as a
 * pet ledge. Keep this list small: every entry must publish its active-scroll
 * and modal state so autonomous motion never fights the screen beneath it.
 */
internal val APP_STATUS_PET_ROUTES: Set<String> = setOf(
    Screen.Settings.route,
    Screen.AppearanceSettings.route,
    Screen.About.route,
)

internal fun petSurfaceOwnerForRoute(route: String?): String? = when (route) {
    Screen.Chat.route -> "chat"
    Screen.Terminal.route -> "terminal"
    in APP_STATUS_PET_ROUTES -> route
    else -> null
}

/** Petdex already supplies interactive pet previews; keep its install cards unobstructed. */
internal fun floatingPetAllowedOnRoute(route: String?): Boolean =
    route != Screen.PetdexBrowse.route

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Onboarding : Screen("onboarding", "Onboarding", Icons.Filled.Settings)
    // `openAgentSheet` — optional flag that tells ChatScreen to auto-open
    // its consolidated AgentInfoSheet on first composition. Added so the
    // Settings → Active Agent card can bounce the user straight to Chat
    // with the sheet pre-opened (Connection / Profile / Personality
    // editing lives inside the sheet). The arg is consumed once and then
    // cleared from the back-stack entry's arguments so returning to the
    // Chat tab later via bottom nav does NOT re-open the sheet.
    //
    // `route` stays the NavHost route template — matching the pattern used
    // by Screen.Pair — while [route] (the function) builds concrete URIs.
    // The bottom-nav selected-state hierarchy check keys off [route]
    // (the template), so the template must match what's registered in the
    // NavHost, and the NavigationBarItem click must navigate via [route]()
    // so no unresolved `{openAgentSheet}` leaks into the destination.
    data object Chat : Screen(
        "chat?openAgentSheet={openAgentSheet}&sessionId={sessionId}&profile={profile}" +
            "&proactiveChatId={proactiveChatId}&connectionId={connectionId}",
        "Chat",
        Icons.AutoMirrored.Filled.Chat,
    ) {
        const val ARG_OPEN_AGENT_SHEET: String = "openAgentSheet"
        const val ARG_SESSION_ID: String = "sessionId"
        const val ARG_PROFILE: String = "profile"
        const val ARG_PROACTIVE_CHAT_ID: String = "proactiveChatId"
        const val ARG_CONNECTION_ID: String = "connectionId"
        fun route(
            openAgentSheet: Boolean = false,
            sessionId: String? = null,
            profile: String? = null,
            proactiveChatId: String? = null,
            connectionId: String? = null,
        ): String {
            val params = buildList {
                if (openAgentSheet) add("$ARG_OPEN_AGENT_SHEET=true")
                connectionId?.takeIf { it.isNotBlank() }?.let {
                    add("$ARG_CONNECTION_ID=${android.net.Uri.encode(it)}")
                }
                sessionId?.takeIf { it.isNotBlank() }?.let {
                    add("$ARG_SESSION_ID=${android.net.Uri.encode(it)}")
                }
                profile?.takeIf { it.isNotBlank() }?.let {
                    add("$ARG_PROFILE=${android.net.Uri.encode(it)}")
                }
                proactiveChatId?.takeIf { it.isNotBlank() }?.let {
                    add("$ARG_PROACTIVE_CHAT_ID=${android.net.Uri.encode(it)}")
                }
            }
            return if (params.isEmpty()) "chat" else "chat?${params.joinToString("&")}"
        }
    }
    data object BotMode : Screen("bot_mode", "Bot Mode", Icons.Filled.Groups)
    data object BotGroup : Screen(
        "bot_mode/groups/{roomKey}",
        "Bot group",
        Icons.Filled.Groups,
    ) {
        const val ARG_ROOM_KEY: String = "roomKey"
        fun route(roomKey: String): String =
            "bot_mode/groups/${android.net.Uri.encode(roomKey)}"
    }
    data object BotChat : Screen(
        "bot_mode/chat/{connectionId}/{profileName}/{sessionId}",
        "Bot Chat",
        Icons.AutoMirrored.Filled.Chat,
    ) {
        const val ARG_CONNECTION_ID: String = "connectionId"
        const val ARG_PROFILE_NAME: String = "profileName"
        const val ARG_SESSION_ID: String = "sessionId"
        fun route(connectionId: String, profileName: String, sessionId: String): String =
            "bot_mode/chat/${android.net.Uri.encode(connectionId)}/" +
                "${android.net.Uri.encode(profileName)}/${android.net.Uri.encode(sessionId)}"
    }
    data object Terminal : Screen("terminal", "Terminal", Icons.Filled.Code)
    data object Bridge : Screen("bridge", "Bridge", Icons.Filled.PhoneAndroid)
    data object Manage : Screen("manage", "Manage", Icons.Filled.Settings)
    data object DashboardSignIn : Screen(
        "dashboard_sign_in?source={source}",
        "Dashboard sign in",
        Icons.Filled.Settings,
    ) {
        const val ARG_SOURCE: String = "source"
        const val SOURCE_GENERAL: String = "general"
        const val SOURCE_PAIR: String = "pair"
        const val SOURCE_ONBOARDING: String = "onboarding"
        fun route(source: String = SOURCE_GENERAL): String = "dashboard_sign_in?source=$source"
    }
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    data object Plugins : Screen("plugins", "Plugins", Icons.Filled.Extension)
    data object GitState : Screen("git_state", "Git", Icons.Filled.Code)
    data object PluginPage : Screen(
        "plugins/{pluginId}/pages/{pageId}",
        "Plugin",
        Icons.Filled.Extension,
    ) {
        const val ARG_PLUGIN_ID: String = "pluginId"
        const val ARG_PAGE_ID: String = "pageId"
        fun route(pluginId: String, pageId: String): String =
            "plugins/${encode(pluginId)}/pages/${encode(pageId)}"

        private fun encode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    // Non-bottom-nav destinations — reached by explicit navigation, not the
    // NavigationBar. "Relay sessions" is the user-facing label; the Kotlin
    // object name keeps `PairedDevices` to avoid churning navigation identifiers
    // and deep-link routes. Opened from Settings → Relay sessions and from the
    // active connection card's Security section.
    data object PairedDevices : Screen("paired_devices", "Relay sessions", Icons.Filled.Settings)
    // Full-screen pair wizard route. Replaces the old in-Settings Dialog
    // launch so the chooser + Confirm + Verify steps + the camera viewport
    // get a real fullscreen surface (the Dialog wasn't actually filling the
    // window — Settings cards were leaking through behind it).
    //
    // Multi-connection: accepts an optional `connectionId` query arg —
    // the ConnectionsSettings "Re-pair" button targets a specific
    // connection. The "Add connection" path creates a transient id-scoped
    // auth draft; it is persisted and activated only after setup succeeds.
    data object Pair : Screen(
        "pair?connectionId={connectionId}&autoStart={autoStart}",
        "Connect",
        Icons.Filled.Settings,
    ) {
        const val ARG_CONNECTION_ID: String = "connectionId"
        /**
         * Optional "skip the chooser, jump into this pair method" hint.
         * Currently only `"scan"` is recognised — the "Add connection" FAB
         * on the Connections screen passes it so the camera opens
         * immediately instead of forcing the user through the Method step.
         * Standard add/re-pair flows leave this null so the full chooser
         * remains available.
         */
        const val ARG_AUTO_START: String = "autoStart"
        fun route(connectionId: String? = null, autoStart: String? = null): String {
            val params = buildList {
                if (connectionId != null) add("connectionId=$connectionId")
                if (autoStart != null) add("autoStart=$autoStart")
            }
            return if (params.isEmpty()) "pair" else "pair?${params.joinToString("&")}"
        }
    }
    data object ConnectionsSettings : Screen("settings/connections", "Gateways", Icons.Filled.Settings)
    // Level-2 detail for a single connection (tabbed: Overview / Routes /
    // Advanced / Security). Drilled into from the Connections list. The
    // `connectionId` path segment survives process death via SavedStateHandle;
    // the route template registers a typed StringType arg and `route(id)`
    // builds the concrete URI (mirrors Screen.ProfileInspector).
    data object ConnectionDetail : Screen(
        "settings/connections/{connectionId}",
        "Connection",
        Icons.Filled.Settings,
    ) {
        const val ARG_CONNECTION_ID: String = "connectionId"
        fun route(connectionId: String): String {
            val encoded = java.net.URLEncoder.encode(connectionId, "UTF-8")
                .replace("+", "%20")
            return "settings/connections/$encoded"
        }
    }
    data object VoiceSettings : Screen("voice_settings", "Voice", Icons.Filled.Settings)
    // === PHASE3-notif-listener-followup ===
    data object NotificationCompanionSettings :
        Screen("settings/notifications", "Notification companion", Icons.Filled.Settings)
    // === END PHASE3-notif-listener-followup ===
    data object ProactiveSettings :
        Screen("settings/proactive", "Threads", Icons.Filled.Settings)
    data object PermissionsSettings : Screen("settings/permissions", "Permissions", Icons.Filled.Settings)
    // === PHASE3-safety-rails: bridge safety route ===
    data object BridgeSafetySettings :
        Screen("settings/bridge_safety", "Bridge safety", Icons.Filled.Settings)
    // === END PHASE3-safety-rails ===
    // Per-category settings sub-screens — split out of the mega SettingsScreen
    // following the VoiceSettingsScreen pattern (see DEVLOG 2026-04-11).
    // (The singular `ConnectionSettings` object was removed on 2026-04-21
    // when its underlying screen was collapsed into the active card of
    // the plural `ConnectionsSettings` subpage. See `ConnectionsSettings`
    // above for the surviving route.)
    data object ChatSettings : Screen("settings/chat", "Chat", Icons.Filled.Settings)
    data object AdvancedSettings : Screen("settings/advanced", "Advanced", Icons.Filled.Settings)
    data object SupervisedAppearanceSettings : Screen(
        "settings/supervised/appearance",
        "Appearance",
        Icons.Filled.Settings,
    )
    data object SupervisedControls : Screen(
        "settings/supervised",
        "Supervised mode",
        Icons.Filled.Settings,
    )
    data object ProviderUsage : Screen("settings/usage", "Usage & limits", Icons.Filled.Settings)
    data object MediaSettings : Screen("settings/media", "Media", Icons.Filled.Settings)
    data object AppearanceSettings : Screen("settings/appearance", "Appearance", Icons.Filled.Settings)
    data object CustomTheme : Screen("settings/appearance/custom-theme", "Custom", Icons.Filled.Settings)
    data object PetdexBrowse : Screen("settings/appearance/petdex", "Petdex", Icons.Filled.Settings)
    data object CustomPetGuide : Screen("settings/appearance/custom-pet", "Create a pet", Icons.Filled.Settings)
    data object Analytics : Screen("settings/analytics", "Analytics", Icons.Filled.Settings)
    data object Diagnostics : Screen("settings/diagnostics", "Diagnostics", Icons.Filled.Settings)
    data object DeveloperSettings : Screen("settings/developer", "Developer", Icons.Filled.Settings)
    data object RealtimeVoiceTest : Screen("settings/developer/realtime_voice", "Realtime voice", Icons.Filled.Settings)
    data object About : Screen("settings/about", "About", Icons.Filled.Settings)

    // Profile Inspector — full-screen read-only viewer with 4 tabs
    // (Config / SOUL / Memory / Skills) for a single profile. The
    // `profileName` path segment survives process death via Android's
    // SavedStateHandle arg propagation; the route template is registered
    // in the NavHost with a typed `StringType` arg, and the concrete
    // URI is built by `route(profileName)`.
    data object ProfileInspector : Screen(
        "settings/profile_inspector/{profileName}?section={section}",
        "Profile Inspector",
        Icons.Filled.Settings,
    ) {
        const val ARG_PROFILE_NAME: String = "profileName"
        const val ARG_SECTION: String = "section"

        /** Tab sections accepted by the `section` query arg. */
        const val SECTION_CONFIG: String = "config"
        const val SECTION_SOUL: String = "soul"
        const val SECTION_MEMORY: String = "memory"
        const val SECTION_SKILLS: String = "skills"

        /**
         * Build a concrete nav URI for the Profile Inspector.
         *
         * @param profileName the profile to inspect (required).
         * @param section     which tab to land on. One of
         *                    [SECTION_CONFIG] / [SECTION_SOUL] /
         *                    [SECTION_MEMORY] / [SECTION_SKILLS].
         *                    Defaults to [SECTION_CONFIG] so callers
         *                    that don't care about the tab — the
         *                    common "Inspect" card entry — land on
         *                    Config, matching the pre-deep-link
         *                    behaviour.
         *
         * Backwards compat: the route template still accepts a
         * call without `?section=` because the query arg has a
         * default value in the navArgument declaration. Old
         * deep-links without the arg resolve to Config.
         */
        fun route(profileName: String, section: String = SECTION_CONFIG): String {
            val encoded = java.net.URLEncoder.encode(profileName, "UTF-8")
                .replace("+", "%20")
            return "settings/profile_inspector/$encoded?section=$section"
        }
    }
}

@Composable
internal fun SupervisedStartupLoadingScreen() {
    HermesRelayTheme(themePreference = "dark") {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Loading protected settings…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RelayApp() {
    AppScaffold()
}

/** One line of the startup sphere's progress narration. */
internal data class StartupCheck(
    val state: StartupCheckState,
    val label: String,
)

internal val STARTUP_SPINNER_FRAMES = listOf("|", "/", "-", "\\")

internal enum class StartupCheckState { Pending, Active, Done, Failed }

/**
 * Terminal-style check line for the startup sphere: monospace glyph + label,
 * dimmed while pending and fading up as the underlying state lands. Failed
 * is visible only briefly — a settled failure releases the gate and the
 * normal UI takes over error presentation.
 */
@Composable
internal fun StartupCheckRow(check: StartupCheck) {
    val targetAlpha = when (check.state) {
        StartupCheckState.Pending -> 0.28f
        StartupCheckState.Active -> 0.85f
        else -> 0.95f
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(400),
        label = "startup-check-alpha",
    )
    // Classic ASCII spinner for the active row — guaranteed glyphs in the
    // platform monospace font (fancier braille spinners render as tofu on
    // some devices) and on-theme for terminal-style narration.
    var spinnerFrame by remember { mutableStateOf(0) }
    if (check.state == StartupCheckState.Active) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(120L)
                spinnerFrame = (spinnerFrame + 1) % STARTUP_SPINNER_FRAMES.size
            }
        }
    }
    val glyph = when (check.state) {
        StartupCheckState.Pending -> "·"
        StartupCheckState.Active -> STARTUP_SPINNER_FRAMES[spinnerFrame]
        StartupCheckState.Done -> "✓"
        StartupCheckState.Failed -> "✕"
    }
    val glyphColor = when (check.state) {
        StartupCheckState.Done -> RelayRefresh.Green
        StartupCheckState.Failed -> RelayRefresh.Danger
        else -> RelayRefresh.Muted
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(alpha),
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = glyphColor,
        )
        Text(
            text = check.label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (check.state == StartupCheckState.Active) {
                RelayRefresh.Paper.copy(alpha = 0.9f)
            } else {
                RelayRefresh.Muted
            },
        )
    }
}
