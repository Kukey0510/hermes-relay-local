package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.HermesRelayApp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.scripts.ScriptCatalog
import com.hermesandroid.relay.ui.scripts.ScriptCatalogResult
import com.hermesandroid.relay.ui.scripts.ScriptEntry
import com.hermesandroid.relay.ui.scripts.ScriptTagStore
import com.hermesandroid.relay.ui.scripts.ScriptApiClient
import com.hermesandroid.relay.ui.scripts.ScriptSyncKind
import com.hermesandroid.relay.ui.scripts.ScriptSyncManager
import com.hermesandroid.relay.ui.scripts.ScriptSyncState
import com.hermesandroid.relay.ui.scripts.applyScriptTagOverrides
import com.hermesandroid.relay.ui.scripts.filterScripts
import com.hermesandroid.relay.ui.scripts.scenarioTagsOf
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.script.runtime.PendingScriptRun
import com.hermesandroid.relay.script.runtime.ScriptRunStore
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.hermesandroid.relay.network.relay.models.Envelope
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(onOpenScript: (String) -> Unit) {
    val context = LocalContext.current
    val tagStore = remember(context) { ScriptTagStore(context) }
    val connection = (context.applicationContext as HermesRelayApp).runtime.connectionViewModel
    val syncManager = remember(context) {
        ScriptSyncManager(context, ScriptApiClient(
            baseUrl = { connection.apiServerUrl.value },
            bearer = connection::getApiKey,
        ))
    }
    val scope = rememberCoroutineScope()
    val overrides by tagStore.overrides.collectAsState(initial = emptyMap())
    val userTags by tagStore.registry.collectAsState(initial = emptySet())
    var result by remember(context) { mutableStateOf(ScriptCatalog.loadResult(context)) }
    var syncState by remember { mutableStateOf(syncManager.state) }
    var selectedGames by remember { mutableStateOf(emptySet<String>()) }
    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    var managingTags by remember { mutableStateOf(false) }
    var pendingRun by remember(context) { mutableStateOf<PendingScriptRun?>(null) }
    val reload: () -> Unit = {
        scope.launch {
            syncState = ScriptSyncState(ScriptSyncKind.Loading, syncManager.state.lastSyncMs)
            syncManager.refresh(force = true)
            syncManager.state.message?.let {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            }
            syncState = syncManager.state
            result = ScriptCatalog.loadResult(context)
        }
    }
    LaunchedEffect(syncManager) {
        ScriptRunStore(context.filesDir).also {
            it.cleanupExpiredLogs()
            pendingRun = it.readPending()
        }
        result = ScriptCatalog.loadResult(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scripts_title)) },
                actions = {
                    IconButton(
                        onClick = { managingTags = true },
                        modifier = Modifier.testTag("scripts.tags.new"),
                    ) { Icon(Icons.Filled.Add, stringResource(R.string.scripts_tags_new_button)) }
                    IconButton(onClick = reload, modifier = Modifier.testTag("scripts.refresh")) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.scripts_refresh))
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = result) {
            ScriptCatalogResult.Error -> ScriptCatalogMessage(
                stringResource(R.string.scripts_error_title), stringResource(R.string.scripts_error_body),
                Modifier.padding(innerPadding), stringResource(R.string.scripts_retry), reload,
            )
            is ScriptCatalogResult.Loaded -> if (current.entries.isEmpty()) {
                ScriptCatalogMessage(
                    stringResource(R.string.scripts_empty_title), stringResource(R.string.scripts_empty_body),
                    Modifier.padding(innerPadding),
                )
            } else {
                val entries = applyScriptTagOverrides(current.entries, overrides)
                val games = entries.map(ScriptEntry::game).distinct().sorted()
                val scenarioTags = scenarioTagsOf(entries, userTags)
                LaunchedEffect(scenarioTags) { selectedTags = selectedTags.intersect(scenarioTags.toSet()) }
                LaunchedEffect(games) { selectedGames = selectedGames.intersect(games.toSet()) }
                val visibleEntries = filterScripts(entries, selectedGames, selectedTags)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("scripts.list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pendingRun?.let { pending ->
                        item {
                            TextButton(
                                onClick = { onOpenScript(pending.scriptId) },
                                modifier = Modifier.testTag("scripts.pending_run"),
                            ) { Text(stringResource(R.string.scripts_pending_run)) }
                        }
                    }
                    item { ScriptSyncStatus(syncState) }
                    item {
                        ScriptTagFilters(
                            games = games,
                            tags = scenarioTags,
                            selectedGames = selectedGames,
                            selectedTags = selectedTags,
                            onAll = { selectedGames = emptySet(); selectedTags = emptySet() },
                            onToggleGame = { game ->
                                selectedGames = if (game in selectedGames) selectedGames - game
                                else selectedGames + game
                            },
                            onToggleTag = { tag ->
                                selectedTags = if (tag in selectedTags) selectedTags - tag
                                else selectedTags + tag
                            },
                        )
                    }
                    items(visibleEntries, key = ScriptEntry::id) { entry ->
                        ScriptCard(entry = entry, onClick = { onOpenScript(entry.id) })
                    }
                }
            }
        }
    }

    if (managingTags) {
        ScriptTagManagerDialog(
            tags = (userTags + overrides.values.flatten()).distinct().sorted(),
            onDismiss = { managingTags = false },
            onCreate = { name -> scope.launch { tagStore.createTag(name) } },
            onDelete = { name ->
                scope.launch {
                    val remaining = tagStore.deleteTag(name)
                    syncManager.submitTags(remaining)
                }
            },
        )
    }
}

@Composable
private fun ScriptSyncStatus(state: ScriptSyncState) {
    val time = state.lastSyncMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }.orEmpty()
    val text = state.message ?: when (state.kind) {
        ScriptSyncKind.Loading -> stringResource(R.string.scripts_sync_loading)
        ScriptSyncKind.Synced -> stringResource(R.string.scripts_sync_at, time)
        ScriptSyncKind.Offline -> if (time.isBlank()) stringResource(R.string.scripts_sync_offline)
            else stringResource(R.string.scripts_sync_offline_last, time)
        ScriptSyncKind.Pending -> stringResource(R.string.scripts_sync_pending)
        ScriptSyncKind.Idle -> if (time.isBlank()) "" else stringResource(R.string.scripts_sync_at, time)
    }
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("scripts.sync.status"))
}

@Composable
private fun ScriptTagFilters(
    games: List<String>,
    tags: List<String>,
    selectedGames: Set<String>,
    selectedTags: Set<String>,
    onAll: () -> Unit,
    onToggleGame: (String) -> Unit,
    onToggleTag: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("scripts.filters"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScriptFilterChip(
                label = stringResource(R.string.scripts_tags_all),
                selected = selectedGames.isEmpty() && selectedTags.isEmpty(),
                onClick = onAll,
                tag = "scripts.filters.all",
            )
        }
        FilterRow(
            label = stringResource(R.string.scripts_filter_games),
            values = games,
            selected = selectedGames,
            rowTag = "scripts.filters.games",
            onToggle = onToggleGame,
        )
        FilterRow(
            label = stringResource(R.string.scripts_filter_scenes),
            values = tags,
            selected = selectedTags,
            rowTag = "scripts.filters.scenes",
            onToggle = onToggleTag,
        )
    }
}

@Composable
private fun FilterRow(
    label: String,
    values: List<String>,
    selected: Set<String>,
    rowTag: String,
    onToggle: (String) -> Unit,
) {
    if (values.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(rowTag),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 2.dp),
        )
        values.forEach { value ->
            ScriptFilterChip(value, value in selected) { onToggle(value) }
        }
    }
}

@Composable
private fun ScriptTagManagerDialog(
    tags: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scripts_tags_manage)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (tags.isEmpty()) {
                    Text(
                        stringResource(R.string.scripts_tags_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tags.forEach { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("scripts.tags.row.$tag"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tag, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { onDelete(tag) },
                            modifier = Modifier.testTag("scripts.tags.delete.$tag"),
                        ) { Icon(Icons.Filled.Close, stringResource(R.string.scripts_tags_delete)) }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.scripts_tags_new)) },
                )
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = { onCreate(input); input = "" },
                    modifier = Modifier.testTag("scripts.tags.create"),
                ) { Text(stringResource(R.string.scripts_tags_create)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scripts_tags_done)) } },
    )
}

@Composable
private fun ScriptFilterChip(
    label: String,
    selected: Boolean,
    tag: String? = null,
    onClick: () -> Unit,
) {
    FilterChip(
        modifier = if (tag == null) Modifier else Modifier.testTag(tag),
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = RelayRefresh.Relay.copy(alpha = 0.12f),
            selectedLabelColor = RelayRefresh.Relay,
        ),
    )
}

@Composable
private fun ScriptCard(entry: ScriptEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("scripts.item.${entry.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(entry.game, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.tags.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.scripts_updated_at, entry.updatedAt), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScriptCatalogMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text(actionLabel) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptDetailScreen(scriptId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val tagStore = remember(context) { ScriptTagStore(context) }
    val overrides by tagStore.overrides.collectAsState(initial = emptyMap())
    val userTags by tagStore.registry.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    val connection = (context.applicationContext as HermesRelayApp).runtime.connectionViewModel
    val syncManager = remember(context) {
        ScriptSyncManager(context, ScriptApiClient(
            baseUrl = { connection.apiServerUrl.value }, bearer = connection::getApiKey,
        ))
    }
    val result = remember(context) { ScriptCatalog.loadResult(context) }
    val defaults = (result as? ScriptCatalogResult.Loaded)?.entries.orEmpty()
    val entry = applyScriptTagOverrides(defaults, overrides).firstOrNull { it.id == scriptId }
    var editing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var recentLogs by remember { mutableStateOf(emptyList<String>()) }
    val runStore = remember(context) { ScriptRunStore(context.filesDir) }
    LaunchedEffect(scriptId) { recentLogs = runStore.recentLines(50) }

    Scaffold(
        modifier = Modifier.testTag("scripts.detail"),
        topBar = { TopAppBar(
            title = { Text(entry?.name ?: stringResource(R.string.scripts_detail_title)) },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.scripts_back))
            } },
        ) },
    ) { innerPadding ->
        if (entry == null) {
            ScriptCatalogMessage(stringResource(R.string.scripts_not_found_title),
                stringResource(R.string.scripts_not_found_body), Modifier.padding(innerPadding),
                stringResource(R.string.scripts_back), onBack)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Text(entry.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                item {
                    Button(enabled = !running, onClick = {
                        running = true
                        scope.launch {
                            val response = connection.bridgeCommandHandler.handleLocalCommand(
                                Envelope(channel = "bridge", type = "bridge.command", payload = buildJsonObject {
                                    put("request_id", java.util.UUID.randomUUID().toString())
                                    put("method", "POST"); put("path", "/script/run")
                                    put("body", buildJsonObject { put("scriptId", entry.id) })
                                })
                            )
                            recentLogs = runStore.recentLines(50).ifEmpty {
                                listOf(response.errorCode ?: response.errorMessage ?: "-")
                            }
                            running = false
                        }
                    }, modifier = Modifier.testTag("scripts.run")) {
                        Text(stringResource(if (running) R.string.scripts_running else R.string.scripts_run))
                    }
                }
                if (recentLogs.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.scripts_recent_results), style = MaterialTheme.typography.titleMedium)
                        recentLogs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                item { DetailField(R.string.scripts_game_label, entry.game) }
                item {
                    DetailField(R.string.scripts_tag_label, entry.tags.joinToString(" · "))
                    TextButton(onClick = { editing = true }) { Text(stringResource(R.string.scripts_tags_edit)) }
                }
                item { DetailField(R.string.scripts_updated_label, entry.updatedAt) }
                item { DetailField(R.string.scripts_description_label, entry.description) }
                item {
                    Text(stringResource(R.string.scripts_steps_label), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entry.steps.forEachIndexed { index, step -> Text(stringResource(R.string.scripts_step_number, index + 1, step)) }
                    }
                }
                item { DetailField(R.string.scripts_source_label, entry.sourceNote) }
            }
            if (editing) {
                ScriptTagsEditor(
                    scriptId = entry.id,
                    choices = (applyScriptTagOverrides(defaults, overrides).flatMap(ScriptEntry::tags) + userTags)
                        .distinct().sorted(),
                    initial = entry.tags,
                    onSave = { tags -> scope.launch {
                        tagStore.setTags(entry.id, tags)
                        val allTags = defaults.associate { catalogEntry ->
                            catalogEntry.id to (if (catalogEntry.id == entry.id) tags.toList().sorted()
                            else overrides[catalogEntry.id] ?: catalogEntry.tags)
                        }
                        syncManager.submitTags(allTags)
                    } },
                    onDismiss = { editing = false },
                )
            }
        }
    }
}

@Composable
private fun ScriptTagsEditor(
    scriptId: String,
    choices: List<String>,
    initial: List<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(scriptId) { mutableStateOf(initial.toSet()) }
    var created by remember(scriptId) { mutableStateOf(emptySet<String>()) }
    var input by remember(scriptId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scripts_tags_edit)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                (choices + created).distinct().sorted().forEach { tag ->
                    ScriptFilterChip(tag, tag in selected) {
                        selected = if (tag in selected) selected - tag else selected + tag
                    }
                }
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.scripts_tags_new)) })
                TextButton(enabled = input.isNotBlank(), onClick = {
                    val tag = input.trim(); created += tag; selected += tag; input = ""
                }) { Text(stringResource(R.string.scripts_tags_create)) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected); onDismiss() }) {
            Text(stringResource(R.string.scripts_tags_save))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scripts_tags_cancel)) } },
    )
}

@Composable
private fun DetailField(labelRes: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
