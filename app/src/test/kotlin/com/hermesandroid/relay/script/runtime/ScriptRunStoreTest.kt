package com.hermesandroid.relay.script.runtime

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScriptRunStoreTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC)

    @Test fun cleanupDeletesFilesOlderThanThreeDayWindowAndKeepsToday() = withTempDir { root ->
        val logs = File(root, "logs").apply { mkdirs() }
        val expired = File(logs, "script-20261110.jsonl").apply { writeText("old") }
        val boundary = File(logs, "script-20261111.jsonl").apply { writeText("kept") }
        val today = File(logs, "script-20261113.jsonl").apply { writeText("today") }

        ScriptRunStore(root, clock).cleanupExpiredLogs()

        assertFalse(expired.exists())
        assertTrue(boundary.exists())
        assertTrue(today.exists())
    }

    @Test fun pendingCanBeWrittenReadAndDeleted() = withTempDir { root ->
        val store = ScriptRunStore(root, clock)
        val pending = PendingScriptRun("script", "run", 4)

        store.writePending(pending)
        assertEquals(pending, store.readPending())
        store.clearPending()
        assertFalse(store.pendingFile().exists())
    }

    @Test fun reportingFailureDoesNotThrowIntoCallerAndRetriesOnlyOnce() = runTest {
        val attempts = AtomicInteger()
        val reporter = ScriptEventReporter(
            sender = ScriptEventSender { attempts.incrementAndGet(); error("offline") },
            scope = this,
        )

        reporter.report(ScriptEvent("script", "run", "error", 1, "probe", "failed", "2026-11-13T12:00:00Z"))
        assertEquals(0, attempts.get())
        advanceUntilIdle()
        assertEquals(2, attempts.get())
    }

    private fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("script-runtime-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
