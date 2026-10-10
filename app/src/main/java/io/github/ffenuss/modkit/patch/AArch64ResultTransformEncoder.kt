package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.analysis.nativecode.AArch64FloatImmediate

/** Retains the original instance field read and scales its live result.
 * Only an exact scalar getter plus verified NOP space inside its indexed span is accepted.
 * No trampoline, allocation, call, state write or integer-width inference is involved.
 */
object AArch64ResultTransformEncoder {
    private const val RET = 0xD65F03C0L
    private const val NOP = 0xD503201FL
    private val landingPads = setOf(0xD503241FL, 0xD503245FL, 0xD503249FL, 0xD50324DFL)

    fun encodeHex(original: ByteArray, returnKind: Il2CppNativeReturnKind,
        factorText: String, capacity: Int): String {
        require(returnKind == Il2CppNativeReturnKind.FLOAT32 || returnKind == Il2CppNativeReturnKind.FLOAT64) {
            "Множитель требует подтверждённый Float или Double."
        }
        val factor = requireNotNull(factorText.toDoubleOrNull()) { "Некорректный множитель." }
        require(factor.isFinite() && factor > 0.0 && factor <= 16.0 && factor != 1.0) {
            "Множитель должен быть положительным, не равным 1 и не больше 16."
        }
        val double = returnKind == Il2CppNativeReturnKind.FLOAT64
        val bits = if (double) factor.toRawBits() else factor.toFloat().toRawBits().toLong() and 0xffffffffL
        require(bits != 0L && bits != (if (double) 1.0.toRawBits() else 1f.toRawBits().toLong())) { "Множитель округляется до 0 или 1." }
        val immediate = requireNotNull(AArch64FloatImmediate.encode(bits, double)) {
            "Множитель не помещается в непосредственное FP-значение."
        }
        require(original.size % 4 == 0 && capacity in 4..original.size) { "Нет полного окна ARM64." }
        val words = LongArray(original.size / 4) { i ->
            (0..3).fold(0L) { value, b -> value or ((original[i * 4 + b].toLong() and 255) shl (8 * b)) }
        }
        val start = if (words.firstOrNull() in landingPads) 1 else 0
        require(words.size >= start + 4 && capacity >= (start + 4) * 4) { "Нет места для множителя до следующего метода." }
        // LDR S0/D0, [X0, #unsigned scaled offset]. Keep this exact instruction.
        val load = words[start]
        require(load and 0xFFC003FFL == if (double) 0xFD400000L else 0xBD400000L) {
            "Множитель поддерживает только одиночное чтение Float/Double из экземпляра."
        }
        var end = start + 1
        while (end < words.size && words[end] == NOP) end++
        require(end < words.size && words[end] == RET) { "Тело не является одиночным геттером без вызовов и записи состояния." }
        // A short getter may have alignment NOPs after RET. Never consume another instruction.
        val needed = maxOf(end + 1, start + 4)
        require(needed * 4 <= capacity && needed <= words.size &&
            (end + 1 until needed).all { words[it] == NOP }) { "После возврата нет подтверждённого NOP-пространства." }
        val out = words.copyOf(needed)
        out[start + 1] = immediate or 16L // FMOV S16/D16, #factor; V16 is caller-saved.
        out[start + 2] = (if (double) 0x1E600800L else 0x1E200800L) or (16L shl 16) // FMUL S0/D0, S0/D0, S16/D16
        out[start + 3] = RET
        return out.flatMap { word -> (0..3).map { ((word ushr (it * 8)) and 255).toInt() } }
            .joinToString(" ") { "%02X".format(it) }
    }
}
