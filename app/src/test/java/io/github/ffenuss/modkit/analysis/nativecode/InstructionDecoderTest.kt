package io.github.ffenuss.modkit.analysis.nativecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionDecoderTest {
    @Test
    fun thumbUnknownInstructionStillHasSafeBoundaryAdvance() {
        val decoder = requireNotNull(
            InstructionDecoders.forArchitecture(
                NativeArchitecture.ARMV7_THUMB,
            ),
        )
        val result = decoder.decodeAt(
            code = byteArrayOf(
                0x00, 0xE8.toByte(), 0x00, 0x00,
            ),
            offset = 0,
            address = 0x1000,
        )

        assertEquals(InstructionBoundaryPolicy.STREAM_SAFE, decoder.boundaryPolicy)
        assertTrue(result.canAdvanceSafely)
        assertEquals(4, result.size)
        assertNull(result.controlFlow)
    }

    @Test
    fun x86UnknownOpcodeDoesNotPretendSafeNextBoundary() {
        val decoder = requireNotNull(
            InstructionDecoders.forArchitecture(
                NativeArchitecture.X86_64,
            ),
        )
        val result = decoder.decodeAt(
            code = byteArrayOf(
                0x48, 0x89.toByte(), 0xD8.toByte(),
            ),
            offset = 0,
            address = 0x2000,
        )

        assertEquals(
            InstructionBoundaryPolicy.KNOWN_BOUNDARY_ONLY,
            decoder.boundaryPolicy,
        )
        assertFalse(result.canAdvanceSafely)
        assertNull(result.size)
        assertNull(result.controlFlow)
    }

    @Test
    fun x86DirectCallAtKnownBoundaryCanAdvanceExactly() {
        val decoder = requireNotNull(
            InstructionDecoders.forArchitecture(
                NativeArchitecture.X86,
            ),
        )
        val result = decoder.decodeAt(
            code = byteArrayOf(
                0xE8.toByte(),
                0x00, 0x00, 0x00, 0x00,
            ),
            offset = 0,
            address = 0x3000,
        )

        assertTrue(result.canAdvanceSafely)
        assertEquals(5, result.size)
        assertEquals(ControlFlowKind.CALL, result.controlFlow?.kind)
        assertEquals(0x3005L, result.controlFlow?.targetAddress)
    }

    @Test
    fun aarch64UsesFixedWidthStreamSafeDecoder() {
        val decoder =
            requireNotNull(
                InstructionDecoders
                    .forArchitecture(
                        NativeArchitecture.AARCH64,
                    ),
            )
        val result =
            decoder.decodeAt(
                code =
                    byteArrayOf(
                        0xC0.toByte(),
                        0x03,
                        0x5F,
                        0xD6.toByte(),
                    ),
                offset = 0,
                address = 0x4000,
            )

        assertEquals(
            InstructionBoundaryPolicy.STREAM_SAFE,
            decoder.boundaryPolicy,
        )
        assertTrue(result.canAdvanceSafely)
        assertEquals(4, result.size)
        assertEquals(
            ControlFlowKind.RETURN,
            result.controlFlow?.kind,
        )
        assertEquals(
            30,
            result.controlFlow?.targetRegister,
        )
    }
}
