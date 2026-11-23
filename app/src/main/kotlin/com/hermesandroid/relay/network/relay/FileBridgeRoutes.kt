package com.hermesandroid.relay.network.relay

import android.content.Context
import android.os.Process
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object FileBridgeSandbox {
    fun resolve(roots: List<File>, rel: String, rejectEscapes: Boolean = false): File? {
        if (rel.isBlank() || File(rel).isAbsolute) return null
        if (rel.replace('\\', '/').split('/').any { it == ".." }) return null

        val candidates = roots.mapNotNull { root ->
            if (!root.exists()) return@mapNotNull null
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@mapNotNull null
            val candidate = runCatching { File(canonicalRoot, rel).canonicalFile }.getOrNull()
                ?: return@mapNotNull null
            val prefix = canonicalRoot.path + File.separator
            val withinRoot = candidate == canonicalRoot || candidate.path.startsWith(prefix)
            // Delete must reject an escape before another root can mask it as a missing path.
            if (rejectEscapes && !withinRoot) return null
            candidate.takeIf { withinRoot }
        }
        return candidates.firstOrNull { it.exists() } ?: candidates.firstOrNull()
    }

    fun readableWithinLimit(file: File, limit: Long = FileBridgeRoutes.MAX_READ_BYTES): Boolean =
        file.isFile && file.length() <= limit
}

object FileBridgeRoutes {
    internal const val MAX_READ_BYTES = 1_048_576L
    private const val MAX_WRITE_BYTES = 4 * 1_048_576
    private const val MAX_ENTRIES = 500

    val pathMethods: Set<Pair<String, String>> = setOf(
        "POST" to "/fs/list",
        "POST" to "/fs/read",
        "POST" to "/fs/write",
        "POST" to "/fs/delete",
        "GET" to "/logcat",
    )

    fun handles(path: String, method: String): Boolean =
        method.uppercase() to path.substringBefore('?') in pathMethods

    data class Result(val status: Int, val payload: JsonObject)

    suspend fun handle(
        appContext: Context?,
        path: String,
        method: String,
        body: JsonObject,
    ): Result {
        if (!handles(path, method)) return error("bad_request", "Unsupported route")
        if (appContext == null) return error("context_unavailable", "Android context is unavailable", 503)
        if (path.substringBefore('?') == "/logcat") return logcat(body)

        return runCatching {
            val roots = listOfNotNull(
                appContext.filesDir,
                appContext.cacheDir,
                appContext.getExternalFilesDir(null),
            )
            when (path.substringBefore('?')) {
                "/fs/list" -> list(roots, body)
                "/fs/read" -> read(roots, body)
                "/fs/write" -> write(roots, body)
                "/fs/delete" -> delete(roots, body)
                else -> error("bad_request", "Unsupported route")
            }
        }.getOrElse { error("bad_request", it.message ?: it::class.java.simpleName) }
    }

    private fun list(roots: List<File>, body: JsonObject): Result {
        val rel = string(body, "path") ?: return error("bad_request", "Missing path")
        val recursive = body["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
        val target = FileBridgeSandbox.resolve(roots, rel)
            ?: return error("path_outside_sandbox", "Path is outside the allowed roots")
        if (!target.exists()) return error("not_found", "Path does not exist")
        if (!target.isDirectory) return error("bad_request", "Path is not a directory")
        val files = if (recursive) target.walkTopDown().drop(1) else target.listFiles()?.asSequence().orEmpty()
        val entries = files.take(MAX_ENTRIES).map { file ->
            buildJsonObject {
                put("name", file.relativeTo(target).path)
                put("is_dir", file.isDirectory)
                put("size", if (file.isFile) file.length() else 0L)
                put("mtime", file.lastModified())
            }
        }.toList()
        return success {
            put("root", rootFor(roots, target))
            put("resolved", target.path)
            put("entries", JsonArray(entries))
            put("count", entries.size)
        }
    }

    private fun read(roots: List<File>, body: JsonObject): Result {
        val rel = string(body, "path") ?: return error("bad_request", "Missing path")
        val requested = body["max_bytes"]?.jsonPrimitive?.intOrNull ?: MAX_READ_BYTES.toInt()
        if (requested < 0 || requested > MAX_READ_BYTES) return error("bad_request", "Invalid max_bytes")
        val target = FileBridgeSandbox.resolve(roots, rel)
            ?: return error("path_outside_sandbox", "Path is outside the allowed roots")
        if (!target.exists()) return error("not_found", "Path does not exist")
        if (!FileBridgeSandbox.readableWithinLimit(target, requested.toLong())) {
            return error("too_large", "File exceeds max_bytes")
        }
        val bytes = target.readBytes()
        val text = decodeUtf8(bytes)
        return success {
            put("root", rootFor(roots, target))
            put("resolved", target.path)
            put("size", bytes.size)
            put("encoding", if (text == null) "base64" else "utf8")
            put("content", text ?: Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }

    private fun write(roots: List<File>, body: JsonObject): Result {
        val rel = string(body, "path") ?: return error("bad_request", "Missing path")
        val encoded = string(body, "content_base64") ?: return error("bad_request", "Missing content_base64")
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            ?: return error("bad_request", "Invalid base64 content")
        if (bytes.size > MAX_WRITE_BYTES) return error("too_large", "File exceeds 4 MB")
        val target = FileBridgeSandbox.resolve(roots, rel)
            ?: return error("path_outside_sandbox", "Path is outside the allowed roots")
        if (body["mkdirs"]?.jsonPrimitive?.booleanOrNull == true) {
            val parent = target.parentFile ?: return error("bad_request", "Parent directory does not exist")
            if (!parent.exists() && !parent.mkdirs()) {
                return error("bad_request", "Could not create parent directories")
            }
        }
        if (target.parentFile?.isDirectory != true) return error("bad_request", "Parent directory does not exist")
        target.writeBytes(bytes)
        return success {
            put("root", rootFor(roots, target))
            put("resolved", target.path)
            put("size", bytes.size)
        }
    }

    private fun delete(roots: List<File>, body: JsonObject): Result {
        val rel = string(body, "path") ?: return error("bad_request", "Missing path")
        val target = FileBridgeSandbox.resolve(roots, rel, rejectEscapes = true)
            ?: return error("path_outside_sandbox", "Path is outside the allowed roots")
        if (roots.any { target == it.canonicalFile }) {
            return error("path_outside_sandbox", "Cannot delete a sandbox root")
        }
        if (!target.exists()) return error("not_found", "Path does not exist", 404)
        if (target.isDirectory && target.list()?.isNotEmpty() == true) {
            return error("directory_not_empty", "Directory is not empty")
        }
        // File.delete never traverses children, including when a directory changes after the check.
        if (!target.delete()) {
            if (target.isDirectory && target.list()?.isNotEmpty() == true) {
                return error("directory_not_empty", "Directory is not empty")
            }
            return error("bad_request", "Could not delete path")
        }
        return success {
            put("root", rootFor(roots, target))
            put("resolved", target.path)
        }
    }

    private fun logcat(body: JsonObject): Result {
        // Query strings are not part of the bridge path contract: callers send
        // `{"lines": N}` in the body (GET with a body works over the relay
        // channel; POST /logcat is registered too).
        val lines = body["lines"]?.jsonPrimitive?.intOrNull ?: 200
        if (lines !in 1..500) return error("bad_request", "lines must be between 1 and 500")
        return runCatching {
            val process = Runtime.getRuntime().exec("logcat -d -t $lines --pid=${Process.myPid()}")
            val output = process.inputStream.bufferedReader().use { it.readLines().takeLast(lines) }
            if (process.waitFor() != 0) error("logcat_unavailable", "logcat exited unsuccessfully", 200)
            else Result(200, buildJsonObject {
                put("ok", true)
                put("lines", buildJsonArray { output.forEach { add(JsonPrimitive(it)) } })
                put("count", output.size)
            })
        }.getOrElse { error("logcat_unavailable", it.message ?: "logcat unavailable", 200) }
    }

    private fun string(body: JsonObject, key: String): String? =
        runCatching { body[key]?.jsonPrimitive?.content }.getOrNull()

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private fun rootFor(roots: List<File>, target: File): String = roots.first {
        val canonical = it.canonicalFile
        target == canonical || target.path.startsWith(canonical.path + File.separator)
    }.canonicalPath

    private inline fun success(extra: JsonObjectBuilderScope.() -> Unit): Result =
        Result(200, buildJsonObject { put("ok", true); JsonObjectBuilderScope(this).extra() })

    private fun error(code: String, detail: String, status: Int = 400): Result =
        Result(status, buildJsonObject {
            put("ok", false)
            put("error", code)
            put("detail", detail)
        })

    private class JsonObjectBuilderScope(private val builder: kotlinx.serialization.json.JsonObjectBuilder) {
        fun put(key: String, value: String) = builder.put(key, value)
        fun put(key: String, value: Int) = builder.put(key, value)
        fun put(key: String, value: Long) = builder.put(key, value)
        fun put(key: String, value: JsonArray) = builder.put(key, value)
    }
}
