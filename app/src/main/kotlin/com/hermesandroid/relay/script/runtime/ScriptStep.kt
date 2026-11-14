package com.hermesandroid.relay.script.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

@Serializable(with = ScriptStepSerializer::class)
sealed interface ScriptStep {
    val id: String?
    val timeoutMs: Long?
    val retries: Int

    data class Tap(val x: Int, val y: Int, override val id: String? = null,
        override val timeoutMs: Long? = null, override val retries: Int = 0) : ScriptStep
    data class TapText(val text: String, override val id: String? = null,
        override val timeoutMs: Long? = null, override val retries: Int = 0) : ScriptStep
    data class Wait(val ms: Long, override val id: String? = null,
        override val timeoutMs: Long? = null, override val retries: Int = 0) : ScriptStep
    data class Probe(val expect: String, override val timeoutMs: Long? = null,
        override val id: String? = null, override val retries: Int = 0) : ScriptStep
    data class Read(val field: String, override val id: String? = null,
        override val timeoutMs: Long? = null, override val retries: Int = 0,
        val asName: String = field) : ScriptStep
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int,
        val durationMs: Long = 400, override val id: String? = null,
        override val timeoutMs: Long? = null, override val retries: Int = 0) : ScriptStep
    data class TapIfText(val text: String, val optional: Boolean = true,
        override val id: String? = null, override val timeoutMs: Long? = null,
        override val retries: Int = 0) : ScriptStep
    data class IfPage(val expect: String, val thenSteps: List<ScriptStep> = emptyList(),
        val elseSteps: List<ScriptStep> = emptyList(), override val id: String? = null,
        override val timeoutMs: Long? = null, override val retries: Int = 0) : ScriptStep
    data class Loop(val times: Int, val maxTotalMs: Long = 600_000,
        val until: String? = null, val steps: List<ScriptStep> = emptyList(),
        override val id: String? = null, override val timeoutMs: Long? = null,
        override val retries: Int = 0, val timesFrom: String? = null) : ScriptStep
    data class Launch(val packageName: String? = null, val waitMs: Long = 0,
        override val id: String? = null, override val timeoutMs: Long? = null,
        override val retries: Int = 0) : ScriptStep
    data class Back(override val id: String? = null, override val timeoutMs: Long? = null,
        override val retries: Int = 0) : ScriptStep
    data class WaitStable(val stableFrames: Int = 3, val intervalMs: Long = 250,
        val stableTimeoutMs: Long = 10_000, val tolerance: Int = 2,
        override val id: String? = null, override val timeoutMs: Long? = null,
        override val retries: Int = 0) : ScriptStep
    data class Snapshot(val tag: String = "manual", override val id: String? = null,
        override val timeoutMs: Long? = null, override val retries: Int = 0) : ScriptStep
}

object ScriptStepSerializer : KSerializer<ScriptStep> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): ScriptStep {
        val json = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonObject
            ?: error("ScriptStep only supports JSON objects")
        fun text(name: String) = (json[name] as? JsonPrimitive)?.content
        fun required(name: String) = text(name) ?: error("missing '$name'")
        val id = text("id")
        val timeout = (json["timeout_ms"] as? JsonPrimitive)?.longOrNull
        val retries = ((json["retries"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
        fun steps(name: String): List<ScriptStep> = (json[name] as? JsonArray)?.map {
            kotlinx.serialization.json.Json.decodeFromJsonElement(this, it)
        } ?: emptyList()
        return when (val op = required("op")) {
            "tap" -> ScriptStep.Tap(required("x").toInt(), required("y").toInt(), id, timeout, retries)
            "tapText" -> ScriptStep.TapText(required("text"), id, timeout, retries)
            "wait" -> ScriptStep.Wait(required("ms").toLong(), id, timeout, retries)
            "probe" -> ScriptStep.Probe(required("expect"), timeout, id, retries)
            "read" -> ScriptStep.Read(required("field"), id, timeout, retries, text("as") ?: required("field"))
            "swipe" -> ScriptStep.Swipe(required("from_x").toInt(), required("from_y").toInt(),
                required("to_x").toInt(), required("to_y").toInt(),
                text("duration_ms")?.toLong() ?: 400, id, timeout, retries)
            "tapIfText" -> ScriptStep.TapIfText(required("text"),
                (json["optional"] as? JsonPrimitive)?.booleanOrNull ?: true, id, timeout, retries)
            "if_page" -> ScriptStep.IfPage(required("expect"), steps("then"), steps("else"), id, timeout, retries)
            "loop" -> ScriptStep.Loop(required("times").toInt(),
                text("max_total_ms")?.toLong() ?: 600_000, text("until"),
                steps("steps"), id, timeout, retries, text("times_from"))
            "launch" -> ScriptStep.Launch(text("package"), text("wait_ms")?.toLong() ?: 0, id, timeout, retries)
            "back" -> ScriptStep.Back(id, timeout, retries)
            "wait_stable" -> ScriptStep.WaitStable(text("frames")?.toInt() ?: 3,
                text("interval_ms")?.toLong() ?: 250, text("timeout_ms")?.toLong() ?: 10_000,
                text("tolerance")?.toInt() ?: 2, id, null, retries)
            "snapshot" -> ScriptStep.Snapshot(text("tag") ?: "manual", id, timeout, retries)
            else -> throw UnsupportedOperationException("unsupported script op: $op")
        }
    }

    override fun serialize(encoder: Encoder, value: ScriptStep) {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        fun put(name: String, value: String) { fields[name] = JsonPrimitive(value) }
        fun put(name: String, value: Number) { fields[name] = JsonPrimitive(value) }
        fun put(name: String, value: Boolean) { fields[name] = JsonPrimitive(value) }
        fun putSteps(name: String, value: List<ScriptStep>) {
            fields[name] = JsonArray(value.map { kotlinx.serialization.json.Json.encodeToJsonElement(this, it) })
        }
        when (value) {
            is ScriptStep.Tap -> { put("op", "tap"); put("x", value.x); put("y", value.y) }
            is ScriptStep.TapText -> { put("op", "tapText"); put("text", value.text) }
            is ScriptStep.Wait -> { put("op", "wait"); put("ms", value.ms) }
            is ScriptStep.Probe -> { put("op", "probe"); put("expect", value.expect) }
            is ScriptStep.Read -> { put("op", "read"); put("field", value.field)
                if (value.asName != value.field) put("as", value.asName) }
            is ScriptStep.Swipe -> { put("op", "swipe"); put("from_x", value.fromX); put("from_y", value.fromY)
                put("to_x", value.toX); put("to_y", value.toY); put("duration_ms", value.durationMs) }
            is ScriptStep.TapIfText -> { put("op", "tapIfText"); put("text", value.text); put("optional", value.optional) }
            is ScriptStep.IfPage -> { put("op", "if_page"); put("expect", value.expect)
                putSteps("then", value.thenSteps); putSteps("else", value.elseSteps) }
            is ScriptStep.Loop -> { put("op", "loop"); put("times", value.times)
                put("max_total_ms", value.maxTotalMs); value.timesFrom?.let { put("times_from", it) }
                value.until?.let { put("until", it) }; putSteps("steps", value.steps) }
            is ScriptStep.Launch -> { put("op", "launch"); value.packageName?.let { put("package", it) }; put("wait_ms", value.waitMs) }
            is ScriptStep.Back -> put("op", "back")
            is ScriptStep.WaitStable -> { put("op", "wait_stable"); put("frames", value.stableFrames)
                put("interval_ms", value.intervalMs); put("timeout_ms", value.stableTimeoutMs); put("tolerance", value.tolerance) }
            is ScriptStep.Snapshot -> { put("op", "snapshot"); put("tag", value.tag) }
        }
        value.id?.let { put("id", it) }
        value.timeoutMs?.let { put("timeout_ms", it) }
        if (value.retries != 0) put("retries", value.retries)
        (encoder as? JsonEncoder)?.encodeJsonElement(JsonObject(fields))
            ?: error("ScriptStep only supports JSON")
    }
}

@Serializable
data class RuntimeScript(
    val id: String,
    val targetPackages: List<String>,
    val steps: List<ScriptStep>,
    val vars: Map<String, JsonPrimitive> = emptyMap(),
    @SerialName("on_fail") val onFail: List<ScriptStep> = emptyList(),
)
