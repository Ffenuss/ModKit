package io.github.ffenuss.modkit.runtime

import java.io.Serializable

enum class RuntimeStageAttemptState {
    NOT_ATTEMPTED,
    COMPLETED,
    BLOCKED,
    FAILED,
}

enum class RuntimeStageBlockerCategory {
    TARGET_ENVIRONMENT,
    EVIDENCE_GAP,
    EXECUTOR_GAP,
    INVALID_INPUT,
}

data class RuntimeStageBlocker(
    val code: String,
    val message: String,
    val category: RuntimeStageBlockerCategory,
) : Serializable

data class RuntimeStageAttempt(
    val stage: RuntimeEscalationStage,
    val state: RuntimeStageAttemptState,
    val attemptedAtEpochMs: Long?,
    val requestedTargetIds: Set<String>,
    val resolvedTargetIds: Set<String>,
    val blockers: List<RuntimeStageBlocker>,
) : Serializable {
    val unresolvedTargetIds: Set<String>
        get() = requestedTargetIds - resolvedTargetIds
}
