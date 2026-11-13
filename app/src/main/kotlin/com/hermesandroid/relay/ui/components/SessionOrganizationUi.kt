package com.hermesandroid.relay.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.RelayRefresh
import java.util.Locale

internal enum class SessionSourceGroup(@StringRes val label: Int) {
    QQ(R.string.session_group_qq),
    Mobile(R.string.session_group_mobile),
    WebDesktop(R.string.session_group_web_desktop),
    Cron(R.string.session_group_cron),
    Api(R.string.session_group_api),
    Other(R.string.session_group_other);

    companion object {
        fun from(source: String?): SessionSourceGroup = when (source?.trim()?.lowercase(Locale.ROOT)) {
            "qqbot" -> QQ
            // This app stamps its own sessions with "relay_app" at session.create;
            // upstream's vocabulary only knows webui/desktop, so without that stamp
            // phone-created chats were indistinguishable from browser ones.
            "relay_app" -> Mobile
            "webui", "desktop" -> WebDesktop
            "cron" -> Cron
            "api_server" -> Api
            else -> Other
        }
    }
}

@Composable
internal fun SessionOrganizationFilters(
    tags: List<String>,
    groups: Set<SessionSourceGroup>,
    selectedTags: Set<String>,
    onClear: () -> Unit,
    onGroup: (SessionSourceGroup) -> Unit,
    onTag: (String) -> Unit,
    onManage: () -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OrganizationChip(stringResource(R.string.session_tags_all), groups.isEmpty() && selectedTags.isEmpty(), onClear)
        SessionSourceGroup.entries.forEach { group ->
            OrganizationChip(stringResource(group.label), group in groups) { onGroup(group) }
        }
        tags.forEach { tag -> OrganizationChip(tag, tag in selectedTags) { onTag(tag) } }
        TextButton(onClick = onManage) { Text(stringResource(R.string.session_tags_manage)) }
    }
}

@Composable
private fun OrganizationChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
internal fun SessionTagsEditor(
    sessionId: String,
    tags: List<String>,
    initial: List<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(sessionId) { mutableStateOf(initial.toSet()) }
    var created by remember(sessionId) { mutableStateOf(emptySet<String>()) }
    var input by remember(sessionId) { mutableStateOf("") }
    val choices = remember(tags, created) { (tags + created).distinct().sorted() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_tags_edit)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { tag ->
                    OrganizationChip(tag, tag in selected) {
                        selected = if (tag in selected) selected - tag else selected + tag
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.session_tags_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val tag = input.trim()
                        created = created + tag
                        selected = selected + tag
                        input = ""
                    },
                ) { Text(stringResource(R.string.session_tags_create)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected); onDismiss() }) {
                Text(stringResource(R.string.session_tags_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_tags_cancel)) }
        },
    )
}

@Composable
internal fun SessionTagsManager(tags: List<String>, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_tags_manage)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (tags.isEmpty()) Text(stringResource(R.string.session_tags_empty))
                tags.forEach { tag ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(tag, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onDelete(tag) }) {
                            Text(stringResource(R.string.session_tags_delete))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_tags_done)) }
        },
    )
}
