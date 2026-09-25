package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AArch64ScalarReturnEncoderTest {
    @Test
    fun integerOneMatchesKnownArm64ReturnBody() {
        assertEquals(
            "20 00 80 D2 C0 03 5F D6",
            AArch64ScalarReturnEncoder.encodeHex(
                Il2CppNativeReturnKind.INTEGER,
                "1",
            ),
        )
    }

    @Test
    fun largeIntegerUsesAlignedMoveWideSequence() {
        val hex =
            AArch64ScalarReturnEncoder.encodeHex(
                Il2CppNativeReturnKind.INTEGER,
                "1311768467463790320",
            )
        val bytes =
            Il2CppNativeMutationDraftBuilder
                .parseHex(hex)

        assertEquals(0, bytes.size % 4)
        assertTrue(bytes.size in 8..20)
        assertEquals(
            listOf(
                0xC0,
                0x03,
                0x5F,
                0xD6,
            ),
            bytes.takeLast(4)
                .map {
                    it.toInt() and 0xff
                },
        )
    }

    @Test
    fun arbitraryFloatAndDoubleBodiesStayInstructionAligned() {
        val floatBytes =
            Il2CppNativeMutationDraftBuilder.parseHex(
                AArch64ScalarReturnEncoder
                    .encodeHex(
                        Il2CppNativeReturnKind.FLOAT32,
                        "2.5",
                    ),
            )
        val doubleBytes =
            Il2CppNativeMutationDraftBuilder.parseHex(
                AArch64ScalarReturnEncoder
                    .encodeHex(
                        Il2CppNativeReturnKind.FLOAT64,
                        "-123.75",
                    ),
            )

        assertEquals(0, floatBytes.size % 4)
        assertEquals(0, doubleBytes.size % 4)
        assertEquals(8, floatBytes.size) // 2.5 is exactly representable by FMOV immediate.
        assertTrue(doubleBytes.size >= 12)
    }

    @Test
    fun refusesUnsupportedReturnKind() {
        val failure =
            runCatching {
                AArch64ScalarReturnEncoder
                    .encodeHex(
                        Il2CppNativeReturnKind.BOOLEAN,
                        "1",
                    )
            }.exceptionOrNull()

        assertTrue(
            failure is IllegalStateException,
        )
    }

    @Test fun shortFloatRecipesFitEightByteGetters() {
        assertEquals("00 10 20 1E C0 03 5F D6", AArch64ScalarReturnEncoder.encodeHex(Il2CppNativeReturnKind.FLOAT32, "2"))
        assertEquals("00 10 60 1E C0 03 5F D6", AArch64ScalarReturnEncoder.encodeHex(Il2CppNativeReturnKind.FLOAT64, "2"))
        assertEquals("E0 03 27 1E C0 03 5F D6", AArch64ScalarReturnEncoder.encodeHex(Il2CppNativeReturnKind.FLOAT32, "0"))
    }
}
