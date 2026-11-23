package com.hermesandroid.relay.network.relay

import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileBridgeRoutesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val files = temporary.newFolder()
        val cache = temporary.newFolder()
        val external = temporary.newFolder()
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
            override fun getExternalFilesDir(type: String?) = external
        }
    }

    private suspend fun delete(context: Context, path: String) = FileBridgeRoutes.handle(
        context, "/fs/delete", "POST", buildJsonObject { put("path", path) },
    )

    private fun assertError(status: Int, error: String, result: FileBridgeRoutes.Result) {
        assertEquals(status, result.status)
        assertEquals(error, result.payload["error"]?.jsonPrimitive?.content)
        assertEquals("false", result.payload["ok"]?.jsonPrimitive?.content)
    }

    @Test
    fun deletesFileAndThenReturnsNotFound() = runTest {
        val context = context()
        val file = context.filesDir.resolve("wrong.txt").apply { writeText("remove") }
        val result = delete(context, "wrong.txt")
        assertEquals(200, result.status)
        assertEquals("true", result.payload["ok"]?.jsonPrimitive?.content)
        assertFalse(file.exists())
        assertError(404, "not_found", delete(context, "wrong.txt"))
    }

    @Test
    fun deletesEmptyDirectoryButLeavesNonEmptyDirectoryIntact() = runTest {
        val context = context()
        val empty = context.filesDir.resolve("empty").apply { mkdir() }
        assertEquals(200, delete(context, "empty").status)
        assertFalse(empty.exists())
        val full = context.filesDir.resolve("full").apply { mkdir() }
        val child = full.resolve("keep.txt").apply { writeText("keep") }
        assertError(400, "directory_not_empty", delete(context, "full"))
        assertTrue(full.isDirectory)
        assertEquals("keep", child.readText())
    }

    @Test
    fun rejectsTraversalAbsolutePathsAndSymlinkEscapes() = runTest {
        val context = context()
        val outside = temporary.newFile().apply { writeText("keep") }
        assertError(400, "path_outside_sandbox", delete(context, "../${outside.name}"))
        assertError(400, "path_outside_sandbox", delete(context, "..\\${outside.name}"))
        assertError(400, "path_outside_sandbox", delete(context, outside.path))
        Files.createSymbolicLink(context.filesDir.resolve("escape").toPath(), outside.toPath())
        assertError(400, "path_outside_sandbox", delete(context, "escape"))
        val cacheFile = context.cacheDir.resolve("escape").apply { writeText("keep cache") }
        assertError(400, "path_outside_sandbox", delete(context, "escape"))
        assertEquals("keep", outside.readText())
        assertEquals("keep cache", cacheFile.readText())
        val outsideDir = temporary.newFolder()
        Files.createSymbolicLink(context.filesDir.resolve("escape-dir").toPath(), outsideDir.toPath())
        assertError(400, "path_outside_sandbox", delete(context, "escape-dir/missing.txt"))
    }

    @Test
    fun rejectsDeletingSandboxRootOrMissingPath() = runTest {
        val context = context()
        assertError(400, "path_outside_sandbox", delete(context, "."))
        assertTrue(context.filesDir.isDirectory)
        assertError(400, "bad_request", FileBridgeRoutes.handle(context, "/fs/delete", "POST", JsonObject(emptyMap())))
    }

    @Test
    fun deleteResolvesTheSameRootsAsReadAndWrite() = runTest {
        val context = context()
        val cacheFile = context.cacheDir.resolve("cache.txt").apply { writeText("remove") }
        val externalFile = requireNotNull(context.getExternalFilesDir(null)).resolve("external.txt").apply { writeText("remove") }
        assertEquals(200, delete(context, "cache.txt").status)
        assertEquals(200, delete(context, "external.txt").status)
        assertFalse(cacheFile.exists())
        assertFalse(externalFile.exists())
    }

    @Test
    fun otherFileRoutesKeepTheirExistingMultiRootResolutionAndErrorCodes() = runTest {
        val context = context()
        val outside = temporary.newFile().apply { writeText("keep") }
        Files.createSymbolicLink(context.filesDir.resolve("escape").toPath(), outside.toPath())
        for (path in listOf("/fs/list", "/fs/read")) {
            val result = FileBridgeRoutes.handle(context, path, "POST", buildJsonObject { put("path", "escape") })
            assertError(400, "not_found", result)
        }
        assertEquals(200, write(context, "escape", false).status)
        assertEquals("saved", context.cacheDir.resolve("escape").readText())
        assertEquals("keep", outside.readText())
    }

    private suspend fun write(context: Context, path: String, mkdirs: Boolean) = FileBridgeRoutes.handle(
        context, "/fs/write", "POST", buildJsonObject {
            put("path", path)
            put("content_base64", Base64.encodeToString("saved".toByteArray(), Base64.NO_WRAP))
            put("mkdirs", mkdirs)
        },
    )

    @Test
    fun writeWithMkdirsAcceptsExistingParentAndRepeatedWrites() = runTest {
        val context = context()
        context.filesDir.resolve("existing").mkdir()
        repeat(2) { assertEquals(200, write(context, "existing/file.txt", true).status) }
        assertEquals("saved", context.filesDir.resolve("existing/file.txt").readText())
    }

    @Test
    fun writeCreatesMissingParentsOnlyWhenRequestedAndRejectsFileParent() = runTest {
        val context = context()
        assertEquals(400, write(context, "new/nested/file.txt", false).status)
        assertEquals(200, write(context, "new/nested/file.txt", true).status)
        assertEquals("saved", context.filesDir.resolve("new/nested/file.txt").readText())
        val parent = context.filesDir.resolve("blocked").apply { writeText("keep") }
        assertEquals(400, write(context, "blocked/file.txt", true).status)
        assertEquals("keep", parent.readText())
    }

    @Test
    fun deleteDispatchIsPostOnly() {
        assertTrue(FileBridgeRoutes.handles("/fs/delete", "POST"))
        assertTrue(FileBridgeRoutes.handles("/fs/delete?unused=1", "post"))
        assertFalse(FileBridgeRoutes.handles("/fs/delete", "GET"))
        assertFalse(FileBridgeRoutes.handles("/fs/delete", "DELETE"))
    }
}
