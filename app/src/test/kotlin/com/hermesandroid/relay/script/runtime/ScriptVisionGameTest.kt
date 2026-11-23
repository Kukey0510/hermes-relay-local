package com.hermesandroid.relay.script.runtime

import android.content.Context
import com.hermesandroid.relay.network.relay.LocalVisionBridgeRoutes
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ScriptVisionGameTest {
    private val context = mockk<Context>()
    private val requests = mutableListOf<JsonObject>()
    private lateinit var access: ScriptAccess

    @Before fun setUp() {
        mockkObject(LocalVisionBridgeRoutes)
        coEvery { LocalVisionBridgeRoutes.handle(null, context, any()) } coAnswers {
            requests += thirdArg<JsonObject>()
            LocalVisionBridgeRoutes.Result(200, buildJsonObject { put("page", "home") })
        }
        val androidAccess = ScriptRuntime.AndroidAccess(context, null, mockk())
        access = object : ScriptAccess by androidAccess {
            override fun foregroundPackage() = "unrelated.pkg"
            override fun screenOn() = true
        }
    }

    @After fun tearDown() {
        unmockkObject(LocalVisionBridgeRoutes)
    }

    @Test fun allThreeOperationsForwardExplicitGameThroughAndroidAccessToRoutes() = runTest {
        val script = Json.decodeFromString<RuntimeScript>("""{
            "id":"games", "targetPackages":["unrelated.pkg"], "steps":[
                {"op":"page","game":"blhx"},
                {"op":"probe","expect":"home","game":"bb"},
                {"op":"if_page","expect":"home","game":"blhx",
                 "then":[{"op":"page","as":"nested","game":"bb"}]}
            ]
        }""")
        val result = ScriptRunner().run(script, access)
        assertTrue(result.success)
        assertTrue(result.logs.first().message.contains("page=home"))
        assertEquals(listOf("blhx", "bb", "blhx", "bb"),
            requests.map { (it["game"] as JsonPrimitive).content })
        assertTrue(requests.all { it["save_unknown"] == JsonPrimitive(false) })
        assertTrue(requests.all { it["with_frame"] == JsonPrimitive(false) })
    }

    @Test fun omittedGameStaysDefaultWithoutInferringFromScriptOrTargetPackage() = runTest {
        val script = RuntimeScript("defaults", listOf("unrelated.pkg"), listOf(
            ScriptStep.Page(), ScriptStep.Probe("home"), ScriptStep.IfPage("home"),
        ), game = "blhx")
        assertTrue(ScriptRunner().run(script, access).success)
        assertEquals(3, requests.size)
        assertTrue(requests.all { "game" !in it })
    }

    @Test fun gameSurvivesSerializationAndNonStringGamesAreRejected() {
        val operations = listOf(
            """{"op":"page"}""",
            """{"op":"probe","expect":"home"}""",
            """{"op":"if_page","expect":"home"}""",
        )
        for (operation in operations) {
            for (suffix in listOf("", ",\"game\":\"blhx\"")) {
                val step = Json.decodeFromString<ScriptStep>(operation.dropLast(1) + suffix + "}")
                val encoded = Json.encodeToString(ScriptStepSerializer, step)
                assertEquals(step, Json.decodeFromString<ScriptStep>(encoded))
                if (suffix.isEmpty()) assertFalse(encoded.contains("game"))
                else assertTrue(encoded.contains("blhx"))
            }
            assertTrue(runCatching {
                Json.decodeFromString<ScriptStep>(operation.dropLast(1) + ""","game":42}""")
            }.isFailure)
        }
    }

    @Test fun legacyAccessImplementationsStillHandleDefaultGameCalls() = runTest {
        val legacy = object : ScriptAccess {
            override fun foregroundPackage() = "unrelated.pkg"
            override fun foregroundPackageFromWindows() = foregroundPackage()
            override suspend fun frameSize(): Pair<Int, Int>? = null
            override suspend fun tap(x: Int, y: Int) = false
            override suspend fun tapText(text: String) = false
            override suspend fun probe(expect: String, timeoutMs: Long) = expect == "home"
            override suspend fun currentPage() = "home"
            override suspend fun read(field: String): String? = null
        }
        val script = RuntimeScript("legacy", listOf("unrelated.pkg"), listOf(
            ScriptStep.Page(), ScriptStep.Probe("home"), ScriptStep.IfPage("home"),
        ))
        val result = ScriptRunner().run(script, legacy)
        assertTrue(result.success)
        assertTrue(result.logs.first().message.contains("page=home"))
    }
}
