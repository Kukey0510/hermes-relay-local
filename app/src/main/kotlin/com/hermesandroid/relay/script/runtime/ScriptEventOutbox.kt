package com.hermesandroid.relay.script.runtime

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Disk-backed outbox for run events the API server never acknowledged.
 *
 * Before this existed a report was fire-and-forget: one request, one retry 100ms
 * later, then dropped. The local jsonl log survived but the server never learned
 * about it (block 24). Now every failed send lands here and is replayed on the
 * next report, on the next run start, or when the server asks for a flush over
 * the bridge (`/script/flush-events`).
 *
 * NDJSON, newest entries win when the cap is hit, because a failed step from
 * three days ago is worth less than the one that just happened.
 */
class ScriptEventOutbox(private val filesDir: File, private val limit: Int = 200) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun file(): File = File(filesDir, "scripts/events_outbox.jsonl")

    @Synchronized
    fun append(event: ScriptEvent) {
        val target = file()
        target.parentFile?.mkdirs()
        val lines = readLines().toMutableList()
        lines += json.encodeToString(ScriptEvent.serializer(), event)
        val kept = if (lines.size > limit) lines.takeLast(limit) else lines
        target.writeText(kept.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    fun readLines(): List<String> =
        file().takeIf(File::isFile)?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    fun pending(): Int = readLines().size

    /** Drops exactly the lines that were acknowledged; leaves the rest queued. */
    @Synchronized
    fun removeAcknowledged(lines: Collection<String>) {
        if (lines.isEmpty()) return
        val remaining = readLines().toMutableList()
        lines.forEach { remaining.remove(it) }
        val target = file()
        if (remaining.isEmpty()) target.delete()
        else target.writeText(remaining.joinToString("\n", postfix = "\n"))
    }
}
