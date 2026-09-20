package io.github.ffenuss.modkit.analysis.nativecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AArch64DisassemblerTest {
    @Test
    fun decodesSimpleBooleanReturn() {
        val code =
            byteArrayOf(
                0x20,
                0x00,
                0x80.toByte(),
                0x52,
                0xC0.toByte(),
                0x03,
                0x5F,
                0xD6.toByte(),
            )

        val result =
            AArch64Disassembler.disassemble(
                code = code,
                startAddress = 0x1000,
            )

        assertEquals(2, result.instructions.size)
        assertEquals(
            "movz",
            result.instructions[0].mnemonic,
        )
        assertEquals(
            "w0, #0x1",
            result.instructions[0].operands,
        )
        assertEquals(
            "ret",
            result.instructions[1].mnemonic,
        )
        assertTrue(
            result.summary.any {
                it.contains(
                    "возврат константы",
                )
            },
        )
    }

    @Test
    fun decodesDirectCallAndConditionalBranch() {
        val code =
            byteArrayOf(
                0x01,
                0x00,
                0x00,
                0x94.toByte(),
                0x40,
                0x00,
                0x00,
                0x54,
            )

        val result =
            AArch64Disassembler.disassemble(
                code = code,
                startAddress = 0x2000,
            )

        assertEquals("bl", result.instructions[0].mnemonic)
        assertEquals(
            "0x2004",
            result.instructions[0].operands,
        )
        assertEquals(
            "b.eq",
            result.instructions[1].mnemonic,
        )
        assertTrue(
            result.summary.any {
                it.contains("вызовов функций")
            },
        )
        assertTrue(
            result.summary.any {
                it.contains("Условных переходов")
            },
        )
    }

    @Test
    fun unknownInstructionStaysExplicitWord() {
        val result =
            AArch64Disassembler.disassemble(
                code =
                    byteArrayOf(
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                    ),
                startAddress = 0x3000,
            )

        assertEquals(1, result.unknownCount)
        assertEquals(
            ".word",
            result.instructions.single().mnemonic,
        )
        assertEquals(
            "0x00000000",
            result.instructions.single().operands,
        )
    }
}
