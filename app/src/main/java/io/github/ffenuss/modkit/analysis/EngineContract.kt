package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import java.io.File

interface CancellationSignal {
    fun isCancelled(): Boolean
}

fun interface ProgressSink {
    fun publish(progress: EngineProgress)
}

/**
 * Runtime workspace passed to specialized engines.
 *
 * The immutable ArtifactIndex is shared. [sources] gives an engine access to
 * already materialized/local source files without rediscovering the target.
 */
data class AnalysisWorkspace(
    val index: ArtifactIndex,
    val sources: List<WorkspaceSource>,
)

data class WorkspaceSource(
    val descriptor: ArtifactSource,
    val file: File,
)

interface AnalysisEngine<out R> {
    val id: String
    val scheduleClass: EngineScheduleClass
    val version: String

    suspend fun analyze(
        workspace: AnalysisWorkspace,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): R
}
