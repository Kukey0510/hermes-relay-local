package com.hermesandroid.relay.script.runtime

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class ScriptEvent(
    val scriptId: String,
    val runId: String,
    val level: String,
    val stepIndex: Int,
    val op: String,
    val message: String,
    val ts: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val page: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val lastStep: String? = null,
)

fun interface ScriptEventSender { suspend fun send(event: ScriptEvent) }

class ScriptEventReporter(
    private val sender: ScriptEventSender,
    private val outbox: ScriptEventOutbox? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()

    /** Fire-and-forget: replay the backlog first, then one request + one retry. */
    fun report(event: ScriptEvent) {
        scope.launch {
            lock.withLock {
                flushQueued()
                if (!deliver(event)) outbox?.append(event)
            }
        }
    }

    /** Re-sends everything queued. Safe to call from a bridge route or at run start. */
    suspend fun flush(): ScriptFlushResult = lock.withLock {
        val pending = outbox?.pending() ?: 0
        val sent = flushQueued()
        ScriptFlushResult(pending = pending, sent = sent, remaining = outbox?.pending() ?: 0)
    }

    private suspend fun flushQueued(): Int {
        val box = outbox ?: return 0
        val queued = box.readLines()
        if (queued.isEmpty()) return 0
        val acknowledged = mutableListOf<String>()
        for (line in queued) {
            val event = runCatching { json.decodeFromString(ScriptEvent.serializer(), line) }.getOrNull()
            if (event == null) {
                acknowledged += line   // unreadable line would block the queue forever
                continue
            }
            if (runCatching { sender.send(event) }.isSuccess) acknowledged += line
        }
        box.removeAcknowledged(acknowledged)
        return acknowledged.size
    }

    private suspend fun deliver(event: ScriptEvent): Boolean {
        repeat(2) { attempt ->
            if (runCatching { sender.send(event) }.isSuccess) return true
            if (attempt == 0) delay(100)
        }
        return false
    }
}

data class ScriptFlushResult(val pending: Int, val sent: Int, val remaining: Int)

class HttpScriptEventSender(
    private val baseUrl: () -> String,
    private val bearer: suspend () -> String?,
    private val client: OkHttpClient = OkHttpClient(),
) : ScriptEventSender {
    private val json = Json

    override suspend fun send(event: ScriptEvent) {
        val root = baseUrl().trim().trimEnd('/').ifBlank { throw IOException("Hermes API base is unavailable") }
        val body = json.encodeToString(event).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$root/script-events").post(body).apply {
            header("Accept", "application/json")
            bearer()?.trim()?.takeIf(String::isNotEmpty)?.let { header("Authorization", "Bearer $it") }
        }.build()
        client.newCall(request).execute().use { if (!it.isSuccessful) throw IOException("HTTP ${it.code}") }
    }
}

internal fun ScriptRunLog.toEvent(scriptId: String, runId: String) = ScriptEvent(
    scriptId = scriptId,
    runId = runId,
    level = if (success) "info" else if (message.contains("cancelled") || message.contains("screen_off")) "warn" else "error",
    stepIndex = stepIndex,
    op = op,
    message = message,
    ts = Instant.now().toString(),
)
