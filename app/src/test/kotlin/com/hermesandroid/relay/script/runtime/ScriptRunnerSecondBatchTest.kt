package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerSecondBatchTest {
    @Test fun launchUsesExplicitOrTargetPackageAndReportsFailures() = runTest {
        val explicit = Access()
        assertTrue(run(script(ScriptStep.Launch("other", 99_999)), explicit).success)
        assertEquals(listOf("other"), explicit.launches)
        assertEquals(listOf(15_000L), explicit.waits)

        val fallback = Access()
        assertTrue(run(script(ScriptStep.Launch()), fallback).success)
        assertEquals(listOf("game.pkg"), fallback.launches)
        assertEquals("invalid_script", run(RuntimeScript("id", emptyList(), listOf(ScriptStep.Launch())), Access()).errorCode)
        assertEquals("launch_failed", run(script(ScriptStep.Launch()), Access(launchOk = false)).errorCode)
    }

    @Test fun backSucceedsOrFailsWithOneAction() = runTest {
        val yes = Access()
        assertTrue(run(script(ScriptStep.Back()), yes).success)
        assertEquals(1, yes.backCalls)
        val no = Access(backOk = false)
        assertFalse(run(script(ScriptStep.Back()), no).success)
        assertEquals("back_failed", run(script(ScriptStep.Back()), Access(backOk = false)).logs.single().message)
    }

    @Test fun readVariablesDriveLoopAndFallback() = runTest {
        val access = Access(reads = mutableListOf("5/5"))
        val result = run(script(ScriptStep.Read("count", asName = "times"),
            ScriptStep.Loop(1, timesFrom = "times", steps = listOf(ScriptStep.Tap(1, 1)))), access)
        assertTrue(result.success)
        assertEquals(5, access.tapCalls)

        val noNumber = Access(reads = mutableListOf("none"))
        val fallback = run(script(ScriptStep.Read("count", asName = "times"),
            ScriptStep.Loop(2, timesFrom = "times", steps = listOf(ScriptStep.Tap(1, 1)))), noNumber)
        assertEquals(2, noNumber.tapCalls)
        assertTrue(fallback.logs.last().message.contains("times_fallback"))
    }

    @Test fun substitutionsCoverTapTextIfPageAndUntil() = runTest {
        val access = Access(probes = mutableListOf(true, true))
        val script = RuntimeScript("id", listOf("game.pkg"), listOf(
            ScriptStep.TapText("go-${'$'}{name}"),
            ScriptStep.IfPage("page-${'$'}{name}", thenSteps = listOf(ScriptStep.Tap(1, 1))),
            ScriptStep.Loop(3, until = "done-${'$'}{name}", steps = listOf(ScriptStep.Tap(2, 2))),
        ), vars = mapOf("name" to kotlinx.serialization.json.JsonPrimitive("alice")))
        assertTrue(run(script, access).success)
        assertEquals(listOf("go-alice"), access.texts)
        assertEquals(listOf("page-alice", "done-alice"), access.probeExpects)
    }

    @Test fun onFailRunsOncePreservesErrorAndTruncatesAtTen() = runTest {
        val cleanup = List(12) { ScriptStep.Back() }
        val result = run(RuntimeScript("id", listOf("game.pkg"), listOf(ScriptStep.Tap(1, 1)),
            onFail = cleanup), Access(tapOk = false, backOk = false))
        assertEquals("step_failed", result.errorCode)
        assertEquals(10, result.logs.count { it.stepIndex == -1 })
        assertTrue(result.logs.drop(1).all { it.op == "on_fail:back" })
    }

    @Test fun waitStableHandlesStableChangingAndMissingFrames() = runTest {
        val stable = Access(frames = mutableListOf(intArrayOf(1, 2), intArrayOf(1, 2), intArrayOf(1, 2)))
        assertTrue(run(script(ScriptStep.WaitStable(intervalMs = 100)), stable).success)
        val changing = Access(frames = MutableList(10) { intArrayOf(it) })
        assertEquals("not_stable", run(script(ScriptStep.WaitStable(stableTimeoutMs = 500, intervalMs = 100,
            tolerance = 0)), changing).errorCode)
        assertEquals("capture_failed", run(script(ScriptStep.WaitStable()), Access(frames = mutableListOf(null))).logs.single().message)
    }

    @Test fun meanDiffAndSnapshotSanitizingAreDeterministic() = runTest {
        assertEquals(0, meanAbsDiff(intArrayOf(1, 2), intArrayOf(1, 2)))
        assertEquals(Int.MAX_VALUE, meanAbsDiff(intArrayOf(1), intArrayOf(1, 2)))
        val access = Access()
        assertTrue(run(script(ScriptStep.Snapshot("bad tag!")), access).success)
        assertEquals(listOf("bad_tag_"), access.snapshots)
        assertEquals("capture_failed", run(script(ScriptStep.Snapshot()), Access(snapshotOk = false)).logs.single().message)
    }

    @Test fun jsonSupportsNewOperationsAndTopLevelFields() {
        val source = """{"id":"x","targetPackages":["p"],"vars":{"n":2},"steps":[{"op":"read","field":"f","as":"v"},{"op":"loop","times":1,"times_from":"v","steps":[]}],"on_fail":[{"op":"back"}]}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<RuntimeScript>(source)
        assertEquals("v", (decoded.steps[0] as ScriptStep.Read).asName)
        assertEquals("v", (decoded.steps[1] as ScriptStep.Loop).timesFrom)
        assertTrue(decoded.onFail.single() is ScriptStep.Back)
    }

    private suspend fun run(script: RuntimeScript, access: ScriptAccess) = ScriptRunner().run(script, access)
    private fun script(vararg steps: ScriptStep) = RuntimeScript("id", listOf("game.pkg"), steps.toList())

    private class Access(
        val launchOk: Boolean = true, val backOk: Boolean = true, val tapOk: Boolean = true,
        val snapshotOk: Boolean = true, val reads: MutableList<String> = mutableListOf("1"),
        val probes: MutableList<Boolean> = mutableListOf(),
        val frames: MutableList<IntArray?> = mutableListOf(),
    ) : ScriptAccess {
        var clock = 0L
        var tapCalls = 0
        var backCalls = 0
        val launches = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val texts = mutableListOf<String>()
        val probeExpects = mutableListOf<String>()
        val snapshots = mutableListOf<String>()
        override fun foregroundPackage() = "game.pkg"
        override suspend fun tap(x: Int, y: Int): Boolean { tapCalls++; return tapOk }
        override suspend fun tapText(text: String): Boolean { texts += text; return true }
        override suspend fun probe(expect: String, timeoutMs: Long): Boolean {
            probeExpects += expect
            return if (probes.isEmpty()) false else probes.removeFirst()
        }
        override suspend fun read(field: String) = if (reads.isEmpty()) null else reads.removeFirst()
        override suspend fun launchApp(pkg: String): Boolean { launches += pkg; return launchOk }
        override suspend fun back(): Boolean { backCalls++; return backOk }
        override suspend fun frameSample() = if (frames.isEmpty()) null else frames.removeFirst()
        override suspend fun saveSnapshot(tag: String): Boolean { snapshots += tag; return snapshotOk }
        override suspend fun waitMs(ms: Long) { waits += ms; clock += ms }
        override fun monotonicMs() = clock
    }
}
