package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEvidenceContractTest {
    @Test
    fun importedSnapshotKeepsProcessObservedButExactRuntimeFactsIndependent() {
        val bundle = RuntimeEvidenceBundle(
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
                    runtimeVirtualAddress = 0x70020100,
                    executableMappingContainsAddress = true,
                ),
            ),
            blockers = emptyList(),
            moduleInventory = listOf(
                RuntimeMappedModule(
                    path = "/data/app/pkg/lib/arm64/libil2cpp.so",
                    fileName = "libil2cpp.so",
                    device = "103:02",
                    inode = 42,
                    regionCount = 2,
                    executableRegionCount = 1,
                    fileZeroBaseCandidates = setOf(0x70000000),
                    staticArtifactMatches = listOf(
                        "base.apk:lib/arm64-v8a/libil2cpp.so",
                    ),
                ),
            ),
            captureSource = ProcMapsCaptureSource.IMPORTED_SNAPSHOT,
            capturePid = null,
            capturedAtEpochMs = 1234,
        )

        val observations = RuntimeEvidenceContract.observations(bundle)

        val process = observations.single {
            it.kind == RuntimeEvidenceObservationKind.PROCESS_OBSERVED
        }
        assertEquals(
            RuntimeEvidenceObservationStrength.OBSERVED,
            process.strength,
        )
        assertFalse(process.independentlyConfirmed)
        assertTrue(process.blockers.isNotEmpty())

        val mapping = observations.single {
            it.kind == RuntimeEvidenceObservationKind.MODULE_MAPPING_CONFIRMED
        }
        assertTrue(mapping.independentlyConfirmed)

        val address = observations.single {
            it.kind == RuntimeEvidenceObservationKind.RUNTIME_ADDRESS_CONFIRMED
        }
        assertTrue(address.independentlyConfirmed)
        assertEquals(ProofLevel.RUNTIME_CONFIRMED, address.proofLevel)

        assertTrue(
            observations.none {
                it.kind == RuntimeEvidenceObservationKind.METHOD_EXECUTION_CONFIRMED
            },
        )
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val MAPS_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val TARGET_ID =
            "il2cpp:method:Assembly-CSharp.dll:6000001:Assembly-CSharp.dll"
    }
}
