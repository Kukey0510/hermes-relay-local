package com.hermesandroid.relay.ui.scripts

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ScriptEntry(
    val id: String,
    val name: String,
    val game: String,
    val tag: String,
    val updatedAt: String,
    val description: String,
    val steps: List<String>,
    val sourceNote: String,
)

sealed interface ScriptCatalogResult {
    data class Loaded(val entries: List<ScriptEntry>) : ScriptCatalogResult
    data object Error : ScriptCatalogResult
}

/**
 * The single script-catalog boundary. Replace [loadResult] when server delivery
 * becomes available; list and detail screens only consume its result model.
 */
object ScriptCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<ScriptEntry> =
        (loadResult(context) as? ScriptCatalogResult.Loaded)?.entries.orEmpty()

    fun loadResult(context: Context): ScriptCatalogResult = runCatching {
        context.assets.open("scripts.json").bufferedReader().use { reader ->
            json.decodeFromString<List<ScriptEntry>>(reader.readText())
        }
    }.fold(
        onSuccess = ScriptCatalogResult::Loaded,
        onFailure = { ScriptCatalogResult.Error },
    )
}
