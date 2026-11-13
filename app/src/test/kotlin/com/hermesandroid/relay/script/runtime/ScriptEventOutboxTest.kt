package com.hermesandroid.relay.script.runtime

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScriptEventOutboxTest {
    private fun event(runId: String, step: Int = 1) = ScriptEvent(
        scriptId = "bl-jjc", runId = runId, level = "error", stepIndex = step,
        op = "tap", message = "boom", ts = "2026-11-14T00:00:00Z",
    )

    @Test fun failedReportIsQueuedInsteadOfDropped() = runTest {
        withTempDir { root ->
            val outbox = ScriptEventOutbox(root)
            val reporter = ScriptEventReporter(ScriptEventSender { throw IOException("offline") }, outbox, this)

            reporter.report(event("r1"))
            advanceUntilIdle()

            assertEquals(1, outbox.pending())
        }
    }

    @Test fun nextReportReplaysBacklogBeforeTheNewEvent() = runTest {
        withTempDir { root ->
            val outbox = ScriptEventOutbox(root)
            var online = false
            val delivered = mutableListOf<String>()
            val sender = ScriptEventSender {
                if (!online) throw IOException("offline")
                delivered += it.runId
            }
            val offline = ScriptEventReporter(sender, outbox, this)
            offline.report(event("r1"))
            advanceUntilIdle()
            assertEquals(1, outbox.pending())

            online = true
            ScriptEventReporter(sender, outbox, this).report(event("r2"))
            advanceUntilIdle()

            assertEquals(listOf("r1", "r2"), delivered)
            assertEquals(0, outbox.pending())
            assertFalse(outbox.file().exists())
        }
    }

    @Test fun manualFlushReportsCountsAndKeepsUndeliverableEntries() = runTest {
        withTempDir { root ->
            val outbox = ScriptEventOutbox(root)
            outbox.append(event("r1"))
            outbox.append(event("r2"))
            var allow = 0
            val sender = ScriptEventSender {
                if (allow <= 0) throw IOException("offline")
                allow -= 1
            }
            val result = ScriptEventReporter(sender, outbox, this).flush()

            assertEquals(2, result.pending)
            assertEquals(1, result.sent)
            assertEquals(1, result.remaining)
            assertEquals(1, outbox.pending())
            assertTrue(outbox.file().exists())
        }
    }

    @Test fun queueKeepsOnlyTheNewestEntriesWhenFull() = withTempDir { root ->
        val outbox = ScriptEventOutbox(root, limit = 3)
        (1..5).forEach { outbox.append(event("r$it", it)) }

        val lines = outbox.readLines()
        assertEquals(3, lines.size)
        assertTrue(lines.first().contains("r3"))
        assertTrue(lines.last().contains("r5"))
    }
}

private fun withTempDir(block: (File) -> Unit) {
    val root = createTempDirectory("script-event-outbox").toFile()
    try {
        block(root)
    } finally {
        root.deleteRecursively()
    }
}
