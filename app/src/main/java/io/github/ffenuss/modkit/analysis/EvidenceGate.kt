package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.ProofLevel

data class EvidenceBlocker(
    val code: String,
    val message: String,
    val requiredFor: ProofLevel,
)

data class ExecutableBindingEvidence(
    val artifactSha256: String,
    val metadataIdentityExact: Boolean,
    val binaryIdentityExact: Boolean,
    val runtimeConfirmed: Boolean,
    val mutationValidated: Boolean,
    val blockers: List<EvidenceBlocker>,
) {
    val proofLevel: ProofLevel
        get() = when {
            mutationValidated && (binaryIdentityExact || runtimeConfirmed) && blockers.isEmpty() ->
                ProofLevel.CHANGE_READY
            runtimeConfirmed -> ProofLevel.RUNTIME_CONFIRMED
            binaryIdentityExact -> ProofLevel.EXACT_BINARY
            metadataIdentityExact -> ProofLevel.EXACT_METADATA
            else -> ProofLevel.STRUCTURAL
        }

    val changeReady: Boolean
        get() = proofLevel == ProofLevel.CHANGE_READY
}

object EvidenceGate {
    fun evaluate(
        artifactSha256: String,
        metadataIdentityExact: Boolean,
        binaryIdentityExact: Boolean,
        runtimeConfirmed: Boolean,
        mutationValidated: Boolean,
        requestedChangeReady: Boolean,
        suppliedBlockers: List<EvidenceBlocker> = emptyList(),
    ): ExecutableBindingEvidence {
        val blockers = suppliedBlockers.toMutableList()

        when {
            !metadataIdentityExact -> {
                blockers += EvidenceBlocker(
                    code = "METADATA_IDENTITY_MISSING",
                    message = "Exact metadata identity/token proof is missing.",
                    requiredFor = ProofLevel.EXACT_METADATA,
                )
            }
            !binaryIdentityExact && !runtimeConfirmed -> {
                if (blockers.none { it.requiredFor == ProofLevel.EXACT_BINARY }) {
                    blockers += EvidenceBlocker(
                        code = "EXECUTABLE_BINDING_MISSING",
                        message = "Exact executable binding or runtime confirmation is missing.",
                        requiredFor = ProofLevel.EXACT_BINARY,
                    )
                }
            }
            !mutationValidated -> {
                blockers += EvidenceBlocker(
                    code = "MUTATION_NOT_VALIDATED",
                    message = if (requestedChangeReady) {
                        "The proposed mutation has not passed prepare/preflight validation."
                    } else {
                        "Prepare/preflight has not validated a concrete mutation yet."
                    },
                    requiredFor = ProofLevel.CHANGE_READY,
                )
            }
        }

        return ExecutableBindingEvidence(
            artifactSha256 = artifactSha256,
            metadataIdentityExact = metadataIdentityExact,
            binaryIdentityExact = binaryIdentityExact,
            runtimeConfirmed = runtimeConfirmed,
            mutationValidated = mutationValidated,
            blockers = blockers.distinctBy { it.code },
        )
    }
}
