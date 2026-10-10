package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.analysis.nativecode.AArch64ReadOnlyBody
import io.github.ffenuss.modkit.patch.AArch64ScalarReturnEncoder
import io.github.ffenuss.modkit.patch.Il2CppNativeMutationDraftBuilder

/** Each backend owns its exact instruction whitelist and platform return ABI. */
internal object JniAbiRecipe {
    val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    data class Proof(val supported: Boolean, val reason: String, val prefix: ByteArray = byteArrayOf())
    fun machine(abi: String) = when (abi) {
        "arm64-v8a" -> 183; "armeabi-v7a" -> 40; "x86_64" -> 62; else -> -1
    }
    fun is64Bit(abi: String) = abi in setOf("arm64-v8a", "x86_64")
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
        if (abi == "x86_64") {
            var offset = 0
            val landing = byteArrayOf(0xf3.toByte(), 0x0f, 0x1e, 0xfa.toByte())
            val prefix = if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(landing)) {
                offset = 4; landing
            } else byteArrayOf()
            fun u(index: Int) = bytes.getOrNull(index)?.toInt()?.and(255) ?: -1
            while (offset < bytes.size) {
                val remaining = bytes.size - offset
                val length = when {
                    u(offset) == 0xc3 -> return Proof(true, "x86_64 leaf без вызовов и записи памяти", prefix)
                    u(offset) == 0x90 -> 1
                    u(offset) == 0xb8 -> 5 // MOV EAX, imm32
                    u(offset) == 0x48 && u(offset + 1) == 0xb8 -> 10 // MOV RAX, imm64
                    u(offset) == 0x66 && u(offset + 1) == 0x0f && u(offset + 2) == 0x6e && u(offset + 3) == 0xc0 -> 4 // MOVD XMM0,EAX
                    u(offset) == 0x66 && u(offset + 1) == 0x48 && u(offset + 2) == 0x0f && u(offset + 3) == 0x6e && u(offset + 4) == 0xc0 -> 5 // MOVQ XMM0,RAX
                    u(offset) == 0x8d && u(offset + 1) == 0x42 -> 3 // LEA EAX,[RDX+disp8], no memory access
                    else -> return Proof(false, "x86_64: инструкция не входит в проверенный leaf-поднабор")
                }
                if (length > remaining) return Proof(false, "Неполная инструкция x86_64")
                offset += length
            }
            return Proof(false, "x86_64: возврат не доказан")
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
            "x86_64" -> {
                // Keep native_v1's aligned, four-byte-range contract. NOP padding follows RET.
                fun immediate(size: Int) = ByteArray(size) { (bits ushr (8 * it)).toByte() }
                val mov = if (result in setOf("J", "D")) byteArrayOf(0x48, 0xb8.toByte()) + immediate(8)
                    else byteArrayOf(0xb8.toByte()) + immediate(4)
                val transfer = when (result) {
                    "F" -> byteArrayOf(0x66, 0x0f, 0x6e, 0xc0.toByte())
                    "D" -> byteArrayOf(0x66, 0x48, 0x0f, 0x6e, 0xc0.toByte())
                    else -> byteArrayOf()
                }
                val body = prefix + mov + transfer + byteArrayOf(0xc3.toByte())
                body + ByteArray((4 - body.size % 4) % 4) { 0x90.toByte() }
            }
            else -> null
        }
    }
}
