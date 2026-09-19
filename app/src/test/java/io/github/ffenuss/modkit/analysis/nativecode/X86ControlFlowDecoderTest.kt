package io.github.ffenuss.modkit.analysis.nativecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class X86ControlFlowDecoderTest {
    @Test
    fun decodesRel32CallAndRel8ConditionalBranch() {
        val call = X86ControlFlowDecoder.decodeAtKnownBoundary(
            code = byteArrayOf(
                0xE8.toByte(),
                0x05, 0x00, 0x00, 0x00,
            ),
            offset = 0,
            address = 0x1000,
            architecture = NativeArchitecture.X86_64,
        )
        val callInsn = requireNotNull(call.instruction)
        assertEquals(ControlFlowKind.CALL, callInsn.kind)
        assertEquals(5, callInsn.size)
        assertEquals(0x100AL, callInsn.targetAddress)

        val branch = X86ControlFlowDecoder.decodeAtKnownBoundary(
            code = byteArrayOf(
                0x75, 0xFE.toByte(),
            ),
            offset = 0,
            address = 0x2000,
            architecture = NativeArchitecture.X86,
        )
        val branchInsn = requireNotNull(branch.instruction)
        assertEquals(ControlFlowKind.CONDITIONAL_BRANCH, branchInsn.kind)
        assertEquals(0x2000L, branchInsn.targetAddress)
        assertEquals(5, branchInsn.conditionCode)
    }

    @Test
    fun decodesRegisterIndirectCallWithRexExtension() {
        // REX.B + FF /2, ModRM 11 010 011 => CALL r11.
        val decoded = X86ControlFlowDecoder.decodeAtKnownBoundary(
            code = byteArrayOf(
                0x41, 0xFF.toByte(), 0xD3.toByte(),
            ),
            offset = 0,
            address = 0x3000,
            architecture = NativeArchitecture.X86_64,
        )

        val insn = requireNotNull(decoded.instruction)
        assertEquals(ControlFlowKind.INDIRECT_CALL, insn.kind)
        assertEquals(11, insn.targetRegister)
        assertEquals(3, insn.size)
        assertNull(decoded.reason)
    }

    @Test
    fun ripRelativeMemoryIndirectCallStaysUnresolved() {
        // FF /2, mod=00 rm=101 => [RIP+disp32] in x86-64.
        val decoded = X86ControlFlowDecoder.decodeAtKnownBoundary(
            code = byteArrayOf(
                0xFF.toByte(), 0x15, 0x78, 0x56, 0x34, 0x12,
            ),
            offset = 0,
            address = 0x4000,
            architecture = NativeArchitecture.X86_64,
        )

        val insn = requireNotNull(decoded.instruction)
        assertEquals(ControlFlowKind.INDIRECT_CALL, insn.kind)
        assertNull(insn.targetAddress)
        assertNull(insn.targetRegister)
        assertEquals(6, insn.size)
        assertTrue(
            requireNotNull(decoded.reason).contains("relocation/register-flow"),
        )
    }

    @Test
    fun sibDisplacementLengthIsDecodedWithoutInventingTarget() {
        // FF /4 with ModRM mod=10 rm=100 + SIB + disp32.
        val decoded = X86ControlFlowDecoder.decodeAtKnownBoundary(
            code = byteArrayOf(
                0xFF.toByte(),
                0xA4.toByte(),
                0x8D.toByte(),
                0x78, 0x56, 0x34, 0x12,
            ),
            offset = 0,
            address = 0x5000,
            architecture = NativeArchitecture.X86,
        )

        val insn = requireNotNull(decoded.instruction)
        assertEquals(ControlFlowKind.INDIRECT_BRANCH, insn.kind)
        assertEquals(7, insn.size)
        assertNull(insn.targetAddress)
    }

    @Test
    fun truncatedDirectCallFailsClosed() {
        val decoded = X86ControlFlowDecoder.decodeAtKnownBoundary(
            code = byteArrayOf(
                0xE8.toByte(), 0x01,
            ),
            offset = 0,
            address = 0x6000,
            architecture = NativeArchitecture.X86_64,
        )

        assertNull(decoded.instruction)
        assertNull(decoded.instructionSize)
        assertTrue(requireNotNull(decoded.reason).contains("truncated"))
    }

    @Test
    fun unsupportedOpcodeDoesNotProvideFakeInstructionLength() {
        val decoded = X86ControlFlowDecoder.decodeAtKnownBoundary(
            code = byteArrayOf(
                0x48, 0x89.toByte(), 0xD8.toByte(),
            ),
            offset = 0,
            address = 0x7000,
            architecture = NativeArchitecture.X86_64,
        )

        assertNull(decoded.instruction)
        assertNull(decoded.instructionSize)
    }
}
