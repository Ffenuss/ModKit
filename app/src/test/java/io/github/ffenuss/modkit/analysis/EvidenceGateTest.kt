package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGateTest {
    @Test
    fun requestedChangeReadyGetsExplicitBlockersWhenBinaryProofIsMissing() {
        val evidence = EvidenceGate.evaluate(
            artifactSha256 = "abc",
            metadataIdentityExact = true,
            binaryIdentityExact = false,
            runtimeConfirmed = false,
            mutationValidated = false,
            requestedChangeReady = true,
        )

        assertFalse(evidence.changeReady)
        assertEquals(ProofLevel.EXACT_METADATA, evidence.proofLevel)
        assertTrue(evidence.blockers.any { it.code == "EXECUTABLE_BINDING_MISSING" })
        assertTrue(evidence.blockers.any { it.code == "MUTATION_NOT_VALIDATED" })
    }

    @Test
    fun changeReadyRequiresExecutableProofAndValidatedMutation() {
        val evidence = EvidenceGate.evaluate(
            artifactSha256 = "abc",
            metadataIdentityExact = true,
            binaryIdentityExact = true,
            runtimeConfirmed = false,
            mutationValidated = true,
            requestedChangeReady = true,
        )

        assertTrue(evidence.changeReady)
        assertEquals(ProofLevel.CHANGE_READY, evidence.proofLevel)
        assertTrue(evidence.blockers.isEmpty())
    }
}
