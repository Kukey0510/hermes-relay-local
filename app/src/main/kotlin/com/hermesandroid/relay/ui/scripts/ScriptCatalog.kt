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
    val game: String,
    val tags: List<String>,
    val updatedAt: String,
    val description: String,
    val steps: List<String>,
    val sourceNote: String,
    val startAt: Int = 0,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private data class ScriptEntrySurrogate(
    val id: String,
    val name: String,
    val game: String,
    val tags: List<String>,
    val updatedAt: String,
    val description: String,
    val steps: JsonArray,
    val sourceNote: String,
    @kotlinx.serialization.SerialName("start_at")
    @kotlinx.serialization.json.JsonNames("startAt") val startAt: Int = 0,
)

/** Accepts the pre-IDEA-012 singular `tag` field while always exposing a list. */
object ScriptEntrySerializer : KSerializer<ScriptEntry> {
    private val delegate = ScriptEntrySurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): ScriptEntry {
        val jsonDecoder = decoder as? JsonDecoder ?: error("ScriptEntry only supports JSON")
        val element = jsonDecoder.decodeJsonElement()
        val objectValue = element as JsonObject
        val compatible = if ("tags" in objectValue) objectValue else JsonObject(
            objectValue + ("tags" to legacyTags(objectValue["tag"])),
        )
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
    id, name, game, tags, updatedAt, description,
    steps.map { (it as? JsonPrimitive)?.content ?: it.toString() }, sourceNote, startAt,
)
private fun ScriptEntry.toSurrogate() = ScriptEntrySurrogate(
    id, name, game, tags, updatedAt, description, JsonArray(steps.map(::JsonPrimitive)), sourceNote, startAt,
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

/**
 * The single script-catalog boundary. Replace [loadResult] when server delivery
 * becomes available; list and detail screens only consume its result model.
 */
object ScriptCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): List<ScriptEntry> =
        (loadResult(context) as? ScriptCatalogResult.Loaded)?.entries.orEmpty()

    fun loadResult(context: Context): ScriptCatalogResult = runCatching {
        val cache = cacheFile(context)
        val asset = { context.assets.open("scripts.json").bufferedReader().use { it.readText() } }
        decode(preferredContent(cache.takeIf(File::isFile)?.readText(), asset))
    }.fold(
        onSuccess = ScriptCatalogResult::Loaded,
        onFailure = { ScriptCatalogResult.Error },
    )

    internal fun decode(content: String): List<ScriptEntry> =
        if (content.trimStart().startsWith("[")) json.decodeFromString(content)
        else json.decodeFromString<ScriptCatalogDocument>(content).scripts

    internal fun preferredContent(cache: String?, asset: () -> String): String = cache ?: asset()

    fun cacheFile(context: Context): File = File(context.filesDir, "scripts/catalog.json")

    internal fun writeCache(context: Context, content: String) {
        decode(content) // Validate completely before replacing the last good catalog.
        val destination = cacheFile(context)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.writeText(content)
        check(temporary.renameTo(destination) || run { temporary.copyTo(destination, overwrite = true); temporary.delete() })
    }
}
