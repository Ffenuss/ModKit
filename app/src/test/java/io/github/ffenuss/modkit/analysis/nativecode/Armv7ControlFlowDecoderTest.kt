package io.github.ffenuss.modkit.analysis.nativecode

import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Armv7ControlFlowDecoderTest {
    @Test
    fun a32DecodesDirectCallConditionalBranchAndReturn() {
        val code = byteArrayOf(
            // BL +0 => target current+8
            0x00, 0x00, 0x00, 0xEB.toByte(),
            // BEQ +0 => target current+8
            0x00, 0x00, 0x00, 0x0A,
            // BX LR
            0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte(),
        )

        val result = Armv7ControlFlowDecoder.scanA32(
            code = code,
            startAddress = 0x1000,
            cancellation = NeverCancelled,
        )

        assertEquals(3, result.instructionsScanned)
        assertEquals(3, result.controlFlow.size)

        val call = result.controlFlow[0]
        assertEquals(ControlFlowKind.CALL, call.kind)
        assertEquals(0x1008L, call.targetAddress)
        assertEquals(NativeArchitecture.ARMV7_A32, call.targetArchitecture)

        val branch = result.controlFlow[1]
        assertEquals(ControlFlowKind.CONDITIONAL_BRANCH, branch.kind)
        assertEquals(0x100cL, branch.targetAddress)
        assertEquals(0, branch.conditionCode)

        val ret = result.controlFlow[2]
        assertEquals(ControlFlowKind.RETURN, ret.kind)
        assertEquals(14, ret.targetRegister)
    }

    @Test
    fun a32BlxImmediateSwitchesTargetModeToThumb() {
        // BLX immediate with H=0 and imm24=0.
        val decoded = Armv7ControlFlowDecoder.decodeA32(
            word = 0xFA000000.toInt(),
            address = 0x2000,
        )

        requireNotNull(decoded)
        assertEquals(ControlFlowKind.CALL, decoded.kind)
        assertEquals(0x2008L, decoded.targetAddress)
        assertEquals(NativeArchitecture.ARMV7_THUMB, decoded.targetArchitecture)
    }

    @Test
    fun thumbDecodesConditionalBranchWideCallAndBxLr() {
        val code = byteArrayOf(
            // BEQ +0
            0x00, 0xD0.toByte(),
            // BL +0: F000 F800
            0x00, 0xF0.toByte(), 0x00, 0xF8.toByte(),
            // BX LR
            0x70, 0x47,
        )

        val result = Armv7ControlFlowDecoder.scanThumb(
            code = code,
            startAddress = 0x3000,
            cancellation = NeverCancelled,
        )

        assertEquals(3, result.instructionsScanned)
        assertEquals(3, result.controlFlow.size)

        assertEquals(
            ControlFlowKind.CONDITIONAL_BRANCH,
            result.controlFlow[0].kind,
        )
        assertEquals(0x3004L, result.controlFlow[0].targetAddress)

        assertEquals(ControlFlowKind.CALL, result.controlFlow[1].kind)
        assertEquals(0x3006L, result.controlFlow[1].targetAddress)

        assertEquals(ControlFlowKind.RETURN, result.controlFlow[2].kind)
        assertEquals(14, result.controlFlow[2].targetRegister)
    }

    @Test
    fun thumbUnknown32BitInstructionStillPreservesBoundary() {
        val code = byteArrayOf(
            // 32-bit Thumb prefix but not one of the branch forms decoded here.
            0x00, 0xE8.toByte(), 0x00, 0x00,
            // B +0, must be decoded at offset 4 rather than desynchronized.
            0x00, 0xE0.toByte(),
        )

        val result = Armv7ControlFlowDecoder.scanThumb(
            code = code,
            startAddress = 0x4000,
            cancellation = NeverCancelled,
        )

        assertEquals(2, result.instructionsScanned)
        assertEquals(1, result.controlFlow.size)
        assertEquals(0x4008L, result.controlFlow.single().targetAddress)
    }

    @Test
    fun truncatedThumb32TailIsNotInventedAsInstruction() {
        val result = Armv7ControlFlowDecoder.scanThumb(
            code = byteArrayOf(
                0x00, 0xF0.toByte(), 0x00,
            ),
            startAddress = 0x5000,
            cancellation = NeverCancelled,
        )

        assertEquals(0, result.instructionsScanned)
        assertTrue(result.controlFlow.isEmpty())
        assertEquals(3, result.truncatedTailBytes)
    }

    @Test
    fun unrelatedA32WordProducesNoControlFlowEvidence() {
        val decoded = Armv7ControlFlowDecoder.decodeA32(
            word = 0xE1A00000.toInt(), // MOV r0, r0
            address = 0x6000,
        )

        assertNull(decoded)
    }

    private object NeverCancelled : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
}
