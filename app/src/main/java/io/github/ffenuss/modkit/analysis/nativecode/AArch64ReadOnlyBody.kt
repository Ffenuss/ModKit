package io.github.ffenuss.modkit.analysis.nativecode

data class AArch64ReadOnlyProof(
    val supported: Boolean,
    val reason: String,
    val visitedInstructions: Int = 0,
    val returnSites: Int = 0,
    val entryLandingPad: Long? = null,
)

/**
 * Checks reachable control flow, not a fixed two-instruction pattern.
 * Only an explicit set of register operations and ordinary loads is accepted.
 * Calls, stores, indirect jumps, loops, truncated paths and unknown encodings
 * remain unsupported. This proves absence of writes/calls, not gameplay intent.
 */
object AArch64ReadOnlyBody {
    private val landingPads = setOf(0xD503241FL, 0xD503245FL, 0xD503249FL, 0xD50324DFL)

    fun inspect(bytes: ByteArray): AArch64ReadOnlyProof {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return AArch64ReadOnlyProof(false, "Неполное окно инструкций ARM64.")
        val words = LongArray(bytes.size / 4) { i ->
            (0..3).fold(0L) { value, b -> value or ((bytes[i * 4 + b].toLong() and 255) shl (b * 8)) }
        }
        val state = IntArray(words.size)
        var visited = 0
        var returns = 0
        var failure: String? = null
        fun relative(value: Long, bits: Int): Int = ((value shl (64 - bits)) shr (64 - bits)).toInt()
        fun visit(index: Int): Boolean {
            if (index !in words.indices) { failure = "Путь выходит за доступное окно: нужен дальнейший анализ тела."; return false }
            if (state[index] == 1) { failure = "Обнаружен цикл: завершение вычисления ещё не доказано."; return false }
            if (state[index] == 2) return true
            state[index] = 1
            visited++
            val word = words[index]
            val successors: IntArray
            when {
                word == 0xD65F03C0L -> { returns++; successors = intArrayOf() }
                word and 0xFC000000L == 0x14000000L ->
                    successors = intArrayOf(index + relative(word and 0x3ffffffL, 26))
                word and 0xFF000010L == 0x54000000L ->
                    successors = intArrayOf(index + 1, index + relative((word ushr 5) and 0x7ffffL, 19))
                word and 0x7E000000L == 0x34000000L ->
                    successors = intArrayOf(index + 1, index + relative((word ushr 5) and 0x7ffffL, 19))
                word and 0x7E000000L == 0x36000000L ->
                    successors = intArrayOf(index + 1, index + relative((word ushr 5) and 0x3fffL, 14))
                isReadOnlyInstruction(word) -> successors = intArrayOf(index + 1)
                else -> {
                    failure = when {
                        word and 0xFC000000L == 0x94000000L || word and 0xFFFFFC1FL == 0xD63F0000L ->
                            "Выполняется вызов другой функции; его побочные эффекты ещё не восстановлены."
                        word and 0x3B000000L == 0x39000000L && (word ushr 22) and 3L == 0L ->
                            "Метод записывает состояние; замена всего тела удалит эту запись."
                        else -> "Нужен анализ инструкции 0x${word.toString(16)} на смещении ${index * 4}."
                    }
                    return false
                }
            }
            if (!successors.all(::visit)) return false
            state[index] = 2
            return true
        }
        val complete = visit(0) && returns > 0
        return AArch64ReadOnlyProof(complete,
            if (complete) "Все достижимые пути возвращают результат без вызовов и записи состояния."
            else failure ?: "Возврат из метода не доказан.", visited, returns,
            words.first().takeIf { it in landingPads })
    }

    private fun isReadOnlyInstruction(w: Long): Boolean {
        if (w == 0xD503201FL || w in landingPads) return true
        // Address formation; move-wide; ordinary integer arithmetic/logic.
        if (w and 0x9F000000L in setOf(0x10000000L, 0x90000000L)) return true
        if (w and 0x7F800000L in setOf(0x12800000L, 0x52800000L, 0x72800000L))
            return w and 0x80000000L != 0L || (w ushr 21) and 3L < 2
        if (w and 0x1F800000L == 0x11000000L) return true // add/sub immediate
        if (w and 0x1F200000L == 0x0B000000L) // add/sub shifted register
            return (w ushr 22) and 3L != 3L && (w and 0x80000000L != 0L || (w ushr 10) and 63L < 32)
        if (w and 0x1F000000L == 0x0A000000L) // logical shifted register, including MOV
            return w and 0x80000000L != 0L || (w ushr 10) and 63L < 32
        if (w and 0x1F800000L == 0x12000000L) // logical immediate
            return (w and 0x80000000L != 0L || w and 0x00400000L == 0L) && (w ushr 10) and 63L != 63L
        if (w and 0x1F800000L == 0x13000000L) // bitfield aliases
            return (w ushr 29) and 3L != 3L && (w ushr 31) and 1L == (w ushr 22) and 1L &&
                (w and 0x80000000L != 0L || ((w ushr 16) and 63L < 32 && (w ushr 10) and 63L < 32))
        if (w and 0x3FE00800L == 0x1A800000L) return true // conditional select aliases
        if (w and 0x7FE00000L == 0x1AC00000L && (w ushr 10) and 63L in setOf(2L, 3L, 8L, 9L, 10L, 11L)) return true
        if (w and 0x7FE00000L == 0x1B000000L) return true // MADD/MSUB
        // Scalar loads only. Exclude store, prefetch, SIMD vector and atomic classes.
        val memory = w and 0x3F000000L
        val opc = (w ushr 22) and 3L
        val size = (w ushr 30) and 3L
        if (memory == 0x39000000L && (opc == 1L || opc == 2L && size <= 2L || opc == 3L && size <= 1L)) return true
        if (memory == 0x3D000000L && opc == 1L && size in 2L..3L) return true
        if (w and 0x3B200C00L == 0x38000000L && w and 0x04000000L == 0L && opc == 1L) return true // LDUR
        if (w and 0x3B000000L == 0x18000000L && size <= 2L) return true // literal scalar load
        // Scalar FP operations have no memory writes or calls.
        if (w and 0xFFFFFC00L in setOf(0x1E260000L, 0x1E270000L, 0x9E660000L, 0x9E670000L)) return true
        if (w and 0xFFE01FE0L in setOf(0x1E201000L, 0x1E601000L)) return true // FMOV immediate
        if (w and 0x00C00000L !in setOf(0L, 0x00400000L)) return false
        if (w and 0xFF3FFC00L in setOf(0x1E204000L, 0x1E20C000L, 0x1E214000L, 0x1E21C000L)) return true
        if (w and 0xFF20FC00L in setOf(0x1E200800L, 0x1E201800L, 0x1E202800L, 0x1E203800L,
                0x1E204800L, 0x1E205800L, 0x1E206800L, 0x1E207800L, 0x1E208800L)) return true
        return w and 0xFF20FC1FL == 0x1E202000L // FCMP registers
    }
}
