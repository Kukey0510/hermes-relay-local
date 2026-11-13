package com.hermesandroid.relay.ui.scripts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.scriptTagDataStore by preferencesDataStore("script_tags")

/** Script-only local tag overrides, deliberately separate from session organization. */
class ScriptTagStore(context: Context) {
    private val store = context.applicationContext.scriptTagDataStore
    private val assignmentsKey = stringPreferencesKey("script_tags_json")
    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    private fun decode(raw: String?): Map<String, List<String>> =
        raw?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    val overrides = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { preferences -> decode(preferences[assignmentsKey]) }

    suspend fun setTags(scriptId: String, tags: Set<String>) {
        store.edit { preferences ->
            val assignments = decode(preferences[assignmentsKey]).toMutableMap()
            assignments[scriptId] = tags.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
            preferences[assignmentsKey] = Json.encodeToString(serializer, assignments)
        }
    }
}
