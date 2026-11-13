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
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.scripts.ScriptCatalog
import com.hermesandroid.relay.ui.scripts.ScriptCatalogResult
import com.hermesandroid.relay.ui.scripts.ScriptEntry
import com.hermesandroid.relay.ui.scripts.ScriptTagStore
import com.hermesandroid.relay.ui.scripts.applyScriptTagOverrides
import com.hermesandroid.relay.ui.scripts.filterScripts
import com.hermesandroid.relay.ui.theme.RelayRefresh
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(onOpenScript: (String) -> Unit) {
    val context = LocalContext.current
    val tagStore = remember(context) { ScriptTagStore(context) }
    val overrides by tagStore.overrides.collectAsState(initial = emptyMap())
    var result by remember(context) { mutableStateOf(ScriptCatalog.loadResult(context)) }
    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    val reload = { result = ScriptCatalog.loadResult(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scripts_title)) },
                actions = {
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
                val availableTags = entries.flatMap(ScriptEntry::tags).distinct().sorted()
                LaunchedEffect(availableTags) { selectedTags = selectedTags.intersect(availableTags.toSet()) }
                val visibleEntries = filterScripts(entries, selectedTags)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("scripts.list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        ScriptTagFilters(availableTags, selectedTags) { tag ->
                            selectedTags = if (tag == null) emptySet()
                            else if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                        }
                    }
                    items(visibleEntries, key = ScriptEntry::id) { entry ->
                        ScriptCard(entry = entry, onClick = { onOpenScript(entry.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptTagFilters(tags: List<String>, selected: Set<String>, onToggle: (String?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("scripts.filters"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ScriptFilterChip(stringResource(R.string.scripts_tags_all), selected.isEmpty()) { onToggle(null) }
        tags.forEach { tag -> ScriptFilterChip(tag, tag in selected) { onToggle(tag) } }
    }
}

@Composable
private fun ScriptFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
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
    val scope = rememberCoroutineScope()
    val result = remember(context) { ScriptCatalog.loadResult(context) }
    val defaults = (result as? ScriptCatalogResult.Loaded)?.entries.orEmpty()
    val entry = applyScriptTagOverrides(defaults, overrides).firstOrNull { it.id == scriptId }
    var editing by remember { mutableStateOf(false) }

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
                item { Text(stringResource(R.string.scripts_read_only_notice), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 20.dp)) }
            }
            if (editing) {
                ScriptTagsEditor(
                    scriptId = entry.id,
                    choices = applyScriptTagOverrides(defaults, overrides).flatMap(ScriptEntry::tags).distinct().sorted(),
                    initial = entry.tags,
                    onSave = { tags -> scope.launch { tagStore.setTags(entry.id, tags) } },
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
