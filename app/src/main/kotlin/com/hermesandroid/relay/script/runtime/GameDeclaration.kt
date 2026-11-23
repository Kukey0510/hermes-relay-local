package com.hermesandroid.relay.script.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** Read-only catalog metadata. Base dimensions never rescale steps or change page criteria. */
@Serializable
data class GameDeclaration(
    @SerialName("package") val packageName: String? = null,
    val orientation: String? = null,
    val base: List<Int>? = null,
    val boot: String? = null,
    val home: String? = null,
) {
    init {
        require(packageName == null || packageName.isNotBlank()) { "empty package" }
        require(orientation == null || orientation in setOf("landscape", "portrait", "any")) { "invalid orientation" }
        require(base == null || (base.size == 2 && base.all { it > 0 })) { "invalid base dimensions" }
    }
}

internal fun decodeGamesDeclaration(root: JsonElement, json: Json, warn: (String) -> Unit): Map<String, GameDeclaration> {
    val games = (root as? JsonObject)?.get("games") ?: return emptyMap()
    if (games !is JsonObject) {
        warn("invalid games declaration: expected object")
        return emptyMap()
    }
    return buildMap {
        for ((name, entry) in games) {
            runCatching {
                require(entry is JsonObject) { "expected game object" }
                for (field in listOf("package", "orientation", "boot", "home")) {
                    entry[field]?.let { require(it is JsonPrimitive && it.isString) { "expected string" } }
                }
                json.decodeFromJsonElement<GameDeclaration>(entry)
            }
                .onSuccess { put(name, it) }
                .onFailure { warn("invalid game declaration: $name") }
        }
    }
}

/** Resolve defaults in memory, before step decoding can erase whether a field was explicit. */
internal fun scriptWithGameDefaults(entry: JsonElement, games: Map<String, GameDeclaration>,
    warn: (String) -> Unit): JsonElement {
    val script = entry as? JsonObject ?: return entry
    val game = (script["game"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return entry
    val declaration = games[game]
    if (declaration == null) {
        warn("undeclared game: $game")
        return entry
    }
    val resolved = script.toMutableMap()
    val defaults = mapOf("package" to declaration.packageName, "orientation" to declaration.orientation,
        "boot" to declaration.boot, "home" to declaration.home)
    for ((field, value) in defaults) {
        if (field !in resolved && value != null) resolved[field] = JsonPrimitive(value)
    }
    if ("targetPackages" !in resolved) {
        resolved["package"]?.let { resolved["targetPackages"] = JsonArray(listOf(it)) }
    }
    val stepDefaults = defaults.keys.mapNotNull { field ->
        // An explicit targetPackages list is also a script-level package override.
        val value = if (field == "package" && "package" !in script && "targetPackages" in script)
            (script["targetPackages"] as? JsonArray)?.firstOrNull()
        else resolved[field]
        value?.let { field to it }
    }.toMap()
    fun resolveSteps(value: JsonElement): JsonElement {
        if (value !is JsonArray) return value
        return JsonArray(value.map { step ->
            if (step !is JsonObject) return@map step
            val fields = step.toMutableMap()
            if ((step["op"] as? JsonPrimitive)?.content == "coldStart") {
                for ((field, default) in stepDefaults) if (field !in fields) fields[field] = default
            }
            for (children in listOf("steps", "then", "else")) {
                fields[children]?.let { fields[children] = resolveSteps(it) }
            }
            JsonObject(fields)
        })
    }
    for (field in listOf("steps", "on_fail")) resolved[field]?.let { resolved[field] = resolveSteps(it) }
    return JsonObject(resolved)
}
