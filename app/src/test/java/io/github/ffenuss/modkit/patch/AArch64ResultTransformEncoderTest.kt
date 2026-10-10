package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import org.junit.Assert.*
import org.junit.Test

class AArch64ResultTransformEncoderTest {
    private fun bytes(vararg words: Long) = words.flatMap { w -> (0..3).map { (w ushr (it * 8)).toByte() } }.toByteArray()
    private fun rejected(code: ByteArray, kind: Il2CppNativeReturnKind = Il2CppNativeReturnKind.FLOAT32,
        factor: String = "2", capacity: Int = code.size) {
        try { AArch64ResultTransformEncoder.encodeHex(code, kind, factor, capacity); fail("Unsupported transform accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun retainsFieldReadAndUsesCallerSavedFpRegister() {
        val code = bytes(0xBD401000, 0xD65F03C0, 0xD503201F, 0xD503201F)
        assertEquals("00 10 40 BD 10 10 20 1E 00 08 30 1E C0 03 5F D6",
            AArch64ResultTransformEncoder.encodeHex(code, Il2CppNativeReturnKind.FLOAT32, "2", 16))
        val double = bytes(0xFD400800, 0xD65F03C0, 0xD503201F, 0xD503201F)
        assertEquals("00 08 40 FD 10 10 60 1E 00 08 70 1E C0 03 5F D6",
            AArch64ResultTransformEncoder.encodeHex(double, Il2CppNativeReturnKind.FLOAT64, "2", 16))
    }

    @Test fun retainsBtiAndCanUseNopsBeforeTheReturn() {
        val code = bytes(0xD503245F, 0xBD401000, 0xD503201F, 0xD503201F, 0xD65F03C0)
        val result = AArch64ResultTransformEncoder.encodeHex(code, Il2CppNativeReturnKind.FLOAT32, "5", 20)
        assertTrue(result.startsWith("5F 24 03 D5 00 10 40 BD "))
        assertEquals(20, Il2CppNativeMutationDraftBuilder.parseHex(result).size)
    }

    @Test fun rejectsCallsStoresBranchesAndAnIncorrectOwnerOrType() {
        val tail = longArrayOf(0xD65F03C0, 0xD503201F, 0xD503201F)
        for (word in listOf(0x94000000L, 0xBD001000L, 0x14000002L, 0xBD401020L, 0xBD401010L, 0xB9401000L))
            rejected(bytes(word, *tail))
        rejected(bytes(0xBD401000, *tail), Il2CppNativeReturnKind.FLOAT64)
        rejected(bytes(0xFD400800, *tail), Il2CppNativeReturnKind.FLOAT32)
        rejected(bytes(0xBD401000, *tail), Il2CppNativeReturnKind.INTEGER)
    }

    @Test fun neverConsumesNonNopTailOrCrossesTheIndexedBoundary() {
        rejected(bytes(0xBD401000, 0xD65F03C0))
        rejected(bytes(0xBD401000, 0xD65F03C0, 0x94000000, 0xD503201F))
        rejected(bytes(0xBD401000, 0xD65F03C0, 0xD503201F, 0xD503201F), capacity = 12)
        rejected(bytes(0xBD401000, 0x1E202800, 0xD65F03C0, 0xD503201F))
    }

    @Test fun computedTransformNeedsUnreachablePaddingAtEveryReturn() {
        // Conditional branch enters the NOPs following the first return: they are not free space.
        rejected(bytes(0xBD401000, 0x1E201001, 0x34000062, 0x1E212800, 0xD65F03C0,
            0xD503201F, 0xD503201F, 0xD65F03C0, 0xD503201F, 0xD503201F))
        // One branch has room, the other does not.
        rejected(bytes(0xBD401000, 0x1E201001, 0x340000A2, 0x1E212800, 0xD65F03C0,
            0xD503201F, 0xD503201F, 0x1E210800, 0xD65F03C0))
        // Wrong FP precision, call, state write and cycle after an otherwise valid entry load.
        for (word in listOf(0x1E612800L, 0x94000000L, 0xBD001000L, 0x14000000L))
            rejected(bytes(0xBD401000, word, 0xD65F03C0, 0xD503201F, 0xD503201F))
        val code = bytes(0xBD401000, 0x1E201001, 0x1E212800, 0xD65F03C0, 0xD503201F, 0xD503201F)
        rejected(code, capacity = 20)
        val patched = Il2CppNativeMutationDraftBuilder.parseHex(AArch64ResultTransformEncoder.encodeHex(
            code, Il2CppNativeReturnKind.FLOAT32, "2", code.size))
        assertEquals(code.take(12), patched.take(12))
        assertEquals(code.size, patched.size)
    }

    @Test fun rejectsIdentityUnrepresentableAndNonFiniteFactors() {
        val code = bytes(0xBD401000, 0xD65F03C0, 0xD503201F, 0xD503201F)
        listOf("0", "1", "-2", "17", "NaN", "Infinity", "0.1", "1.000000001", "1e-300").forEach {
            rejected(code, factor = it)
        }
    }
}
