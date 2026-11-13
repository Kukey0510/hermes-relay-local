package com.hermesandroid.relay.ui.scripts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.scriptTagDataStore by preferencesDataStore("script_tags")

/**
 * Script-only local tag overrides, deliberately separate from session organization.
 *
 * Tags used to be derived from the assignments alone, so a tag created for one script vanished
 * once it was removed from every script and could never be deleted on purpose (block 25).
 * [registry] keeps the user's own tag names independent of the assignments.
 */
class ScriptTagStore(context: Context) {
    private val store = context.applicationContext.scriptTagDataStore
    private val assignmentsKey = stringPreferencesKey("script_tags_json")
    private val registryKey = stringPreferencesKey("script_tag_registry_json")
    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private val tagListSerializer = ListSerializer(String.serializer())

    private fun decodeAssignments(raw: String?): Map<String, List<String>> =
        raw?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun decodeRegistry(raw: String?): Set<String> =
        raw?.let { runCatching { Json.decodeFromString(tagListSerializer, it) }.getOrNull() }
            ?.filter(String::isNotBlank)?.toSet().orEmpty()

    private fun clean(tags: Collection<String>): List<String> =
        tags.map(String::trim).filter(String::isNotEmpty).distinct().sorted()

    val overrides: Flow<Map<String, List<String>>> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { preferences -> decodeAssignments(preferences[assignmentsKey]) }

    /** User-created tag names, including ones not attached to any script yet. */
    val registry: Flow<Set<String>> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { preferences -> decodeRegistry(preferences[registryKey]) }

    suspend fun setTags(scriptId: String, tags: Set<String>) {
        store.edit { preferences ->
            val cleanTags = clean(tags)
            val assignments = decodeAssignments(preferences[assignmentsKey]).toMutableMap()
            if (cleanTags.isEmpty()) assignments.remove(scriptId) else assignments[scriptId] = cleanTags
            preferences[assignmentsKey] = Json.encodeToString(serializer, assignments)
            preferences[registryKey] = Json.encodeToString(
                tagListSerializer, (decodeRegistry(preferences[registryKey]) + cleanTags).sorted(),
            )
        }
    }

    /** Adds a tag name before any script uses it. Returns the trimmed name ("" when blank). */
    suspend fun createTag(name: String): String {
        val tag = name.trim()
        if (tag.isEmpty()) return ""
        store.edit { preferences ->
            preferences[registryKey] = Json.encodeToString(
                tagListSerializer, (decodeRegistry(preferences[registryKey]) + tag).sorted(),
            )
        }
        return tag
    }

    /** Drops the tag everywhere and returns the assignments that remain (for server sync). */
    suspend fun deleteTag(name: String): Map<String, List<String>> {
        var remaining: Map<String, List<String>> = emptyMap()
        store.edit { preferences ->
            preferences[registryKey] = Json.encodeToString(
                tagListSerializer, (decodeRegistry(preferences[registryKey]) - name).sorted(),
            )
            val assignments = decodeAssignments(preferences[assignmentsKey])
                .mapValues { (_, tags) -> tags - name }
                .filterValues { it.isNotEmpty() }
            preferences[assignmentsKey] = Json.encodeToString(serializer, assignments)
            remaining = assignments
        }
        return remaining
    }
}
