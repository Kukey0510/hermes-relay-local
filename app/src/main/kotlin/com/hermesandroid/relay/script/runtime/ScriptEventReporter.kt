package com.hermesandroid.relay.script.runtime

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class ScriptEvent(
    val scriptId: String,
    val runId: String,
    val level: String,
    val stepIndex: Int,
    val op: String,
    val message: String,
    val ts: String,
)

fun interface ScriptEventSender { suspend fun send(event: ScriptEvent) }

class ScriptEventReporter(
    private val sender: ScriptEventSender,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Fire-and-forget; one initial request and at most one retry. */
    fun report(event: ScriptEvent) {
        scope.launch {
            repeat(2) { attempt ->
                if (runCatching { sender.send(event) }.isSuccess) return@launch
                if (attempt == 0) delay(100)
            }
        }
    }
}

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
    level = if (success) "info" else "error",
    stepIndex = stepIndex,
    op = op,
    message = message,
    ts = Instant.now().toString(),
)
