package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.modification.*

/** Recreate scalar bytes from the common request at transfer time; reject stale cached bytes. */
object NativeRecipeRuntimeLowering {
    fun verify(recipe: AutoModRecipe, artifactSha256: String, targetId: String,
        returnKind: Il2CppNativeReturnKind, original: ByteArray, capacity: Int,
        evidence: BindingEvidence): ByteArray {
        val native = requireNotNull(recipe.native)
        val cached = Il2CppNativeMutationDraftBuilder.parseHex(requireNotNull(native.replacementHex))
        if (recipe.modificationRequests.isEmpty()) {
            require(recipe.scalarValue == null && recipe.scalarValues.isEmpty()) { "Scalar native recipe lacks its common request" }
            return cached // Legacy non-scalar recipes retain their existing separate proof path.
        }
        require(recipe.modificationRequests.size == 1 && native.targetId == targetId)
        val kind = when(returnKind) {
            Il2CppNativeReturnKind.BOOLEAN -> ScalarKind.BOOLEAN
            Il2CppNativeReturnKind.INTEGER -> ScalarKind.INT32
            Il2CppNativeReturnKind.FLOAT32 -> ScalarKind.FLOAT32
            Il2CppNativeReturnKind.FLOAT64 -> ScalarKind.FLOAT64
            else -> error("Native result type is not scalar")
        }
        val expected = ExistingModificationRequests.result(recipe.id, native.category.name,
            CodeFamily.NATIVE, artifactSha256, targetId, kind, requireNotNull(recipe.scalarValue), recipe.scalarMode)
        require(recipe.modificationRequests.single() == expected) { "Native common request differs from bound source/type/selection" }
        val adapter = NativeResultModificationAdapter(expected.target, original, capacity, evidence)
        val prepared = adapter.prepare(expected)
        require(prepared is AdapterPreparation.Prepared) { "Native common request cannot be lowered against current original bytes" }
        val generated = Il2CppNativeMutationDraftBuilder.parseHex(adapter.replacementHex(prepared))
        require(generated.contentEquals(cached)) { "Native replacement bytes differ from common request" }
        return generated
    }
}
