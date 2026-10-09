package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.analysis.nativecode.AArch64ReadOnlyBody
import io.github.ffenuss.modkit.patch.AArch64ScalarReturnEncoder
import io.github.ffenuss.modkit.patch.Il2CppNativeMutationDraftBuilder

/** Each backend owns its exact instruction whitelist and platform return ABI. */
internal object JniAbiRecipe {
    val supportedAbis = setOf("arm64-v8a", "armeabi-v7a")
    data class Proof(val supported: Boolean, val reason: String, val prefix: ByteArray = byteArrayOf())
    fun machine(abi: String) = when (abi) {
        "arm64-v8a" -> 183; "armeabi-v7a" -> 40; else -> -1
    }
    fun is64Bit(abi: String) = abi == "arm64-v8a"
    fun inspect(abi: String, bytes: ByteArray): Proof {
        if (abi == "arm64-v8a") {
            val p = AArch64ReadOnlyBody.inspect(bytes)
            return Proof(p.supported, p.reason, p.entryLandingPad?.let(::word) ?: byteArrayOf())
        }
        if (abi == "armeabi-v7a") {
            // ARM state only. Thumb/interworking and hard-float require separate evidence.
            if (bytes.size % 4 != 0) return Proof(false, "Неполное тело ARM; Thumb ещё не поддерживается")
            var offset = 0
            while (offset + 4 <= bytes.size) {
                val w = (0..3).fold(0L) { a, b -> a or ((bytes[offset + b].toLong() and 255) shl (8 * b)) }
                if (w == 0xe12fff1eL) return Proof(true, "ARM leaf: константы и возврат без записи состояния")
                // Unconditional MOV/MOVW/MOVT into r0/r1 only. No SP/LR/PC or memory operations.
                if (!(w and 0xffffe000L == 0xe3a00000L ||
                    w and 0xfff0e000L == 0xe3000000L || w and 0xfff0e000L == 0xe3400000L))
                    return Proof(false, "ARM: не доказан leaf-геттер без вызовов и записи")
                offset += 4
            }
            return Proof(false, "ARM: возврат не доказан")
        }
        return Proof(false, "Нет проверенного JNI-рецепта для ABI $abi")
    }
    private fun word(value: Long) = ByteArray(4) { (value ushr (8 * it)).toByte() }
    private fun armMove(register: Int, value: Long): ByteArray {
        val bits = value and 0xffffffffL
        // ARM modified immediate encodes an 8-bit value rotated right by an even count.
        for (rotation in 0..15) for (imm in 0..255) {
            if ((Integer.rotateRight(imm, rotation * 2).toLong() and 0xffffffffL) == bits)
                return word(0xe3a00000L or (register.toLong() shl 12) or (rotation.toLong() shl 8) or imm.toLong())
        }
        fun wide(base: Long, value16: Long) = word(base or (register.toLong() shl 12) or
            ((value16 and 0xf000) shl 4) or (value16 and 0xfff))
        return wide(0xe3000000L, bits and 0xffff) +
            if (bits ushr 16 != 0L) wide(0xe3400000L, bits ushr 16) else byteArrayOf()
    }
    fun encode(abi: String, result: String, value: String, prefix: ByteArray = byteArrayOf()): ByteArray? {
        if (abi == "arm64-v8a") return prefix + Il2CppNativeMutationDraftBuilder.parseHex(
            AArch64ScalarReturnEncoder.encodeHex(requireNotNull(JniSpaceRecipeScanner.returnKind(result)), value))
        val bits = when (result) {
            "F" -> value.toFloat().toBits().toLong() and 0xffffffffL
            "D" -> value.toDouble().toBits()
            "Z", "B", "S", "C", "I", "J" -> value.toLong()
            else -> return null
        }
        return when (abi) {
            "armeabi-v7a" -> armMove(0, bits) +
                (if (result in setOf("J", "D")) armMove(1, bits ushr 32) else byteArrayOf()) + word(0xe12fff1eL)
            else -> null
        }
    }
}
