package io.github.ffenuss.modkit.analysis.nativecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AArch64MethodAnalyzerTest {
    @Test
    fun recognizesConstantReturnPrefix() {
        val disassembly =
            AArch64Disassembler.disassemble(
                code =
                    byteArrayOf(
                        0x20,
                        0x00,
                        0x80.toByte(),
                        0x52,
                        0xC0.toByte(),
                        0x03,
                        0x5F,
                        0xD6.toByte(),
                        0x1F,
                        0x20,
                        0x03,
                        0xD5.toByte(),
                    ),
                startAddress = 0x1000,
            )

        val analysis =
            AArch64MethodAnalyzer.analyze(
                disassembly,
            )

        assertEquals(
            AArch64MethodShape.RETURN_CONSTANT,
            analysis.shape,
        )
        assertEquals(
            "return 1;",
            analysis.pseudoCode,
        )
    }

    @Test
    fun recognizesDirectInstanceFieldGetter() {
        // ldr w0, [x0,#0x18] ; ret
        val disassembly =
            AArch64Disassembler.disassemble(
                code =
                    words(
                        0xB9401800L,
                        0xD65F03C0L,
                    ),
                startAddress = 0x2000,
            )

        val analysis =
            AArch64MethodAnalyzer.analyze(
                disassembly,
            )

        assertEquals(
            AArch64MethodShape.INSTANCE_FIELD_GETTER,
            analysis.shape,
        )
        assertEquals(
            0x18L,
            analysis.fieldOffset,
        )
        assertTrue(
            analysis.pseudoCode.contains(
                "field_0x18",
            ),
        )
    }

    @Test
    fun recognizesDirectInstanceFieldSetter() {
        // str w1, [x0,#0x1c] ; ret
        val disassembly =
            AArch64Disassembler.disassemble(
                code =
                    words(
                        0xB9001C01L,
                        0xD65F03C0L,
                    ),
                startAddress = 0x3000,
            )

        val analysis =
            AArch64MethodAnalyzer.analyze(
                disassembly,
            )

        assertEquals(
            AArch64MethodShape.INSTANCE_FIELD_SETTER,
            analysis.shape,
        )
        assertEquals(
            0x1cL,
            analysis.fieldOffset,
        )
    }

    private fun words(
        vararg words: Long,
    ): ByteArray =
        ByteArray(words.size * 4)
            .also {
                out ->
                words.forEachIndexed {
                        index,
                        word,
                    ->
                    val base = index * 4
                    out[base] =
                        word.toByte()
                    out[base + 1] =
                        (word ushr 8).toByte()
                    out[base + 2] =
                        (word ushr 16).toByte()
                    out[base + 3] =
                        (word ushr 24).toByte()
                }
            }

    @Test
    fun recognizesFloatFieldGetter() {
        val disassembly =
            AArch64Disassembler.disassemble(
                code =
                    words(
                        0xBD401000L,
                        0xD65F03C0L,
                    ),
                startAddress = 0x4000,
            )

        val analysis =
            AArch64MethodAnalyzer.analyze(
                disassembly,
            )

        assertEquals(
            AArch64MethodShape.INSTANCE_FIELD_GETTER,
            analysis.shape,
        )
        assertEquals(0x10L, analysis.fieldOffset)
        assertTrue(
            analysis.facts.any {
                it.contains("float")
            },
        )
        assertTrue(
            analysis.facts.any {
                it.contains("S0/D0")
            },
        )
    }

    @Test
    fun recognizesFloatFieldSetter() {
        val disassembly =
            AArch64Disassembler.disassemble(
                code =
                    words(
                        0xBD001000L,
                        0xD65F03C0L,
                    ),
                startAddress = 0x5000,
            )

        val analysis =
            AArch64MethodAnalyzer.analyze(
                disassembly,
            )

        assertEquals(
            AArch64MethodShape.INSTANCE_FIELD_SETTER,
            analysis.shape,
        )
        assertEquals(0x10L, analysis.fieldOffset)
        assertTrue(
            analysis.facts.any {
                it.contains("FP-аргумент")
            },
        )
    }

}
