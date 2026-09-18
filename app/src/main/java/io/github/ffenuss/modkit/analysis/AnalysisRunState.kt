package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress

sealed interface AnalysisTargetDescriptor {
    val label: String

    data class FileUri(
        val uri: String,
        override val label: String,
    ) : AnalysisTargetDescriptor

    data class InstalledPackage(
        val packageName: String,
        override val label: String,
    ) : AnalysisTargetDescriptor
}

sealed interface AnalysisRunState {
    data object Idle : AnalysisRunState

    data class Running(
        val runId: Long,
        val target: AnalysisTargetDescriptor,
        val progress: EngineProgress?,
        val startedAtEpochMs: Long,
    ) : AnalysisRunState

    data class Cancelling(
        val runId: Long,
        val target: AnalysisTargetDescriptor,
        val progress: EngineProgress?,
        val startedAtEpochMs: Long,
    ) : AnalysisRunState

    data class Stalled(
        val runId: Long,
        val target: AnalysisTargetDescriptor,
        val progress: EngineProgress?,
        val startedAtEpochMs: Long,
        val heartbeatAgeMs: Long,
    ) : AnalysisRunState

    data class Completed(
        val runId: Long,
        val target: AnalysisTargetDescriptor,
        val result: FastAnalysisResult,
    ) : AnalysisRunState

    data class Cancelled(
        val runId: Long,
        val target: AnalysisTargetDescriptor,
    ) : AnalysisRunState

    data class Failed(
        val runId: Long,
        val target: AnalysisTargetDescriptor,
        val message: String,
    ) : AnalysisRunState

    data class Interrupted(
        val target: AnalysisTargetDescriptor,
        val previousProgress: EngineProgress?,
        val message: String,
    ) : AnalysisRunState
}
