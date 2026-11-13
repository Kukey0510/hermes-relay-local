package com.hermesandroid.relay.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ChatBackgroundRepository
import com.hermesandroid.relay.data.ChatBackgroundSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBackgroundSettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { ChatBackgroundRepository(context) }
    val settings by repository.settings.collectAsState(initial = ChatBackgroundSettings())
    val scope = rememberCoroutineScope()
    var draftAlpha by remember { mutableStateOf<Float?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(settings.alpha) {
        if (draftAlpha == settings.alpha) draftAlpha = null
    }
    val alpha = draftAlpha ?: settings.alpha
    fun save(action: suspend () -> Unit) {
        scope.launch {
            failed = false
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                failed = true
            } catch (_: SecurityException) {
                failed = true
            } catch (_: IllegalArgumentException) {
                failed = true
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            busy = true
            save {
                try {
                    val path = repository.importPhoto(uri)
                    repository.select("custom", customPath = path)
                } finally {
                    busy = false
                }
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.chat_background_title)) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chat_background_close))
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                ChatBackgroundLayer(settings.copy(alpha = alpha), Modifier.fillMaxSize())
                Text(stringResource(R.string.chat_background_preview), color = MaterialTheme.colorScheme.onSurface)
            }
            Button(
                enabled = !busy,
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            ) { Text(stringResource(R.string.chat_background_gallery)) }
            Button(enabled = !busy, onClick = { showPresets = !showPresets }) {
                Text(stringResource(R.string.chat_background_presets))
            }
            Button(enabled = !busy, onClick = { save { repository.select("none") } }) {
                Text(stringResource(R.string.chat_background_none))
            }
            if (showPresets) {
                // Three fixed cells form a bounded grid without nested lazy scrolling.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatBackgroundRepository.PRESETS.forEachIndexed { index, id ->
                        val isSelected = settings.mode == "preset" && settings.presetId == id
                        Column(
                            Modifier.weight(1f)
                                .border(if (isSelected) 2.dp else 1.dp, MaterialTheme.colorScheme.outline)
                                .semantics { selected = isSelected }
                                .clickable(enabled = !busy, role = Role.Button) {
                                    save { repository.select("preset", presetId = id) }
                                },
                        ) {
                            ChatBackgroundLayer(
                                ChatBackgroundSettings(mode = "preset", presetId = id),
                                Modifier.fillMaxWidth().height(100.dp),
                            )
                            Text(stringResource(R.string.chat_background_preset_number, index + 1), Modifier.padding(4.dp))
                        }
                    }
                }
            }
            Text(stringResource(R.string.chat_background_opacity_value, (alpha * 100).toInt()))
            Slider(
                value = alpha,
                onValueChange = { draftAlpha = it },
                onValueChangeFinished = {
                    val value = alpha
                    save {
                        repository.setAlpha(value)
                    }
                },
                valueRange = 0f..1f,
            )
            if (busy) Text(stringResource(R.string.chat_background_importing))
            if (failed) Text(stringResource(R.string.chat_background_error), color = MaterialTheme.colorScheme.error)
        }
    }
}
