package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEvidenceRestoreTest {
    @Test
    fun restoresOnlyConsistentHistoricalRuntimeAddress() {
        val restored = RuntimeEvidenceIntegrator.restorePersistedSnapshot(
            result = exactBinaryResult(),
            evidence = evidence(runtimeVirtualAddress = 0x70020100),
        )

        val target = requireNotNull(restored.evidenceGraph).targets.single()
        assertEquals(ProofLevel.RUNTIME_CONFIRMED, target.proofLevel)
        assertEquals(0x20100L, target.rva)
        assertEquals(0x70020100L, target.runtimeVirtualAddress)
        assertTrue(
            target.facts.any {
                it.kind == "runtime-address-restored-snapshot"
            },
        )
        assertTrue(target.proofLevel != ProofLevel.CHANGE_READY)
    }

    @Test
    fun refusesInternallyInconsistentPersistedRuntimeAddress() {
        val restored = RuntimeEvidenceIntegrator.restorePersistedSnapshot(
            result = exactBinaryResult(),
            evidence = evidence(runtimeVirtualAddress = 0x71020100),
        )

        val target = requireNotNull(restored.evidenceGraph).targets.single()
        assertEquals(ProofLevel.EXACT_BINARY, target.proofLevel)
        assertNull(target.runtimeVirtualAddress)
    }

    private fun exactBinaryResult(): FastAnalysisResult =
        FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = SHA,
                sources = listOf(ArtifactSource("base.apk", 1, SHA)),
                entries = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
            evidenceGraph = EvidenceGraph(
                artifactSha256 = SHA,
                targets = listOf(
                    EvidenceTarget(
                        id = TARGET_ID,
                        runtimeId = "unity_il2cpp",
                        kind = EvidenceTargetKind.METHOD,
                        displayName = "Game.Player.Hit",
                        artifact = "base.apk:lib/arm64-v8a/libil2cpp.so",
                        abi = "arm64-v8a",
                        declaringType = "Game.Player",
                        memberName = "Hit",
                        metadataToken = 0x06000001,
                        rva = null,
                        binaryVirtualAddress = 0x20100,
                        runtimeVirtualAddress = null,
                        fileOffset = 0x20100,
                        proofLevel = ProofLevel.EXACT_BINARY,
                        userStatus = UserFindingStatus.CONFIRMED,
                        blockers = emptyList(),
                        facts = emptyList(),
                    ),
                ),
            ),
        )

    private fun evidence(runtimeVirtualAddress: Long) =
        RuntimeEvidenceBundle(
            artifactSha256 = SHA,
            procMapsSha256 = MAPS_SHA,
            moduleMappings = listOf(
                RuntimeModuleMappingEvidence(
                    moduleName = "libil2cpp.so",
                    mappedPath = "/data/app/pkg/lib/arm64/libil2cpp.so",
                    loadBias = 0x70000000,
                    elfImageBaseVirtualAddress = 0,
                    pageSize = 4096,
                    matchedLoadSegments = 2,
                    matchedExecutableSegments = 1,
                    zeroOffsetMappingMatched = true,
                    confirmed = true,
                    blockers = emptyList(),
                ),
            ),
            addressConfirmations = listOf(
                RuntimeAddressConfirmation(
                    targetId = TARGET_ID,
                    moduleName = "libil2cpp.so",
                    binaryVirtualAddress = 0x20100,
                    rva = 0x20100,
                    runtimeVirtualAddress = runtimeVirtualAddress,
                    executableMappingContainsAddress = true,
                ),
            ),
            blockers = emptyList(),
            captureSource = ProcMapsCaptureSource.IMPORTED_SNAPSHOT,
            capturedAtEpochMs = 1234,
        )

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val MAPS_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val TARGET_ID =
            "il2cpp:method:Assembly-CSharp.dll:6000001:Assembly-CSharp.dll"
    }
}
