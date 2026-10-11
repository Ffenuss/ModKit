package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.patch.DexLocalPatchEngine
import io.github.ffenuss.modkit.patch.DexLocalAction
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
            listOf("jni-parameter-int-ammo", "i", "0", "12", "9999", "40280011c0035fd6",
                AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("I")!!, "9999").replace(" ", ""), "2"),
            listOf("jni-parameter-long-ammo", "i", "0", "22", "9999", "40500011c0035fd6",
                AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("I")!!, "9999").replace(" ", ""), "4294967298"),
            listOf("jni-byte-energy", "b", "-7", "-7", "127", "c0008012c0035fd6",
                AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("B")!!,
                    JniSpaceRecipeScanner.replacementValue("B", DexLocalAction.INT_9999)).replace(" ", "")),
            listOf("jni-short-health", "s", "-300", "-300", "9999", "60258012c0035fd6",
                AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("S")!!,
                    JniSpaceRecipeScanner.replacementValue("S", DexLocalAction.INT_9999)).replace(" ", "")),
            listOf("jni-char-ammo", "c", "50000", "50000", "9999", "006a9852c0035fd6",
                AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("C")!!,
                    JniSpaceRecipeScanner.replacementValue("C", DexLocalAction.INT_9999)).replace(" ", "")),
        )
        File("build/native-jni-verification.tsv").apply { parentFile.mkdirs(); writeText(rows.joinToString("\n") { it.joinToString("\t") }) }
    }
    @Test fun primitiveGetterArgumentsRemainTypedAndBounded() {
        assertTrue(JniSpaceRecipeScanner.supportedParameters(emptyList()))
        assertTrue(JniSpaceRecipeScanner.supportedParameters(listOf("Z", "B", "C", "S", "I", "J", "F", "D")))
        for (type in listOf("V", "[I", "[D", "Ljava/lang/String;", "Ldev/game/Player;", "", "II"))
            assertFalse(JniSpaceRecipeScanner.supportedParameters(listOf(type)))
        assertFalse(JniSpaceRecipeScanner.supportedParameters(List(9) { "I" }))
        val intAmmo = JniSpaceRecipeScanner.Method("Ldev/game/Player;", "getAmmo", "I", listOf("I"))
        val longAmmo = intAmmo.copy(parameters = listOf("J"))
        val declarations = listOf(intAmmo, longAmmo)
        val exports = setOf(intAmmo.longName, longAmmo.longName)
        assertEquals(intAmmo.longName, JniSpaceRecipeScanner.exportName(intAmmo, declarations, exports))
        assertEquals(longAmmo.longName, JniSpaceRecipeScanner.exportName(longAmmo, declarations, exports))
        assertNotEquals(intAmmo.key, longAmmo.key)
        assertNull(JniSpaceRecipeScanner.exportName(intAmmo, declarations, exports + intAmmo.shortName))
        val ambiguousReturn = intAmmo.copy(result = "J")
        assertEquals(intAmmo.longName, ambiguousReturn.longName)
        assertNull(JniSpaceRecipeScanner.exportName(intAmmo, listOf(intAmmo, ambiguousReturn), exports))
        assertNull(JniSpaceRecipeScanner.exportName(ambiguousReturn, listOf(intAmmo, ambiguousReturn), exports))
        assertNull(JniSpaceRecipeScanner.exportName(intAmmo, listOf(intAmmo, intAmmo), exports))
    }
    @Test fun wideJniGettersUseTheirDeclaredReturnAbi() {
        assertNotNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/game/Player;", "getStamina", "J"))
        assertNotNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/game/Player;", "getMoveSpeed", "D"))
        assertEquals(Il2CppNativeReturnKind.INTEGER, JniSpaceRecipeScanner.returnKind("J"))
        assertEquals(Il2CppNativeReturnKind.FLOAT64, JniSpaceRecipeScanner.returnKind("D"))
        assertEquals(Il2CppNativeReturnKind.FLOAT32, JniSpaceRecipeScanner.returnKind("F"))
        assertEquals("00 10 60 1E C0 03 5F D6", AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("D")!!, "2"))
        assertEquals("E0 E1 84 D2 C0 03 5F D6", AArch64ScalarReturnEncoder.encodeHex(JniSpaceRecipeScanner.returnKind("J")!!, "9999"))
        for (type in listOf("V", "[J", "Ljava/lang/Double;")) assertNull(JniSpaceRecipeScanner.returnKind(type))
        assertNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/server/Player;", "getMoveSpeed", "D"))
    }
    @Test fun narrowJniDefaultsFitTheirDeclaredResultAndRejectMismatchedActions() {
        for ((type, expected) in listOf("B" to "127", "S" to "9999", "C" to "9999")) {
            assertEquals(Il2CppNativeReturnKind.INTEGER, JniSpaceRecipeScanner.returnKind(type))
            assertEquals(expected, JniSpaceRecipeScanner.replacementValue(type, DexLocalAction.INT_9999))
            assertEquals("99", JniSpaceRecipeScanner.replacementValue(type, DexLocalAction.INT_99))
            assertNotNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/game/Player;", "getHealth", type))
            assertNull(DexLocalPatchEngine.nativeGameplayKind("Ldev/server/Player;", "getHealth", type))
            assertThrows(IllegalArgumentException::class.java) { JniSpaceRecipeScanner.replacementValue(type, DexLocalAction.FLOAT_2) }
            assertThrows(IllegalArgumentException::class.java) { JniSpaceRecipeScanner.replacementValue(type, DexLocalAction.TRUE) }
        }
        assertEquals("1", JniSpaceRecipeScanner.replacementValue("Z", DexLocalAction.TRUE))
        assertEquals("0", JniSpaceRecipeScanner.replacementValue("Z", DexLocalAction.FALSE))
        for (type in listOf("Z", "F", "D", "V", "[B", "Ljava/lang/Byte;"))
            assertThrows(IllegalArgumentException::class.java) { JniSpaceRecipeScanner.replacementValue(type, DexLocalAction.INT_9999) }
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
