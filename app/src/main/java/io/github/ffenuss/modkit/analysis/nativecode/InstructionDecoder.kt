package io.github.ffenuss.modkit.analysis.nativecode

enum class InstructionBoundaryPolicy {
    STREAM_SAFE,
    KNOWN_BOUNDARY_ONLY,
}

data class InstructionDecodeResult(
    val architecture: NativeArchitecture,
    val size: Int?,
    val controlFlow: ControlFlowInstruction?,
    val canAdvanceSafely: Boolean,
    val reason: String?,
)

interface InstructionDecoder {
    val architecture: NativeArchitecture
    val boundaryPolicy: InstructionBoundaryPolicy

    fun decodeAt(
        code: ByteArray,
        offset: Int,
        address: Long,
    ): InstructionDecodeResult
}

/**
 * Internal decoder registry. A backend is exposed only when its boundary policy
 * is explicit; variable-length x86 never claims stream-safe decoding until a
 * complete instruction-length decoder exists.
 */
object InstructionDecoders {
    fun forArchitecture(
        architecture: NativeArchitecture,
    ): InstructionDecoder? =
        when (architecture) {
            NativeArchitecture.ARMV7_A32 -> ArmA32InstructionDecoder
            NativeArchitecture.ARMV7_THUMB -> ArmThumbInstructionDecoder
            NativeArchitecture.X86 -> X86KnownBoundaryInstructionDecoder(
                NativeArchitecture.X86,
            )
            NativeArchitecture.X86_64 -> X86KnownBoundaryInstructionDecoder(
                NativeArchitecture.X86_64,
            )
            NativeArchitecture.AARCH64 -> null
        }
}

private object ArmA32InstructionDecoder : InstructionDecoder {
    override val architecture = NativeArchitecture.ARMV7_A32
    override val boundaryPolicy = InstructionBoundaryPolicy.STREAM_SAFE

    override fun decodeAt(
        code: ByteArray,
        offset: Int,
        address: Long,
    ): InstructionDecodeResult {
        if (offset < 0 || offset + 4 > code.size) {
            return InstructionDecodeResult(
                architecture = architecture,
                size = null,
                controlFlow = null,
                canAdvanceSafely = false,
                reason = "truncated A32 instruction",
            )
        }
        val word =
            (code[offset].toInt() and 0xff) or
                ((code[offset + 1].toInt() and 0xff) shl 8) or
                ((code[offset + 2].toInt() and 0xff) shl 16) or
                ((code[offset + 3].toInt() and 0xff) shl 24)
        return InstructionDecodeResult(
            architecture = architecture,
            size = 4,
            controlFlow = Armv7ControlFlowDecoder.decodeA32(
                word = word,
                address = address,
            ),
            canAdvanceSafely = true,
            reason = null,
        )
    }
}

private object ArmThumbInstructionDecoder : InstructionDecoder {
    override val architecture = NativeArchitecture.ARMV7_THUMB
    override val boundaryPolicy = InstructionBoundaryPolicy.STREAM_SAFE

    override fun decodeAt(
        code: ByteArray,
        offset: Int,
        address: Long,
    ): InstructionDecodeResult {
        if (offset < 0 || offset + 2 > code.size) {
            return InstructionDecodeResult(
                architecture = architecture,
                size = null,
                controlFlow = null,
                canAdvanceSafely = false,
                reason = "truncated Thumb instruction",
            )
        }
        val first =
            (code[offset].toInt() and 0xff) or
                ((code[offset + 1].toInt() and 0xff) shl 8)
        val size = if (Armv7ControlFlowDecoder.isThumb32Prefix(first)) 4 else 2
        if (offset + size > code.size) {
            return InstructionDecodeResult(
                architecture = architecture,
                size = null,
                controlFlow = null,
                canAdvanceSafely = false,
                reason = "truncated Thumb-2 instruction",
            )
        }

        val flow = if (size == 2) {
            Armv7ControlFlowDecoder.decodeThumb16(first, address)
        } else {
            val second =
                (code[offset + 2].toInt() and 0xff) or
                    ((code[offset + 3].toInt() and 0xff) shl 8)
            Armv7ControlFlowDecoder.decodeThumb32(
                first = first,
                second = second,
                address = address,
            )
        }

        return InstructionDecodeResult(
            architecture = architecture,
            size = size,
            controlFlow = flow,
            canAdvanceSafely = true,
            reason = null,
        )
    }
}

private class X86KnownBoundaryInstructionDecoder(
    override val architecture: NativeArchitecture,
) : InstructionDecoder {
    override val boundaryPolicy =
        InstructionBoundaryPolicy.KNOWN_BOUNDARY_ONLY

    override fun decodeAt(
        code: ByteArray,
        offset: Int,
        address: Long,
    ): InstructionDecodeResult {
        val decoded = X86ControlFlowDecoder.decodeAtKnownBoundary(
            code = code,
            offset = offset,
            address = address,
            architecture = architecture,
        )
        return InstructionDecodeResult(
            architecture = architecture,
            size = decoded.instructionSize,
            controlFlow = decoded.instruction,
            canAdvanceSafely = decoded.instructionSize != null,
            reason = decoded.reason,
        )
    }
}
