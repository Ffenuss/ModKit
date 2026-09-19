package io.github.ffenuss.modkit.analysis.nativecode

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal

enum class NativeArchitecture {
    ARMV7_A32,
    ARMV7_THUMB,
    AARCH64,
    X86,
    X86_64,
}

enum class ControlFlowKind {
    CALL,
    BRANCH,
    CONDITIONAL_BRANCH,
    INDIRECT_CALL,
    INDIRECT_BRANCH,
    RETURN,
}

data class ControlFlowInstruction(
    val address: Long,
    val size: Int,
    val architecture: NativeArchitecture,
    val kind: ControlFlowKind,
    val targetAddress: Long? = null,
    val targetArchitecture: NativeArchitecture? = null,
    val targetRegister: Int? = null,
    val conditionCode: Int? = null,
)

data class Armv7ScanResult(
    val architecture: NativeArchitecture,
    val instructionsScanned: Int,
    val controlFlow: List<ControlFlowInstruction>,
    val truncatedTailBytes: Int,
)

/**
 * Conservative fixed-width ARMv7/Thumb control-flow decoder.
 *
 * It emits only encodings whose instruction boundaries and targets are defined
 * by the architecture. Unknown instructions are skipped using A32 fixed width
 * or the Thumb 16/32-bit prefix rule; arbitrary byte-pattern matches are never
 * treated as branch/call evidence.
 */
object Armv7ControlFlowDecoder {
    fun scanA32(
        code: ByteArray,
        startAddress: Long,
        cancellation: CancellationSignal,
    ): Armv7ScanResult {
        val limit = code.size and -4
        val out = mutableListOf<ControlFlowInstruction>()
        var offset = 0
        var instructions = 0
        while (offset < limit) {
            checkCancelled(cancellation)
            val word = u32(code, offset)
            val address = startAddress + offset
            decodeA32(word, address)?.let(out::add)
            instructions++
            offset += 4
        }
        return Armv7ScanResult(
            architecture = NativeArchitecture.ARMV7_A32,
            instructionsScanned = instructions,
            controlFlow = out,
            truncatedTailBytes = code.size - limit,
        )
    }

    fun scanThumb(
        code: ByteArray,
        startAddress: Long,
        cancellation: CancellationSignal,
    ): Armv7ScanResult {
        val out = mutableListOf<ControlFlowInstruction>()
        var offset = 0
        var instructions = 0
        while (offset + 2 <= code.size) {
            checkCancelled(cancellation)
            val first = u16(code, offset)
            val width = if (isThumb32Prefix(first)) 4 else 2
            if (offset + width > code.size) break

            val address = startAddress + offset
            if (width == 4) {
                val second = u16(code, offset + 2)
                decodeThumb32(first, second, address)?.let(out::add)
            } else {
                decodeThumb16(first, address)?.let(out::add)
            }
            instructions++
            offset += width
        }

        return Armv7ScanResult(
            architecture = NativeArchitecture.ARMV7_THUMB,
            instructionsScanned = instructions,
            controlFlow = out,
            truncatedTailBytes = code.size - offset,
        )
    }

    internal fun decodeA32(
        word: Int,
        address: Long,
    ): ControlFlowInstruction? {
        val cond = (word ushr 28) and 0xf

        // A32 B / BL immediate, excluding unconditional-space encodings.
        if (cond != 0xf && (word and 0x0e000000) == 0x0a000000) {
            val link = (word and 0x01000000) != 0
            val imm24 = word and 0x00ffffff
            val displacement = signExtend(imm24.toLong(), 24) shl 2
            val target = safeAdd(address + 8L, displacement) ?: return null
            return ControlFlowInstruction(
                address = address,
                size = 4,
                architecture = NativeArchitecture.ARMV7_A32,
                kind = when {
                    link -> ControlFlowKind.CALL
                    cond != 0xe -> ControlFlowKind.CONDITIONAL_BRANCH
                    else -> ControlFlowKind.BRANCH
                },
                targetAddress = target,
                targetArchitecture = NativeArchitecture.ARMV7_A32,
                conditionCode = cond.takeIf { it != 0xe },
            )
        }

        // A32 BLX immediate: 1111 101H imm24.
        if ((word and 0xfe000000.toInt()) == 0xfa000000.toInt()) {
            val h = (word ushr 24) and 1
            val imm24 = word and 0x00ffffff
            val encoded = (imm24.toLong() shl 2) or (h.toLong() shl 1)
            val displacement = signExtend(encoded, 26)
            val target = safeAdd(address + 8L, displacement) ?: return null
            return ControlFlowInstruction(
                address = address,
                size = 4,
                architecture = NativeArchitecture.ARMV7_A32,
                kind = ControlFlowKind.CALL,
                targetAddress = target,
                targetArchitecture = NativeArchitecture.ARMV7_THUMB,
            )
        }

        // BX / BLX register.
        val op = word and 0x0ffffff0
        if (op == 0x012fff10 || op == 0x012fff30) {
            val register = word and 0xf
            val link = op == 0x012fff30
            return ControlFlowInstruction(
                address = address,
                size = 4,
                architecture = NativeArchitecture.ARMV7_A32,
                kind = when {
                    !link && register == 14 -> ControlFlowKind.RETURN
                    link -> ControlFlowKind.INDIRECT_CALL
                    else -> ControlFlowKind.INDIRECT_BRANCH
                },
                targetRegister = register,
                conditionCode = cond.takeIf { it != 0xe },
            )
        }

        return null
    }

    internal fun decodeThumb16(
        halfword: Int,
        address: Long,
    ): ControlFlowInstruction? {
        // Thumb conditional B T1, excluding SVC/UDF encodings.
        if ((halfword and 0xf000) == 0xd000) {
            val cond = (halfword ushr 8) and 0xf
            if (cond < 0xe) {
                val displacement = signExtend(
                    ((halfword and 0xff).toLong() shl 1),
                    9,
                )
                val target = safeAdd(address + 4L, displacement) ?: return null
                return ControlFlowInstruction(
                    address = address,
                    size = 2,
                    architecture = NativeArchitecture.ARMV7_THUMB,
                    kind = ControlFlowKind.CONDITIONAL_BRANCH,
                    targetAddress = target,
                    targetArchitecture = NativeArchitecture.ARMV7_THUMB,
                    conditionCode = cond,
                )
            }
        }

        // Thumb unconditional B T2.
        if ((halfword and 0xf800) == 0xe000) {
            val displacement = signExtend(
                ((halfword and 0x7ff).toLong() shl 1),
                12,
            )
            val target = safeAdd(address + 4L, displacement) ?: return null
            return ControlFlowInstruction(
                address = address,
                size = 2,
                architecture = NativeArchitecture.ARMV7_THUMB,
                kind = ControlFlowKind.BRANCH,
                targetAddress = target,
                targetArchitecture = NativeArchitecture.ARMV7_THUMB,
            )
        }

        // Thumb BX / BLX register T1.
        if ((halfword and 0xff07) == 0x4700) {
            val link = (halfword and 0x0080) != 0
            val register = (halfword ushr 3) and 0xf
            return ControlFlowInstruction(
                address = address,
                size = 2,
                architecture = NativeArchitecture.ARMV7_THUMB,
                kind = when {
                    !link && register == 14 -> ControlFlowKind.RETURN
                    link -> ControlFlowKind.INDIRECT_CALL
                    else -> ControlFlowKind.INDIRECT_BRANCH
                },
                targetRegister = register,
            )
        }

        return null
    }

    internal fun decodeThumb32(
        first: Int,
        second: Int,
        address: Long,
    ): ControlFlowInstruction? {
        // Thumb-2 BL T1.
        if (
            (first and 0xf800) == 0xf000 &&
            (second and 0xd000) == 0xd000
        ) {
            return ControlFlowInstruction(
                address = address,
                size = 4,
                architecture = NativeArchitecture.ARMV7_THUMB,
                kind = ControlFlowKind.CALL,
                targetAddress = thumbWideTarget(first, second, address),
                targetArchitecture = NativeArchitecture.ARMV7_THUMB,
            )
        }

        // Thumb-2 B.W T4.
        if (
            (first and 0xf800) == 0xf000 &&
            (second and 0xd000) == 0x9000
        ) {
            return ControlFlowInstruction(
                address = address,
                size = 4,
                architecture = NativeArchitecture.ARMV7_THUMB,
                kind = ControlFlowKind.BRANCH,
                targetAddress = thumbWideTarget(first, second, address),
                targetArchitecture = NativeArchitecture.ARMV7_THUMB,
            )
        }

        return null
    }

    internal fun isThumb32Prefix(first: Int): Boolean =
        (first and 0xf800) in setOf(0xe800, 0xf000, 0xf800)

    private fun thumbWideTarget(
        first: Int,
        second: Int,
        address: Long,
    ): Long? {
        val s = (first ushr 10) and 1
        val imm10 = first and 0x03ff
        val j1 = (second ushr 13) and 1
        val j2 = (second ushr 11) and 1
        val imm11 = second and 0x07ff
        val i1 = (j1 xor s xor 1) and 1
        val i2 = (j2 xor s xor 1) and 1
        val imm25 =
            (s.toLong() shl 24) or
                (i1.toLong() shl 23) or
                (i2.toLong() shl 22) or
                (imm10.toLong() shl 12) or
                (imm11.toLong() shl 1)
        val displacement = signExtend(imm25, 25)
        return safeAdd(address + 4L, displacement)
    }

    private fun u16(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        u16(bytes, offset) or
            (u16(bytes, offset + 2) shl 16)

    private fun signExtend(
        value: Long,
        bits: Int,
    ): Long {
        val shift = 64 - bits
        return (value shl shift) shr shift
    }

    private fun safeAdd(
        left: Long,
        right: Long,
    ): Long? =
        runCatching { Math.addExact(left, right) }.getOrNull()

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }
}
