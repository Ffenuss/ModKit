package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.modification.*
import org.junit.Assert.*
import org.junit.Test

class NativeRecipeRuntimeLoweringTest {
    private val sha = "a".repeat(64)
    private val source get() = Il2CppNativeMutationDraftBuilder.parseHex("00 10 40 BD C0 03 5F D6 1F 20 03 D5 1F 20 03 D5")
    private val evidence = BindingEvidence(true, true, true, true, true, true)
    private fun recipe(): AutoModRecipe {
        val request = ExistingModificationRequests.result("speed", GameplayModificationCategory.MOVEMENT.name,
            CodeFamily.NATIVE, sha, "method16", ScalarKind.FLOAT32, "2", ScalarRecipeMode.MULTIPLIER)
        val adapter = NativeResultModificationAdapter(request.target, source, 16, evidence)
        val hex = adapter.replacementHex(adapter.prepare(request) as AdapterPreparation.Prepared)
        return AutoModRecipe("speed", "Speed", "Speed ×2", "", "Player", native = GameplayModificationOpportunity(
            "speed", GameplayModificationCategory.MOVEMENT, "Speed", "method16", "Player.getSpeed",
            GameplayMutationAction.FORCE_TWO, hex, true, null, "Verified", GameplayModificationConfidence.STRONG_NUMERIC_CANDIDATE),
            scalarValue = "2", scalarMode = ScalarRecipeMode.MULTIPLIER, modificationRequests = listOf(request))
    }
    private fun verify(recipe: AutoModRecipe, source: ByteArray = this.source, proof: BindingEvidence = evidence) =
        NativeRecipeRuntimeLowering.verify(recipe, sha, "method16", Il2CppNativeReturnKind.FLOAT32, source, source.size, proof)

    @Test fun transferRegeneratesBytesAndDoesNotChangeOriginalMemory() {
        val source = this.source; val snapshot = source.copyOf(); val recipe = recipe()
        assertArrayEquals(Il2CppNativeMutationDraftBuilder.parseHex(recipe.native!!.replacementHex!!), verify(recipe, source))
        assertArrayEquals(snapshot, source)
    }
    @Test fun staleScalarCachedBytesTargetsOrProofCannotReachTransfer() {
        val recipe = recipe(); val request = recipe.modificationRequests.single()
        for (bad in listOf(recipe.copy(scalarValue = "5"), recipe.copy(modificationRequests = emptyList()),
            recipe.copy(modificationRequests = listOf(request.copy(target = request.target.copy(artifactSha256 = "b".repeat(64))))),
            recipe.copy(native = recipe.native!!.copy(replacementHex = "C0 03 5F D6")))) {
            assertThrows(IllegalArgumentException::class.java) { verify(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { verify(recipe, proof = evidence.copy(originalVerified = false)) }
        assertThrows(IllegalArgumentException::class.java) { verify(recipe, Il2CppNativeMutationDraftBuilder.parseHex("00 10 40 BD 00 00 00 94 C0 03 5F D6 1F 20 03 D5")) }
    }
}
