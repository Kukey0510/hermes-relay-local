package com.hermesandroid.relay.ui.scripts

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
data class ScriptSyncState(val kind: ScriptSyncKind, val lastSyncMs: Long? = null)

internal interface ScriptSyncStorage {
    var lastAttemptMs: Long
    var lastSyncMs: Long
    var pendingTags: Map<String, List<String>>?
    fun writeCatalog(content: String)
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
    override fun writeCatalog(content: String) = ScriptCatalog.writeCache(appContext, content)
}

class ScriptSyncManager internal constructor(
    private val api: ScriptApiClient,
    private val storage: ScriptSyncStorage,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, api: ScriptApiClient) : this(api, AndroidScriptSyncStorage(context))

    var state = ScriptSyncState(
        if (storage.pendingTags != null) ScriptSyncKind.Pending else ScriptSyncKind.Idle,
        storage.lastSyncMs.takeIf { it > 0 },
    )
        private set

    suspend fun refresh(force: Boolean = false): Boolean {
        val current = now()
        if (!force && storage.lastAttemptMs > 0 && current - storage.lastAttemptMs < SCRIPT_CATALOG_THROTTLE_MS) return false
        storage.lastAttemptMs = current
        state = ScriptSyncState(ScriptSyncKind.Loading, storage.lastSyncMs.takeIf { it > 0 })
        storage.pendingTags?.let { retryPending(it) }
        return runCatching {
            storage.writeCatalog(api.getCatalog())
            storage.lastSyncMs = now()
            state = ScriptSyncState(ScriptSyncKind.Synced, storage.lastSyncMs)
        }.fold(
            onSuccess = { true },
            onFailure = {
                state = ScriptSyncState(
                    if (storage.pendingTags != null) ScriptSyncKind.Pending else ScriptSyncKind.Offline,
                    storage.lastSyncMs.takeIf { it > 0 },
                )
                false
            },
        )
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
