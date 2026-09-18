package io.github.ffenuss.modkit.analysis

enum class DetectionStatus {
    CONFIRMED,
    LIKELY,
    SIGNAL,
}

enum class DetectionConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

data class RuntimeProfile(
    val runtimeId: String,
    val title: String,
    val status: DetectionStatus,
    val confidence: DetectionConfidence,
    val evidence: List<String>,
)

data class ArtifactSource(
    val displayName: String,
    val size: Long,
    val sha256: String,
)

enum class BinaryFormat {
    DEX,
    ELF,
    WASM,
    IL2CPP_METADATA,
    ZIP,
    UNKNOWN,
}

data class FastAnalysisResult(
    val index: ArtifactIndex,
    val elapsedMs: Long,
)
