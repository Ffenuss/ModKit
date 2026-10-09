package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.patch.Il2CppNativeMutationDraftBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class JniAbiRecipeTest {
    private fun bytes(hex: String) = Il2CppNativeMutationDraftBuilder.parseHex(hex)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test fun armStateProofRejectsBranchesStoresCallsAndRegisterSideEffects() {
        assertTrue(JniAbiRecipe.inspect("armeabi-v7a", bytes("0700a0e31eff2fe1")).supported)
        for (instruction in listOf("000000ea", "000000eb", "000080e5", "0700a003", "0720a0e3", "0000a0e1", "1eff2fe0"))
            assertFalse(instruction, JniAbiRecipe.inspect("armeabi-v7a", bytes(instruction + "1eff2fe1")).supported)
        assertFalse(JniAbiRecipe.inspect("armeabi-v7a", bytes("0700a0e3")).supported)
        assertFalse(JniAbiRecipe.inspect("armeabi-v7a", bytes("07207047")).supported)
        assertFalse(JniAbiRecipe.inspect("armeabi-v7a", byteArrayOf(1, 2, 3)).supported)
        for (abi in listOf("x86", "x86_64", "unknown")) {
            assertFalse(JniAbiRecipe.inspect(abi, bytes("1eff2fe1")).supported)
            assertNull(JniAbiRecipe.encode(abi, "I", "9999"))
        }
    }

    @Test fun emittedArm32PrimitiveReturnsAreExportedForIndependentCpuExecution() {
        val cases = listOf("Z" to "1", "B" to "127", "S" to "9999", "C" to "9999", "I" to "9999",
            "J" to "4294977295", "F" to "2", "D" to "2", "I" to "-123456789", "D" to "2.5")
        val rows = cases.map { (type, value) ->
            val emitted = requireNotNull(JniAbiRecipe.encode("armeabi-v7a", type, value))
            assertTrue("Emitted body must fit the independent leaf proof", JniAbiRecipe.inspect("armeabi-v7a", emitted).supported)
            assertEquals(0, emitted.size % 4)
            "$type\t$value\t${hex(emitted)}"
        }
        assertEquals("0f0702e31eff2fe1", hex(JniAbiRecipe.encode("armeabi-v7a", "I", "9999")!!))
        File("build/native-arm32-verification.tsv").apply { parentFile.mkdirs(); writeText(rows.joinToString("\n")) }
    }

    @Test fun arm64StillPreservesLandingPadsAndTypedResults() {
        for (type in listOf("Z", "B", "S", "C", "I", "J", "F", "D")) {
            val code = JniAbiRecipe.encode("arm64-v8a", type, if (type == "Z") "1" else "2")!!
            assertTrue(JniAbiRecipe.inspect("arm64-v8a", code).supported)
        }
        val prefix = bytes("5f2403d5")
        assertArrayEquals(prefix, JniAbiRecipe.encode("arm64-v8a", "I", "9999", prefix)!!.take(4).toByteArray())
        assertEquals(prefix.toList(), JniAbiRecipe.inspect("arm64-v8a", prefix + bytes("e0e18452c0035fd6")).prefix.toList())
    }
}
