package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNativeLookupGraphAnnotatorTest {
    @Test
    fun confirmedDlsymAddsFactWithoutChangingProofOrStatus() {
        val target = target()
        val result = baseResult(target)
        val validation = RuntimeNativeTraceValidationResult(
            observations = listOf(
                RuntimeEvidenceObservation(
                    id = "obs",
                    kind =
                        RuntimeEvidenceObservationKind.JNI_DLSYM_OBSERVED,
                    strength =
                        RuntimeEvidenceObservationStrength.CONFIRMED,
                    subjectId = SYMBOL,
                    artifactSha256 = SHA,
                    captureSha256 = "maps",
                    captureSource =
                        ProcMapsCaptureSource.REPACKED_TEST_RUNTIME,
                    capturedAtEpochMs = 1,
                    summary =
                        "TARGETED_PROBE DLSYM observed for $SYMBOL in $MODULE at 0x1234",
                    blockers = emptyList(),
                    proofLevel = null,
                ),
            ),
            rejectedEvents = 0,
            blockers = emptyList(),
        )

        val updated = RuntimeNativeLookupGraphAnnotator.annotate(
            result = result,
            moduleName = MODULE,
            symbolName = SYMBOL,
            validation = validation,
        )

        val annotated =
            requireNotNull(updated.evidenceGraph).targets.single()
        assertEquals(ProofLevel.EXACT_BINARY, annotated.proofLevel)
        assertEquals(UserFindingStatus.CONFIRMED, annotated.userStatus)
        assertEquals(target.rva, annotated.rva)
        assertEquals(
            target.runtimeVirtualAddress,
            annotated.runtimeVirtualAddress,
        )
        assertTrue(
            annotated.facts.any {
                it.engineId == "runtime.native-lookup" &&
                    it.kind == "dlsym-observed"
            },
        )
    }

    @Test
    fun unmatchedObservationDoesNotInventNewTarget() {
        val target = target()
        val result = baseResult(target)
        val validation = RuntimeNativeTraceValidationResult(
            observations = listOf(
                RuntimeEvidenceObservation(
                    id = "obs",
                    kind =
                        RuntimeEvidenceObservationKind.JNI_DLSYM_OBSERVED,
                    strength =
                        RuntimeEvidenceObservationStrength.CONFIRMED,
                    subjectId = "OtherSymbol",
                    artifactSha256 = SHA,
                    captureSha256 = "maps",
                    captureSource =
                        ProcMapsCaptureSource.REPACKED_TEST_RUNTIME,
                    capturedAtEpochMs = 1,
                    summary = "other",
                ),
            ),
            rejectedEvents = 0,
            blockers = emptyList(),
        )

        val updated = RuntimeNativeLookupGraphAnnotator.annotate(
            result = result,
            moduleName = MODULE,
            symbolName = "OtherSymbol",
            validation = validation,
        )

        assertEquals(
            listOf(target),
            requireNotNull(updated.evidenceGraph).targets,
        )
    }

    private fun baseResult(
        target: EvidenceTarget,
    ): FastAnalysisResult {
        val index = ArtifactIndex(
            artifactSha256 = SHA,
            sources = emptyList(),
            entries = emptyList(),
        )
        return FastAnalysisResult(
            index = index,
            routingPlan = EngineRoutingPlan(
                engines = emptyList(),
                missingCapabilities = emptyList(),
            ),
            elapsedMs = 1,
            evidenceGraph = EvidenceGraph(
                artifactSha256 = SHA,
                targets = listOf(target),
            ),
        )
    }

    private fun target() = EvidenceTarget(
        id = "native:$MODULE:$SYMBOL",
        runtimeId = "native",
        kind = EvidenceTargetKind.NATIVE_FUNCTION,
        displayName = SYMBOL,
        artifact = "base.apk:lib/arm64-v8a/$MODULE",
        abi = "arm64-v8a",
        declaringType = null,
        memberName = SYMBOL,
        metadataToken = null,
        rva = 0x100,
        binaryVirtualAddress = 0x1100,
        runtimeVirtualAddress = null,
        fileOffset = 0x200,
        proofLevel = ProofLevel.EXACT_BINARY,
        userStatus = UserFindingStatus.CONFIRMED,
        blockers = emptyList(),
        facts = emptyList(),
    )

    companion object {
        private const val MODULE = "libsample.so"
        private const val SYMBOL = "ResolveTarget"
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
