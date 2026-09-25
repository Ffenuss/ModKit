package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.domain.ProofLevel
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItemMode
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SelectedRuntimeMenuBuilderTest {
    @Test fun singleVerifiedNativeRecipeProducesInitiallyOffReversibleBytes() {
        val root = Files.createTempDirectory("modkit-selected-runtime").toFile()
        try {
            val model = fixture(root)
            val menu = SelectedRuntimeMenuBuilder.build(
                model.result, listOf(model.recipe), model.analysisRoot, neverCancelled(),
            )
            assertEquals(1, menu.items.size)
            assertEquals(1, menu.patchItemCount)
            val switch = menu.items.single()
            assertEquals(RepackedRuntimeTestMenuItemMode.PATCH, switch.mode)
            assertEquals("libil2cpp.so", switch.moduleName)
            assertEquals(0x1000L, switch.binaryVirtualAddress)
            assertEquals("1F 20 03 D5 1F 20 03 D5", switch.originalHex)
            assertEquals("20 00 80 D2 C0 03 5F D6", switch.replacementHex)
            assertFalse(switch.originalHex == switch.replacementHex)
            // Producing a runtime menu must not mutate the extracted library.
            assertArrayEquals(model.original, model.library.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidOrRepeatedSelectionFailsClosed() {
        val root = Files.createTempDirectory("modkit-runtime-negative").toFile()
        try {
            val model = fixture(root)
            assertThrows(IllegalArgumentException::class.java) {
                SelectedRuntimeMenuBuilder.build(
                    model.result, listOf(model.recipe, model.recipe), model.analysisRoot, neverCancelled(),
                )
            }
            val sensitive = model.recipe.copy(native = model.recipe.native!!.copy(
                category = GameplayModificationCategory.OWNER_ENTITLEMENT,
            ))
            assertFalse(RuntimeRecipeSelectionPolicy.supports(sensitive))
            assertThrows(IllegalArgumentException::class.java) {
                SelectedRuntimeMenuBuilder.build(
                    model.result, listOf(sensitive), model.analysisRoot, neverCancelled(),
                )
            }
            val staleTarget = model.recipe.copy(native = model.recipe.native!!.copy(targetId = "missing"))
            assertThrows(IllegalArgumentException::class.java) {
                SelectedRuntimeMenuBuilder.build(
                    model.result, listOf(staleTarget), model.analysisRoot, neverCancelled(),
                )
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun overlappingPatchRangesAreRejected() {
        val root = Files.createTempDirectory("modkit-overlapping-runtime").toFile()
        try {
            val model = fixture(root)
            val alias = model.recipe.copy(id = "second", title = "Other recipe")
            assertThrows(IllegalArgumentException::class.java) {
                SelectedRuntimeMenuBuilder.build(
                    model.result, listOf(model.recipe, alias), model.analysisRoot, neverCancelled(),
                )
            }
        } finally { root.deleteRecursively() }
    }

    private data class Fixture(
        val result: FastAnalysisResult,
        val recipe: AutoModRecipe,
        val analysisRoot: File,
        val library: File,
        val original: ByteArray,
    )

    private fun fixture(root: File): Fixture {
        val sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val artifact = "split_config.arm64_v8a.apk:lib/arm64-v8a/libil2cpp.so"
        val token = 0x06000001L
        val id = "il2cpp:method:Assembly-CSharp.dll:6000001:Assembly-CSharp.dll"
        val target = EvidenceTarget(
            id = id,
            runtimeId = "unity_il2cpp",
            kind = EvidenceTargetKind.METHOD,
            displayName = "Game.Player.get_Health",
            artifact = artifact,
            abi = "arm64-v8a",
            declaringType = "Game.Player",
            memberName = "get_Health",
            metadataToken = token,
            rva = null,
            binaryVirtualAddress = 0x1000,
            runtimeVirtualAddress = null,
            fileOffset = 0x100,
            proofLevel = ProofLevel.EXACT_BINARY,
            userStatus = UserFindingStatus.CONFIRMED,
            blockers = emptyList(),
            facts = emptyList(),
        )
        val binding = Il2CppMethodBinaryBinding(
            methodIndex = 1,
            managedIdentity = target.displayName,
            metadataToken = token,
            imageName = "Assembly-CSharp.dll",
            moduleName = "Assembly-CSharp.dll",
            slotIndex = 0,
            functionVirtualAddress = 0x1000,
            functionFileOffset = 0x100,
            returnTypeIndex = 1,
            returnKind = Il2CppNativeReturnKind.INTEGER,
            returnTypeProof = "test",
        )
        val indexFile = File(root, "native-index.bin")
        val index = NativeFunctionIndexWriter(indexFile, neverCancelled()).use { writer ->
            writer.add(0x100)
            writer.add(0x120)
            writer.finish(2, true)
        }
        val result = FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = sha,
                sources = emptyList(), entries = emptyList(),
                detectedAbis = setOf("arm64-v8a"), runtimeProfiles = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
            il2cppBinaryBinding = Il2CppBinaryBindingResult(
                evidence = listOf(Il2CppBinaryEvidence(
                    libraryEntry = artifact, machine = 183, pointerSize = 8,
                    relativeRelocationCount = 1,
                    codeRegistrationVirtualAddress = null,
                    metadataRegistrationVirtualAddress = 0x1000,
                    codegenRegisterVirtualAddress = null,
                    moduleArrayDiscovery = "TEST",
                    modules = emptyList(),
                    bindings = listOf(binding),
                    blockers = emptyList(),
                    functionIndex = index,
                )),
                exactBindingCount = 1, warnings = emptyList(),
            ),
            evidenceGraph = EvidenceGraph(artifactSha256 = sha, targets = listOf(target)),
        )
        val original = ByteArray(0x400)
        for (position in 0x100 until 0x120 step 4) {
            byteArrayOf(0x1f, 0x20, 0x03, 0xd5.toByte())
                .copyInto(original, position)
        }
        val analysisRoot = File(root, "analysis-results")
        val library = File(analysisRoot, "$sha/il2cpp/native/arm64-v8a-libil2cpp.so")
        library.parentFile!!.mkdirs()
        RandomAccessFile(library, "rw").use { it.write(original) }
        val opportunity = GameplayModificationOpportunity(
            id = "health",
            category = GameplayModificationCategory.SURVIVABILITY,
            title = "Health 999",
            targetId = target.id,
            targetDisplayName = target.displayName,
            action = GameplayMutationAction.FORCE_SCALAR_DEFAULT,
            replacementHex = "20 00 80 D2 C0 03 5F D6",
            selectable = true,
            blocker = null,
            evidenceSummary = "Exact binary binding",
            confidence = GameplayModificationConfidence.STRONG_NUMERIC_CANDIDATE,
        )
        val recipe = AutoModRecipe(
            id = "health", category = "Здоровье", title = "999 HP",
            description = "Test", targetLabel = "Game.Player",
            native = opportunity,
        )
        return Fixture(result, recipe, analysisRoot, library, original)
    }

    private fun neverCancelled() = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
}
