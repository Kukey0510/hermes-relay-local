package com.hermesandroid.relay.script.runtime

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerPrimitivesTest {
    private fun script(id: String = "root", vararg steps: ScriptStep) =
        RuntimeScript(id, listOf("game.pkg"), steps.toList())

    @Test fun includeExpandsLatestStepsSharesVariablesAndIgnoresChildDefaults() = runTest {
        val access = Access()
        var child = RuntimeScript("child", listOf("other.pkg"), listOf(
            ScriptStep.SetVar("rescues", "${'$'}{parent}"), ScriptStep.IncrVar("rescues")),
            vars = mapOf("parent" to JsonPrimitive("99")), onFail = listOf(ScriptStep.TapText("cleanup")))
        val parent = script(steps = arrayOf(
            ScriptStep.SetVar("parent", "2"), ScriptStep.Include("child"),
            ScriptStep.IfVar("rescues", equals = "3", thenSteps = listOf(ScriptStep.TapText("hit"))),
            ScriptStep.Include("child"),
        ))
        var loads = 0
        val result = ScriptRunner().run(parent, access, resolveScript = {
            assertEquals("child", it)
            loads++
            val latest = child
            child = child.copy(steps = listOf(ScriptStep.TapText("new")))
            latest
        })
        assertTrue(result.success)
        assertEquals(2, loads)
        assertEquals(listOf("hit", "new"), access.texts)
    }

    @Test fun includeReportsMissingAndInvalidTargets() = runTest {
        val parent = script(steps = arrayOf(ScriptStep.Include("missing")))
        assertEquals("script_not_found", ScriptRunner().run(parent, Access()).errorCode)
        val invalid = ScriptRunner().run(parent, Access(), resolveScript = {
            Json.decodeFromString<RuntimeScript>("broken")
        })
        assertEquals("invalid_script", invalid.errorCode)
    }

    @Test fun includeRejectsDirectAndIndirectCyclesButAllowsRepeatedSiblings() = runTest {
        val direct = script(steps = arrayOf(ScriptStep.Include("root")))
        assertEquals("invalid_script", ScriptRunner().run(direct, Access()).errorCode)
        val child = script("child", ScriptStep.Include("root"))
        assertEquals("invalid_script", ScriptRunner().run(
            script(steps = arrayOf(ScriptStep.Include("child"))), Access(), resolveScript = { child }).errorCode)
        val access = Access()
        assertTrue(ScriptRunner().run(script(steps = arrayOf(
            ScriptStep.Include("child"), ScriptStep.Include("child"))), access,
            resolveScript = { script("child", ScriptStep.TapText("child")) }).success)
        assertEquals(listOf("child", "child"), access.texts)
    }

    @Test fun includesShareDepthLimitWithExistingContainers() = runTest {
        suspend fun nested(levels: Int): ScriptRunResult {
            var step: ScriptStep = ScriptStep.Include("child")
            repeat(levels) { step = ScriptStep.Loop(1, steps = listOf(step)) }
            return ScriptRunner().run(script(steps = arrayOf(step)), Access(), resolveScript = {
                script("child", ScriptStep.Tap(1, 1))
            })
        }
        assertTrue(nested(7).success)
        assertEquals("invalid_script", nested(8).errorCode)
        val chain = ScriptRunner().run(script(steps = arrayOf(ScriptStep.Include("1"))), Access(),
            resolveScript = { id -> script(id, ScriptStep.Include((id.toInt() + 1).toString())) })
        assertEquals("invalid_script", chain.errorCode)
        assertTrue(chain.logs.single().message.contains("nesting_too_deep"))
    }

    @Test fun includeContainerBypassesGuardButChildrenAreIndividuallyGuarded() = runTest {
        val access = Access().apply { foreground = "com.android.systemui" }
        val parent = script(steps = arrayOf(ScriptStep.Include("child")))
        assertTrue(ScriptRunner().run(parent, access, resolveScript = { script("child") }).success)
        val denied = ScriptRunner().run(parent, access, resolveScript = { script("child", ScriptStep.Tap(1, 1)) })
        assertEquals("guard_system_ui", denied.errorCode)
        assertEquals(0, access.taps)
        // Launch is exempt, then each subsequent child reads the new foreground again.
        val restored = ScriptRunner().run(parent, access, resolveScript = {
            script("child", ScriptStep.Launch(), ScriptStep.Tap(1, 1))
        })
        assertTrue(restored.success)
        assertEquals(1, access.taps)
    }

    @Test fun assignmentAndIncrementUseRoundScopeAndStringComparisons() = runTest {
        val access = Access()
        val result = ScriptRunner().run(script(steps = arrayOf(
            ScriptStep.SetVar("rescues", "0"), ScriptStep.IncrVar("rescues", 1),
            ScriptStep.IfVar("rescues", equals = "1", thenSteps = listOf(ScriptStep.TapText("one"))),
            ScriptStep.Loop(2, steps = listOf(ScriptStep.IncrVar("rescues"))),
            ScriptStep.IfVar("rescues", equals = "3", thenSteps = listOf(ScriptStep.TapText("three"))),
            ScriptStep.SetVar("copy", "${'$'}{rescues}"), ScriptStep.TapText("${'$'}{copy}"),
            ScriptStep.SetVar("bad", "not a number"), ScriptStep.IncrVar("bad", -2),
            ScriptStep.TapText("${'$'}{bad}"), ScriptStep.IncrVar("undeclared"),
            ScriptStep.TapText("${'$'}{undeclared}"),
            ScriptStep.SetVar("literal", "${'$'}{unknown}"), ScriptStep.TapText("${'$'}{literal}"),
        )), access)
        assertTrue(result.success)
        assertEquals(listOf("one", "three", "3", "-2", "1", "${'$'}{unknown}"), access.texts)
        val next = Access()
        assertTrue(ScriptRunner().run(script(steps = arrayOf(ScriptStep.IncrVar("rescues"),
            ScriptStep.TapText("${'$'}{rescues}"))), next).success)
        assertEquals(listOf("1"), next.texts)
    }

    @Test fun newOperationsRoundTripAndDefaultsDecode() {
        val source = """{"id":"root","targetPackages":["game.pkg"],"steps":[
            {"op":"include","script":"child"},{"op":"set_var","name":"n","value":"0"},
            {"op":"incr_var","name":"n"},{"op":"incr_var","name":"n","by":-3}]}"""
        val parsed = Json.decodeFromString<RuntimeScript>(source)
        assertTrue(parsed.autoEvidence)
        assertEquals(1L, (parsed.steps[2] as ScriptStep.IncrVar).by)
        assertEquals(-3L, (parsed.steps[3] as ScriptStep.IncrVar).by)
        parsed.steps.forEach { step ->
            assertEquals(step, Json.decodeFromString<ScriptStep>(Json.encodeToString(ScriptStepSerializer, step)))
        }
        assertFalse(Json.decodeFromString<RuntimeScript>(source.dropLast(1) + ",\"auto_evidence\":false}").autoEvidence)
    }

    @Test fun failureCapturesOnceAndAppendsOnlyLastTwentyLogsAsJsonLines() = runTest {
        val root = Files.createTempDirectory("script-evidence-").toFile()
        try {
            val store = ScriptRunStore(root)
            val access = Access().apply { tapOk = false }
            val parent = script(steps = (List(25) { ScriptStep.Wait(0) } + ScriptStep.Tap(1, 1)).toTypedArray())
            val result = ScriptRunner().run(parent, access, runId = "test-run", evidenceStore = store)
            assertFalse(result.success)
            assertEquals(listOf("fail_root_25"), access.snapshots)
            val file = store.evidenceFile("test-run")
            assertEquals(root.resolve("logs/script-failure-test-run.jsonl"), file)
            val events = file.readLines().map { Json.decodeFromString<ScriptLogEvent>(it) }
            assertEquals(20, events.size)
            assertEquals(result.logs.takeLast(20).map { it.message }, events.map { it.message })
            assertEquals(6, events.first().stepIndex)
            assertEquals(false, events.last().success)
            assertTrue(events.all { it.runId == "test-run" && it.scriptId == "root" &&
                it.snapshotTag == "fail_root_25" && it.snapshotSaved == true && it.errorCode == "step_failed" })
            store.appendFailureEvidence("root", "test-run", "step_failed", result.logs, "again", false)
            assertEquals(40, file.readLines().size)
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationScreenOffOptOutAndSuccessNeverCaptureOrWriteEvidence() = runTest {
        val root = Files.createTempDirectory("script-no-evidence-").toFile()
        try {
            val store = ScriptRunStore(root)
            for (mode in listOf("cancelled", "screen_off", "disabled", "success")) {
                val access = Access().apply {
                    tapOk = mode == "success"
                    on = mode != "screen_off"
                }
                val parent = script(steps = arrayOf(ScriptStep.Tap(1, 1))).copy(autoEvidence = mode != "disabled")
                val result = ScriptRunner().run(parent, access, runId = mode, evidenceStore = store,
                    onRunStart = { if (mode == "cancelled") ScriptRunControl.requestStop("test") })
                if (mode in setOf("cancelled", "screen_off")) assertEquals(mode, result.errorCode)
                assertTrue(access.snapshots.isEmpty())
                assertFalse(store.evidenceFile(mode).exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun snapshotAndDiskErrorsDoNotReplaceOriginalFailure() = runTest {
        val root = Files.createTempDirectory("script-bad-evidence-").toFile()
        try {
            val store = ScriptRunStore(root)
            val access = Access().apply { tapOk = false; snapshotThrows = true }
            val result = ScriptRunner().run(script(steps = arrayOf(ScriptStep.Tap(1, 1))), access,
                runId = "snapshot-error", evidenceStore = store)
            assertEquals("step_failed", result.errorCode)
            assertEquals(1, access.snapshots.size)
            assertEquals(false, Json.decodeFromString<ScriptLogEvent>(
                store.evidenceFile("snapshot-error").readLines().single()).snapshotSaved)
            root.resolve("logs").deleteRecursively()
            root.resolve("logs").writeText("blocks directory creation")
            assertEquals("step_failed", ScriptRunner().run(script(steps = arrayOf(ScriptStep.Tap(1, 1))),
                access, evidenceStore = store).errorCode)
        } finally { root.deleteRecursively() }
    }

    private class Access : ScriptAccess {
        var foreground = "game.pkg"
        var on = true
        var tapOk = true
        var taps = 0
        var snapshotThrows = false
        val texts = mutableListOf<String>()
        val snapshots = mutableListOf<String>()
        override fun foregroundPackage() = foreground
        override fun foregroundPackageFromWindows() = foreground
        override fun screenOn() = on
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override suspend fun tap(x: Int, y: Int): Boolean { taps++; return tapOk }
        override suspend fun tapText(text: String): Boolean { texts += text; return true }
        override suspend fun probe(expect: String, timeoutMs: Long) = false
        override suspend fun read(field: String): String? = null
        override suspend fun launchApp(pkg: String): Boolean { foreground = pkg; return true }
        override suspend fun waitMs(ms: Long) {}
        override suspend fun saveSnapshot(tag: String): Boolean {
            snapshots += tag
            if (snapshotThrows) error("capture unavailable")
            return true
        }
    }
}
