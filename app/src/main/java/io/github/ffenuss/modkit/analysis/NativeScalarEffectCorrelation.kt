package io.github.ffenuss.modkit.analysis

/**
 * Checkpoints are collected from the *game's visible state* after one real
 * player action at each transition, not from a provider's patch-ON flag.
 */
enum class NativeScalarEffectPhase {
    START,
    UNMODIFIED_ACTION,
    UNMODIFIED_CONTROL_ACTION,
    PATCH_ENABLED_ACTION,
    PATCH_DISABLED_ACTION,
    PATCH_ENABLED_REPEAT_ACTION,
    PATCH_DISABLED_REPEAT_ACTION,
    OVERLAY_CLOSED_ACTION,
}

data class NativeScalarUiObservation(
    val phase: NativeScalarEffectPhase,
    val displayedCounter: Long,
    val monotonicTimestampMs: Long,
)

/**
 * A scoped correlation of one exact discovered native function with an
 * observed and reversible game action. This is not a general-purpose
 * semantic inference or an authorization to modify any arbitrary game.
 */
data class NativeScalarEffectConfirmation(
    val originalArtifactSha256: String,
    val packageName: String,
    val moduleName: String,
    val actionLabel: String,
    val abi: String,
    val symbol: String,
    val patchVirtualAddress: Long,
    val expectedHex: String,
    val replacementHex: String,
    val unmodifiedStep: Int,
    val modifiedStep: Int,
    val observations: List<NativeScalarUiObservation>,
    val observedDeltas: List<Long>,
) {
    val baselineControlActions: Int = 2
    val completedPatchOnOffCycles: Int = 2
    val source: String = "CI-owned fixture; independent UI hierarchy samples"
}

/**
 * Exact, conservative gate for the repository-owned fixture experiment.
 * Requires eight separately sampled UI checkpoints: two baseline controls,
 * two independent ON/OFF cycles and restoration after overlay teardown.
 * Source SHA and proposed bytes must be exact. A one-time coincidence or
 * patch-ON state alone cannot satisfy this repeated intervention protocol.
 * No UI readings
 * are synthesized here. A separately installed game/overlay test is
 * responsible for collecting them from the actual Android UI.
 */
object NativeScalarEffectCorrelation {
    private val expectedPhases = NativeScalarEffectPhase.entries
    private val sha256 = Regex("[0-9a-f]{64}")
    private val module = Regex("[A-Za-z0-9_.-]{1,128}\\.so")
    private val packageNamePattern =
        Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

    fun confirm(
        originalArtifactSha256: String,
        packageName: String,
        moduleName: String,
        actionLabel: String,
        candidate: NativeScalarExportCandidate,
        patch: NativeScalarExportPatch,
        modifiedStep: Int,
        observations: List<NativeScalarUiObservation>,
    ): NativeScalarEffectConfirmation {
        require(sha256.matches(originalArtifactSha256)) {
            "Missing exact source APK-set SHA-256"
        }
        require(packageNamePattern.matches(packageName) && packageName.length <= 200) {
            "Invalid game package identity"
        }
        require(packageName == "dev.modkit.nativefixture") {
            "This controlled experiment is restricted to the owned native fixture"
        }
        require(module.matches(moduleName) && moduleName == "libmodkit_native_fixture.so") {
            "This proof requires the owned native fixture module"
        }
        require(actionLabel.isNotBlank() && actionLabel.length <= 120) {
            "No bounded user-visible action label"
        }
        require(!candidate.selectable) {
            "Structural-only candidates must not be pre-authorized"
        }
        require(candidate.symbol.isNotBlank() && candidate.symbol.length <= 512) {
            "Missing or unbounded native symbol"
        }
        require(candidate.abi in setOf("arm64-v8a", "x86_64")) {
            "Unsupported native ABI"
        }
        require(patch == NativeScalarExportDiscovery.propose(candidate, modifiedStep)) {
            "Proposed native bytes/address differ from exact ELF scan"
        }
        require(observations.size == expectedPhases.size) {
            "Eight original UI observations are required"
        }
        require(observations.map { it.phase } == expectedPhases) {
            "UI checkpoints are missing, repeated or out of sequence"
        }
        require(observations.all {
            it.displayedCounter in 0..1_000_000_000L &&
                it.monotonicTimestampMs > 0L
        }) { "Counter or timestamp out of bounded range" }
        require(observations.zipWithNext().all {
            (before, after) -> after.monotonicTimestampMs > before.monotonicTimestampMs
        }) { "UI observations are not chronologically ordered" }

        val deltas = observations.zipWithNext().map { (before, after) ->
            after.displayedCounter - before.displayedCounter
        }
        val expectedDeltas = listOf(
            candidate.value.toLong(), // Baseline action
            candidate.value.toLong(), // Baseline control without a toggle
            modifiedStep.toLong(), // First ON trial
            candidate.value.toLong(), // First OFF recovery
            modifiedStep.toLong(), // Independent repeat ON trial
            candidate.value.toLong(), // Independent repeat OFF recovery
            candidate.value.toLong(), // Overlay closed after re-enabling
        )
        require(candidate.value > 0 && modifiedStep > 0 && candidate.value != modifiedStep) {
            "No distinct positive native movement effect"
        }
        require(deltas == expectedDeltas) {
            "UI movement did not replicate baseline controls, ON/OFF cycles and overlay restoration"
        }

        return NativeScalarEffectConfirmation(
            originalArtifactSha256 = originalArtifactSha256,
            packageName = packageName,
            moduleName = moduleName,
            actionLabel = actionLabel,
            abi = candidate.abi,
            symbol = candidate.symbol,
            patchVirtualAddress = patch.patchVirtualAddress,
            expectedHex = patch.expectedHex,
            replacementHex = patch.replacementHex,
            unmodifiedStep = candidate.value,
            modifiedStep = modifiedStep,
            observations = observations.toList(),
            observedDeltas = deltas,
        )
    }
}
