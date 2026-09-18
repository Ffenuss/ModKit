package io.github.ffenuss.modkit.analysis

import java.util.concurrent.atomic.AtomicBoolean

class AtomicCancellationSignal : CancellationSignal {
    private val cancelled = AtomicBoolean(false)

    override fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }
}
