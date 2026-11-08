package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ElfInventoryRecord
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.NativeScalarExportCandidate
import io.github.ffenuss.modkit.analysis.UniversalElfInventoryResult
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItemMode
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NativeScalarExportInfoBuilderTest {
    private fun candidate(
        name: String = "sample_scalar",
        abi: String = "x86_64",
        selectable: Boolean = false,
    ) = NativeScalarExportCandidate(
        abi = abi,
        symbol = name,
        binaryVirtualAddress = 0x600,
        fileOffset = 0x600,
        patchVirtualAddress = if (abi == "x86_64") 0x601 else 0x600,
        value = 2,
        originalBodyHex = if (abi == "x86_64") "b802000000c3" else "40008052c0035fd6",
        expectedPatchHex = if (abi == "x86_64") "02000000" else "40008052",
        selectable = selectable,
    )

    private fun inventory(
        matches: List<NativeScalarExportCandidate>,
        mismatch: Boolean = false,
    ) = UniversalElfInventoryResult(
        records = listOf(
            ElfInventoryRecord(
                container = "nativefixture-debug.apk",
                entryPath = "lib/x86_64/libmodkit_native_fixture.so",
                size = 16000,
                bits = 64,
                machine = 62,
                architecture = "x86_64",
                indexedAbi = "x86_64",
                abiPathMismatch = mismatch,
                loadSegmentCount = 4,
                executableLoadSegmentCount = 1,
                dynamicSymbolCount = 10,
                definedDynamicSymbolCount = 5,
                sampledDefinedSymbols = matches.map { it.symbol },
                warnings = emptyList(),
                scalarExportCandidates = matches,
            ),
        ),
        warnings = emptyList(),
    )

    @Test fun exportsBoundedReadOnlyHintsWithoutExecutableBytes() {
        val source = inventory((0 until 25).map { candidate("step_$it") })
        val items = NativeScalarExportInfoBuilder.build(source)
        assertEquals(12, items.size)
        assertEquals(12, items.map { it.id }.toSet().size)
        assertTrue(items.all { it.mode == RepackedRuntimeTestMenuItemMode.INFO })
        assertTrue(items.all { it.replacementHex.isEmpty() })
        assertTrue(items.all { it.originalHex.isEmpty() })
        assertTrue(items.all { it.detail.contains("эффект не проверен") })
        assertTrue(items.all { it.label.startsWith("Native export") })
    }

    @Test fun rejectsConflictedAbiAndUnexpectedSelectableCandidates() {
        assertTrue(NativeScalarExportInfoBuilder.build(inventory(listOf(candidate()), true)).isEmpty())
        assertTrue(NativeScalarExportInfoBuilder.build(inventory(listOf(candidate(selectable = true)))).isEmpty())
        assertTrue(NativeScalarExportInfoBuilder.build(inventory(listOf(candidate(abi = "arm64-v8a")))).isEmpty())
        assertTrue(NativeScalarExportInfoBuilder.build(null).isEmpty())
        assertTrue(NativeScalarExportInfoBuilder.build(inventory(listOf(candidate())), 0).isEmpty())
        try {
            NativeScalarExportInfoBuilder.build(inventory(listOf(candidate())), 13)
            fail("Never allow unbounded INFO candidates")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun runtimeMenuIncludesNativeDiscoveryAsInfoButNeverAsPatch() {
        val digest = "ab".repeat(32)
        val analysis = FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = digest,
                sources = emptyList(),
                entries = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
            elfInventory = inventory(listOf(candidate())),
        )
        val prep = PatchPreparationPlan(
            artifactSha256 = digest,
            sourceShaVerified = true,
            preparedAtEpochMs = 1,
            targets = emptyList(),
            globalBlockers = emptyList(),
        )
        val root = Files.createTempDirectory("modkit-native-info-menu").toFile()
        try {
            val menu = RuntimeGameplayTestMenuBuilder.build(
                result = analysis,
                preparation = prep,
                analysisResultsRoot = root,
                stagingRoot = root,
            )
            assertEquals(0, menu.patchItemCount)
            assertEquals(1, menu.infoItemCount)
            assertEquals(RepackedRuntimeTestMenuItemMode.INFO, menu.items.single().mode)
            assertEquals("", menu.items.single().replacementHex)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun fullNativeInventoryKeepsFourVisibleHintsWithoutCreatingPatch() {
        val digest = "bc".repeat(32)
        val result = FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = digest, sources = emptyList(), entries = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
            elfInventory = inventory((0 until 25).map { candidate("hidden_$it") }),
        )
        val preparation = PatchPreparationPlan(
            artifactSha256 = digest, sourceShaVerified = true,
            preparedAtEpochMs = 1, targets = emptyList(), globalBlockers = emptyList(),
        )
        val root = Files.createTempDirectory("modkit-native-reserved-budget").toFile()
        try {
            val menu = RuntimeGameplayTestMenuBuilder.build(
                result, preparation, root, root,
            )
            assertEquals(4, menu.infoItemCount)
            assertEquals(0, menu.patchItemCount)
            assertTrue(menu.items.all { it.id.startsWith("info:native:") })
            assertTrue(menu.items.all { it.mode == RepackedRuntimeTestMenuItemMode.INFO })
            assertTrue(menu.items.all { it.replacementHex.isEmpty() })
        } finally {
            root.deleteRecursively()
        }
    }


}
