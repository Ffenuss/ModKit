package io.github.ffenuss.modkit.analysis.nativecode

data class AArch64DecodedInstruction(
    val address: Long,
    val word: Long,
    val mnemonic: String,
    val operands: String,
    val recognized: Boolean,
) {
    val text: String
        get() =
            buildString {
                append("0x")
                append(address.toString(16))
                append(": ")
                append(mnemonic)
                if (operands.isNotBlank()) {
                    append(' ')
                    append(operands)
                }
            }
}

data class AArch64Disassembly(
    val instructions: List<AArch64DecodedInstruction>,
    val recognizedCount: Int,
    val unknownCount: Int,
    val summary: List<String>,
)

/**
 * Small, dependency-free AArch64 decoder for the instruction families ModKit
 * needs first when inspecting IL2CPP method bodies.
 *
 * Unknown instructions remain explicit .word rows. The decoder never invents
 * source semantics for an encoding it has not recognized.
 */
object AArch64Disassembler {
    fun disassemble(
        code: ByteArray,
        startAddress: Long,
        maxInstructions: Int = 128,
    ): AArch64Disassembly {
        require(maxInstructions in 1..4096) {
            "AArch64 instruction limit is out of bounds."
        }

        val count =
            minOf(
                code.size / 4,
                maxInstructions,
            )
        val instructions =
            ArrayList<AArch64DecodedInstruction>(count)

        repeat(count) { index ->
            val offset = index * 4
            val word = u32(code, offset)
            instructions +=
                decode(
                    word = word,
                    address = startAddress + offset,
                )
        }

        val recognized =
            instructions.count { it.recognized }
        return AArch64Disassembly(
            instructions = instructions,
            recognizedCount = recognized,
            unknownCount =
                instructions.size - recognized,
            summary = summarize(instructions),
        )
    }

    internal fun decode(
        word: Long,
        address: Long,
    ): AArch64DecodedInstruction {
        // NOP.
        if (word == 0xD503201FL) {
            return row(
                address,
                word,
                "nop",
                "",
            )
        }

        // RET Xn.
        if (
            (word and 0xFFFFFC1FL) ==
            0xD65F0000L
        ) {
            val rn =
                ((word ushr 5) and 0x1f)
                    .toInt()
            return row(
                address,
                word,
                "ret",
                if (rn == 30) "" else xReg(rn),
            )
        }

        // BR Xn.
        if (
            (word and 0xFFFFFC1FL) ==
            0xD61F0000L
        ) {
            val rn =
                ((word ushr 5) and 0x1f)
                    .toInt()
            return row(
                address,
                word,
                "br",
                xReg(rn),
            )
        }

        // BLR Xn.
        if (
            (word and 0xFFFFFC1FL) ==
            0xD63F0000L
        ) {
            val rn =
                ((word ushr 5) and 0x1f)
                    .toInt()
            return row(
                address,
                word,
                "blr",
                xReg(rn),
            )
        }

        // B / BL immediate.
        if (
            (word and 0x7C000000L) ==
            0x14000000L
        ) {
            val link =
                (word and 0x80000000L) != 0L
            val imm26 =
                word and 0x03FFFFFFL
            val displacement =
                signExtend(
                    imm26 shl 2,
                    28,
                )
            val target =
                address + displacement
            return row(
                address,
                word,
                if (link) "bl" else "b",
                hexAddress(target),
            )
        }

        // B.cond.
        if (
            (word and 0xFF000010L) ==
            0x54000000L
        ) {
            val imm19 =
                (word ushr 5) and 0x7FFFFL
            val displacement =
                signExtend(
                    imm19 shl 2,
                    21,
                )
            val condition =
                CONDITIONS[
                    (word and 0xfL)
                        .toInt()
                ]
            return row(
                address,
                word,
                "b." + condition,
                hexAddress(
                    address + displacement,
                ),
            )
        }

        // CBZ / CBNZ.
        if (
            (word and 0x7E000000L) ==
            0x34000000L
        ) {
            val is64 =
                (word and 0x80000000L) != 0L
            val nonZero =
                (word and 0x01000000L) != 0L
            val imm19 =
                (word ushr 5) and 0x7FFFFL
            val displacement =
                signExtend(
                    imm19 shl 2,
                    21,
                )
            val rt =
                (word and 0x1fL).toInt()
            return row(
                address,
                word,
                if (nonZero) "cbnz" else "cbz",
                reg(
                    rt,
                    is64,
                    spAllowed = false,
                ) +
                    ", " +
                    hexAddress(
                        address + displacement,
                    ),
            )
        }

        // TBZ / TBNZ.
        if (
            (word and 0x7E000000L) ==
            0x36000000L
        ) {
            val nonZero =
                (word and 0x01000000L) != 0L
            val b5 =
                (word ushr 31) and 1L
            val b40 =
                (word ushr 19) and 0x1fL
            val bit =
                (b5 shl 5) or b40
            val imm14 =
                (word ushr 5) and 0x3FFFL
            val displacement =
                signExtend(
                    imm14 shl 2,
                    16,
                )
            val rt =
                (word and 0x1fL).toInt()
            return row(
                address,
                word,
                if (nonZero) "tbnz" else "tbz",
                xReg(rt) +
                    ", #" +
                    bit +
                    ", " +
                    hexAddress(
                        address + displacement,
                    ),
            )
        }

        // ADR / ADRP.
        val adrClass =
            word and 0x9F000000L
        if (
            adrClass == 0x10000000L ||
            adrClass == 0x90000000L
        ) {
            val page =
                adrClass == 0x90000000L
            val immlo =
                (word ushr 29) and 0x3L
            val immhi =
                (word ushr 5) and 0x7FFFFL
            val imm21 =
                (immhi shl 2) or immlo
            val displacement =
                signExtend(
                    imm21,
                    21,
                )
            val target =
                if (page) {
                    (address and -0x1000L) +
                        (displacement shl 12)
                } else {
                    address + displacement
                }
            val rd =
                (word and 0x1fL).toInt()
            return row(
                address,
                word,
                if (page) "adrp" else "adr",
                xReg(rd) +
                    ", " +
                    hexAddress(target),
            )
        }

        // MOVN / MOVZ / MOVK wide immediate.
        val wideClass =
            word and 0x7F800000L
        if (
            wideClass == 0x12800000L ||
            wideClass == 0x52800000L ||
            wideClass == 0x72800000L
        ) {
            val is64 =
                (word and 0x80000000L) != 0L
            val hw =
                ((word ushr 21) and 0x3L)
                    .toInt()
            if (!is64 && hw > 1) {
                return unknown(
                    address,
                    word,
                )
            }
            val imm16 =
                (word ushr 5) and 0xFFFFL
            val shift =
                hw * 16
            val rd =
                (word and 0x1fL).toInt()
            val mnemonic =
                when (wideClass) {
                    0x12800000L -> "movn"
                    0x52800000L -> "movz"
                    else -> "movk"
                }
            return row(
                address,
                word,
                mnemonic,
                reg(
                    rd,
                    is64,
                    spAllowed = false,
                ) +
                    ", #0x" +
                    imm16.toString(16) +
                    (
                        if (shift == 0) {
                            ""
                        } else {
                            ", lsl #" + shift
                        }
                        ),
            )
        }

        // ADD / SUB immediate, including CMP/CMN aliases when Rd == ZR.
        if (
            (word and 0x1F000000L) ==
            0x11000000L
        ) {
            val is64 =
                (word and 0x80000000L) != 0L
            val subtract =
                (word and 0x40000000L) != 0L
            val setFlags =
                (word and 0x20000000L) != 0L
            val shift12 =
                (word and 0x00400000L) != 0L
            val imm12 =
                (word ushr 10) and 0xFFFL
            val immediate =
                if (shift12) {
                    imm12 shl 12
                } else {
                    imm12
                }
            val rn =
                ((word ushr 5) and 0x1fL)
                    .toInt()
            val rd =
                (word and 0x1fL).toInt()

            if (setFlags && rd == 31) {
                return row(
                    address,
                    word,
                    if (subtract) "cmp" else "cmn",
                    reg(
                        rn,
                        is64,
                        spAllowed = true,
                    ) +
                        ", #" +
                        immediate,
                )
            }

            return row(
                address,
                word,
                when {
                    subtract && setFlags ->
                        "subs"
                    subtract ->
                        "sub"
                    setFlags ->
                        "adds"
                    else ->
                        "add"
                },
                reg(
                    rd,
                    is64,
                    spAllowed = !setFlags,
                ) +
                    ", " +
                    reg(
                        rn,
                        is64,
                        spAllowed = true,
                    ) +
                    ", #" +
                    immediate,
            )
        }

        // Load/store register (unsigned immediate), integer GPR forms.
        if (
            (word and 0x3B000000L) ==
            0x39000000L
        ) {
            val size =
                ((word ushr 30) and 0x3L)
                    .toInt()
            val load =
                (word and 0x00400000L) != 0L
            val imm12 =
                (word ushr 10) and 0xFFFL
            val scale =
                1L shl size
            val byteOffset =
                imm12 * scale
            val rn =
                ((word ushr 5) and 0x1fL)
                    .toInt()
            val rt =
                (word and 0x1fL).toInt()
            val mnemonic =
                when (size) {
                    0 ->
                        if (load) "ldrb" else "strb"
                    1 ->
                        if (load) "ldrh" else "strh"
                    2 ->
                        if (load) "ldr" else "str"
                    else ->
                        if (load) "ldr" else "str"
                }
            val is64 =
                size == 3
            return row(
                address,
                word,
                mnemonic,
                reg(
                    rt,
                    is64,
                    spAllowed = false,
                ) +
                    ", [" +
                    xReg(rn, spAllowed = true) +
                    (
                        if (byteOffset == 0L) {
                            "]"
                        } else {
                            ", #" +
                                byteOffset +
                                "]"
                        }
                        ),
            )
        }

        // Load/store pair, common integer prologue/epilogue forms.
        if (
            (word and 0x3A000000L) ==
            0x28000000L
        ) {
            val opc =
                ((word ushr 30) and 0x3L)
                    .toInt()
            val is64 =
                opc == 2
            if (opc == 0 || opc == 2) {
                val load =
                    (word and 0x00400000L) != 0L
                val mode =
                    ((word ushr 23) and 0x3L)
                        .toInt()
                val imm7 =
                    (word ushr 15) and 0x7fL
                val displacement =
                    signExtend(
                        imm7,
                        7,
                    ) *
                        if (is64) 8 else 4
                val rt2 =
                    ((word ushr 10) and 0x1fL)
                        .toInt()
                val rn =
                    ((word ushr 5) and 0x1fL)
                        .toInt()
                val rt =
                    (word and 0x1fL).toInt()
                val addressText =
                    when (mode) {
                        1 ->
                            "[" +
                                xReg(
                                    rn,
                                    spAllowed = true,
                                ) +
                                "], #" +
                                displacement
                        3 ->
                            "[" +
                                xReg(
                                    rn,
                                    spAllowed = true,
                                ) +
                                ", #" +
                                displacement +
                                "]!"
                        else ->
                            "[" +
                                xReg(
                                    rn,
                                    spAllowed = true,
                                ) +
                                (
                                    if (
                                        displacement ==
                                        0L
                                    ) {
                                        "]"
                                    } else {
                                        ", #" +
                                            displacement +
                                            "]"
                                    }
                                    )
                    }
                return row(
                    address,
                    word,
                    if (load) "ldp" else "stp",
                    reg(
                        rt,
                        is64,
                        spAllowed = false,
                    ) +
                        ", " +
                        reg(
                            rt2,
                            is64,
                            spAllowed = false,
                        ) +
                        ", " +
                        addressText,
                )
            }
        }

        return unknown(
            address,
            word,
        )
    }

    private fun summarize(
        instructions: List<AArch64DecodedInstruction>,
    ): List<String> {
        if (instructions.isEmpty()) {
            return listOf(
                "Нет полных ARM64-инструкций.",
            )
        }

        val out = mutableListOf<String>()
        val firstTwo =
            instructions.take(3)
        val first =
            firstTwo.firstOrNull()
        val retIndex =
            firstTwo.indexOfFirst {
                it.mnemonic == "ret"
            }

        if (
            first != null &&
            retIndex in 1..2 &&
            first.mnemonic == "movz" &&
            (
                first.operands.startsWith("w0,") ||
                    first.operands.startsWith("x0,")
                )
        ) {
            out +=
                "Начало похоже на простой возврат константы через W0/X0."
        }

        val callCount =
            instructions.count {
                it.mnemonic == "bl" ||
                    it.mnemonic == "blr"
            }
        if (callCount > 0) {
            out +=
                "В окне найдено вызовов функций: " +
                    callCount +
                    "."
        }

        val conditionalCount =
            instructions.count {
                it.mnemonic.startsWith("b.") ||
                    it.mnemonic == "cbz" ||
                    it.mnemonic == "cbnz" ||
                    it.mnemonic == "tbz" ||
                    it.mnemonic == "tbnz"
            }
        if (conditionalCount > 0) {
            out +=
                "Условных переходов: " +
                    conditionalCount +
                    "."
        }

        val memoryCount =
            instructions.count {
                it.mnemonic.startsWith("ldr") ||
                    it.mnemonic.startsWith("str") ||
                    it.mnemonic == "ldp" ||
                    it.mnemonic == "stp"
            }
        if (memoryCount > 0) {
            out +=
                "Обращений к памяти: " +
                    memoryCount +
                    "."
        }

        if (out.isEmpty()) {
            out +=
                "Структура метода пока требует более глубокого декодирования."
        }
        return out
    }

    private fun row(
        address: Long,
        word: Long,
        mnemonic: String,
        operands: String,
    ): AArch64DecodedInstruction =
        AArch64DecodedInstruction(
            address = address,
            word = word,
            mnemonic = mnemonic,
            operands = operands,
            recognized = true,
        )

    private fun unknown(
        address: Long,
        word: Long,
    ): AArch64DecodedInstruction =
        AArch64DecodedInstruction(
            address = address,
            word = word,
            mnemonic = ".word",
            operands =
                "0x" +
                    word
                        .toString(16)
                        .padStart(8, '0'),
            recognized = false,
        )

    private fun reg(
        index: Int,
        is64: Boolean,
        spAllowed: Boolean,
    ): String =
        if (is64) {
            xReg(index, spAllowed)
        } else {
            when {
                index == 31 && spAllowed ->
                    "wsp"
                index == 31 ->
                    "wzr"
                else ->
                    "w" + index
            }
        }

    private fun xReg(
        index: Int,
        spAllowed: Boolean = false,
    ): String =
        when {
            index == 31 && spAllowed ->
                "sp"
            index == 31 ->
                "xzr"
            else ->
                "x" + index
        }

    private fun hexAddress(
        value: Long,
    ): String =
        "0x" + value.toString(16)

    private fun u32(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun signExtend(
        value: Long,
        bits: Int,
    ): Long {
        val shift =
            64 - bits
        return (value shl shift) shr shift
    }

    private val CONDITIONS =
        listOf(
            "eq",
            "ne",
            "cs",
            "cc",
            "mi",
            "pl",
            "vs",
            "vc",
            "hi",
            "ls",
            "ge",
            "lt",
            "gt",
            "le",
            "al",
            "nv",
        )
}
