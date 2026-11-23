package com.hermesandroid.relay.script.runtime

object ScriptRunControl {
    private val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var reason: String? = null
    private var pausedRunId: String? = null
    private var resumeSignal: kotlinx.coroutines.CompletableDeferred<List<ScriptStep>?>? = null
    @Synchronized fun requestStop(reason: String) {
        this.reason = reason; cancel.set(true)
        resumeSignal?.complete(null)
    }
    @Synchronized internal fun beginPause(runId: String): kotlinx.coroutines.CompletableDeferred<List<ScriptStep>?> {
        val signal = kotlinx.coroutines.CompletableDeferred<List<ScriptStep>?>()
        pausedRunId = runId
        resumeSignal = signal
        if (cancel.get()) signal.complete(null)
        return signal
    }
    @Synchronized internal fun endPause() { pausedRunId = null; resumeSignal = null }
    @Synchronized fun isPaused(runId: String): Boolean =
        pausedRunId == runId && resumeSignal?.isCompleted == false
    @Synchronized fun resume(runId: String, steps: List<ScriptStep>? = null) {
        if (pausedRunId == runId) resumeSignal?.complete(steps)
    }
    @Synchronized fun reset() { endPause(); reason = null; cancel.set(false) }
    fun isCancelled(): Boolean = cancel.get()
}
