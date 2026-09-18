package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass

interface CancellationSignal {
    fun isCancelled(): Boolean
}

fun interface ProgressSink {
    fun publish(progress: EngineProgress)
}

interface AnalysisEngine<out R> {
    val id: String
    val scheduleClass: EngineScheduleClass
    val version: String

    suspend fun analyze(
        index: ArtifactIndex,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): R
}
