package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGateTest {
    @Test
    fun requestedChangeReadyGetsImmediateBinaryBlockerWhenBinaryProofIsMissing() {
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
        assertEquals(1, evidence.blockers.size)
        assertEquals("EXECUTABLE_BINDING_MISSING", evidence.blockers.single().code)
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


    @Test
    fun ordinaryAnalysisStillExplainsWhyChangeReadyIsFalse() {
        val evidence = EvidenceGate.evaluate(
            artifactSha256 = "abc",
            metadataIdentityExact = true,
            binaryIdentityExact = false,
            runtimeConfirmed = false,
            mutationValidated = false,
            requestedChangeReady = false,
        )

        assertEquals(ProofLevel.EXACT_METADATA, evidence.proofLevel)
        assertFalse(evidence.changeReady)
        assertEquals(1, evidence.blockers.size)
        assertEquals("EXECUTABLE_BINDING_MISSING", evidence.blockers.single().code)
        assertEquals(ProofLevel.EXACT_BINARY, evidence.blockers.single().requiredFor)
    }

    @Test
    fun exactBinaryExplainsThatPreparePreflightIsNext() {
        val evidence = EvidenceGate.evaluate(
            artifactSha256 = "abc",
            metadataIdentityExact = true,
            binaryIdentityExact = true,
            runtimeConfirmed = false,
            mutationValidated = false,
            requestedChangeReady = false,
        )

        assertEquals(ProofLevel.EXACT_BINARY, evidence.proofLevel)
        assertFalse(evidence.changeReady)
        assertEquals("MUTATION_NOT_VALIDATED", evidence.blockers.single().code)
        assertEquals(ProofLevel.CHANGE_READY, evidence.blockers.single().requiredFor)
    }

    @Test
    fun detailedBinaryBlockerReplacesGenericExecutableBlocker() {
        val evidence = EvidenceGate.evaluate(
            artifactSha256 = "abc",
            metadataIdentityExact = true,
            binaryIdentityExact = false,
            runtimeConfirmed = false,
            mutationValidated = false,
            requestedChangeReady = false,
            suppliedBlockers = listOf(
                EvidenceBlocker(
                    code = "AMBIGUOUS_CODEGEN_MODULE_ARRAY",
                    message = "ambiguous",
                    requiredFor = ProofLevel.EXACT_BINARY,
                ),
            ),
        )

        assertEquals(1, evidence.blockers.size)
        assertEquals("AMBIGUOUS_CODEGEN_MODULE_ARRAY", evidence.blockers.single().code)
    }
}
