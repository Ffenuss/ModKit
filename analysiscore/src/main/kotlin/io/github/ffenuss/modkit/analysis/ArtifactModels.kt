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
) : java.io.Serializable

data class ArtifactSource(
    val displayName: String,
    val size: Long,
    val sha256: String,
) : java.io.Serializable

enum class BinaryFormat {
    DEX,
    ELF,
    WASM,
    IL2CPP_METADATA,
    ZIP,
    UNKNOWN,
}
