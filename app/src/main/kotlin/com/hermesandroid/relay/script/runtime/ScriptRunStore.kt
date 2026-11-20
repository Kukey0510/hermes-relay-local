package com.hermesandroid.relay.script.runtime

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PendingScriptRun(
    val scriptId: String,
    val runId: String,
    val stepIndex: Int,
)

/** Disk state for script execution. File names, rather than mtimes, define retention. */
class ScriptRunStore(
    private val filesDir: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { encodeDefaults = true }
    private val dayFormat = DateTimeFormatter.BASIC_ISO_DATE
    private val logName = Regex("script-(\\d{8})\\.jsonl")

    fun cleanupExpiredLogs() {
        val oldestKept = LocalDate.now(clock).minusDays(2)
        logsDir().listFiles().orEmpty().forEach { file ->
            val date = logName.matchEntire(file.name)?.groupValues?.get(1)
                ?.let { runCatching { LocalDate.parse(it, dayFormat) }.getOrNull() }
            if (date != null && date < oldestKept) file.delete()
        }
    }

    @Synchronized
    fun append(event: ScriptLogEvent) {
        val file = todayLogFile()
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(event) + "\n")
    }

    /** Separate local evidence: never forwarded to the event reporter or outbox. */
    @Synchronized
    fun appendFailureEvidence(scriptId: String, runId: String, errorCode: String,
        logs: List<ScriptRunLog>, snapshotTag: String, snapshotSaved: Boolean) {
        val file = evidenceFile(runId)
        logs.takeLast(20).forEach { log ->
            file.appendText(json.encodeToString(ScriptLogEvent(
                event = "step", scriptId = scriptId, runId = runId, ts = ScriptLogEvent.timestamp(clock),
                stepIndex = log.stepIndex, op = log.op, success = log.success, attempts = log.attempts,
                message = log.message, errorCode = errorCode, stepId = log.stepId,
                snapshotTag = snapshotTag, snapshotSaved = snapshotSaved,
            )) + "\n")
        }
    }

    fun evidenceFile(runId: String): File = File(logsDir(),
        "script-failure-${runId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.jsonl")

    fun recentLines(limit: Int = 50): List<String> =
        if (!todayLogFile().isFile) emptyList() else todayLogFile().useLines { it.toList().takeLast(limit) }

    fun writePending(value: PendingScriptRun) {
        pendingFile().parentFile?.mkdirs()
        val temporary = File(pendingFile().parentFile, "${pendingFile().name}.tmp")
        temporary.writeText(json.encodeToString(value))
        if (!temporary.renameTo(pendingFile())) {
            pendingFile().writeText(temporary.readText())
            temporary.delete()
        }
    }

    fun readPending(): PendingScriptRun? = pendingFile().takeIf(File::isFile)?.let {
        runCatching { json.decodeFromString<PendingScriptRun>(it.readText()) }.getOrNull()
    }

    fun clearPending() { pendingFile().delete() }

    fun todayLogFile(): File = File(logsDir(), "script-${LocalDate.now(clock).format(dayFormat)}.jsonl")
    fun pendingFile(): File = File(filesDir, "scripts/pending_run.json")
    private fun logsDir() = File(filesDir, "logs").also(File::mkdirs)
}

@Serializable
data class ScriptLogEvent(
    val event: String,
    val scriptId: String,
    val runId: String,
    val ts: String,
    val stepIndex: Int? = null,
    val op: String? = null,
    val success: Boolean? = null,
    val attempts: Int? = null,
    val message: String? = null,
    val durationMs: Long? = null,
    val errorCode: String? = null,
    val stepId: String? = null,
    val snapshotTag: String? = null,
    val snapshotSaved: Boolean? = null,
) {
    companion object {
        fun timestamp(clock: Clock = Clock.systemUTC()): String = Instant.now(clock).toString()
    }
}
