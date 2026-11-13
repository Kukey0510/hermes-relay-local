package com.hermesandroid.relay.script.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        return when (val op = required("op")) {
            "tap" -> ScriptStep.Tap(required("x").toInt(), required("y").toInt(), id, timeout, retries)
            "tapText" -> ScriptStep.TapText(required("text"), id, timeout, retries)
            "wait" -> ScriptStep.Wait(required("ms").toLong(), id, timeout, retries)
            "probe" -> ScriptStep.Probe(required("expect"), timeout, id, retries)
            "read" -> ScriptStep.Read(required("field"), id, timeout, retries)
            else -> throw UnsupportedOperationException("unsupported script op: $op")
        }
    }

    override fun serialize(encoder: Encoder, value: ScriptStep) {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        fun put(name: String, value: String) { fields[name] = JsonPrimitive(value) }
        fun put(name: String, value: Number) { fields[name] = JsonPrimitive(value) }
        when (value) {
            is ScriptStep.Tap -> { put("op", "tap"); put("x", value.x); put("y", value.y) }
            is ScriptStep.TapText -> { put("op", "tapText"); put("text", value.text) }
            is ScriptStep.Wait -> { put("op", "wait"); put("ms", value.ms) }
            is ScriptStep.Probe -> { put("op", "probe"); put("expect", value.expect) }
            is ScriptStep.Read -> { put("op", "read"); put("field", value.field) }
        }
        value.id?.let { put("id", it) }
        value.timeoutMs?.let { put("timeout_ms", it) }
        if (value.retries != 0) put("retries", value.retries)
        (encoder as? JsonEncoder)?.encodeJsonElement(JsonObject(fields))
            ?: error("ScriptStep only supports JSON")
    }
}

@Serializable
data class RuntimeScript(val id: String, val targetPackages: List<String>, val steps: List<ScriptStep>)
