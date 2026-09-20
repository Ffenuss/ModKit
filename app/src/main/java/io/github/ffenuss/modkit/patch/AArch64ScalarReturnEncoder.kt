package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind

/**
 * Encodes a small, ABI-safe ARM64 function body for proven scalar return
 * types. It never emits branches, relocations or memory accesses.
 *
 * The body is intended only for an in-place method replacement after the
 * normal unique-body and method-boundary preflight has passed.
 */
object AArch64ScalarReturnEncoder {
    fun encodeHex(
        returnKind: Il2CppNativeReturnKind,
        valueText: String,
    ): String {
        val bytes =
            when (returnKind) {
                Il2CppNativeReturnKind.INTEGER ->
                    encodeInteger(
                        valueText.trim().toLongOrNull()
                            ?: error(
                                "Введите целое число в диапазоне Int64.",
                            ),
                    )
                Il2CppNativeReturnKind.FLOAT32 -> {
                    val value =
                        valueText.trim().toFloatOrNull()
                            ?: error(
                                "Введите корректное Float-значение.",
                            )
                    require(value.isFinite()) {
                        "NaN/Infinity не поддерживаются."
                    }
                    encodeFloat(value)
                }
                Il2CppNativeReturnKind.FLOAT64 -> {
                    val value =
                        valueText.trim().toDoubleOrNull()
                            ?: error(
                                "Введите корректное Double-значение.",
                            )
                    require(value.isFinite()) {
                        "NaN/Infinity не поддерживаются."
                    }
                    encodeDouble(value)
                }
                else ->
                    error(
                        "Произвольное числовое значение доступно только для доказанного integer/float/double return type.",
                    )
            }
        return bytes.toDisplayHex()
    }

    fun encodeInteger(
        value: Long,
    ): ByteArray =
        buildList {
            addAll(
                encodeMoveWide(
                    register = 0,
                    bits = value,
                    widthBits = 64,
                ),
            )
            add(RET)
        }.toBytes()

    fun encodeFloat(
        value: Float,
    ): ByteArray {
        val raw =
            value.toRawBits()
                .toLong()
                .and(0xffffffffL)
        return buildList {
            addAll(
                encodeMoveWide(
                    register = SCRATCH_REGISTER,
                    bits = raw,
                    widthBits = 32,
                ),
            )
            add(
                FMOV_S_FROM_W_BASE or
                    (SCRATCH_REGISTER.toLong() shl 5),
            )
            add(RET)
        }.toBytes()
    }

    fun encodeDouble(
        value: Double,
    ): ByteArray {
        val raw =
            value.toRawBits()
        return buildList {
            addAll(
                encodeMoveWide(
                    register = SCRATCH_REGISTER,
                    bits = raw,
                    widthBits = 64,
                ),
            )
            add(
                FMOV_D_FROM_X_BASE or
                    (SCRATCH_REGISTER.toLong() shl 5),
            )
            add(RET)
        }.toBytes()
    }

    private fun encodeMoveWide(
        register: Int,
        bits: Long,
        widthBits: Int,
    ): List<Long> {
        require(register in 0..30)
        require(widthBits == 32 || widthBits == 64)

        val halfWords =
            widthBits / 16
        val chunks =
            IntArray(halfWords) { index ->
                (
                    (bits ushr (index * 16)) and
                        0xffffL
                    ).toInt()
            }
        val firstNonZero =
            chunks.indexOfFirst { it != 0 }
                .let {
                    if (it < 0) 0 else it
                }

        val is64 =
            widthBits == 64
        val movzBase =
            if (is64) {
                MOVZ_X_BASE
            } else {
                MOVZ_W_BASE
            }
        val movkBase =
            if (is64) {
                MOVK_X_BASE
            } else {
                MOVK_W_BASE
            }

        val out =
            mutableListOf<Long>()
        out +=
            movzBase or
                (firstNonZero.toLong() shl 21) or
                (chunks[firstNonZero].toLong() shl 5) or
                register.toLong()

        chunks.forEachIndexed {
                index,
                chunk,
            ->
            if (
                index != firstNonZero &&
                chunk != 0
            ) {
                out +=
                    movkBase or
                        (index.toLong() shl 21) or
                        (chunk.toLong() shl 5) or
                        register.toLong()
            }
        }
        return out
    }

    private fun List<Long>.toBytes(): ByteArray {
        val out =
            ByteArray(size * 4)
        forEachIndexed {
                index,
                word,
            ->
            val base = index * 4
            out[base] =
                word.toByte()
            out[base + 1] =
                (word ushr 8).toByte()
            out[base + 2] =
                (word ushr 16).toByte()
            out[base + 3] =
                (word ushr 24).toByte()
        }
        return out
    }

    private fun ByteArray.toDisplayHex(): String =
        joinToString(" ") {
            "%02X".format(
                it.toInt() and 0xff,
            )
        }

    private const val SCRATCH_REGISTER = 16
    private const val MOVZ_W_BASE = 0x52800000L
    private const val MOVK_W_BASE = 0x72800000L
    private const val MOVZ_X_BASE = 0xD2800000L
    private const val MOVK_X_BASE = 0xF2800000L
    private const val FMOV_S_FROM_W_BASE = 0x1E270000L
    private const val FMOV_D_FROM_X_BASE = 0x9E670000L
    private const val RET = 0xD65F03C0L
}
