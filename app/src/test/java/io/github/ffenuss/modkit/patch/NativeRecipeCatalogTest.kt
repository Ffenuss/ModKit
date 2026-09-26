package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.domain.ProofLevel
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NativeRecipeCatalogTest {
    private val active = object : CancellationSignal { override fun isCancelled() = false }
    private val sha = "a".repeat(64)
    private val artifact = "base.apk:lib/arm64-v8a/libil2cpp.so"
    private fun withFixture(words: List<Long>, shared: Boolean = false,
        member: String = "get_DamageMultiplier", owner: String = "Game.Player",
        kind: Il2CppNativeReturnKind = Il2CppNativeReturnKind.FLOAT32,
        nextBoundary: Boolean = true,
        check: (AutoModRecipe, FastAnalysisResult, File) -> Unit) {
        val root = Files.createTempDirectory("native-recipes-").toFile()
        try {
            val code = words.flatMap { w -> (0..3).map { (w ushr (it * 8)).toByte() } }.toByteArray()
            val native = File(root, "$sha/il2cpp/native/arm64-v8a-libil2cpp.so")
            native.parentFile.mkdirs()
            native.writeBytes(ByteArray(16) + code + ByteArray(16))
            val index = NativeFunctionIndexWriter(File(root, "functions.idx"), active).use {
                it.add(16); if (shared) it.add(16); if (nextBoundary) it.add(16L + code.size)
                it.finish(1L + (if (shared) 1 else 0) + (if (nextBoundary) 1 else 0), true)
            }
            val token = 0x6000001L
            val target = EvidenceTarget("il2cpp:method:Assembly-CSharp.dll:6000001:Assembly-CSharp.dll",
                "unity_il2cpp", EvidenceTargetKind.METHOD, "$owner.$member", artifact, "arm64-v8a",
                owner, member, token, null, 0x100010, null, 16,
                ProofLevel.EXACT_BINARY, UserFindingStatus.CONFIRMED, emptyList(), emptyList())
            val binding = Il2CppMethodBinaryBinding(0, target.displayName, token, "Assembly-CSharp.dll",
                "Assembly-CSharp.dll", 0, 0x100010, 16, 1, kind, "fixture: exact scalar type")
            val evidence = Il2CppBinaryEvidence(artifact, 183, 8, 0, null, 0x2000, null, "fixture", emptyList(),
                listOf(binding), emptyList(), index)
            val result = FastAnalysisResult(index = ArtifactIndex(sha, listOf(ArtifactSource("base.apk", native.length(), sha)), emptyList()),
                routingPlan = EngineRoutingPlan(emptyList(), emptyList()), elapsedMs = 1,
                il2cppBinaryBinding = Il2CppBinaryBindingResult(listOf(evidence), 1, emptyList()),
                evidenceGraph = EvidenceGraph(sha, listOf(target)))
            val plan = PatchPreparationPlan(sha, true, 1, listOf(PreparedTarget(target,
                PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE, emptyList())), emptyList())
            check(NativeRecipeCatalog.create(result, plan, root, active).single(), result, root)
        } finally { root.deleteRecursively() }
    }

    @Test fun numericCandidateBecomesSelectableWithAFittingValueAndRealDraft() {
        withFixture(listOf(0xBD401000, 0xD65F03C0)) { recipe, result, root ->
            assertTrue(recipe.blocker, recipe.selectable)
            assertEquals(listOf("0", "0.5", "1", "2", "3", "5", "16", "31"), recipe.scalarValues.map { it.value })
            val chosen = recipe.withScalarValue("5")
            assertEquals("5", chosen.scalarValue)
            val draft = Il2CppNativeMutationDraftBuilder.build(result, chosen.native!!.targetId,
                chosen.native.replacementHex!!, root, File(root, "staging"))
            assertEquals("00 90 22 1E C0 03 5F D6", draft.replacementHex)
        }
    }
    @Test fun numericChoicesDoNotBypassASharedBody() {
        withFixture(listOf(0xBD401000, 0xD65F03C0), true) { recipe, _, _ ->
            assertFalse(recipe.selectable)
            assertTrue(recipe.blocker.orEmpty().contains("2 методов"))
        }
    }
    @Test fun shortFloatingHealthGetterDefaultsToPositiveValueThatActuallyFits() {
        val vectors = mutableListOf<String>()
        listOf(Il2CppNativeReturnKind.FLOAT32 to 0xBD401000L,
            Il2CppNativeReturnKind.FLOAT64 to 0xFD400800L).forEach { (kind, load) ->
            withFixture(listOf(load, 0xD65F03C0), member = "get_MaxHealthRaw",
                owner = "FlickEngine.CharacterSheetHealth", kind = kind) { recipe, result, root ->
                assertTrue(recipe.blocker, recipe.selectable)
                assertEquals("31", recipe.scalarValue)
                assertEquals("Базовый предел здоровья · значение 31", recipe.title)
                assertFalse(recipe.scalarValues.any { it.value == "999" })
                val draft = Il2CppNativeMutationDraftBuilder.build(result, recipe.native!!.targetId,
                    recipe.native.replacementHex!!, root, File(root, "staging"))
                assertEquals(8, Il2CppNativeMutationDraftBuilder.parseHex(draft.replacementHex).size)
                vectors += listOf("catalog-health-${kind.name}",
                    if (kind == Il2CppNativeReturnKind.FLOAT32) "f" else "d", "7", "7",
                    recipe.scalarValue, draft.originalHex, draft.replacementHex).joinToString("\t")
            }
        }
        // Execute the catalog's chosen bytes independently in CI, not a separately chosen value.
        File("build/native-catalog-verification.tsv").apply {
            parentFile.mkdirs(); writeText(vectors.joinToString("\n"))
        }
    }
    @Test fun largerHealthBodyKeepsPreferredValueAndBtiShortBodyKeepsLandingPad() {
        withFixture(listOf(0xBD401000, 0x1E202800, 0x1E202800, 0xD65F03C0), member = "get_MaxHealthRaw") { recipe, _, _ ->
            assertTrue(recipe.blocker, recipe.selectable)
            assertEquals("999", recipe.scalarValue)
        }
        withFixture(listOf(0xD503245F, 0xBD401000, 0xD65F03C0), member = "get_MaxHealthRaw") { recipe, _, _ ->
            assertTrue(recipe.blocker, recipe.selectable)
            assertEquals("31", recipe.scalarValue)
            assertTrue(recipe.native!!.replacementHex!!.startsWith("5F 24 03 D5 "))
        }
    }
    @Test fun numericChoicesDoNotRemoveCalls() {
        withFixture(listOf(0x94000400, 0x1E201000, 0xD65F03C0)) { recipe, _, _ ->
            assertFalse(recipe.selectable)
            assertTrue(recipe.blocker.orEmpty().contains("вызов"))
        }
    }
    @Test fun keepsTheBtiLandingPadInEveryOfferedPatch() {
        withFixture(listOf(0xD503245F, 0xBD401000, 0xD65F03C0)) { recipe, _, _ ->
            assertTrue(recipe.blocker, recipe.selectable)
            assertTrue(recipe.scalarValues.all { it.replacementHex.startsWith("5F 24 03 D5 ") })
        }
    }
    @Test fun aNamedFunctionDoesNotNeedTheCSharpPropertyPrefix() {
        withFixture(listOf(0xB9401800, 0xD65F03C0), member = "GetMaxHealth",
            kind = Il2CppNativeReturnKind.INTEGER) { recipe, _, _ ->
            assertTrue(recipe.blocker, recipe.selectable)
            assertEquals("Максимальное здоровье · значение 999", recipe.title)
        }
    }
    @Test fun semanticBooleanGetsAnExplicitChoiceAfterBodyProof() {
        withFixture(listOf(0xB9401808, 0x7100011F, 0x1A9FD7E0, 0xD65F03C0),
            member = "get_CanLevelUp", kind = Il2CppNativeReturnKind.BOOLEAN) { recipe, _, _ ->
            assertTrue(recipe.blocker, recipe.selectable)
            assertEquals(listOf("0", "1"), recipe.scalarValues.map { it.value })
            assertFalse("A scalar proof is not gameplay purpose", recipe.verification.purposeConfirmed)
        }
    }
    @Test fun displayHealthIsNotPresentedAsTheGameplayHealthCategory() {
        withFixture(listOf(0xB9401800, 0xD65F03C0), member = "get_MaxHealth",
            owner = "Game.DiskInfoBoxMain", kind = Il2CppNativeReturnKind.INTEGER) { recipe, _, _ ->
            assertTrue(recipe.blocker, recipe.selectable)
            assertEquals("Визуальные изменения", recipe.category)
            assertTrue(recipe.description.contains("отображения"))
        }
    }
    @Test fun identicalReplacementIsNotOfferedAsAModification() {
        withFixture(listOf(0x1E201000, 0xD65F03C0)) { recipe, _, _ ->
            assertTrue(recipe.blocker, recipe.selectable)
            assertFalse(recipe.scalarValues.any { it.value == "2" })
            assertEquals("3", recipe.scalarValue)
        }
    }
    @Test fun lastIndexedFunctionCannotPromiseAMultiInstructionDraft() {
        withFixture(listOf(0xBD401000, 0xD65F03C0), nextBoundary = false) { recipe, _, _ ->
            assertFalse(recipe.selectable)
            assertTrue(recipe.blocker.orEmpty().contains("Граница"))
        }
    }
    @Test fun booleanPresetAlreadyPresentIsNotOfferedAsAModification() {
        withFixture(listOf(0x52800020, 0xD65F03C0), member = "get_IsAlive",
            kind = Il2CppNativeReturnKind.BOOLEAN) { recipe, _, _ ->
            assertFalse(recipe.selectable)
            assertTrue(recipe.blocker.orEmpty().contains("уже возвращает"))
        }
    }
}
