package io.github.ffenuss.modkit.analysis

import org.junit.Assert.*
import org.junit.Test

class NativeMetricExperimentPlannerTest {
    private val candidate = NativeScalarExportCandidate(
        abi = "arm64-v8a", symbol = "obfuscated001",
        binaryVirtualAddress = 0x600, fileOffset = 0x600,
        patchVirtualAddress = 0x600, value = 2,
        originalBodyHex = "40008052c0035fd6",
        expectedPatchHex = "40008052",
    )
    private val sha = "ef".repeat(32)

    private fun record(
        container: String = "base.apk",
        entryPath: String = "lib/arm64-v8a/libgame.so",
        candidates: List<NativeScalarExportCandidate> = listOf(candidate),
        mismatch: Boolean = false,
        indexedAbi: String = "arm64-v8a",
    ) = ElfInventoryRecord(
        container = container,
        entryPath = entryPath,
        size = 2048,
        bits = 64,
        machine = 183,
        architecture = "arm64-v8a",
        indexedAbi = indexedAbi,
        abiPathMismatch = mismatch,
        loadSegmentCount = 2,
        executableLoadSegmentCount = 1,
        dynamicSymbolCount = 4,
        definedDynamicSymbolCount = 2,
        sampledDefinedSymbols = candidates.map { it.symbol },
        warnings = emptyList(),
        scalarExportCandidates = candidates,
    )

    private fun result(records: List<ElfInventoryRecord>) = FastAnalysisResult(
        index = ArtifactIndex(
            artifactSha256 = sha,
            sources = emptyList(),
            entries = emptyList(),
        ),
        routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
        elapsedMs = 1,
        elfInventory = UniversalElfInventoryResult(records, emptyList()),
    )

    private fun selected(
        result: FastAnalysisResult,
        entry: String = "lib/arm64-v8a/libgame.so",
        symbol: String = "obfuscated001",
        container: String = "base.apk",
        abi: String = "arm64-v8a",
    ) = NativeMetricExperimentPlanner.fromAnalysis(
        result = result,
        packageName = "com.example.mygame",
        sourceContainer = container,
        elfEntryPath = entry,
        abi = abi,
        symbol = symbol,
        actionLabel = "Move button",
        metricLabel = "Distance",
        replacementValue = 7,
    )

    private fun denied(task: () -> Unit) {
        try {
            task()
            fail("Invalid native export binding should be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun bindsExplicitExperimentToAnExistingScannedExport() {
        val plan = selected(result(listOf(record())))
        assertEquals("com.example.mygame", plan.packageName)
        assertEquals("libgame.so", plan.moduleName)
        assertEquals(candidate, plan.candidate)
        assertEquals("40008052", plan.proposedPatch.expectedHex)
        assertEquals("e0008052", plan.proposedPatch.replacementHex)
        assertEquals(sha, plan.originalArtifactSha256)
        assertFalse(plan.candidate.selectable)
    }

    @Test fun rejectsAmbiguousOrMissingExportAndUnindexedAbi() {
        denied { selected(result(emptyList())) }
        denied { selected(result(listOf(record(), record()))) }
        denied { selected(result(listOf(record(candidates = listOf(candidate, candidate))))) }
        denied { selected(result(listOf(record(mismatch = true)))) }
        denied { selected(result(listOf(record(indexedAbi = "x86_64")))) }
        denied { selected(result(listOf(record())), symbol = "missing_symbol") }
        denied { selected(result(listOf(record())), container = "other.apk") }
        denied { selected(result(listOf(record())), entry = "lib/arm64-v8a/other.so") }
        denied { selected(result(listOf(record())), abi = "x86_64") }
        denied { selected(result(listOf(record(
            entryPath = "lib/arm64-v8a/../libgame.so",
        ))), entry = "lib/arm64-v8a/../libgame.so") }
    }

    @Test fun rejectsCorruptedOrPreauthorizedInventoryCandidates() {
        denied { selected(result(listOf(record(candidates = listOf(
            candidate.copy(selectable = true),
        ))))) }
        denied { selected(result(listOf(record(candidates = listOf(
            candidate.copy(expectedPatchHex = "ffffffff"),
        ))))) }
        val bad = result(listOf(record())).copy(index =
            ArtifactIndex(
                artifactSha256 = "bad", sources = emptyList(),
                entries = emptyList(),
            ))
        denied { selected(bad) }
    }
}
