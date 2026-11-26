package com.hermesandroid.relay.ui.scripts

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable(with = ScriptEntrySerializer::class)
data class ScriptEntry(
    val id: String,
    val name: String,
    val game: String = "",
    val tags: List<String>,
    val updatedAt: String,
    val description: String,
    val steps: List<String>,
    val sourceNote: String,
    val startAt: Int = 0,
    val games: List<String> = emptyList(),
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private data class ScriptEntrySurrogate(
    val id: String,
    val name: String,
    val game: String = "",
    val tags: List<String>,
    val updatedAt: String,
    val description: String,
    val steps: JsonArray,
    val sourceNote: String,
    @kotlinx.serialization.SerialName("start_at")
    @kotlinx.serialization.json.JsonNames("startAt") val startAt: Int = 0,
    val games: List<String> = emptyList(),
)

/** Accepts the pre-IDEA-012 singular `tag` field while always exposing a list. */
object ScriptEntrySerializer : KSerializer<ScriptEntry> {
    private val delegate = ScriptEntrySurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): ScriptEntry {
        val jsonDecoder = decoder as? JsonDecoder ?: error("ScriptEntry only supports JSON")
        val element = jsonDecoder.decodeJsonElement()
        val objectValue = element as JsonObject
        val fields = objectValue.toMutableMap()
        if ("tags" !in fields) fields["tags"] = legacyTags(objectValue["tag"])
        // A missing/null game belongs to the unknown partition, without changing its raw JSON.
        for (key in listOf("game", "games")) {
            if (fields[key] == kotlinx.serialization.json.JsonNull) fields.remove(key)
        }
        val compatible = JsonObject(fields)
        return jsonDecoder.json.decodeFromJsonElement(delegate, compatible).toEntry()
    }

    override fun serialize(encoder: Encoder, value: ScriptEntry) {
        encoder.encodeSerializableValue(delegate, value.toSurrogate())
    }

    private fun legacyTags(tag: JsonElement?): JsonArray = JsonArray(
        (tag as? JsonPrimitive)?.content?.let(::listOf).orEmpty().map(::JsonPrimitive),
    )
}

private fun ScriptEntrySurrogate.toEntry() = ScriptEntry(
    id, name, game.ifBlank { games.firstOrNull().orEmpty() }, tags, updatedAt, description,
    steps.map { (it as? JsonPrimitive)?.content ?: it.toString() }, sourceNote, startAt, games,
)
private fun ScriptEntry.toSurrogate() = ScriptEntrySurrogate(
    id, name, game, tags, updatedAt, description, JsonArray(steps.map(::JsonPrimitive)), sourceNote, startAt, games,
)

@Serializable
data class ScriptCatalogDocument(val version: String, val scripts: List<ScriptEntry>)

internal fun filterScripts(entries: List<ScriptEntry>, selectedTags: Set<String>): List<ScriptEntry> =
    if (selectedTags.isEmpty()) entries else entries.filter { entry -> entry.tags.any(selectedTags::contains) }

/** Block 25: the filter bar has a game row and a scenario row, so both narrow at once. */
internal fun filterScripts(
    entries: List<ScriptEntry>,
    selectedGames: Set<String>,
    selectedTags: Set<String>,
): List<ScriptEntry> = entries.filter { entry ->
    (selectedGames.isEmpty() || entry.game in selectedGames) &&
        (selectedTags.isEmpty() || entry.tags.any(selectedTags::contains))
}

/** Tags that are not a game name — those belong in the scenario row, not the game row. */
internal fun scenarioTagsOf(entries: List<ScriptEntry>, extraTags: Set<String>): List<String> {
    val games = entries.map(ScriptEntry::game).toSet()
    return (entries.flatMap(ScriptEntry::tags) + extraTags).distinct().filterNot(games::contains).sorted()
}

internal fun applyScriptTagOverrides(
    entries: List<ScriptEntry>,
    overrides: Map<String, List<String>>,
): List<ScriptEntry> = entries.map { entry -> overrides[entry.id]?.let { entry.copy(tags = it) } ?: entry }

sealed interface ScriptCatalogResult {
    data class Loaded(val entries: List<ScriptEntry>) : ScriptCatalogResult
    data object Error : ScriptCatalogResult
}

/** Shared disk boundary for both catalog screens and the script runtime. */
object ScriptCatalog {
    internal const val UNKNOWN_GAME = "<unknown>"
    private val json = Json { ignoreUnknownKeys = true }
    private fun warn(message: String) { android.util.Log.w("ScriptCatalog", message) }

    fun load(context: Context): List<ScriptEntry> =
        (loadResult(context) as? ScriptCatalogResult.Loaded)?.entries.orEmpty()

    fun loadResult(context: Context): ScriptCatalogResult = runCatching {
        decode(readContent(context))
    }.fold(
        onSuccess = ScriptCatalogResult::Loaded,
        onFailure = { ScriptCatalogResult.Error },
    )

    fun readContent(context: Context): String = readContent(context.filesDir, {
        context.assets.open("scripts.json").bufferedReader().use { it.readText() }
    }, ::warn)

    internal fun readContent(filesDir: File, asset: () -> String, warn: (String) -> Unit = ::warn): String {
        val files = catalogFiles(filesDir)
        if (files.isNotEmpty()) return mergeContents(files.map { it.readText() }, warn)
        return preferredContent(File(filesDir, "scripts/catalog.json").takeIf(File::isFile)?.readText(), asset)
    }

    /** Sort by filename so the last duplicate id has a stable winner. */
    internal fun catalogFiles(filesDir: File): List<File> = File(filesDir, "scripts/catalog")
        .listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy(File::getName)

    private fun scripts(root: JsonElement): JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> root["scripts"] as? JsonArray ?: error("missing scripts array")
        else -> error("invalid catalog root")
    }

    internal fun mergeContents(contents: List<String>, warn: (String) -> Unit): String {
        val entries = linkedMapOf<String, JsonElement>()
        val declarations = linkedMapOf<String, JsonElement>()
        for (content in contents) {
            val root = json.parseToJsonElement(content)
            ((root as? JsonObject)?.get("games") as? JsonObject)?.let { declarations.putAll(it) }
            for (entry in scripts(root)) {
                val id = ((entry as? JsonObject)?.get("id") as? JsonPrimitive)?.content
                    ?: error("missing script id")
                if (entries.put(id, entry) != null) warn("duplicate script id '$id': later catalog wins")
            }
        }
        // This merged view is never used for version comparisons; those read each disk file.
        return JsonObject(mapOf("version" to JsonPrimitive(""), "scripts" to JsonArray(entries.values.toList()),
            "games" to JsonObject(declarations))).toString()
    }

    /** Keep the original JSON: runtime fields and object steps must survive sync unchanged. */
    internal fun splitByGame(content: String): Map<String, String> {
        val root = json.parseToJsonElement(content)
        val groups = linkedMapOf<String, MutableList<JsonElement>>()
        for (entry in scripts(root)) {
            val script = entry as? JsonObject ?: error("invalid script")
            val names = ((script["games"] as? JsonArray).orEmpty() + listOfNotNull(script["game"]))
                .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim() }
                .filter(String::isNotEmpty).distinct().ifEmpty { listOf(UNKNOWN_GAME) }
            for (game in names) {
                requireSafeGame(game)
                groups.getOrPut(game) { mutableListOf() }.add(entry)
            }
        }
        if (groups.isEmpty()) groups[UNKNOWN_GAME] = mutableListOf()
        return groups.mapValues { (_, entries) -> withScripts(root, entries) }
    }

    internal fun emptyCatalog(content: String): String = withScripts(json.parseToJsonElement(content), emptyList())

    private fun withScripts(root: JsonElement, entries: List<JsonElement>): String = when (root) {
        is JsonArray -> JsonArray(entries).toString()
        is JsonObject -> JsonObject(root + ("scripts" to JsonArray(entries))).toString()
        else -> error("invalid catalog root")
    }

    internal fun decode(content: String): List<ScriptEntry> =
        if (content.trimStart().startsWith("[")) json.decodeFromString(content)
        else json.decodeFromString<ScriptCatalogDocument>(content).scripts

    internal fun preferredContent(cache: String?, asset: () -> String): String = cache ?: asset()

    /** Legacy path is read-only; all replacements use [writeForGame]. */
    fun cacheFile(context: Context): File = File(context.filesDir, "scripts/catalog.json")

    internal fun gameCacheFile(filesDir: File, game: String): File {
        requireSafeGame(game)
        return File(filesDir, "scripts/catalog/$game.json")
    }

    private fun requireSafeGame(game: String) {
        require(game.isNotBlank() && game != "." && game != ".." &&
            game.none { it == '/' || it == '\\' || it.isISOControl() }) { "invalid game" }
    }

    fun writeForGame(context: Context, game: String, catalog: String) = writeForGame(context.filesDir, game, catalog)

    internal fun writeForGame(filesDir: File, game: String, catalog: String) {
        decode(catalog) // Validate completely, then persist the original JSON without field loss.
        val destination = gameCacheFile(filesDir, game)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(catalog)
        check(temporary.renameTo(destination) || run { temporary.copyTo(destination, overwrite = true); temporary.delete() })
    }

    internal fun cachedGames(filesDir: File): Set<String> {
        val files = catalogFiles(filesDir)
        if (files.isNotEmpty()) return files.map { it.nameWithoutExtension }.toSet()
        val legacy = File(filesDir, "scripts/catalog.json").takeIf(File::isFile) ?: return emptySet()
        return runCatching { splitByGame(legacy.readText()).keys }.getOrDefault(emptySet())
    }

    internal fun cachedForGame(filesDir: File, game: String): String? {
        val file = gameCacheFile(filesDir, game)
        if (file.isFile) return file.readText()
        val legacy = File(filesDir, "scripts/catalog.json").takeIf(File::isFile) ?: return null
        val content = legacy.readText()
        // Corrupt bytes still reach the backup-before-repair path.
        return runCatching { splitByGame(content)[game] }.getOrElse { content }
    }
}
