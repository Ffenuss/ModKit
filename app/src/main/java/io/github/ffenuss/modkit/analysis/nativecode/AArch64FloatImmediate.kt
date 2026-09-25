package io.github.ffenuss.modkit.analysis.nativecode

/** Architectural VFPExpandImm, shared by the scalar emitter and recognizer. */
object AArch64FloatImmediate {
    fun bits(immediate: Int, double: Boolean): Long {
        require(immediate in 0..255)
        val b = (immediate ushr 6) and 1
        val exponent = ((1 - b) shl (if (double) 10 else 7)) or
            (if (b == 1) { if (double) 0x3fc else 0x7c } else 0) or ((immediate ushr 4) and 3)
        return ((immediate.toLong() ushr 7) shl (if (double) 63 else 31)) or
            (exponent.toLong() shl (if (double) 52 else 23)) or
            ((immediate.toLong() and 15) shl (if (double) 48 else 19))
    }

    fun encode(raw: Long, double: Boolean): Long? {
        if (raw == 0L) return if (double) 0x9E6703E0L else 0x1E2703E0L // FMOV from ZR
        val immediate = (0..255).firstOrNull { bits(it, double) == raw } ?: return null
        return (if (double) 0x1E601000L else 0x1E201000L) or (immediate.toLong() shl 13)
    }
}
