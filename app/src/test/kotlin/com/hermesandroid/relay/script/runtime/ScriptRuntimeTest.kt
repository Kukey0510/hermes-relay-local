package com.hermesandroid.relay.script.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScriptRuntimeTest {
    @Test fun badCatalogEntryDoesNotHideAnyGoodScripts() {
        val entries = """[
            {"id":"first","targetPackages":[],"steps":[{"op":"wait","ms":200}]},
            {"id":"broken","targetPackages":[],"steps":[{"op":"no_such_op"}]},
            {"id":"last","targetPackages":[],"steps":[{"op":"wait","ms":100}]}
        ]"""
        for (content in listOf(entries, """{"scripts":$entries}""")) {
            val scripts = ScriptRuntime.decodeCatalog(content)
            assertEquals(listOf("first", "last"), scripts.map { it.id })
            assertEquals(ScriptStep.Wait(200), scripts.first().steps.single())
            assertEquals(ScriptStep.Wait(100), scripts.last().steps.single())
            assertNull(scripts.firstOrNull { it.id == "broken" })
            assertEquals("invalid_script", ScriptRuntime.missingScriptError("broken"))
            assertEquals("script_not_found", ScriptRuntime.missingScriptError("absent"))
        }
        ScriptRuntime.decodeCatalog("[]")
        assertEquals("script_not_found", ScriptRuntime.missingScriptError("broken"))
    }

    @Test fun coldStartUsesCurrentLandscapeFrameCenter() {
        assertEquals(1230 to 540, ScriptRuntime.coldStartTapPoint(null, null, 2460 to 1080) {
            error("frame available; fallback must not be read")
        })
    }

    @Test fun coldStartFallsBackWithoutFrameAndPreservesExplicitCoordinates() {
        assertEquals(540 to 1230, ScriptRuntime.coldStartTapPoint(null, null, null) { 1080 to 2460 })
        assertEquals(12 to 34, ScriptRuntime.coldStartTapPoint(12, 34, 2460 to 1080) {
            error("explicit coordinates must not read fallback")
        })
        assertEquals(12 to 540, ScriptRuntime.coldStartTapPoint(12, null, 2460 to 1080) { 1080 to 2460 })
        assertEquals(1230 to 34, ScriptRuntime.coldStartTapPoint(null, 34, 2460 to 1080) { 1080 to 2460 })
    }
}
