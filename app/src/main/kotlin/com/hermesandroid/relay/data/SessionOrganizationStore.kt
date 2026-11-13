package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.sessionOrganizationDataStore by preferencesDataStore("session_organization")

data class SessionOrganization(
    val tagsBySession: Map<String, List<String>> = emptyMap(),
    val tags: Set<String> = emptySet(),
    val collapsedGroups: Set<String> = emptySet(),
)

/** Local presentation metadata only; never sent to a Gateway or API server. */
class SessionOrganizationStore(context: Context) {
    private val store = context.applicationContext.sessionOrganizationDataStore
    private val assignmentsKey = stringPreferencesKey("session_tags_json")
    private val tagsKey = stringSetPreferencesKey("tags")
    private val collapsedKey = stringSetPreferencesKey("collapsed_groups")
    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    private fun decode(raw: String?): Map<String, List<String>> =
        raw?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    val state = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { prefs ->
        val assignments = decode(prefs[assignmentsKey])
        SessionOrganization(
            tagsBySession = assignments,
            tags = prefs[tagsKey].orEmpty() + assignments.values.flatten(),
            collapsedGroups = prefs[collapsedKey].orEmpty(),
        )
    }

    suspend fun setSessionTags(sessionId: String, tags: Set<String>) {
        store.edit { prefs ->
            val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            val assignments = decode(prefs[assignmentsKey]).toMutableMap()
            if (normalized.isEmpty()) assignments.remove(sessionId) else assignments[sessionId] = normalized
            prefs[assignmentsKey] = Json.encodeToString(serializer, assignments)
            prefs[tagsKey] = prefs[tagsKey].orEmpty() + normalized
        }
    }

    suspend fun deleteTag(tag: String) {
        store.edit { prefs ->
            val assignments = decode(prefs[assignmentsKey]).mapValues { (_, tags) -> tags - tag }
                .filterValues { it.isNotEmpty() }
            prefs[assignmentsKey] = Json.encodeToString(serializer, assignments)
            prefs[tagsKey] = prefs[tagsKey].orEmpty() - tag
        }
    }

    suspend fun toggleGroup(key: String) {
        store.edit { prefs ->
            val collapsed = prefs[collapsedKey].orEmpty()
            prefs[collapsedKey] = if (key in collapsed) collapsed - key else collapsed + key
        }
    }
}
