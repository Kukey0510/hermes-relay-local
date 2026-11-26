package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptMiscTest {
    private fun script(vararg steps: ScriptStep) = RuntimeScript("root", listOf("game.pkg"), steps.toList())
    private fun log(index: Int) = ScriptRunLog(index, "id-$index", "tap", true, 1, "step $index", 2, 3)

    @Test fun statusJsonIncludesStepFieldsAndExistingStatusFields() {
        val status = ScriptRuntime.RunStatus("run", state = "done", errorCode = "step_failed",
            lastStep = 7, startedAt = 0L, finishedAt = 12_000_000L, logs = listOf(log(7)))
        assertEquals(Json.parseToJsonElement("""{
            "runId":"run","state":"done","errorCode":"step_failed","lastStep":7,"elapsed_ms":12,
            "logs":[{"stepIndex":7,"stepId":"id-7","op":"tap","success":true,"attempts":1,
                "message":"step 7","loopDepth":2,"iteration":3}]
        }"""), scriptStatusJson("run", status))
    }

    @Test fun statusJsonLimitsLogsToNewestTwoHundredInOrder() {
        val status = ScriptRuntime.RunStatus("run", logs = (0..249).map(::log))
        val logs = scriptStatusJson("run", status).getValue("logs").jsonArray
        assertEquals(200, logs.size)
        assertEquals(JsonPrimitive(50), logs.first().jsonObject["stepIndex"])
        assertEquals(JsonPrimitive(249), logs.last().jsonObject["stepIndex"])
    }

    @Test fun statusUpdatesKeepBoundedImmutableSnapshotsAndSurviveCompletion() {
        val initial = ScriptRuntime.RunStatus("run")
        val live = (0..249).fold(initial) { status, index -> status.withLog(log(index)) }
        assertTrue(initial.logs.isEmpty())
        assertEquals((50..249).toList(), live.logs.map { it.stepIndex })
        assertEquals(249, live.lastStep)
        for (state in listOf("paused", "done", "cancelled")) {
            val copy = live.copy(state = state, finishedAt = System.nanoTime())
            assertEquals(live.logs, copy.logs)
            assertEquals(200, scriptStatusJson("run", copy).getValue("logs").jsonArray.size)
        }
    }

    @Test fun unknownStatusHasEmptyLogsAndNonLoopFieldsAreExplicitNulls() {
        val unknown = scriptStatusJson("absent", null)
        assertEquals(JsonPrimitive("unknown"), unknown["state"])
        assertTrue(unknown.getValue("logs").jsonArray.isEmpty())
        val status = ScriptRuntime.RunStatus("run", logs = listOf(
            ScriptRunLog(0, null, "probe", false, 1, "miss")))
        val entry = scriptStatusJson("run", status).getValue("logs").jsonArray.single().jsonObject
        assertEquals(JsonNull, entry["loopDepth"])
        assertEquals(JsonNull, entry["iteration"])
        assertEquals(JsonPrimitive(false), entry["success"])
    }

    @Test fun pageTimingsParseSumAndFormatMilliseconds() {
        val first = PageTimings.fromPayload(Json.parseToJsonElement(
            """{"capture_ms":28,"match_ms":202}""").jsonObject)
        val second = PageTimings.fromPayload(Json.parseToJsonElement(
            """{"capture_ms":2900,"match_ms":3}""").jsonObject)
        assertEquals(" capture_ms=28 match_ms=202", first.messageSuffix())
        assertEquals(PageTimings(2928, 205), first + second)
        assertEquals(PageTimings(), PageTimings.fromPayload(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test fun pageAndProbeMessagesCarryTimingsAndPreserveResults() = runTest {
        val access = Access().apply { pageTimings = PageTimings(28, 202) }
        val result = ScriptRunner().run(script(ScriptStep.Page(), ScriptStep.Probe("home")), access)
        assertTrue(result.success)
        assertEquals(listOf("page=home as=page capture_ms=28 match_ms=202",
            "probe=home capture_ms=28 match_ms=202"), result.logs.map { it.message })
    }

    @Test fun unknownPageAndFailedProbeStillLogTimings() = runTest {
        val access = Access().apply {
            page = null
            probeHit = false
            pageTimings = PageTimings(2900, 0)
        }
        val result = ScriptRunner().run(script(ScriptStep.Page(), ScriptStep.Probe("home")), access)
        assertEquals("step_failed", result.errorCode)
        assertEquals("page=other as=page capture_ms=2900 match_ms=0", result.logs.first().message)
        assertEquals("probe=home capture_ms=2900 match_ms=0", result.logs.last().message)
    }

    @Test fun runtimeStartAtParsesBothSpellingsAndDefaultsToZero() {
        val source = """{"id":"root","targetPackages":[],"steps":[{"op":"wait","ms":0},{"op":"wait","ms":1}]"""
        assertEquals(0, Json.decodeFromString<RuntimeScript>("$source}").startAt)
        for (name in listOf("start_at", "startAt")) {
            val parsed = Json.decodeFromString<RuntimeScript>("$source,\"$name\":1}")
            assertEquals(1, parsed.startAt)
            assertEquals(parsed, Json.decodeFromString<RuntimeScript>(Json.encodeToString(RuntimeScript.serializer(), parsed)))
        }
    }

    @Test fun startAtSkipsActionsAndKeepsOriginalStepIndicesInCallbacks() = runTest {
        val access = Access()
        val observed = mutableListOf<ScriptRunLog>()
        val result = ScriptRunner().run(script(ScriptStep.Tap(0, 0), ScriptStep.Tap(1, 1),
            ScriptStep.Tap(2, 2)).copy(startAt = 1), access, onStep = { log, _ -> observed += log })
        assertTrue(result.success)
        assertEquals(listOf(1 to 1, 2 to 2), access.taps)
        assertEquals(listOf(1, 2), result.logs.map { it.stepIndex })
        assertEquals(result.logs, observed)
    }

    @Test fun omittedStartAtAndExplicitZeroExecuteIdentically() = runTest {
        val source = script(ScriptStep.Tap(1, 2), ScriptStep.Page())
        val implicit = ScriptRunner().run(source, Access())
        val explicit = ScriptRunner().run(source.copy(startAt = 0), Access())
        assertEquals(implicit.logs, explicit.logs)
        assertEquals(implicit.success, explicit.success)
    }

    @Test fun invalidStartAtFailsBeforeCallbacksAndActionsAndReleasesRunner() = runTest {
        for (startAt in listOf(-1, 1, Int.MAX_VALUE)) {
            val access = Access()
            var callbacks = 0
            val result = ScriptRunner().run(script(ScriptStep.Tap(1, 2)).copy(startAt = startAt), access,
                onRunStart = { callbacks++ },
                onStep = { _, _ -> callbacks++ })
            assertFalse(result.success)
            assertEquals("invalid_script", result.errorCode)
            assertTrue(result.logs.isEmpty())
            assertTrue(access.taps.isEmpty())
            assertEquals(0, callbacks)
        }
        assertTrue(ScriptRunner().run(script(ScriptStep.Tap(1, 2)), Access()).success)
    }

    @Test fun emptyScriptKeepsZeroStartCompatibilityButRejectsOtherOffsets() = runTest {
        assertTrue(ScriptRunner().run(script(), Access()).success)
        assertEquals("invalid_script", ScriptRunner().run(script().copy(startAt = 1), Access()).errorCode)
        assertEquals("invalid_script", ScriptRunner().run(script().copy(startAt = -1), Access()).errorCode)
    }

    @Test fun includedScriptHonorsStartAtAndRejectsInvalidOffsets() = runTest {
        val access = Access()
        val child = script(ScriptStep.Tap(1, 1), ScriptStep.Tap(2, 2)).copy(id = "child", startAt = 1)
        assertTrue(ScriptRunner().run(script(ScriptStep.Include("child")), access, resolveScript = { child }).success)
        assertEquals(listOf(2 to 2), access.taps)
        val invalid = ScriptRunner().run(script(ScriptStep.Include("child")), Access(),
            resolveScript = { child.copy(startAt = 2) })
        assertEquals("invalid_script", invalid.errorCode)
    }

    @Test fun weakestMissingCentersOrYFailsParsingAndOtherDefaultsRemain() {
        for (fields in listOf("\"y\":355", "\"centers\":[590]", "\"dry\":true")) {
            val error = runCatching { Json.decodeFromString<ScriptStep>("""{"op":"tapWeakest",$fields}""") }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("invalid_script"))
        }
        val step = Json.decodeFromString<ScriptStep>("""{"op":"tapWeakest","centers":[100],"y":200}""") as ScriptStep.TapWeakest
        assertEquals(0, step.fallbackIndex)
        assertEquals(0.5, step.minConfidence, 0.0)
        assertFalse(step.dry)
        assertEquals(step, Json.decodeFromString<ScriptStep>(Json.encodeToString(ScriptStepSerializer, step)))
    }

    @Test fun includePrefixesExistingChildLogsWithoutChangingTheirCount() = runTest {
        val child = script(ScriptStep.Log("begin"), ScriptStep.Loop(3, steps = listOf(
            ScriptStep.Page(), ScriptStep.Tap(1, 2), ScriptStep.Wait(0)))).copy(id = "child")
        val plain = ScriptRunner().run(child, Access())
        val observed = mutableListOf<ScriptRunLog>()
        val included = ScriptRunner().run(script(ScriptStep.Include("child")), Access(),
            resolveScript = { child }, onStep = { log, _ -> observed += log })
        assertTrue(included.success)
        assertEquals(plain.logs.size, included.logs.size)
        assertEquals(8, included.logs.size)
        assertEquals(included.logs, observed)
        assertEquals("include:child/log begin", included.logs.first().message)
        assertEquals("include:child/loop[3]/tap tap x=1 y=2", included.logs[6].message)
        assertEquals(1, included.logs[6].loopDepth)
        assertEquals(3, included.logs[6].iteration)
        assertEquals("include", included.logs.last().message)
    }

    @Test fun nestedIncludePathsRestoreAfterEachContainerAndRoot() = runTest {
        val child = script(ScriptStep.Include("grand"), ScriptStep.Log("child done")).copy(id = "child")
        val grand = script(ScriptStep.Loop(1, steps = listOf(ScriptStep.Log("deep")))).copy(id = "grand")
        val result = ScriptRunner().run(script(ScriptStep.Include("child"), ScriptStep.Log("root done")), Access(),
            resolveScript = { if (it == "child") child else grand })
        assertTrue(result.success)
        assertEquals(listOf("include:child/include:grand/loop[1]/log deep",
            "include:child/log child done", "include", "root done"), result.logs.map { it.message })
    }

    @Test fun includedFailedTapKeepsAttemptsErrorAndLogCount() = runTest {
        val child = script(ScriptStep.Loop(1, steps = listOf(ScriptStep.Tap(1, 2, retries = 2))))
        val access = Access().apply { tapHit = false }
        val result = ScriptRunner().run(script(ScriptStep.Include("child")), access, resolveScript = { child })
        assertEquals("step_failed", result.errorCode)
        assertEquals(2, result.logs.size)
        assertEquals(3, result.logs.first().attempts)
        assertEquals(3, access.taps.size)
        assertFalse(result.logs.first().success)
        assertEquals("include:child/loop[1]/tap tap x=1 y=2", result.logs.first().message)
    }

    private class Access : ScriptAccess {
        val taps = mutableListOf<Pair<Int, Int>>()
        var page: String? = "home"
        var probeHit = true
        var tapHit = true
        override var pageTimings: PageTimings? = null
        override fun foregroundPackage() = "game.pkg"
        override fun foregroundPackageFromWindows() = "game.pkg"
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override suspend fun tap(x: Int, y: Int): Boolean { taps += x to y; return tapHit }
        override suspend fun tapText(text: String) = true
        override suspend fun probe(expect: String, timeoutMs: Long) = probeHit
        override suspend fun read(field: String): String? = null
        override suspend fun currentPage() = page
        override suspend fun waitMs(ms: Long) {}
    }
}
