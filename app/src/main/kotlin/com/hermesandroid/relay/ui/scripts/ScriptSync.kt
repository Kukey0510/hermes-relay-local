package com.hermesandroid.relay.ui.scripts

import android.content.Context
import java.io.IOException
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.hermesandroid.relay.script.runtime.ScriptRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

const val SCRIPT_CATALOG_THROTTLE_MS = 5 * 60 * 1000L

@Serializable
private data class ScriptTagsRequest(val tags: Map<String, List<String>>)

class ScriptApiClient(
    private val baseUrl: () -> String,
    private val bearer: suspend () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getCatalog(): String = execute("scripts/catalog") { it.get() }

    suspend fun postTags(tags: Map<String, List<String>>) {
        val body = json.encodeToString(ScriptTagsRequest(tags))
            .toRequestBody("application/json".toMediaType())
        execute("scripts/tags") { it.post(body) }
    }

    /** Block 16 calls this contract endpoint; keeping it here prevents field/path drift. */
    suspend fun postScriptEvent(jsonBody: String) {
        execute("script-events") {
            it.post(jsonBody.toRequestBody("application/json".toMediaType()))
        }
    }

    private suspend fun execute(path: String, configure: (Request.Builder) -> Request.Builder): String =
        withContext(Dispatchers.IO) {
            val root = baseUrl().trim().trimEnd('/').ifBlank { throw IOException("Hermes API base is unavailable") }
            val builder = Request.Builder().url("$root/$path").header("Accept", "application/json")
            bearer()?.trim()?.takeIf(String::isNotEmpty)?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(configure(builder).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.string()
            }
        }
}

enum class ScriptSyncKind { Idle, Loading, Synced, Offline, Pending }
data class ScriptSyncState(val kind: ScriptSyncKind, val lastSyncMs: Long? = null,
    val message: String? = null)

internal interface ScriptSyncStorage {
    var lastAttemptMs: Long
    var lastSyncMs: Long
    var pendingTags: Map<String, List<String>>?
    fun readCatalog(): String?
    fun backupCatalog(timestampMs: Long)
    fun writeCatalog(content: String)
    fun catalogGames(): Set<String> = emptySet()
    fun readCatalogForGame(game: String): String? = readCatalog()
    fun backupCatalogForGame(game: String, timestampMs: Long) = backupCatalog(timestampMs)
    fun writeCatalogForGame(game: String, content: String) = writeCatalog(content)
}

private class AndroidScriptSyncStorage(context: Context) : ScriptSyncStorage {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("script_sync", Context.MODE_PRIVATE)
    private val json = Json
    private val serializer: KSerializer<Map<String, List<String>>> =
        MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    override var lastAttemptMs: Long
        get() = prefs.getLong("last_attempt_ms", 0L)
        set(value) { prefs.edit().putLong("last_attempt_ms", value).apply() }
    override var lastSyncMs: Long
        get() = prefs.getLong("last_sync_ms", 0L)
        set(value) { prefs.edit().putLong("last_sync_ms", value).apply() }
    override var pendingTags: Map<String, List<String>>?
        get() = prefs.getString("pending_tags", null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        set(value) { prefs.edit().apply { if (value == null) remove("pending_tags") else putString("pending_tags", json.encodeToString(serializer, value)) }.apply() }
    override fun readCatalog(): String? = ScriptCatalog.cacheFile(appContext).takeIf(File::isFile)?.readText()
    override fun backupCatalog(timestampMs: Long) = backupCatalogFile(ScriptCatalog.cacheFile(appContext), timestampMs)
    override fun writeCatalog(content: String) {
        ScriptCatalog.splitByGame(content).forEach { (game, catalog) -> writeCatalogForGame(game, catalog) }
    }
    override fun catalogGames(): Set<String> = ScriptCatalog.cachedGames(appContext.filesDir)
    override fun readCatalogForGame(game: String): String? = ScriptCatalog.cachedForGame(appContext.filesDir, game)
    override fun backupCatalogForGame(game: String, timestampMs: Long) {
        val file = ScriptCatalog.gameCacheFile(appContext.filesDir, game)
        if (file.isFile) backupCatalogFile(file, timestampMs)
        else if (readCatalogForGame(game) != null) backupCatalog(timestampMs)
    }
    override fun writeCatalogForGame(game: String, content: String) = ScriptCatalog.writeForGame(appContext, game, content)
}

/** Back up raw bytes, including corrupt JSON; lexicographic UTC names define retention. */
internal fun backupCatalogFile(catalog: File, timestampMs: Long) {
    if (!catalog.isFile) return
    val format = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    val pattern = Regex("${Regex.escape(catalog.name)}\\.bak_\\d{8}-\\d{6}")
    fun backups() = catalog.parentFile?.listFiles().orEmpty()
        .filter { it.isFile && pattern.matches(it.name) }.sortedByDescending { it.name }
    // Preserve distinct copies even when two forced refreshes land in the same second or the clock moves back.
    val latest = backups().firstOrNull()?.name?.substringAfter(".bak_")
        ?.let { Instant.from(format.parse(it)).toEpochMilli() }
    val backupTime = if (latest != null && timestampMs / 1_000 <= latest / 1_000) latest + 1_000 else timestampMs
    catalog.copyTo(File(catalog.parentFile, "${catalog.name}.bak_${format.format(Instant.ofEpochMilli(backupTime))}"))
    backups().drop(3).forEach { check(it.delete()) { "Cannot remove old catalog backup" } }
}

class ScriptSyncManager internal constructor(
    private val api: ScriptApiClient,
    private val storage: ScriptSyncStorage,
    private val isRunActive: () -> Boolean = ScriptRuntime::isRunning,
    private val log: (String) -> Unit = { android.util.Log.i("ScriptSync", it); Unit },
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, api: ScriptApiClient) : this(api, AndroidScriptSyncStorage(context))

    var state = ScriptSyncState(
        if (storage.pendingTags != null) ScriptSyncKind.Pending else ScriptSyncKind.Idle,
        storage.lastSyncMs.takeIf { it > 0 },
    )
        private set

    private fun refuseRunning(): Boolean {
        state = ScriptSyncState(state.kind.takeUnless { it == ScriptSyncKind.Loading } ?: ScriptSyncKind.Idle,
            storage.lastSyncMs.takeIf { it > 0 }, "脚本运行中，不同步")
        return false
    }

    private fun catalogVersion(content: String): String? {
        ScriptCatalog.decode(content) // Preserve full validation before replacing the last good cache.
        val version = ((Json.parseToJsonElement(content) as? JsonObject)?.get("version") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content ?: return null
        return version.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z").matches(it) }
            ?.also { Instant.parse(it) }
    }

    suspend fun refresh(force: Boolean = false): Boolean {
        if (isRunActive()) return refuseRunning()
        val current = now()
        if (!force && storage.lastAttemptMs > 0 && current - storage.lastAttemptMs < SCRIPT_CATALOG_THROTTLE_MS) return false
        storage.lastAttemptMs = current
        state = ScriptSyncState(ScriptSyncKind.Loading, storage.lastSyncMs.takeIf { it > 0 })
        storage.pendingTags?.let { retryPending(it) }
        if (isRunActive()) return refuseRunning()
        return runCatching {
            val incoming = api.getCatalog()
            val serverVersion = catalogVersion(incoming)
            val incomingGames = ScriptCatalog.splitByGame(incoming)
            // Reserve and disk replacement share the runtime lock, closing the fetch/start race.
            var blocked = false
            val updated = ScriptRuntime.updateCatalogIfIdle {
                if (isRunActive()) { blocked = true; return@updateCatalogIfIdle }
                var unknownVersion = false
                for (game in incomingGames.keys + storage.catalogGames()) {
                    val catalog = incomingGames[game] ?: ScriptCatalog.emptyCatalog(incoming)
                    val cached = storage.readCatalogForGame(game)
                    val localVersion = cached?.let { runCatching { catalogVersion(it) }.getOrNull() }
                    if (localVersion != null && serverVersion == null) {
                        unknownVersion = true
                        log("skip: server version unknown (game=$game)")
                        continue
                    }
                    when {
                        localVersion != null && serverVersion != null && serverVersion < localVersion -> {
                            val suffix = if (game == ScriptCatalog.UNKNOWN_GAME) "" else " (game=$game)"
                            log("skip: server older (server=$serverVersion, local=$localVersion)$suffix")
                        }
                        localVersion != null && serverVersion == localVersion -> Unit
                        else -> {
                            storage.backupCatalogForGame(game, now())
                            storage.writeCatalogForGame(game, catalog)
                            storage.lastSyncMs = now()
                        }
                    }
                }
                if (unknownVersion) throw IOException("Server catalog version is unknown")
                state = ScriptSyncState(ScriptSyncKind.Synced, storage.lastSyncMs.takeIf { it > 0 })
            }
            if (!updated || blocked) refuseRunning() else true
        }.getOrElse {
            state = ScriptSyncState(
                if (storage.pendingTags != null) ScriptSyncKind.Pending else ScriptSyncKind.Offline,
                storage.lastSyncMs.takeIf { it > 0 },
            )
            false
        }
    }

    suspend fun submitTags(tags: Map<String, List<String>>): Boolean {
        storage.pendingTags = tags
        state = ScriptSyncState(ScriptSyncKind.Pending, storage.lastSyncMs.takeIf { it > 0 })
        return retryPending(tags)
    }

    private suspend fun retryPending(tags: Map<String, List<String>>): Boolean = runCatching {
        api.postTags(tags)
        storage.pendingTags = null
        state = ScriptSyncState(ScriptSyncKind.Synced, storage.lastSyncMs.takeIf { it > 0 })
    }.isSuccess
}
