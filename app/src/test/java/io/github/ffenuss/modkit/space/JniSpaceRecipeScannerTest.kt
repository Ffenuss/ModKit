package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.patch.DexLocalPatchEngine
import org.junit.Assert.*
import org.junit.Test

class JniSpaceRecipeScannerTest {
    @Test fun jniEncodingAndOverloadResolutionAreExact() {
        assertEquals("a_b_1c_2_3_00424_0d83d_0de00", JniSpaceRecipeScanner.mangle("a/b_c;[\u0424\uD83D\uDE00"))
        val zero = JniSpaceRecipeScanner.Method("Ldev/game/Player;", "getHealth", "I", emptyList())
        val other = zero.copy(parameters = listOf("I"))
        assertEquals(zero.shortName, JniSpaceRecipeScanner.exportName(zero, listOf(zero), setOf(zero.shortName)))
        assertNull(JniSpaceRecipeScanner.exportName(zero, listOf(zero, other), setOf(zero.shortName, zero.longName)))
        assertEquals(zero.longName, JniSpaceRecipeScanner.exportName(zero, listOf(zero, other), setOf(zero.longName)))
        assertNull(JniSpaceRecipeScanner.exportName(zero, listOf(zero), setOf(other.longName)))
    }
    @Test fun untypedOrUnrelatedMethodsCannotBecomeRecipes() {
        assertNotNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/game/Player;", "getHealth", "I"))
        assertNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/game/Player;", "getHealth", "Ljava/lang/Object;"))
        assertNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/server/Player;", "getHealth", "I"))
        assertNull(DexLocalPatchEngine.nativeGameplayKind("Landroid/view/Player;", "getHealth", "I"))
        assertNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/game/Player;", "getServerHealth", "I"))
        assertNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/game/Player;", "isPremium", "Z"))
    }
}
