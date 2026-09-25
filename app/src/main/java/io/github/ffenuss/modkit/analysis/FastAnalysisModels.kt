package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.runtime.RuntimeEvidenceBundle
import io.github.ffenuss.modkit.runtime.RuntimeStageAttempt

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

data class FastAnalysisResult(
    val index: ArtifactIndex,
    val routingPlan: EngineRoutingPlan,
    val elapsedMs: Long,
    val dexInventory: DexInventoryResult? = null,
    val elfInventory: UniversalElfInventoryResult? = null,
    val il2cppFastDump: Il2CppFastDumpResult? = null,
    val il2cppBinaryBinding: Il2CppBinaryBindingResult? = null,
    val il2cppEvidence: ExecutableBindingEvidence? = null,
    val evidenceGraph: EvidenceGraph? = null,
    val confirmationQueue: List<ConfirmationRequest> = emptyList(),
    val runtimeEvidence: RuntimeEvidenceBundle? = null,
    val runtimeStageAttempts: List<RuntimeStageAttempt> = emptyList(),
    val engineCacheHits: Set<String> = emptySet(),
    val engineWarnings: List<String> = emptyList(),
    val unrealAssetInventory: UnrealAssetInventoryResult? = null,
    val flutterAssetInventory: FlutterAssetInventoryResult? = null,
)
