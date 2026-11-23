package com.hermesandroid.relay.script.runtime

import com.hermesandroid.relay.bridge.BridgeSafetyManager
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.network.relay.BridgeCommandHandler
import com.hermesandroid.relay.network.relay.models.Envelope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ScriptResumeRouteTest {
    // Reserve without Android services or launching a background run.
    private fun reserve(): String = ScriptRuntime::class.java.getDeclaredMethod("reserve").let {
        it.isAccessible = true
        it.invoke(ScriptRuntime) as String
    }

    @After fun cleanup() {
        ScriptRunControl.reset()
        ScriptRuntime::class.java.getDeclaredField("activeRunId").apply {
            isAccessible = true; set(ScriptRuntime, null)
        }
        unmockkObject(BuildFlavor)
    }

    private fun command(path: String, body: JsonObject) = Envelope("bridge", "bridge.command",
        payload = buildJsonObject {
            put("request_id", "test"); put("path", path); put("method", "POST"); put("body", body)
        })

    @Test fun resumeReturns200ForMissingAndFinishedRuns() = runTest {
        val handler = BridgeCommandHandler(mockk(relaxed = true), this)
        val missing = handler.handleLocalCommand(command("/script/resume", Json.parseToJsonElement("""{"runId":"missing"}""") as JsonObject))
        assertEquals(200, missing.status)
        assertEquals(Json.parseToJsonElement("""{"ok":false,"error":"run_not_found"}"""), missing.resultJson)
        val runId = reserve()
        ScriptRuntime::class.java.getDeclaredField("activeRunId").apply { isAccessible = true; set(ScriptRuntime, null) }
        val ended = handler.handleLocalCommand(command("/script/resume", buildJsonObject { put("runId", runId) }))
        assertEquals(200, ended.status)
        assertEquals(missing.resultJson, ended.resultJson)
    }

    @Test fun resumeJsonDeliversStepsAndStatusShowsPausedUntilResumed() = runTest {
        mockkObject(BuildFlavor)
        every { BuildFlavor.isSideload } returns true
        val safety = mockk<BridgeSafetyManager>()
        coEvery { safety.authorizeCapability("/script/status", "POST", any()) } returns BridgeSafetyManager.CapabilityAuthorization(true)
        val handler = BridgeCommandHandler(mockk(relaxed = true), this, safetyManager = safety)
        val runId = reserve()
        val body = buildJsonObject { put("runId", runId) }
        for (steps in listOf(null, "[]", "[{\"op\":\"tap\",\"x\":7,\"y\":8}]")) {
            val signal = ScriptRunControl.beginPause(runId)
            assertEquals("paused", ScriptRuntime.status(runId)?.state)
            val status = handler.handleLocalCommand(command("/script/status", body))
            assertEquals(status.errorMessage, 200, status.status)
            assertEquals("\"paused\"", status.resultJson?.get("state").toString())
            val resumeBody = buildJsonObject {
                put("runId", runId)
                if (steps != null) put("steps", Json.parseToJsonElement(steps))
            }
            val response = handler.handleLocalCommand(command("/script/resume/", resumeBody))
            assertEquals(response.errorMessage, 200, response.status)
            assertEquals(buildJsonObject { put("ok", true); put("runId", runId); put("resumed", true) }, response.resultJson)
            val delivered = signal.await()
            when (steps) {
                null -> assertNull(delivered)
                "[]" -> assertEquals(emptyList<ScriptStep>(), delivered)
                else -> assertEquals(listOf(ScriptStep.Tap(7, 8)), delivered)
            }
            assertEquals("running", ScriptRuntime.status(runId)?.state)
            ScriptRunControl.endPause()
        }
        coVerify(exactly = 3) { safety.authorizeCapability("/script/status", "POST", any()) }
    }
}
