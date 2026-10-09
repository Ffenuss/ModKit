package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.patch.DexLocalPatchEngine
import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.patch.AArch64ScalarReturnEncoder
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class JniSpaceRecipeScannerTest {
    @Test fun exportActualWideJniEncoderBytesForIndependentCpuVerification() {
        val rows = listOf(
            listOf("jni-long-stamina", "j", "11", "11", "9999", "600180d2c0035fd6",
                AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("J")!!, "9999").replace(" ", "")),
            listOf("jni-double-speed", "d", "1", "1", "2", "00106e1ec0035fd6",
                AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("D")!!, "2").replace(" ", "")),
        )
        File("build/native-jni-verification.tsv").apply { parentFile.mkdirs(); writeText(rows.joinToString("\n") { it.joinToString("\t") }) }
    }
    @Test fun wideJniGettersUseTheirDeclaredReturnAbi() {
        assertNotNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/game/Player;", "getStamina", "J"))
        assertNotNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/game/Player;", "getMoveSpeed", "D"))
        assertEquals(Il2CppNativeReturnKind.INTEGER, JniSpaceRecipeScanner.returnKind("J"))
        assertEquals(Il2CppNativeReturnKind.FLOAT64, JniSpaceRecipeScanner.returnKind("D"))
        assertEquals(Il2CppNativeReturnKind.FLOAT32, JniSpaceRecipeScanner.returnKind("F"))
        assertEquals("00 10 60 1E C0 03 5F D6", AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("D")!!, "2"))
        assertEquals("E0 E1 84 D2 C0 03 5F D6", AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("J")!!, "9999"))
        for (type in listOf("V", "B", "S", "C", "[J", "Ljava/lang/Double;")) assertNull(JniSpaceRecipeScanner.returnKind(type))
        assertNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/server/Player;", "getMoveSpeed", "D"))
    }
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
