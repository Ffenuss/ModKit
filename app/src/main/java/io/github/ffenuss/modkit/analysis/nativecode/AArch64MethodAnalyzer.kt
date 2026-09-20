package io.github.ffenuss.modkit.analysis.nativecode

enum class AArch64MethodShape(
    val title: String,
) {
    RETURN_CONSTANT("Возврат константы"),
    INSTANCE_FIELD_GETTER("Чтение поля объекта"),
    INSTANCE_FIELD_SETTER("Запись поля объекта"),
    CALLING_METHOD("Метод с вызовами"),
    BRANCHING_METHOD("Метод с условиями"),
    UNKNOWN("Форма пока не распознана"),
}

data class AArch64MethodAnalysis(
    val shape: AArch64MethodShape,
    val confidence: String,
    val pseudoCode: String,
    val facts: List<String>,
    val fieldOffset: Long? = null,
    val constantBits: Long? = null,
)

/**
 * Conservative shape recognizer for short IL2CPP AArch64 methods.
 *
 * It emits source-like pseudocode only for instruction sequences whose register
 * convention and memory operand can be proven directly from the machine words.
 * Anything more complex stays descriptive rather than being guessed.
 */
object AArch64MethodAnalyzer {
    fun analyze(
        disassembly: AArch64Disassembly,
    ): AArch64MethodAnalysis {
        val instructions =
            disassembly.instructions
                .dropWhile {
                    it.mnemonic == "nop"
                }

        detectConstantReturn(instructions)
            ?.let {
                return it
            }
        detectFieldGetter(instructions)
            ?.let {
                return it
            }
        detectFieldSetter(instructions)
            ?.let {
                return it
            }

        val calls =
            instructions.count {
                it.mnemonic == "bl" ||
                    it.mnemonic == "blr"
            }
        val conditions =
            instructions.count {
                it.mnemonic.startsWith("b.") ||
                    it.mnemonic == "cbz" ||
                    it.mnemonic == "cbnz" ||
                    it.mnemonic == "tbz" ||
                    it.mnemonic == "tbnz"
            }

        return when {
            conditions > 0 ->
                AArch64MethodAnalysis(
                    shape =
                        AArch64MethodShape
                            .BRANCHING_METHOD,
                    confidence = "структурно подтверждено",
                    pseudoCode =
                        buildString {
                            appendLine(
                                "// В методе есть условные ветвления.",
                            )
                            appendLine(
                                "// Для точного if/else нужен дальнейший CFG/data-flow анализ.",
                            )
                        }.trimEnd(),
                    facts =
                        listOf(
                            "Условных переходов: $conditions",
                            "Вызовов функций: $calls",
                        ),
                )
            calls > 0 ->
                AArch64MethodAnalysis(
                    shape =
                        AArch64MethodShape
                            .CALLING_METHOD,
                    confidence = "структурно подтверждено",
                    pseudoCode =
                        buildString {
                            appendLine(
                                "// Метод вызывает другие native-функции.",
                            )
                            appendLine(
                                "// Аргументы и результаты будут восстановлены data-flow анализатором.",
                            )
                        }.trimEnd(),
                    facts =
                        listOf(
                            "Вызовов функций: $calls",
                        ),
                )
            else ->
                AArch64MethodAnalysis(
                    shape =
                        AArch64MethodShape.UNKNOWN,
                    confidence = "недостаточно данных",
                    pseudoCode =
                        "// Безопасный псевдокод пока не восстановлен.",
                    facts =
                        listOf(
                            "Распознано инструкций: " +
                                disassembly.recognizedCount,
                            "Неизвестных инструкций: " +
                                disassembly.unknownCount,
                        ),
                )
        }
    }

    private fun detectConstantReturn(
        instructions: List<AArch64DecodedInstruction>,
    ): AArch64MethodAnalysis? {
        val retIndex =
            instructions.indexOfFirst {
                it.mnemonic == "ret"
            }
        if (
            retIndex !in 1..6
        ) {
            return null
        }

        val beforeRet =
            instructions.take(retIndex)
        val integerSequence =
            beforeRet.all {
                it.mnemonic == "movz" ||
                    it.mnemonic == "movk"
            }

        if (integerSequence) {
            val decoded =
                decodeMoveWideSequence(
                    beforeRet,
                ) ?: return null
            if (decoded.register != 0) {
                return null
            }

            val value =
                decoded.bits
            return AArch64MethodAnalysis(
                shape =
                    AArch64MethodShape
                        .RETURN_CONSTANT,
                confidence = "высокая",
                pseudoCode =
                    "return " +
                        if (
                            decoded.widthBits ==
                            32
                        ) {
                            value.toInt()
                                .toString()
                        } else {
                            value.toString()
                        } +
                        ";",
                facts =
                    listOf(
                        "Результат формируется только в " +
                            if (
                                decoded.widthBits ==
                                32
                            ) {
                                "W0"
                            } else {
                                "X0"
                            },
                        "После формирования результата сразу RET.",
                        "Raw bits: 0x" +
                            value.toULong()
                                .toString(16),
                    ),
                constantBits =
                    value,
            )
        }

        val fmov =
            beforeRet.lastOrNull()
                ?: return null
        val movePrefix =
            beforeRet.dropLast(1)
        if (
            movePrefix.isEmpty() ||
            movePrefix.any {
                it.mnemonic != "movz" &&
                    it.mnemonic != "movk"
            }
        ) {
            return null
        }
        val decoded =
            decodeMoveWideSequence(
                movePrefix,
            ) ?: return null
        val fmovWord =
            fmov.word
        val fmovS =
            (
                fmovWord and
                    0xFFFFFC1FL
                ) ==
                0x1E270000L
        val fmovD =
            (
                fmovWord and
                    0xFFFFFC1FL
                ) ==
                0x9E670000L
        if (!fmovS && !fmovD) {
            return null
        }
        val sourceRegister =
            ((fmovWord ushr 5) and
                0x1fL).toInt()
        val destinationRegister =
            (fmovWord and 0x1fL)
                .toInt()
        if (
            destinationRegister != 0 ||
            sourceRegister !=
                decoded.register ||
            (
                fmovS &&
                    decoded.widthBits != 32
                ) ||
            (
                fmovD &&
                    decoded.widthBits != 64
                )
        ) {
            return null
        }

        val valueText =
            if (fmovS) {
                Float.fromBits(
                    decoded.bits.toInt(),
                ).toString() + "f"
            } else {
                Double.fromBits(
                    decoded.bits,
                ).toString()
            }
        return AArch64MethodAnalysis(
            shape =
                AArch64MethodShape
                    .RETURN_CONSTANT,
            confidence = "высокая",
            pseudoCode =
                "return " +
                    valueText +
                    ";",
            facts =
                listOf(
                    "Константные IEEE-754 bits собираются в " +
                        if (fmovS) {
                            "W$sourceRegister"
                        } else {
                            "X$sourceRegister"
                        } +
                        " и переносятся в " +
                        if (fmovS) {
                            "S0"
                        } else {
                            "D0"
                        } +
                        ".",
                    "После FMOV сразу RET.",
                    "Raw bits: 0x" +
                        decoded.bits
                            .toULong()
                            .toString(16),
                ),
            constantBits =
                decoded.bits,
        )
    }

    private fun detectFieldGetter(
        instructions: List<AArch64DecodedInstruction>,
    ): AArch64MethodAnalysis? {
        if (
            instructions.size < 2 ||
            instructions[1].mnemonic !=
            "ret"
        ) {
            return null
        }

        val memory =
            decodeUnsignedLoadStore(
                instructions[0].word,
            ) ?: return null
        if (
            !memory.load ||
            memory.baseRegister != 0 ||
            memory.targetRegister != 0
        ) {
            return null
        }

        val type =
            memoryTypeLabel(memory)
        return AArch64MethodAnalysis(
            shape =
                AArch64MethodShape
                    .INSTANCE_FIELD_GETTER,
            confidence = "высокая",
            pseudoCode =
                "return this->field_0x" +
                    memory.byteOffset
                        .toString(16) +
                    ";",
            facts =
                listOf(
                    "this приходит через X0.",
                    "Чтение $type из [X0 + 0x" +
                        memory.byteOffset
                            .toString(16) +
                        "].",
                    if (memory.floating) {
                        "Значение возвращается через S0/D0 и сразу RET."
                    } else {
                        "Значение возвращается через W0/X0 и сразу RET."
                    },
                ),
            fieldOffset =
                memory.byteOffset,
        )
    }

    private fun detectFieldSetter(
        instructions: List<AArch64DecodedInstruction>,
    ): AArch64MethodAnalysis? {
        if (
            instructions.size < 2 ||
            instructions[1].mnemonic !=
            "ret"
        ) {
            return null
        }

        val memory =
            decodeUnsignedLoadStore(
                instructions[0].word,
            ) ?: return null
        val expectedArgumentRegister =
            if (memory.floating) {
                0
            } else {
                1
            }
        if (
            memory.load ||
            memory.baseRegister != 0 ||
            memory.targetRegister !=
                expectedArgumentRegister
        ) {
            return null
        }

        val type =
            memoryTypeLabel(memory)
        return AArch64MethodAnalysis(
            shape =
                AArch64MethodShape
                    .INSTANCE_FIELD_SETTER,
            confidence = "высокая",
            pseudoCode =
                "this->field_0x" +
                    memory.byteOffset
                        .toString(16) +
                    " = arg0;",
            facts =
                listOf(
                    if (memory.floating) {
                        "this приходит через X0, первый FP-аргумент — через S0/D0."
                    } else {
                        "this приходит через X0, первый scalar/reference аргумент — через W1/X1."
                    },
                    "Запись $type в [X0 + 0x" +
                        memory.byteOffset
                            .toString(16) +
                        "].",
                    "После записи сразу RET.",
                ),
            fieldOffset =
                memory.byteOffset,
        )
    }

    private data class MoveWideValue(
        val register: Int,
        val widthBits: Int,
        val bits: Long,
    )

    private fun decodeMoveWideSequence(
        instructions:
            List<AArch64DecodedInstruction>,
    ): MoveWideValue? {
        if (instructions.isEmpty()) {
            return null
        }

        var register: Int? = null
        var widthBits: Int? = null
        var value = 0L
        var initialized = false

        instructions.forEach {
                instruction,
            ->
            val word =
                instruction.word
            val wideClass =
                word and 0x7F800000L
            if (
                wideClass != 0x52800000L &&
                wideClass != 0x72800000L
            ) {
                return null
            }
            val is64 =
                (word and
                    0x80000000L) != 0L
            val width =
                if (is64) 64 else 32
            val hw =
                ((word ushr 21) and
                    0x3L).toInt()
            if (
                !is64 &&
                hw > 1
            ) {
                return null
            }
            val rd =
                (word and 0x1fL)
                    .toInt()
            if (
                register != null &&
                register != rd
            ) {
                return null
            }
            if (
                widthBits != null &&
                widthBits != width
            ) {
                return null
            }
            register = rd
            widthBits = width

            val imm16 =
                (word ushr 5) and
                    0xffffL
            val shift =
                hw * 16
            val mask =
                0xffffL shl shift

            if (
                wideClass ==
                0x52800000L
            ) {
                if (initialized) {
                    return null
                }
                value =
                    imm16 shl shift
                initialized = true
            } else {
                if (!initialized) {
                    return null
                }
                value =
                    (value and mask.inv()) or
                        (imm16 shl shift)
            }
        }

        if (
            !initialized ||
            register == null ||
            widthBits == null
        ) {
            return null
        }
        if (widthBits == 32) {
            value =
                value and 0xffffffffL
        }
        return MoveWideValue(
            register =
                requireNotNull(register),
            widthBits =
                requireNotNull(widthBits),
            bits = value,
        )
    }

    private data class UnsignedLoadStore(
        val load: Boolean,
        val floating: Boolean,
        val byteWidth: Int,
        val targetRegister: Int,
        val baseRegister: Int,
        val byteOffset: Long,
    )

    private fun memoryTypeLabel(
        memory: UnsignedLoadStore,
    ): String =
        if (memory.floating) {
            if (memory.byteWidth == 4) {
                "float"
            } else {
                "double"
            }
        } else {
            when (memory.byteWidth) {
                1 -> "byte/bool"
                2 -> "uint16"
                4 -> "uint32"
                8 -> "uint64/reference"
                else -> "value"
            }
        }

    private fun decodeUnsignedLoadStore(
        word: Long,
    ): UnsignedLoadStore? {
        val memoryClass =
            word and 0x3F000000L
        val floating =
            when (memoryClass) {
                0x39000000L ->
                    false
                0x3D000000L ->
                    true
                else ->
                    return null
            }
        val size =
            ((word ushr 30) and
                0x3L).toInt()
        if (
            floating &&
            size !in setOf(2, 3)
        ) {
            return null
        }
        val byteWidth =
            1 shl size
        val load =
            (word and
                0x00400000L) != 0L
        val imm12 =
            (word ushr 10) and
                0xfffL
        return UnsignedLoadStore(
            load = load,
            floating = floating,
            byteWidth = byteWidth,
            targetRegister =
                (word and 0x1fL)
                    .toInt(),
            baseRegister =
                ((word ushr 5) and
                    0x1fL).toInt(),
            byteOffset =
                imm12 *
                    byteWidth,
        )
    }
}
