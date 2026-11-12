package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(onOpenScript: (String) -> Unit) {
    val context = LocalContext.current
    var result by remember(context) { mutableStateOf(ScriptCatalog.loadResult(context)) }
    val reload = { result = ScriptCatalog.loadResult(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scripts_title)) },
                actions = {
                    IconButton(
                        onClick = reload,
                        modifier = Modifier.testTag("scripts.refresh"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.scripts_refresh),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val current = result) {
            ScriptCatalogResult.Error -> ScriptCatalogMessage(
                title = stringResource(R.string.scripts_error_title),
                body = stringResource(R.string.scripts_error_body),
                actionLabel = stringResource(R.string.scripts_retry),
                onAction = reload,
                modifier = Modifier.padding(innerPadding),
            )
            is ScriptCatalogResult.Loaded -> if (current.entries.isEmpty()) {
                ScriptCatalogMessage(
                    title = stringResource(R.string.scripts_empty_title),
                    body = stringResource(R.string.scripts_empty_body),
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .testTag("scripts.list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current.entries, key = ScriptEntry::id) { entry ->
                        ScriptCard(entry = entry, onClick = { onOpenScript(entry.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptCard(entry: ScriptEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("scripts.item.${entry.id}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(entry.game, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(entry.tag, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.scripts_updated_at, entry.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    val result = remember(context) { ScriptCatalog.loadResult(context) }
    val entry = (result as? ScriptCatalogResult.Loaded)?.entries?.firstOrNull { it.id == scriptId }

    Scaffold(
        modifier = Modifier.testTag("scripts.detail"),
        topBar = {
            TopAppBar(
                title = { Text(entry?.name ?: stringResource(R.string.scripts_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.scripts_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (entry == null) {
            ScriptCatalogMessage(
                title = stringResource(R.string.scripts_not_found_title),
                body = stringResource(R.string.scripts_not_found_body),
                actionLabel = stringResource(R.string.scripts_back),
                onAction = onBack,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Text(entry.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                item { DetailField(R.string.scripts_game_label, entry.game) }
                item { DetailField(R.string.scripts_tag_label, entry.tag) }
                item { DetailField(R.string.scripts_updated_label, entry.updatedAt) }
                item { DetailField(R.string.scripts_description_label, entry.description) }
                item {
                    Text(stringResource(R.string.scripts_steps_label), style = MaterialTheme.typography.titleMedium)
                    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        entry.steps.forEachIndexed { index, step ->
                            Text(stringResource(R.string.scripts_step_number, index + 1, step))
                        }
                    }
                }
                item { DetailField(R.string.scripts_source_label, entry.sourceNote) }
                item {
                    Text(
                        stringResource(R.string.scripts_read_only_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailField(labelRes: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
