package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRunnerTest {
    private val target = "game.pkg"

    @Test fun fourStepsSucceed() = runTest {
        val access = FakeAccess()
        val result = ScriptRunner().run(script(
            ScriptStep.Tap(1, 2), ScriptStep.TapText("go"),
            ScriptStep.Read("coins"), ScriptStep.Wait(1),
        ), access)
        assertTrue(result.success)
        assertEquals(4, result.logs.size)
    }

    @Test fun probeTimeoutStopsFollowingSteps() = runTest {
        val access = FakeAccess(probeAnswers = mutableListOf(false))
        val result = ScriptRunner().run(script(ScriptStep.Probe("home", 1), ScriptStep.Tap(1, 2)), access)
        assertFalse(result.success)
        assertEquals(0, access.tapCalls)
        assertEquals(1, result.logs.size)
    }

    @Test fun retriesCanSucceedOnThirdAttempt() = runTest {
        val access = FakeAccess(tapAnswers = mutableListOf(false, false, true))
        val result = ScriptRunner().run(script(ScriptStep.Tap(1, 2, retries = 2), ScriptStep.Wait(0)), access)
        assertTrue(result.success)
        assertEquals(3, result.logs.first().attempts)
        assertEquals(2, result.logs.size)
    }

    @Test fun packageOutsideAllowlistRejectsBeforeAction() = runTest {
        val access = FakeAccess(packageName = "other.pkg")
        val result = ScriptRunner().run(script(ScriptStep.Tap(1, 2)), access)
        assertEquals(ScriptGuard.PACKAGE_MISMATCH, result.errorCode)
        assertEquals(0, access.tapCalls)
    }

    @Test fun swipeUsesClampedDurationAndCoordinates() = runTest {
        val access = FakeAccess(screen = 100 to 200)
        val result = ScriptRunner().run(script(
            ScriptStep.Swipe(-4, 250, 120, -9, 9_000),
            ScriptStep.Swipe(1, 2, 3, 4),
        ), access)
        assertTrue(result.success)
        assertEquals(listOf(SwipeCall(0, 199, 99, 0, 2_000), SwipeCall(1, 2, 3, 4, 400)), access.swipes)
        assertEquals(listOf("swipe", "swipe"), result.logs.map { it.op })
    }

    @Test fun tapIfTextHandlesPresentOptionalAndRequiredAbsent() = runTest {
        val present = ScriptRunner().run(script(ScriptStep.TapIfText("确定")),
            FakeAccess(textAnswers = mutableListOf(true)))
        assertTrue(present.success)
        val optional = ScriptRunner().run(script(ScriptStep.TapIfText("公告")),
            FakeAccess(textAnswers = mutableListOf(false)))
        assertTrue(optional.success)
        assertEquals("absent:公告", optional.logs.single().message)
        assertEquals("tapIfText", optional.logs.single().op)
        val required = ScriptRunner().run(script(ScriptStep.TapIfText("重试", optional = false)),
            FakeAccess(textAnswers = mutableListOf(false)))
        assertFalse(required.success)
    }

    @Test fun ifPageChoosesBranchAndPropagatesFailure() = runTest {
        val yes = FakeAccess(probeAnswers = mutableListOf(true))
        assertTrue(ScriptRunner().run(script(ScriptStep.IfPage("home",
            thenSteps = listOf(ScriptStep.Tap(1, 1)), elseSteps = listOf(ScriptStep.Tap(2, 2)))), yes).success)
        assertEquals(listOf(1 to 1), yes.tapCoordinates)
        val no = FakeAccess(probeAnswers = mutableListOf(false))
        assertTrue(ScriptRunner().run(script(ScriptStep.IfPage("home",
            thenSteps = listOf(ScriptStep.Tap(1, 1)), elseSteps = listOf(ScriptStep.Tap(2, 2)))), no).success)
        assertEquals(listOf(2 to 2), no.tapCoordinates)
        val failed = ScriptRunner().run(script(ScriptStep.IfPage("home",
            thenSteps = listOf(ScriptStep.Tap(1, 1)))),
            FakeAccess(tapAnswers = mutableListOf(false), probeAnswers = mutableListOf(true)))
        assertFalse(failed.success)
    }

    @Test fun nestedIfPageIsInvalidScript() = runTest {
        val nested = ScriptStep.IfPage("home", thenSteps = listOf(ScriptStep.IfPage("dialog")))
        assertEquals("invalid_script", ScriptRunner().run(script(nested), FakeAccess()).errorCode)
    }

    @Test fun loopStopsForTimesAndUntil() = runTest {
        val full = FakeAccess()
        val fullResult = ScriptRunner().run(script(ScriptStep.Loop(3, steps = listOf(ScriptStep.Tap(1, 1)))), full)
        assertTrue(fullResult.success)
        assertEquals(3, full.tapCalls)
        assertEquals("loop_stop:times", fullResult.logs.single().message)
        val until = FakeAccess(probeAnswers = mutableListOf(false, true))
        val untilResult = ScriptRunner().run(script(ScriptStep.Loop(5, until = "result",
            steps = listOf(ScriptStep.Tap(1, 1)))), until)
        assertEquals(2, until.tapCalls)
        assertEquals("loop_stop:until", untilResult.logs.single().message)
    }

    @Test fun loopStopsAtMaxTotalAndRejectsInvalidBounds() = runTest {
        val access = FakeAccess(clockAdvancePerTap = 11)
        val result = ScriptRunner().run(script(ScriptStep.Loop(5, maxTotalMs = 10,
            steps = listOf(ScriptStep.Tap(1, 1)))), access)
        assertTrue(result.success)
        assertEquals(1, access.tapCalls)
        assertEquals("loop_stop:max_total_ms", result.logs.single().message)
        assertEquals("invalid_script", ScriptRunner().run(script(ScriptStep.Loop(0)), FakeAccess()).errorCode)
        assertEquals("invalid_script", ScriptRunner().run(script(ScriptStep.Loop(101)), FakeAccess()).errorCode)
    }

    @Test fun newStepsRoundTripTheirJsonNamesAndDefaults() {
        val source = """{"op":"if_page","expect":"home","then":[{"op":"swipe","from_x":1,"from_y":2,"to_x":3,"to_y":4}],"else":[{"op":"tapIfText","text":"确定"}]}"""
        val decoded = Json.decodeFromString<ScriptStep>(source) as ScriptStep.IfPage
        assertEquals(400, (decoded.thenSteps.single() as ScriptStep.Swipe).durationMs)
        assertTrue((decoded.elseSteps.single() as ScriptStep.TapIfText).optional)
        val encoded = Json.encodeToString<ScriptStep>(decoded)
        assertTrue(encoded.contains("\"if_page\""))
        assertTrue(encoded.contains("\"from_x\""))
    }

    @Test fun tapWeakestRoundTripsAndFallsBackWhenEveryCardFails() = runTest {
        val decoded = Json.decodeFromString<ScriptStep>(
            """{"op":"tapWeakest","fallback_index":2,"min_confidence":0.7,"dry":false}"""
        ) as ScriptStep.TapWeakest
        assertEquals(2, decoded.fallbackIndex)
        assertEquals(0.7, decoded.minConfidence, 0.0)
        val access = FakeAccess()
        val result = ScriptRunner().run(script(decoded), access)
        assertTrue(result.success)
        assertEquals(listOf(1330 to 355), access.tapCoordinates)
        assertTrue(result.logs.single().message.contains("power_fallback"))
    }

    @Test fun tapWeakestSelectsSmallestRecognizedSumAndDryDoesNotTap() = runTest {
        val access = FakeAccess().apply { powers = PowerRecognition(listOf(
            listOf(PowerReading(100), PowerReading(200)), listOf(PowerReading(50), PowerReading(60)),
            listOf(PowerReading(null), PowerReading(1)), listOf(PowerReading(90), PowerReading(90)),
        ), 17) }
        val result = ScriptRunner().run(script(ScriptStep.TapWeakest(dry = true)), access)
        assertTrue(result.success)
        assertTrue(access.tapCoordinates.isEmpty())
        assertTrue(result.logs.single().message.contains("selected=1"))
        assertTrue(result.logs.single().message.contains("duration_ms=17"))
    }

    @Test fun waitIsClampedToThirtySeconds() = runTest {
        val access = FakeAccess()
        assertTrue(ScriptRunner().run(script(ScriptStep.Wait(99_999)), access).success)
        assertEquals(listOf(30_000L), access.waits)
    }

    @Test fun unknownForegroundDoesNotAbortRun() = runTest {
        // 冷启动瞬间无障碍读不到前台包名（null），不能把整条脚本判死
        val access = FakeAccess(packageName = null)
        val result = ScriptRunner().run(script(ScriptStep.Wait(1), ScriptStep.Tap(1, 2)), access)
        assertTrue(result.success)
        assertEquals(1, access.tapCalls)
    }

    @Test fun throwingActionBecomesStepExceptionInsteadOfCrash() = runTest {
        val access = FakeAccess(throwOnTap = true)
        val result = ScriptRunner().run(script(ScriptStep.Tap(1, 2)), access)
        assertFalse(result.success)
        assertEquals("step_exception", result.errorCode)
        assertEquals(1, result.logs.size)
    }

    private fun script(vararg steps: ScriptStep) = RuntimeScript("id", listOf(target), steps.toList())

    private class FakeAccess(
        var packageName: String? = "game.pkg",
        val throwOnTap: Boolean = false,
        val tapAnswers: MutableList<Boolean> = mutableListOf(true),
        val probeAnswers: MutableList<Boolean> = mutableListOf(true),
        val textAnswers: MutableList<Boolean> = mutableListOf(true),
        val screen: Pair<Int, Int> = 1080 to 1920,
        val clockAdvancePerTap: Long = 0,
    ) : ScriptAccess {
        var tapCalls = 0
        var clock = 0L
        val waits = mutableListOf<Long>()
        val tapCoordinates = mutableListOf<Pair<Int, Int>>()
        var powers: PowerRecognition? = null
        val swipes = mutableListOf<SwipeCall>()
        override fun foregroundPackage() = packageName
        override suspend fun tap(x: Int, y: Int): Boolean {
            if (throwOnTap) error("boom")
            tapCalls++; tapCoordinates += x to y; clock += clockAdvancePerTap
            return tapAnswers.firstOrNull()?.also { if (tapAnswers.size > 1) tapAnswers.removeFirst() } ?: true
        }
        override suspend fun tapText(text: String) = textAnswers.removeFirst()
        override suspend fun recognizePowers(minConfidence: Double) = powers
        override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long): Boolean {
            swipes += SwipeCall(fromX, fromY, toX, toY, durationMs); return true
        }
        override fun screenSize() = screen
        override fun monotonicMs() = clock
        override suspend fun probe(expect: String, timeoutMs: Long) = probeAnswers.removeFirst()
        override suspend fun read(field: String) = "42"
        override suspend fun waitMs(ms: Long) { waits += ms }
    }

    private data class SwipeCall(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val duration: Long)
}
