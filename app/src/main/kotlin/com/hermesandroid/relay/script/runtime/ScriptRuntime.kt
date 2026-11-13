package com.hermesandroid.relay.script.runtime

import android.content.Context
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.network.relay.LocalVisionBridgeRoutes
import com.hermesandroid.relay.ui.scripts.ScriptCatalog
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

object ScriptRuntime {
    private val runner = ScriptRunner()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Catalog(val scripts: List<RuntimeScript>)

    fun load(context: Context, scriptId: String): RuntimeScript? {
        val cache = ScriptCatalog.cacheFile(context)
        val content = if (cache.isFile) cache.readText()
            else context.assets.open("scripts.json").bufferedReader().use { it.readText() }
        val scripts = if (content.trimStart().startsWith("[")) json.decodeFromString<List<RuntimeScript>>(content)
            else json.decodeFromString<Catalog>(content).scripts
        return scripts.firstOrNull { it.id == scriptId }
    }

    suspend fun run(context: Context, screenCapture: ScreenCapture?, scriptId: String): ScriptRunResult {
        val script = runCatching { load(context, scriptId) }.getOrElse {
            return ScriptRunResult(success = false, errorCode = "invalid_script")
        } ?: return ScriptRunResult(success = false, errorCode = "script_not_found")
        val service = HermesAccessibilityService.instance
            ?: return ScriptRunResult(success = false, errorCode = "service_unavailable")
        return runner.run(script, AndroidAccess(context, screenCapture, service))
    }

    private class AndroidAccess(
        private val context: Context,
        private val capture: ScreenCapture?,
        private val service: HermesAccessibilityService,
    ) : ScriptAccess {
        override fun foregroundPackage(): String? =
            service.rootInActiveWindow?.packageName?.toString() ?: service.currentApp

        override suspend fun tap(x: Int, y: Int) = service.actionExecutor.tap(x, y).ok
        override suspend fun tapText(text: String) = service.actionExecutor.tapText(text).ok

        override suspend fun probe(expect: String, timeoutMs: Long): Boolean {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0)
            do {
                val result = LocalVisionBridgeRoutes.handle(capture, context, buildJsonObject { put("with_frame", false) })
                if ((result.payload["page"] as? JsonPrimitive)?.content == expect) return true
                if (android.os.SystemClock.elapsedRealtime() >= deadline) return false
                delay(100)
            } while (true)
        }

        override suspend fun read(field: String): String? {
            val result = LocalVisionBridgeRoutes.handleRead(capture, context, buildJsonObject { put("field", field) })
            return (result.payload["value"] as? JsonPrimitive)?.contentOrNull
        }
    }
}
