package com.hermesandroid.relay.script.runtime

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun tapTemplateReportsHitAndSharesTappedCoordinates() = runTest {
        val access = Access().apply { templateHit = "hit:0.93:190,720" }
        val area = listOf(100, 600, 200, 240)
        val result = ScriptRunner().run(script(steps = arrayOf(
            ScriptStep.TapTemplate("pool.png", area, 0.92, asName = "pool", offsetX = -4, offsetY = 8),
            ScriptStep.TapText("${'$'}{pool}"),
        )), access)

        assertTrue(result.success)
        assertEquals("tap_template", result.logs.first().op)
        assertEquals("template=pool.png score=0.93 at=190,720", result.logs.first().message)
        assertEquals(1, access.taps)
        assertEquals(listOf(190 to 720), access.tapPoints)
        assertEquals(listOf("190,720"), access.texts)
        assertEquals(
            listOf(ScriptStep.TapTemplate("pool.png", area, 0.92, offsetX = -4, offsetY = 8)),
            access.templateCalls,
        )
    }

    @Test fun tapTemplateMissUsesOptionalSemanticsAndKeepsPreviousVariable() = runTest {
        val access = Access()
        val required = ScriptRunner().run(script(steps = arrayOf(ScriptStep.TapTemplate("pool.png"))), access)
        assertFalse(required.success)
        assertEquals("step_failed", required.errorCode)
        assertEquals("template_not_found:pool.png", required.logs.single().message)

        val optional = ScriptRunner().run(script(steps = arrayOf(
            ScriptStep.SetVar("pool", "previous"),
            ScriptStep.TapTemplate("pool.png", optional = true, asName = "pool"),
            ScriptStep.TapText("${'$'}{pool}"),
        )), access)
        assertTrue(optional.success)
        assertEquals("absent:pool.png", optional.logs[1].message)
        assertEquals(listOf("previous"), access.texts)
    }

    @Test fun tapTemplateWithoutTapReportsHitAndAssignsVariable() = runTest {
        val access = Access().apply { templateHit = "hit:0.93:190,720" }
        val step = Json.decodeFromString<ScriptStep>(
            """{"op":"tap_template","template":"pool.png","tap":false,"as":"pool"}""")
        val result = ScriptRunner().run(script(steps = arrayOf(
            step, ScriptStep.TapText("${'$'}{pool}"),
        )), access)

        assertTrue(result.success)
        assertEquals(0, access.taps)
        assertFalse(access.templateCalls.single().tap)
        assertEquals(listOf("190,720"), access.texts)
        assertEquals("template=pool.png score=0.93 at=190,720", result.logs.first().message)
    }

    @Test fun tapTemplateWithoutTapOptionalMissKeepsPreviousVariable() = runTest {
        val access = Access()
        val step = Json.decodeFromString<ScriptStep>(
            """{"op":"tap_template","template":"pool.png","tap":false,"optional":true,"as":"pool"}""")
        val result = ScriptRunner().run(script(steps = arrayOf(
            ScriptStep.SetVar("pool", "previous"), step, ScriptStep.TapText("${'$'}{pool}"),
        )), access)

        assertTrue(result.success)
        assertEquals(0, access.taps)
        assertEquals("absent:pool.png", result.logs[1].message)
        assertEquals(listOf("previous"), access.texts)
    }

    @Test fun logInsideLoopRecordsEveryIterationAndSubstitutesCurrentVariable() = runTest {
        val access = Access().apply { templateHit = "hit:0.93:190,720" }
        val result = ScriptRunner().run(script(steps = arrayOf(
            ScriptStep.SetVar("cnt", "0"),
            ScriptStep.Loop(3, steps = listOf(
                ScriptStep.TapTemplate("pool.png", tap = false),
                ScriptStep.IncrVar("cnt"),
                ScriptStep.Log("order_hit template=pool n=${'$'}{cnt}"),
            )),
        )), access)

        assertTrue(result.success)
        val logs = result.logs.filter { it.op == "log" }
        assertEquals(3, logs.size)
        assertEquals(listOf(1, 2, 3), logs.map { it.iteration })
        assertEquals(listOf(1, 1, 1), logs.map { it.loopDepth })
        assertEquals((1..3).map { "order_hit template=pool n=$it" }, logs.map { it.message })
        assertEquals(listOf("log", "log", "log", "loop"), result.logs.drop(1).map { it.op })
        assertEquals(0, access.taps)
        assertTrue(access.texts.isEmpty())
    }

    @Test fun logRecordsAtTopLevelInBranchesAndNestedLoops() = runTest {
        val result = ScriptRunner().run(script(steps = arrayOf(
            ScriptStep.Log("top"),
            ScriptStep.SetVar("mode", "ready"),
            ScriptStep.IfVar("mode", equals = "ready", thenSteps = listOf(ScriptStep.Log("branch"))),
            ScriptStep.Loop(2, steps = listOf(
                ScriptStep.Log("outer"),
                ScriptStep.Loop(2, steps = listOf(ScriptStep.Log("inner"))),
            )),
        )), Access())

        assertTrue(result.success)
        val logs = result.logs.filter { it.op == "log" }
        assertEquals(listOf("top", "branch", "outer", "inner", "inner", "outer", "inner", "inner"),
            logs.map { it.message })
        assertEquals(listOf(null, null, 1, 2, 2, 1, 2, 2), logs.map { it.loopDepth })
        assertEquals(listOf(null, null, 1, 1, 2, 2, 1, 2), logs.map { it.iteration })
    }

    @Test fun logTruncatesToThreeHundredCharactersAfterSubstitution() = runTest {
        val message = "订单".repeat(200)
        val result = ScriptRunner().run(script(steps = arrayOf(
            ScriptStep.SetVar("message", message),
            ScriptStep.Log("${'$'}{message}"),
            ScriptStep.Loop(1, steps = listOf(ScriptStep.Log("${'$'}{message}"))),
        )), Access())

        assertTrue(result.success)
        val logs = result.logs.filter { it.op == "log" }
        assertEquals(2, logs.size)
        assertTrue(logs.all { it.message.length == 300 && it.message == message.take(300) })
    }

    @Test fun logRoundTripsFieldsAndRequiresMessage() {
        val source = """{"op":"log","message":"n=${'$'}{cnt}","id":"hit","timeout_ms":5000,"retries":2}"""
        val step = Json.decodeFromString<ScriptStep>(source)
        assertEquals(ScriptStep.Log("n=${'$'}{cnt}", "hit", 5_000, 2), step)
        assertEquals(source, Json.encodeToString(ScriptStepSerializer, step))
        assertEquals(step, Json.decodeFromString<ScriptStep>(Json.encodeToString(ScriptStepSerializer, step)))
        val missing = runCatching { Json.decodeFromString<ScriptStep>("""{"op":"log"}""") }.exceptionOrNull()
        assertEquals("missing 'message'", missing?.message)
    }

    @Test fun tapTemplateSerializesOnlyFalseAndPreservesLegacyBytes() {
        val legacy = """{"op":"tap_template","template":"pool.png","min":0.9,"optional":false,"offset_x":0,"offset_y":0}"""
        val defaultStep = Json.decodeFromString<ScriptStep>(legacy) as ScriptStep.TapTemplate
        val explicitTrue = Json.decodeFromString<ScriptStep>(legacy.dropLast(1) + ",\"tap\":true}")
        assertTrue(defaultStep.tap)
        assertEquals(defaultStep, explicitTrue)
        assertEquals(legacy, Json.encodeToString(ScriptStepSerializer, defaultStep))
        assertEquals(legacy, Json.encodeToString(ScriptStepSerializer, explicitTrue))

        val noTap = defaultStep.copy(tap = false)
        val encoded = Json.encodeToString(ScriptStepSerializer, noTap)
        assertEquals(legacy.dropLast(1) + ",\"tap\":false}", encoded)
        assertEquals(noTap, Json.decodeFromString<ScriptStep>(encoded))
    }

    @Test fun tapTemplateIsGuardedBeforeMatching() = runTest {
        val access = Access().apply { foreground = "com.android.systemui" }
        val result = ScriptRunner().run(script(steps = arrayOf(
            ScriptStep.TapTemplate("pool.png", optional = true),
        )), access)

        assertEquals("guard_system_ui", result.errorCode)
        assertTrue(access.templateCalls.isEmpty())
    }

    @Test fun tapTemplateRoundTripsFieldsAndClampsFiniteScores() {
        val source = """{"op":"tap_template","template":"pool.png","area":[100,600,200,240],
            "min":0.93,"optional":true,"as":"pool","offset_x":-4,"offset_y":8,
            "id":"select","timeout_ms":5000,"retries":2}"""
        val step = Json.decodeFromString<ScriptStep>(source)
        assertEquals(ScriptStep.TapTemplate(
            "pool.png", listOf(100, 600, 200, 240), 0.93, true, "pool", -4, 8,
            id = "select", timeoutMs = 5_000, retries = 2,
        ), step)
        assertEquals(step, Json.decodeFromString<ScriptStep>(Json.encodeToString(ScriptStepSerializer, step)))

        val defaults = """{"op":"tap_template","template":"pool.png"}"""
        assertEquals(ScriptStep.TapTemplate("pool.png"), Json.decodeFromString<ScriptStep>(defaults))
        assertEquals(0.50, (Json.decodeFromString<ScriptStep>(source.replace("0.93", "0.30")) as ScriptStep.TapTemplate).min, 0.0)
        assertEquals(1.00, (Json.decodeFromString<ScriptStep>(source.replace("0.93", "1.30")) as ScriptStep.TapTemplate).min, 0.0)
    }

    @Test fun tapTemplateRejectsMalformedFieldsInCatalog() {
        val invalidSteps = listOf(
            "",
            ""","template":"" """,
            ""","template":"../pool.png" """,
            ""","template":"pool.png","min":null""",
            ""","template":"pool.png","min":"invalid" """,
            ""","template":"pool.png","min":"NaN" """,
            ""","template":"pool.png","min":1e400""",
            ""","template":"pool.png","area":[0,0,100]""",
            ""","template":"pool.png","area":[0,0,100,1.5]""",
        )
        for (fields in invalidSteps) {
            val catalog = """[{"id":"invalid","targetPackages":["game.pkg"],
                "steps":[{"op":"tap_template"$fields}]}]"""
            assertNull(catalog, ScriptRuntime.decodeCatalog(catalog, warn = {}).singleOrNull())
            assertEquals("invalid_script", ScriptRuntime.missingScriptError("invalid"))
        }
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
        val tapPoints = mutableListOf<Pair<Int, Int>>()
        var templateHit: String? = null
        val templateCalls = mutableListOf<ScriptStep.TapTemplate>()
        var snapshotThrows = false
        val texts = mutableListOf<String>()
        val snapshots = mutableListOf<String>()
        override fun foregroundPackage() = foreground
        override fun foregroundPackageFromWindows() = foreground
        override fun screenOn() = on
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override suspend fun tap(x: Int, y: Int): Boolean { taps++; tapPoints += x to y; return tapOk }
        override suspend fun tapTemplate(name: String, area: List<Int>?, minScore: Double,
            offsetX: Int, offsetY: Int, tap: Boolean): String? {
            templateCalls += ScriptStep.TapTemplate(name, area, minScore, offsetX = offsetX, offsetY = offsetY, tap = tap)
            val hit = templateHit ?: return null
            val (x, y) = hit.substringAfterLast(':').split(',').map { it.toInt() }
            return if (!tap || tap(x, y)) hit else null
        }
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
