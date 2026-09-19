package io.github.ffenuss.modkit.analysis

import java.util.concurrent.atomic.AtomicReference

class EngineSkipController {
    private val requestedEngine = AtomicReference<String?>(null)

    fun request(engineId: String) {
        requestedEngine.set(engineId)
    }

    fun isRequested(engineId: String): Boolean =
        requestedEngine.get() == engineId

    fun consume(engineId: String): Boolean =
        requestedEngine.compareAndSet(engineId, null)

    fun clear() {
        requestedEngine.set(null)
    }
}
