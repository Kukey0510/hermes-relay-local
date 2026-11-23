package com.hermesandroid.relay.network.relay

import android.content.Context
import android.content.res.AssetManager
import com.axiomlabs.hermesrelay.localvision.RgbImage
import com.hermesandroid.relay.accessibility.ScreenCapture
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalVisionBridgeRoutesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val assets = mockk<AssetManager>()
    private val capture = mockk<ScreenCapture>()
    private lateinit var directory: File
    private var revision = 1_800_000_000_000L

    @Before fun setUp() {
        directory = temporary.newFolder("localvision")
        every { context.applicationContext } returns context
        every { context.filesDir } returns temporary.root
        every { context.assets } returns assets
        every { assets.open("pages.json") } answers {
            ByteArrayInputStream(pages("builtin").toByteArray())
        }
        coEvery { capture.captureLocalFrame(any()) } returns
            Result.success(RgbImage(1, 1, intArrayOf(0xff0000)))
        every { capture.lastFrameInfo } returns null
    }

    @Test fun gamesRemainIndependentWhenRequestsAlternateOnTheSameFrame() = runTest {
        write("pages/blhx.json", pages("blhx_home"))
        write("pages/bb.json", pages("bb_home"))
        repeat(2) {
            assertDetection("blhx", "blhx_home", "pages_blhx")
            assertDetection("bb", "bb_home", "pages_bb")
        }
    }

    @Test fun noGameUsesOnlyDefaultEvenWhenGameFilesExist() = runTest {
        write("pages/blhx.json", pages("game_home"))
        write("pages.json", pages("default_home"))
        assertDetection(null, "default_home", "default")
    }

    @Test fun malformedOrInvalidGameSpecKeepsLastGoodUntilRepaired() = runTest {
        write("pages.json", pages("default_home"))
        write("pages/blhx.json", pages("original"))
        write("pages/bb.json", pages("bb_home"))
        assertDetection("blhx", "original", "pages_blhx")
        for (broken in listOf("{broken", """{"pages":{"bad":{}}}""")) {
            write("pages/blhx.json", broken)
            assertDetection("blhx", "original", "stale")
            assertDetection("bb", "bb_home", "pages_bb")
            assertDetection("blhx", "original", "stale")
        }
        write("pages/blhx.json", pages("repaired"))
        assertDetection("blhx", "repaired", "pages_blhx")
        write("pages/blhx.json", "invalid again")
        assertDetection("blhx", "repaired", "stale")
    }

    @Test fun defaultLastGoodIsSharedAcrossGameFallbacks() = runTest {
        write("pages.json", pages("default_home"))
        assertDetection(null, "default_home", "default")
        write("pages.json", "{broken")
        assertDetection("missing", "default_home", "stale")
        assertDetection(null, "default_home", "stale")
    }

    @Test fun firstFailureAndMissingFilesFollowTheEntireFallbackChain() = runTest {
        write("pages/blhx.json", "{broken")
        write("pages.json", pages("default_home"))
        assertDetection("blhx", "default_home", "default")
        assertDetection("missing", "default_home", "default")
        // A separate filesDir has no process-local history for either file.
        val freshRoot = temporary.newFolder("fresh")
        every { context.filesDir } returns freshRoot
        directory = File(freshRoot, "localvision")
        assertDetection("missing", "builtin", "assets", "assets_fallback")
        write("pages/blhx.json", "{broken")
        write("pages.json", """{"pages":[]}""")
        assertDetection("blhx", "builtin", "assets", "assets_fallback")
        assertDetection(null, "builtin", "assets", "assets_fallback")
    }

    @Test fun deletionNeverUsesStaleAndRecreationInvalidatesTheCache() = runTest {
        val gameFile = write("pages/blhx.json", pages("game_home"))
        val defaultFile = write("pages.json", pages("default_home"))
        assertDetection("blhx", "game_home", "pages_blhx")
        assertTrue(gameFile.delete())
        assertDetection("blhx", "default_home", "default")
        assertTrue(defaultFile.delete())
        assertDetection("blhx", "builtin", "assets", "assets_fallback")
        assertDetection(null, "builtin", "assets", "assets_fallback")
        write("pages/blhx.json", pages("recreated"))
        assertDetection("blhx", "recreated", "pages_blhx")
    }

    @Test fun cacheObservesGameMtimeAndFallbackLengthIndependently() = runTest {
        write("pages/blhx.json", pages("first"))
        assertDetection("blhx", "first", "pages_blhx")
        // Same length, different mtime.
        write("pages/blhx.json", pages("other"))
        assertDetection("blhx", "other", "pages_blhx")
        val fallback = write("pages.json", pages("default"))
        assertDetection("missing", "default", "default")
        val modifiedAt = fallback.lastModified()
        fallback.writeText(pages("longer_default"))
        assertTrue(fallback.setLastModified(modifiedAt))
        // Same mtime, different length; the missing game file is unchanged.
        assertDetection("missing", "longer_default", "default")
    }

    @Test fun invalidGameCannotSelectFilesOutsidePagesDirectory() = runTest {
        for (game in listOf(JsonPrimitive("../pages"), JsonPrimitive(42), JsonPrimitive(""))) {
            val result = LocalVisionBridgeRoutes.handle(capture, context, buildJsonObject { put("game", game) })
            assertEquals(400, result.status)
            assertEquals("invalid_game", (result.payload["error"] as JsonPrimitive).content)
        }
    }

    private suspend fun assertDetection(game: String?, page: String, spec: String, source: String = "hot") {
        val result = LocalVisionBridgeRoutes.handle(capture, context, request(game))
        assertEquals(200, result.status)
        assertEquals(page, (result.payload["page"] as JsonPrimitive).content)
        assertEquals(spec, (result.payload["spec"] as JsonPrimitive).content)
        assertEquals(source, (result.payload["source"] as JsonPrimitive).content)
    }

    private fun request(game: String?): JsonObject = buildJsonObject {
        game?.let { put("game", it) }
        put("with_frame", false)
        put("save_unknown", false)
    }

    private fun write(path: String, content: String): File = directory.resolve(path).also {
        it.parentFile!!.mkdirs()
        it.writeText(content)
        assertTrue(it.setLastModified(++revision))
    }

    private fun pages(id: String): String =
        """{"pages":{"$id":{"checks":[{"type":"color","area":[0,0,1,1],"expect":[255,0,0],"tol":0}]}}}"""
}
