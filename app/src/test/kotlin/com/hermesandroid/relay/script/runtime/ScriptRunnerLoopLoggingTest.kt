package com.hermesandroid.relay.script.runtime

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerLoopLoggingTest {
    private fun script(vararg steps: ScriptStep) = RuntimeScript("loop-logs", listOf("game.pkg"), steps.toList())

    @Test
    fun threeIterationsWriteNinePageAndTapEventsAndOneLoopStop() = runTest {
        val root = Files.createTempDirectory("loop-logs-").toFile()
        try {
            val store = ScriptRunStore(root)
            val access = Access()
            val result = ScriptRunner().run(script(ScriptStep.Loop(3, steps = listOf(
                ScriptStep.Page(asName = "before"), ScriptStep.Wait(0), ScriptStep.Tap(12, 34),
                ScriptStep.Snapshot("quiet"), ScriptStep.Page(asName = "after"),
            ))), access, onStep = { log, duration ->
                store.append(log.toLogEvent("loop-logs", "run", duration))
            })
            assertTrue(result.success)
            assertEquals(10, result.logs.size)
            assertEquals(List(3) { listOf("page", "tap", "page") }.flatten() + "loop", result.logs.map { it.op })
            assertEquals(List(3) { iteration -> List(3) { iteration + 1 } }.flatten(), result.logs.dropLast(1).map { it.iteration })
            assertTrue(result.logs.dropLast(1).all { it.loopDepth == 1 && it.stepIndex == 0 })
            assertEquals("tap x=12 y=34", result.logs[1].message)
            assertEquals("page=home as=before", result.logs.first().message)
            assertEquals("loop_stop:times", result.logs.last().message)
            assertNull(result.logs.last().loopDepth)
            assertNull(result.logs.last().iteration)
            assertEquals(3, access.taps)
            assertEquals(3, access.snapshots)

            val lines = store.todayLogFile().readLines()
            val events = lines.map { Json.decodeFromString<ScriptLogEvent>(it) }
            assertEquals(10, events.size)
            assertEquals(result.logs.map { it.loopDepth }, events.map { it.loopDepth })
            assertEquals(result.logs.map { it.iteration }, events.map { it.iteration })
            assertTrue(events.all { it.event == "step" && it.durationMs != null })
            val topLevel = Json.parseToJsonElement(lines.last()).toString()
            assertFalse(topLevel.contains("loopDepth"))
            assertFalse(topLevel.contains("iteration"))

            store.appendFailureEvidence("loop-logs", "failed", "step_failed", result.logs, "snapshot", true)
            val evidence = store.evidenceFile("failed").readLines().map { Json.decodeFromString<ScriptLogEvent>(it) }
            assertEquals(events.map { it.loopDepth }, evidence.map { it.loopDepth })
            assertEquals(events.map { it.iteration }, evidence.map { it.iteration })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nestedLoopsRestoreOuterIterationAcrossConditionalAndIncludedSteps() = runTest {
        val result = ScriptRunner().run(script(ScriptStep.Loop(2, steps = listOf(
            ScriptStep.IfPage("home", thenSteps = listOf(ScriptStep.Include("child"))),
            ScriptStep.Loop(2, steps = listOf(ScriptStep.Tap(1, 2))),
            ScriptStep.Page(),
        ))), Access(), resolveScript = {
            RuntimeScript("child", listOf("game.pkg"), listOf(ScriptStep.Page()))
        })
        assertTrue(result.success)
        val children = result.logs.dropLast(1)
        assertEquals(listOf(1, 2, 2, 1, 1, 2, 2, 1), children.map { it.loopDepth })
        assertEquals(listOf(1, 1, 2, 1, 2, 1, 2, 2), children.map { it.iteration })
    }

    @Test
    fun childRetriesProduceOneLogAndKeepFailureAndGuardSemantics() = runTest {
        val access = Access().apply { failures = 2 }
        val retried = ScriptRunner().run(script(ScriptStep.Loop(1,
            steps = listOf(ScriptStep.Tap(1, 2, retries = 2)))), access)
        assertTrue(retried.success)
        assertEquals(3, retried.logs.first().attempts)
        assertEquals(2, retried.logs.size)

        val failed = ScriptRunner().run(script(ScriptStep.Loop(3, steps = listOf(ScriptStep.Tap(1, 2)))),
            Access().apply { failures = 10 })
        assertEquals("step_failed", failed.errorCode)
        assertEquals(2, failed.logs.size)
        assertFalse(failed.logs.first().success)
        assertEquals("loop:tap", failed.logs.last().message)

        val deniedAccess = Access().apply { foreground = "com.android.systemui" }
        val denied = ScriptRunner().run(script(ScriptStep.Loop(1, steps = listOf(ScriptStep.Tap(1, 2)))), deniedAccess)
        assertEquals("guard_system_ui", denied.errorCode)
        assertEquals("guard_system_ui", denied.logs.last().message)
        assertEquals(0, deniedAccess.taps)
        assertEquals(0, denied.logs.first().attempts)
        assertEquals(1, denied.logs.first().loopDepth)
    }

    @Test
    fun topLevelStepsAndNonLoopChildrenKeepExistingLogs() = runTest {
        val result = ScriptRunner().run(script(
            ScriptStep.Tap(1, 2), ScriptStep.Page(),
            ScriptStep.IfPage("home", thenSteps = listOf(ScriptStep.Tap(3, 4))),
        ), Access())
        assertTrue(result.success)
        assertEquals(listOf("tap", "page", "if_page"), result.logs.map { it.op })
        assertEquals("tap", result.logs.first().message)
        assertTrue(result.logs.all { it.loopDepth == null && it.iteration == null })
    }

    private class Access : ScriptAccess {
        var foreground = "game.pkg"
        var taps = 0
        var snapshots = 0
        var failures = 0
        override fun foregroundPackage() = foreground
        override fun foregroundPackageFromWindows() = foreground
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override suspend fun tap(x: Int, y: Int): Boolean { taps++; return failures-- <= 0 }
        override suspend fun tapText(text: String) = true
        override suspend fun probe(expect: String, timeoutMs: Long) = true
        override suspend fun read(field: String): String? = null
        override suspend fun currentPage() = "home"
        override suspend fun saveSnapshot(tag: String): Boolean { snapshots++; return true }
        override suspend fun waitMs(ms: Long) {}
    }
}
