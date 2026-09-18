package io.github.ffenuss.modkit.domain

enum class EngineScheduleClass {
    FAST,
    TARGETED,
    CONFIRMATION,
    BACKGROUND,
}

enum class CapabilityState {
    DONE,
    IMPLEMENTED_BUT_INCOMPLETE,
    TO_ADD,
    TO_REWORK,
    HIDE_FROM_SIMPLE_UX_KEEP_INTERNAL,
}

enum class ProofLevel {
    DISCOVERED,
    SEMANTIC,
    STRUCTURAL,
    EXACT_METADATA,
    EXACT_BINARY,
    RUNTIME_CONFIRMED,
    CHANGE_READY,
}

enum class RunState {
    IDLE,
    RUNNING,
    CANCELLING,
    STALLED,
    COMPLETED,
    FAILED,
}

data class EngineProgress(
    val engineId: String,
    val scheduleClass: EngineScheduleClass,
    val state: RunState,
    val currentTask: String? = null,
    val currentArtifact: String? = null,
    val processed: Long? = null,
    val total: Long? = null,
    val lastHeartbeatEpochMs: Long? = null,
)
