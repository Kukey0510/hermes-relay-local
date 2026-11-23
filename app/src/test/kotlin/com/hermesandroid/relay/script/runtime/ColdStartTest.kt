package com.hermesandroid.relay.script.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ColdStartTest {
    @Before fun resetStop() { ScriptRunControl.reset() }

    @Test fun fieldsDefaultAndRoundTrip() {
        val defaults = Json.decodeFromString<ScriptStep>("""{"op":"coldStart"}""") as ScriptStep.ColdStart
        assertEquals(emptyList<String>(), defaults.accept)
        assertEquals("landscape", defaults.orientation)
        for (orientation in listOf("landscape", "portrait", "any")) {
            val step: ScriptStep = defaults.copy(accept = listOf("list", "battle"), orientation = orientation)
            val encoded = Json.encodeToString(step)
            assertTrue(Json.parseToJsonElement(encoded).jsonObject.containsKey("accept"))
            assertTrue(Json.parseToJsonElement(encoded).jsonObject.containsKey("orientation"))
            assertEquals(step, Json.decodeFromString<ScriptStep>(encoded))
        }
    }

    @Test fun invalidNewFieldsAreRejected() {
        for (content in listOf(
            """{"op":"coldStart","accept":"list"}""",
            """{"op":"coldStart","accept":[1]}""",
            """{"op":"coldStart","orientation":"sideways"}""",
        )) {
            assertTrue(runCatching { Json.decodeFromString<ScriptStep>(content) }.isFailure)
        }
    }

    @Test fun homeKeepsExistingResult() {
        val access = Access()
        assertEquals("ok:home_at_1000ms taps=0 popups=0", access.run(page = { "home" }))
        assertEquals(0, access.launches)
        assertTrue(access.taps.isEmpty())
    }

    @Test fun acceptedListReturnsWithoutLaunchTapOrFrameRead() {
        val access = Access()
        assertEquals("ok:already_on_list", access.run(listOf("list"), page = { "list" }, frame = {
            error("accepted page must not capture a boot frame")
        }))
        assertEquals(0, access.launches)
        assertTrue(access.taps.isEmpty())
        assertEquals(1_000L, access.clock)
    }

    @Test fun emptyAcceptDoesNotAcceptListAndOtherWaitsFullTimeout() {
        for (page in listOf("list", "other")) {
            val access = Access()
            assertTrue(access.run(page = { page }).startsWith("fail:no_home rescue=0 last=$page taps=0"))
            assertEquals(6_000L, access.clock)
        }
    }

    @Test fun popupWinsOverAcceptEvenAfterTapBudgetIsSpent() {
        val access = Access()
        var reads = 0
        val result = access.run(listOf("popup", "list"), maxPopups = 1, page = {
            if (++reads < 3) "popup" else "list"
        })
        assertEquals("ok:already_on_list", result)
        assertEquals(listOf(1580 to 375), access.taps)
    }

    @Test fun portraitForegroundEntersPageLoopAndTapsPortraitCenter() {
        val access = Access()
        var reads = 0
        val result = access.run(page = { if (++reads == 1) "boot" else "home" }, frame = { 1080 to 2460 })
        assertTrue(result.startsWith("ok:home_at_"))
        assertEquals(0, access.launches)
        assertEquals(listOf(540 to 1230), access.taps)
    }

    @Test fun unknownPackageRetriesTwiceBeforeLaunchAndTimesOut() {
        val access = Access()
        val result = access.run(foreground = { null }, page = { "other" })
        assertEquals("fail:foreground_timeout last=unknown", result)
        assertEquals(4_000L, access.clock)
        assertEquals(listOf(2_000L), access.launchTimes)
        assertTrue(access.taps.isEmpty())
    }

    @Test fun knownWrongPackageTimesOutWithLastPackage() {
        val access = Access()
        assertEquals("fail:foreground_timeout last=launcher", access.run(foreground = { "launcher" }))
    }

    @Test fun unknownPackageWithRecognizedBootStillClicksBoot() {
        val access = Access()
        var reads = 0
        val result = access.run(foreground = { null }, page = { if (++reads <= 2) "boot" else "home" })
        assertTrue(result.startsWith("ok:home_at_"))
        assertEquals(listOf(1230 to 540), access.taps)
        assertEquals(0, access.launches)
    }

    @Test fun orientationOnlyAffectsFallbackCenterAndExplicitCoordinatesStayFixed() {
        for ((orientation, center) in listOf("landscape" to (1230 to 540), "portrait" to (540 to 1230), "any" to (540 to 1230))) {
            val access = Access()
            var reads = 0
            access.run(orientation = orientation, frame = { null }, page = { if (++reads == 1) "boot" else "home" })
            assertEquals(listOf(center), access.taps)
            assertEquals(0, access.launches)
        }
        val access = Access()
        var reads = 0
        access.run(tapX = 12, tapY = 34, page = { if (++reads == 1) "boot" else "home" }, frame = {
            error("fixed coordinates do not read frame size")
        })
        assertEquals(listOf(12 to 34), access.taps)
    }

    @Test fun runnerPassesAcceptAndOrientationToAccess() = runBlocking {
        val access = object : ScriptAccess by Access() {
            override suspend fun coldStartTo(pkg: String?, bootPage: String, homePage: String,
                tapX: Int?, tapY: Int?, bootWaitMs: Long, homeWaitMs: Long, intervalMs: Long,
                popupPage: String, popupX: Int, popupY: Int, maxPopups: Int,
                acceptPages: List<String>, orientation: String): String {
                assertEquals("game", pkg)
                assertEquals(listOf("list"), acceptPages)
                assertEquals("portrait", orientation)
                return "ok:already_on_list"
            }
        }
        val result = ScriptRunner().run(RuntimeScript("cold", listOf("game"), listOf(
            ScriptStep.ColdStart(accept = listOf("list"), orientation = "portrait"),
        )), access)
        assertTrue(result.success)
        assertEquals("ok:already_on_list", result.logs.single().message)
    }

    private class Access : ScriptAccess {
        var clock = 0L
        var launches = 0
        val launchTimes = mutableListOf<Long>()
        val taps = mutableListOf<Pair<Int, Int>>()
        override fun foregroundPackageFromWindows() = foregroundPackage()
        override suspend fun frameSize(): Pair<Int, Int>? = null
        override fun foregroundPackage() = "game"
        override fun screenSize() = 1080 to 2460
        override fun monotonicMs() = clock
        override suspend fun tap(x: Int, y: Int): Boolean { taps += x to y; return true }
        override suspend fun tapText(text: String) = false
        override suspend fun probe(expect: String, timeoutMs: Long) = false
        override suspend fun read(field: String): String? = null
        override suspend fun launchApp(pkg: String): Boolean { launches++; launchTimes += clock; return true }
        override suspend fun waitMs(ms: Long) { clock += ms }
        fun run(accept: List<String> = emptyList(), foreground: () -> String? = { "game" },
            page: suspend () -> String = { "other" }, frame: suspend () -> Pair<Int, Int>? = { 2460 to 1080 },
            orientation: String = "landscape", maxPopups: Int = 3, tapX: Int? = null, tapY: Int? = null): String = runBlocking {
            coldStartToPages("game", "boot", "home", tapX, tapY, 4_000, 5_000, 1_000,
                "popup", 1580, 375, maxPopups, accept, orientation, foreground, page, frame)
        }
    }
}
