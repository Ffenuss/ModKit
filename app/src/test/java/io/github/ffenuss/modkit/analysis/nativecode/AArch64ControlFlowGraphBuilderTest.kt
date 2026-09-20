package io.github.ffenuss.modkit.analysis.nativecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AArch64ControlFlowGraphBuilderTest {
    @Test
    fun buildsTwoWayConditionalFlow() {
        // cbz w0, +8 ; movz w0,#1 ; ret
        val disassembly =
            AArch64Disassembler.disassemble(
                code =
                    words(
                        0x34000040L,
                        0x52800020L,
                        0xD65F03C0L,
                    ),
                startAddress = 0x1000,
            )

        val graph =
            AArch64ControlFlowGraphBuilder
                .build(disassembly)

        assertEquals(3, graph.blocks.size)
        assertEquals(3, graph.edges.size)
        assertTrue(
            graph.edges.any {
                it.kind ==
                    AArch64ControlFlowEdgeKind
                        .CONDITIONAL_TRUE &&
                    it.toAddress == 0x1008L
            },
        )
        assertTrue(
            graph.edges.any {
                it.kind ==
                    AArch64ControlFlowEdgeKind
                        .CONDITIONAL_FALSE &&
                    it.toAddress == 0x1004L
            },
        )
        assertTrue(
            graph.completeInsideWindow,
        )
    }

    @Test
    fun marksExternalDirectBranch() {
        val disassembly =
            AArch64Disassembler.disassemble(
                code =
                    words(
                        0x14000004L,
                        0xD65F03C0L,
                    ),
                startAddress = 0x2000,
            )

        val graph =
            AArch64ControlFlowGraphBuilder
                .build(disassembly)

        assertFalse(
            graph.completeInsideWindow,
        )
        assertTrue(
            0x2010L in
                graph.externalTargets,
        )
    }

    private fun words(
        vararg values: Long,
    ): ByteArray =
        ByteArray(values.size * 4)
            .also {
                out ->
                values.forEachIndexed {
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
}
