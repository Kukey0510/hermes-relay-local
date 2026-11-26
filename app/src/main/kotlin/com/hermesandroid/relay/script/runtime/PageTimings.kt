package com.hermesandroid.relay.script.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Probe totals exclude the wait between captures; matching includes loading page specs. */
data class PageTimings(val captureMs: Long = 0, val matchMs: Long = 0) {
    operator fun plus(other: PageTimings) = PageTimings(captureMs + other.captureMs, matchMs + other.matchMs)

    fun messageSuffix(): String = " capture_ms=$captureMs match_ms=$matchMs"

    companion object {
        fun fromPayload(payload: JsonObject) = PageTimings(
            (payload["capture_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
            (payload["match_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }
}
