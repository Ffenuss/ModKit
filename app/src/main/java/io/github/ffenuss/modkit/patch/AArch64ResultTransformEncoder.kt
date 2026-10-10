package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.analysis.nativecode.AArch64FloatImmediate
import io.github.ffenuss.modkit.analysis.nativecode.AArch64ReadOnlyBody

/** Retains the original instance field read and scales its live result.
 * An exact entry field read may be followed by proven read-only computation and branches.
 * Returns expand into unreachable NOP space inside the indexed span; instructions do not move.
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
        val out: LongArray
        if (end < words.size && words[end] == RET) {
            // Compact straight-line getter: NOPs before or after RET can be reused.
            val needed = maxOf(end + 1, start + 4)
            require(needed * 4 <= capacity && needed <= words.size &&
                (end + 1 until needed).all { words[it] == NOP }) { "После возврата нет подтверждённого NOP-пространства." }
            out = words.copyOf(needed)
            out[start + 1] = immediate or 16L
            out[start + 2] = (if (double) 0x1E600800L else 0x1E200800L) or (16L shl 16)
            out[start + 3] = RET
        } else {
            // Inspect only bytes inside the verified method boundary. No branch relocation.
            val proof = AArch64ReadOnlyBody.inspect(original.copyOf(capacity / 4 * 4))
            require(proof.supported) { proof.reason }
            // The unconditional entry load initializes S0/D0 on every path. This revision
            // requires all later scalar FP instructions to retain that same precision.
            require(proof.reachableOffsets.all { offset ->
                val word = words[offset / 4]
                when {
                    word and 0x3F000000L == 0x3D000000L ->
                        (word ushr 30) and 3L == if (double) 3L else 2L
                    word and 0x3B000000L == 0x18000000L && word and 0x04000000L != 0L -> false
                    word and 0x7F000000L == 0x1E000000L ->
                        (word and 0x00400000L != 0L) == double
                    else -> true
                }
            }) { "Вычисление меняет FP-ширину или использует неподдержанное FP-чтение." }
            val returns = proof.returnOffsets.map { it / 4 }
            require(returns.isNotEmpty() && returns.all { index ->
                (index + 3) * 4 <= capacity && (index + 1..index + 2).all {
                    it in words.indices && words[it] == NOP && it * 4 !in proof.reachableOffsets
                }
            }) { "Для каждого возврата нужны два недостижимых NOP внутри границы метода." }
            val needed = maxOf(proof.reachableOffsets.maxOrNull()!! / 4 + 1, returns.maxOrNull()!! + 3)
            out = words.copyOf(needed)
            for (index in returns) {
                out[index] = immediate or 16L // V16 is caller-saved; original result is already computed.
                out[index + 1] = (if (double) 0x1E600800L else 0x1E200800L) or (16L shl 16)
                out[index + 2] = RET
            }
        }
        return out.flatMap { word -> (0..3).map { ((word ushr (it * 8)) and 255).toInt() } }
            .joinToString(" ") { "%02X".format(it) }
    }
}
