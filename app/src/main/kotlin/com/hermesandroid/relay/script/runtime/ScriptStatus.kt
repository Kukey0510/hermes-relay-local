package com.hermesandroid.relay.script.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val SCRIPT_STATUS_LOG_LIMIT = 200

/** Shared, pure JSON contract for the bridge status response. */
internal fun scriptStatusJson(runId: String, status: ScriptRuntime.RunStatus?): JsonObject = buildJsonObject {
    put("runId", runId)
    put("state", status?.state ?: "unknown")
    put("errorCode", status?.errorCode?.let(::JsonPrimitive) ?: JsonNull)
    put("lastStep", status?.lastStep?.let(::JsonPrimitive) ?: JsonNull)
    put("elapsed_ms", status?.elapsedMs ?: 0L)
    put("logs", JsonArray(status?.logs.orEmpty().takeLast(SCRIPT_STATUS_LOG_LIMIT).map { log ->
        buildJsonObject {
            put("stepIndex", log.stepIndex)
            put("stepId", log.stepId?.let(::JsonPrimitive) ?: JsonNull)
            put("op", log.op)
            put("success", log.success)
            put("attempts", log.attempts)
            put("message", log.message)
            put("loopDepth", log.loopDepth?.let(::JsonPrimitive) ?: JsonNull)
            put("iteration", log.iteration?.let(::JsonPrimitive) ?: JsonNull)
        }
    }))
}
