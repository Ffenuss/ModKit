package io.github.ffenuss.modkit.analysis.nativecode

data class X86ControlFlowDecodeResult(
    val instruction: ControlFlowInstruction?,
    val instructionSize: Int?,
    val reason: String?,
)

/**
 * Conservative x86/x86-64 control-flow decoder for a caller-supplied,
 * already-known instruction boundary.
 *
 * It does not scan arbitrary bytes or guess boundaries. Direct rel8/rel32
 * control flow is resolved exactly. FF-group indirect CALL/JMP decodes enough
 * ModRM/SIB/displacement structure to determine the instruction length, but
 * leaves memory targets unresolved until relocation/register-flow evidence is
 * available.
 */
object X86ControlFlowDecoder {
    fun decodeAtKnownBoundary(
        code: ByteArray,
        offset: Int,
        address: Long,
        architecture: NativeArchitecture,
    ): X86ControlFlowDecodeResult {
        require(
            architecture == NativeArchitecture.X86 ||
                architecture == NativeArchitecture.X86_64,
        ) {
            "X86 decoder requires X86 or X86_64 architecture."
        }
        if (offset !in code.indices) {
            return X86ControlFlowDecodeResult(
                instruction = null,
                instructionSize = null,
                reason = "offset outside buffer",
            )
        }

        var cursor = offset
        var operandSizePrefix = false
        var addressSizePrefix = false
        var rex = 0

        while (cursor < code.size && cursor - offset < MAX_INSTRUCTION_BYTES) {
            val value = u8(code, cursor)
            when {
                value in legacyPrefixes -> {
                    if (value == 0x66) operandSizePrefix = true
                    if (value == 0x67) addressSizePrefix = true
                    cursor++
                }
                architecture == NativeArchitecture.X86_64 && value in 0x40..0x4f -> {
                    rex = value
                    cursor++
                }
                else -> break
            }
        }

        if (cursor >= code.size || cursor - offset >= MAX_INSTRUCTION_BYTES) {
            return truncatedOrUnsupported("missing opcode")
        }

        val opcodeOffset = cursor
        val opcode = u8(code, cursor++)
        val prefixBytes = opcodeOffset - offset

        fun direct(
            kind: ControlFlowKind,
            displacement: Long,
            encodedSize: Int,
            condition: Int? = null,
        ): X86ControlFlowDecodeResult {
            val size = prefixBytes + encodedSize
            val target = safeAdd(address + size, displacement)
                ?: return truncatedOrUnsupported("target address overflow")
            return X86ControlFlowDecodeResult(
                instruction = ControlFlowInstruction(
                    address = address,
                    size = size,
                    architecture = architecture,
                    kind = kind,
                    targetAddress = target,
                    targetArchitecture = architecture,
                    conditionCode = condition,
                ),
                instructionSize = size,
                reason = null,
            )
        }

        when {
            opcode == 0xe8 -> {
                val rel = i32(code, cursor)
                    ?: return truncatedOrUnsupported("truncated CALL rel32")
                return direct(
                    kind = ControlFlowKind.CALL,
                    displacement = rel,
                    encodedSize = 5,
                )
            }

            opcode == 0xe9 -> {
                val rel = i32(code, cursor)
                    ?: return truncatedOrUnsupported("truncated JMP rel32")
                return direct(
                    kind = ControlFlowKind.BRANCH,
                    displacement = rel,
                    encodedSize = 5,
                )
            }

            opcode == 0xeb -> {
                val rel = i8(code, cursor)
                    ?: return truncatedOrUnsupported("truncated JMP rel8")
                return direct(
                    kind = ControlFlowKind.BRANCH,
                    displacement = rel,
                    encodedSize = 2,
                )
            }

            opcode in 0x70..0x7f -> {
                val rel = i8(code, cursor)
                    ?: return truncatedOrUnsupported("truncated Jcc rel8")
                return direct(
                    kind = ControlFlowKind.CONDITIONAL_BRANCH,
                    displacement = rel,
                    encodedSize = 2,
                    condition = opcode and 0xf,
                )
            }

            opcode == 0x0f -> {
                if (cursor >= code.size) {
                    return truncatedOrUnsupported("truncated 0F opcode")
                }
                val second = u8(code, cursor++)
                if (second in 0x80..0x8f) {
                    val rel = i32(code, cursor)
                        ?: return truncatedOrUnsupported("truncated Jcc rel32")
                    return direct(
                        kind = ControlFlowKind.CONDITIONAL_BRANCH,
                        displacement = rel,
                        encodedSize = 6,
                        condition = second and 0xf,
                    )
                }
                return X86ControlFlowDecodeResult(
                    instruction = null,
                    instructionSize = null,
                    reason = "opcode is not a supported control-flow instruction",
                )
            }

            opcode == 0xc3 -> {
                val size = prefixBytes + 1
                return X86ControlFlowDecodeResult(
                    instruction = ControlFlowInstruction(
                        address = address,
                        size = size,
                        architecture = architecture,
                        kind = ControlFlowKind.RETURN,
                    ),
                    instructionSize = size,
                    reason = null,
                )
            }

            opcode == 0xc2 -> {
                if (cursor + 2 > code.size) {
                    return truncatedOrUnsupported("truncated RET imm16")
                }
                val size = prefixBytes + 3
                return X86ControlFlowDecodeResult(
                    instruction = ControlFlowInstruction(
                        address = address,
                        size = size,
                        architecture = architecture,
                        kind = ControlFlowKind.RETURN,
                    ),
                    instructionSize = size,
                    reason = null,
                )
            }

            opcode == 0xff -> {
                if (cursor >= code.size) {
                    return truncatedOrUnsupported("truncated FF ModRM")
                }
                val modRm = u8(code, cursor)
                val group = (modRm ushr 3) and 7
                val kind = when (group) {
                    2, 3 -> ControlFlowKind.INDIRECT_CALL
                    4, 5 -> ControlFlowKind.INDIRECT_BRANCH
                    else -> null
                } ?: return X86ControlFlowDecodeResult(
                    instruction = null,
                    instructionSize = null,
                    reason = "FF group is not CALL/JMP",
                )

                val addressing = decodeModRmLength(
                    code = code,
                    modRmOffset = cursor,
                    architecture = architecture,
                    addressSizePrefix = addressSizePrefix,
                ) ?: return truncatedOrUnsupported("truncated ModRM/SIB displacement")

                val size = prefixBytes + 1 + addressing.bytes
                if (size > MAX_INSTRUCTION_BYTES) {
                    return truncatedOrUnsupported("instruction exceeds x86 architectural limit")
                }

                val mod = (modRm ushr 6) and 3
                val rmLow = modRm and 7
                val rexB = if (architecture == NativeArchitecture.X86_64) {
                    rex and 1
                } else {
                    0
                }
                val register = if (mod == 3) {
                    rmLow or (rexB shl 3)
                } else {
                    null
                }

                return X86ControlFlowDecodeResult(
                    instruction = ControlFlowInstruction(
                        address = address,
                        size = size,
                        architecture = architecture,
                        kind = kind,
                        targetRegister = register,
                    ),
                    instructionSize = size,
                    reason = if (register == null) {
                        "memory-indirect target requires relocation/register-flow evidence"
                    } else {
                        null
                    },
                )
            }

            else -> {
                @Suppress("UNUSED_VARIABLE")
                val ignoredOperandSizePrefix = operandSizePrefix
                return X86ControlFlowDecodeResult(
                    instruction = null,
                    instructionSize = null,
                    reason = "opcode is not a supported control-flow instruction",
                )
            }
        }
    }

    private data class AddressingLength(
        val bytes: Int,
    )

    private fun decodeModRmLength(
        code: ByteArray,
        modRmOffset: Int,
        architecture: NativeArchitecture,
        addressSizePrefix: Boolean,
    ): AddressingLength? {
        if (modRmOffset >= code.size) return null
        val modRm = u8(code, modRmOffset)
        val mod = (modRm ushr 6) and 3
        val rm = modRm and 7

        // 16-bit addressing is deliberately not guessed in the first internal
        // backend. A future x86 decoder must model it explicitly.
        if (
            architecture == NativeArchitecture.X86 &&
            addressSizePrefix
        ) {
            return null
        }

        // In x86-64 address-size override selects 32-bit addressing, whose
        // ModRM/SIB byte counts match the handling below.
        var bytes = 1
        var hasSib = false
        var sibBase = -1
        if (mod != 3 && rm == 4) {
            if (modRmOffset + bytes >= code.size) return null
            val sib = u8(code, modRmOffset + bytes)
            bytes++
            hasSib = true
            sibBase = sib and 7
        }

        val displacementBytes = when {
            mod == 0 && !hasSib && rm == 5 -> 4
            mod == 0 && hasSib && sibBase == 5 -> 4
            mod == 1 -> 1
            mod == 2 -> 4
            else -> 0
        }
        if (modRmOffset + bytes + displacementBytes > code.size) return null
        bytes += displacementBytes

        return AddressingLength(bytes)
    }

    private fun u8(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        bytes[offset].toInt() and 0xff

    private fun i8(
        bytes: ByteArray,
        offset: Int,
    ): Long? {
        if (offset >= bytes.size) return null
        return bytes[offset].toLong()
    }

    private fun i32(
        bytes: ByteArray,
        offset: Int,
    ): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        val value =
            (u8(bytes, offset).toLong()) or
                (u8(bytes, offset + 1).toLong() shl 8) or
                (u8(bytes, offset + 2).toLong() shl 16) or
                (u8(bytes, offset + 3).toLong() shl 24)
        return value.toInt().toLong()
    }

    private fun safeAdd(
        left: Long,
        right: Long,
    ): Long? =
        runCatching { Math.addExact(left, right) }.getOrNull()

    private fun truncatedOrUnsupported(
        reason: String,
    ) = X86ControlFlowDecodeResult(
        instruction = null,
        instructionSize = null,
        reason = reason,
    )

    private const val MAX_INSTRUCTION_BYTES = 15

    private val legacyPrefixes = setOf(
        0xf0,
        0xf2,
        0xf3,
        0x2e,
        0x36,
        0x3e,
        0x26,
        0x64,
        0x65,
        0x66,
        0x67,
    )
}
