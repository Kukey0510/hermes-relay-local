package com.hermesandroid.relay.script.runtime

object ScriptRunControl {
    private val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var reason: String? = null
    fun requestStop(reason: String) { this.reason = reason; cancel.set(true) }
    fun reset() { reason = null; cancel.set(false) }
    fun isCancelled(): Boolean = cancel.get()
}
