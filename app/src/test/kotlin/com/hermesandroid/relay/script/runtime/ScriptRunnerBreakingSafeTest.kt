package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerBreakingSafeTest {
    private fun script(vararg steps: ScriptStep) = RuntimeScript("safe", listOf("game.pkg"), steps.toList())
    private fun decode(source: String) = Json.decodeFromString<ScriptStep>(source)

    @Test fun untilStringAndBothObjectFormsRoundTripAndForwardPageAndSpec() = runTest {
        val forms = listOf(
            "\"home\"" to ("home" to null),
            """{"page":"jc_home","spec":"jc"}""" to ("jc_home" to "jc"),
            """{"page":"home"}""" to ("home" to null),
        )
        for ((until, expected) in forms) {
            val step = decode("""{"op":"loop","times":3,"until":$until,"steps":[{"op":"tap","x":1,"y":2}]}""")
            val encoded = Json.encodeToString(ScriptStepSerializer, step)
            assertEquals(Json.parseToJsonElement(until), Json.parseToJsonElement(encoded).jsonObject["until"])
            assertEquals(step, decode(encoded))
            val access = Access()
            val result = ScriptRunner().run(script(step), access)
            assertTrue(result.success)
            assertEquals("loop_stop:until", result.logs.last().message)
            assertEquals(listOf(expected), access.probes)
            assertEquals(listOf(1_000L), access.probeTimeouts)
            assertEquals(listOf(1 to 2), access.taps)
        }
    }

    @Test fun untilObjectsSubstitutePageAndOmittedUntilRemainsOptional() = runTest {
        val access = Access()
        val result = ScriptRunner().run(script(
            ScriptStep.SetVar("end", "jc_home"),
            decode("""{"op":"loop","times":2,"until":{"page":"${'$'}{end}","spec":"jc"}}"""),
            decode("""{"op":"loop","times":1}"""),
        ), access)
        assertTrue(result.success)
        assertEquals(listOf("jc_home" to "jc"), access.probes)
        assertEquals("loop_stop:times", result.logs.last().message)
    }

    @Test fun legacyCatalogStillParsesExecutesAndUsesDefaultSpec() = runTest {
        val catalog = """[{"id":"legacy","targetPackages":["game.pkg"],"steps":[
            {"op":"loop","times":3,"until":"home","steps":[{"op":"tapWeakest","fallback_index":2,"centers":[590,960,1330,1700],"y":355}]}
        ]}]"""
        val parsed = ScriptRuntime.decodeCatalog(catalog, warn = {})
        assertEquals(1, parsed.size)
        val loop = parsed.single().steps.single() as ScriptStep.Loop
        assertEquals("home", loop.until)
        assertNull(loop.untilCondition)
        val access = Access()
        val result = ScriptRunner().run(parsed.single(), access)
        assertTrue(result.success)
        assertEquals(listOf("home" to null), access.probes)
        assertEquals(listOf(1330 to 355), access.taps)
    }

    @Test fun malformedUntilObjectsAreInvalidCatalogEntries() {
        for (until in listOf("42", "[]", "{}", """{"page":12}""", """{"page":"home","spec":42}""")) {
            val catalog = """[{"id":"bad","targetPackages":["game.pkg"],"steps":[{"op":"loop","times":1,"until":$until}]}]"""
            assertTrue(ScriptRuntime.decodeCatalog(catalog, warn = {}).isEmpty())
            assertEquals("invalid_script", ScriptRuntime.missingScriptError("bad"))
        }
    }

    @Test fun weakestUsesExplicitCoordinatesAndSelectedIndexWithoutReordering() = runTest {
        val step = decode("""{"op":"tapWeakest","fallback_index":0,"centers":[800,200,600],"y":720}""")
        assertEquals(step, decode(Json.encodeToString(ScriptStepSerializer, step)))
        val access = Access().apply {
            powers = PowerRecognition(listOf(
                listOf(PowerReading(2), PowerReading(100)),
                listOf(PowerReading(40), PowerReading(40)),
                listOf(PowerReading(1, 0.2f), PowerReading(1)),
                listOf(PowerReading(0), PowerReading(0)), // No corresponding center: ignore.
            ), 7)
        }
        val result = ScriptRunner().run(script(step), access)
        assertTrue(result.success)
        assertEquals(listOf(200 to 720), access.taps)
        assertTrue(result.logs.single().message.contains("selected=1"))
        assertTrue(result.logs.single().message.contains("power_selected"))
    }

    @Test fun weakestExplicitCoordinatesKeepFallbackClamping() = runTest {
        val centers = listOf(590, 960, 1330, 1700)
        for ((fallback, index) in listOf(-1 to 0, 0 to 0, 1 to 1, 2 to 2, 3 to 3, 99 to 3)) {
            val step = decode("""{"op":"tapWeakest","fallback_index":$fallback,"centers":[590,960,1330,1700],"y":355}""") as ScriptStep.TapWeakest
            assertEquals(centers, step.centers)
            assertEquals(355, step.y)
            val access = Access()
            val result = ScriptRunner().run(script(step), access)
            assertTrue(result.success)
            assertEquals(listOf(centers[index] to 355), access.taps)
            assertTrue(result.logs.single().message.contains("power_fallback"))
            assertTrue(result.logs.single().message.contains("selected=$index"))
        }
    }

    @Test fun weakestSupportsOneThroughEightCardsAndFallbackUsesConfiguredRange() = runTest {
        for (count in listOf(1, 2, 8)) {
            val centers = List(count) { 100 + it * 100 }
            val access = Access()
            val result = ScriptRunner().run(script(ScriptStep.TapWeakest(fallbackIndex = 99,
                centers = centers, y = 0)), access)
            assertTrue(result.success)
            assertEquals(listOf(centers.last() to 0), access.taps)
            assertTrue(result.logs.single().message.contains("selected=${count - 1}"))
        }
        val access = Access().apply {
            powers = PowerRecognition(List(8) { index -> listOf(PowerReading(80 - index), PowerReading(1)) }, 9)
        }
        val result = ScriptRunner().run(script(ScriptStep.TapWeakest(centers = List(8) { it * 100 }, y = 1080)), access)
        assertTrue(result.success)
        assertEquals(listOf(700 to 1080), access.taps)
        assertTrue(result.logs.single().message.contains("selected=7"))
    }

    @Test fun weakestInvalidCoordinatesReturnInvalidScriptBeforeRecognitionOrTapEvenWhenDry() = runTest {
        for (step in listOf(
            ScriptStep.TapWeakest(centers = emptyList(), y = 355),
            ScriptStep.TapWeakest(centers = List(9) { it }, y = 355),
            ScriptStep.TapWeakest(centers = listOf(590), y = -1),
            ScriptStep.TapWeakest(centers = listOf(590), y = 1081, dry = true),
        )) {
            val access = Access()
            val result = ScriptRunner().run(script(step), access)
            assertFalse(result.success)
            assertEquals("invalid_script", result.errorCode)
            assertTrue(access.taps.isEmpty())
            assertEquals(0, access.recognitions)
        }
    }

    @Test fun weakestMalformedCoordinatesAreInvalidCatalogEntries() {
        for (fields in listOf(
            """"centers":[]""", """"centers":[0,1,2,3,4,5,6,7,8]""",
            """"centers":[1.5]""", """"centers":["100"]""", """"centers":null""",
            "\"y\":\"355\"", """"y":null""", """"y":1.5""",
        )) {
            val coordinates = if (fields.startsWith("\"centers\"")) "$fields,\"y\":355" else "\"centers\":[590],$fields"
            val catalog = """[{"id":"bad","targetPackages":["game.pkg"],"steps":[{"op":"tapWeakest",$coordinates}]}]"""
            assertTrue(ScriptRuntime.decodeCatalog(catalog, warn = {}).isEmpty())
            assertEquals("invalid_script", ScriptRuntime.missingScriptError("bad"))
        }
    }

    @Test fun weakestDryModeStillSelectsButDoesNotTap() = runTest {
        val access = Access()
        val result = ScriptRunner().run(script(ScriptStep.TapWeakest(fallbackIndex = 1,
            centers = listOf(10, 20), y = 30, dry = true)), access)
        assertTrue(result.success)
        assertTrue(access.taps.isEmpty())
        assertTrue(result.logs.single().message.contains("selected=1"))
    }

    @Test fun ifPageHundredMsProbeBudgetDoesNotCutOffThreeHundredMsThenOrElse() = runTest {
        for (hit in listOf(true, false)) {
            val access = Access().apply { probeHit = hit; probeDelay = 20 }
            val start = testScheduler.currentTime
            val result = ScriptRunner().run(script(ScriptStep.IfPage("home", timeoutMs = 100,
                thenSteps = listOf(ScriptStep.Wait(300), ScriptStep.Tap(1, 1)),
                elseSteps = listOf(ScriptStep.Wait(300), ScriptStep.Tap(2, 2)))), access)
            assertTrue(result.success)
            assertEquals(listOf(if (hit) 1 to 1 else 2 to 2), access.taps)
            assertEquals(320L, testScheduler.currentTime - start)
            assertEquals(listOf(100L), access.probeTimeouts)
        }
    }

    @Test fun ifPageProbeOverrunKeepsStepTimeoutAndNeverRunsEitherBranch() = runTest {
        val access = Access().apply { probeDelay = 300 }
        val result = ScriptRunner().run(script(ScriptStep.IfPage("home", timeoutMs = 100,
            thenSteps = listOf(ScriptStep.Tap(1, 1)), elseSteps = listOf(ScriptStep.Tap(2, 2)))), access)
        assertFalse(result.success)
        assertEquals("step_timeout", result.errorCode)
        assertEquals("timeout", result.logs.single().message)
        assertEquals(100L, testScheduler.currentTime)
        assertTrue(access.taps.isEmpty())
    }

    @Test fun ifPageKeepsDefaultProbeBudgetAndHonorsChildTimeoutAndRetries() = runTest {
        val access = Access()
        val result = ScriptRunner().run(script(ScriptStep.IfPage("home", thenSteps = listOf(
            ScriptStep.Wait(300, timeoutMs = 50, retries = 1), ScriptStep.Tap(1, 1)))), access)
        assertEquals("step_timeout", result.errorCode)
        assertEquals("if_page:timeout", result.logs.single().message)
        assertEquals(100L, testScheduler.currentTime)
        assertEquals(listOf(3_000L), access.probeTimeouts)
        assertTrue(access.taps.isEmpty())
    }

    @Test fun enclosingLoopDeadlineStillInterruptsLongIfPageBranchAndUntilProbe() = runTest {
        val access = Access()
        val result = ScriptRunner().run(script(ScriptStep.Loop(3, maxTotalMs = 150, steps = listOf(
            ScriptStep.IfPage("home", timeoutMs = 100, thenSteps = listOf(
                ScriptStep.Wait(300), ScriptStep.Tap(1, 1)))))), access)
        assertTrue(result.success)
        assertEquals("loop_stop:max_total_ms", result.logs.last().message)
        assertEquals(150L, testScheduler.currentTime)
        assertTrue(access.taps.isEmpty())
        access.probeDelay = 300
        val untilResult = ScriptRunner().run(script(ScriptStep.Loop(2, maxTotalMs = 50, until = "home")), access)
        assertEquals("loop_stop:max_total_ms", untilResult.logs.single().message)
        assertEquals(200L, testScheduler.currentTime)
    }

    @Test fun outerScriptCancellationStillPropagatesAndReleasesRunner() = runTest {
        val access = Access()
        val cancelled = withTimeoutOrNull(150) {
            ScriptRunner().run(script(ScriptStep.IfPage("home", timeoutMs = 100,
                thenSteps = listOf(ScriptStep.Wait(300), ScriptStep.Tap(1, 1)))), access)
        }
        assertNull(cancelled)
        assertTrue(access.taps.isEmpty())
        assertTrue(ScriptRunner().run(script(ScriptStep.Wait(0)), access).success)
    }

    @Test fun ifPageProbeCancellationIsNotConvertedToStepTimeout() = runTest {
        val access = object : ScriptAccess by Access() {
            override suspend fun probe(expect: String, timeoutMs: Long, game: String?): Boolean {
                throw CancellationException("outer cancelled")
            }
        }
        val failure = runCatching { ScriptRunner().run(script(ScriptStep.IfPage("home")), access) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("outer cancelled", failure?.message)
    }

    private open class Access : ScriptAccess {
        val taps = mutableListOf<Pair<Int, Int>>()
        val probes = mutableListOf<Pair<String, String?>>()
        val probeTimeouts = mutableListOf<Long>()
        var probeHit = true
        var probeDelay = 0L
        var powers: PowerRecognition? = null
        var recognitions = 0
        override fun foregroundPackage() = "game.pkg"
        override fun foregroundPackageFromWindows() = foregroundPackage()
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override fun screenSize() = 2460 to 1080
        override suspend fun tap(x: Int, y: Int): Boolean { taps += x to y; return true }
        override suspend fun tapText(text: String) = true
        override suspend fun read(field: String): String? = null
        override suspend fun recognizePowers(minConfidence: Double): PowerRecognition? { recognitions++; return powers }
        override suspend fun probe(expect: String, timeoutMs: Long) = probe(expect, timeoutMs, null)
        override suspend fun probe(expect: String, timeoutMs: Long, game: String?): Boolean {
            probes += expect to game
            probeTimeouts += timeoutMs
            delay(probeDelay)
            return probeHit
        }
    }
}
