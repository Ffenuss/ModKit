package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.modification.*
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Test

class DexRecipeRuntimeSelectionTest {
    private fun recipes(type: String = "F"): List<AutoModRecipe> {
        val method = ImmutableMethod("Lgame/Player;", "getRunSpeed", emptyList(), type, AccessFlags.PUBLIC.value,
            emptySet(), emptySet(), ImmutableMethodImplementation(if (type == "D") 3 else 2,
                listOf(if (type == "D") ImmutableInstruction51l(Opcode.CONST_WIDE, 0, 1.25.toRawBits())
                    else ImmutableInstruction31i(Opcode.CONST, 0, if (type == "F") 1.25f.toRawBits() else 7),
                    ImmutableInstruction11x(if (type == "D") Opcode.RETURN_WIDE else Opcode.RETURN, 0)), emptyList(), emptyList()))
        val clazz = ImmutableClassDef("Lgame/Player;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), null,
            emptySet(), emptyList(), listOf(method))
        val store = MemoryDataStore()
        val bytes = try { DexPool.writeTo(store, ImmutableDexFile(Opcodes.getDefault(), listOf(clazz))); store.data.copyOf(store.size) }
            finally { store.close() }
        return DexRecipeCatalog.create(DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, AtomicCancellationSignal()))
    }

    @Test fun catalogSeparatesConstantAndMultiplierAndPropagatesSelectedWidthAndCoefficient() {
        for (type in listOf("F", "D")) {
            val recipes = recipes(type)
            assertEquals(2, recipes.size)
            val value = recipes.single { it.scalarMode == ScalarRecipeMode.VALUE }
            val multiplier = recipes.single { it.scalarMode == ScalarRecipeMode.MULTIPLIER }
            assertNotEquals(value.id, multiplier.id)
            assertTrue(RuntimeRecipeSelectionPolicy.supports(multiplier))
            assertNull(DexRecipeRuntimeSelection.create(value).single().resultTransform)
            val chosen = multiplier.withScalarValue("5")
            val selected = DexRecipeRuntimeSelection.create(chosen).single()
            assertEquals(ValueOperation.Multiply(if(type == "F") ScalarValue.Single(5f) else ScalarValue.DoublePrecision(5.0)), selected.resultTransform)
            assertEquals(selected.resultTransform, selected.commonRequest!!.operation)
        }
        assertFalse(recipes("I").any { it.scalarMode == ScalarRecipeMode.MULTIPLIER })
    }

    @Test fun missingOrChangedRequestsAndStaleDisplayCoefficientFailBeforeInstrumentation() {
        val recipe = recipes().single { it.scalarMode == ScalarRecipeMode.MULTIPLIER }
        val request = recipe.modificationRequests.single()
        val invalid = listOf(recipe.copy(modificationRequests = emptyList()),
            recipe.copy(modificationRequests = listOf(request, request)),
            recipe.copy(modificationRequests = listOf(request.copy(target = request.target.copy(artifactSha256 = "b".repeat(64))))),
            recipe.copy(scalarValue = "5"), recipe.copy(scalarMode = ScalarRecipeMode.VALUE),
            recipe.copy(modificationRequests = listOf(request.copy(point = InterceptionPoint.ARGUMENT, argumentIndex = 0))))
        for (bad in invalid) {
            assertFalse(RuntimeRecipeSelectionPolicy.supports(bad))
            assertThrows(IllegalArgumentException::class.java) { DexRecipeRuntimeSelection.create(bad) }
        }
    }
}
