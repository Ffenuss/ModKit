package io.github.ffenuss.modkit.analysis

import java.io.Serializable

/**
 * One structurally identified exported native function whose first instructions
 * are exactly "return a small scalar constant". This is not game semantics:
 * nothing in an ELF instruction proves the function controls movement, health
 * or any other in-game property.
 */
data class NativeScalarExportCandidate(
    val abi: String,
    val symbol: String,
    val binaryVirtualAddress: Long,
    val fileOffset: Long,
    val patchVirtualAddress: Long,
    val value: Int,
    val originalBodyHex: String,
    val expectedPatchHex: String,
    val selectable: Boolean = false,
    val blocker: String = "Structural ELF match only; return type, gameplay role and effect unverified.",
) : Serializable

/** Explicit, byte-exact 4-byte proposal. Never install or execute implicitly. */
data class NativeScalarExportPatch(
    val symbol: String,
    val abi: String,
    val patchVirtualAddress: Long,
    val expectedHex: String,
    val replacementHex: String,
) : Serializable

/**
 * Bounded discovery over the existing audited [ElfImage] parser. No name
 * dictionary, fixture-specific symbol or disassembler heuristics are used.
 *
 * Exact supported shapes (only global/weak STT_FUNC, file-backed executable):
 * - ARM64: MOVZ W0,#imm16 (shift 0); RET
 * - x86_64: MOV EAX,imm32 (0..65535); RET
 *
 * These are exported-symbol *candidates*, not ready-to-apply game mods.
 */
object NativeScalarExportDiscovery {
    const val MAX_CANDIDATES_PER_LIBRARY = 128
    private const val ARM64 = "arm64-v8a"
    private const val X64 = "x86_64"

    fun scan(
        elf: ElfImage,
        cancellation: CancellationSignal,
    ): List<NativeScalarExportCandidate> {
        val abi = when {
            elf.is64Bit && elf.machine == 183 -> ARM64
            elf.is64Bit && elf.machine == 62 -> X64
            else -> return emptyList()
        }
        val minimumLength = if (abi == ARM64) 8 else 6
        val functions = elf.dynamicSymbols.filter {
            it.defined && it.type == 2 && it.binding in 1..2 &&
                it.value > 0 && it.size in minimumLength.toLong()..32L &&
                it.name.isNotBlank()
        }
        // If two exported names refer to the same code, do not create
        // independently selectable per-name patches for shared bodies.
        val addresses = elf.dynamicSymbols.asSequence()
            .filter { it.defined && it.type == 2 && it.value > 0 }
            .groupingBy { it.value }
            .eachCount()
        val found = ArrayList<NativeScalarExportCandidate>()
        functions.sortedWith(compareBy<ElfDynamicSymbol> { it.value }.thenBy { it.name })
            .forEachIndexed { index, symbol ->
                if (index % 128 == 0 && cancellation.isCancelled()) {
                    throw AnalysisCancelledException()
                }
                if (found.size == MAX_CANDIDATES_PER_LIBRARY) return found
                if (addresses[symbol.value] != 1) return@forEachIndexed
                if (abi == ARM64 && symbol.value % 4L != 0L) return@forEachIndexed
                val withinExecutableSegment = elf.loadSegments.any { segment ->
                    segment.executable &&
                        symbol.value >= segment.virtualAddress &&
                        symbol.value - segment.virtualAddress <=
                            segment.fileSize - minimumLength.toLong()
                }
                if (!withinExecutableSegment) return@forEachIndexed
                val body = elf.readFileWindowAtVa(symbol.value, minimumLength)
                    ?: return@forEachIndexed
                if (body.size != minimumLength) return@forEachIndexed
                val scalar = recognizeBody(abi, body) ?: return@forEachIndexed
                val fileOffset = elf.fileOffsetForVa(symbol.value, minimumLength.toLong())
                    ?: return@forEachIndexed
                val patchStart = if (abi == X64) 1 else 0
                val patch = body.copyOfRange(patchStart, patchStart + 4)
                found += NativeScalarExportCandidate(
                    abi = abi,
                    symbol = symbol.name,
                    binaryVirtualAddress = symbol.value,
                    fileOffset = fileOffset,
                    patchVirtualAddress = symbol.value + patchStart,
                    value = scalar,
                    originalBodyHex = body.hex(),
                    expectedPatchHex = patch.hex(),
                )
            }
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
        return found
    }

    /**
     * A byte-only draft for *explicit* user confirmation after verifying
     * the exported function's return type and in-game role. A caller must
     * re-verify original APK/source identity and exact runtime bytes.
     */
    fun propose(
        candidate: NativeScalarExportCandidate,
        newValue: Int,
    ): NativeScalarExportPatch {
        require(newValue in 0..65535) {
            "Only exact nonnegative small 32-bit integer returns are supported"
        }
        require(newValue != candidate.value) { "Replacement would be a no-op" }
        val original = candidate.originalBodyHex.toByteArrayFromHex()
        require(recognizeBody(candidate.abi, original) == candidate.value) {
            "Native candidate no longer matches exact machine-code shape"
        }
        val patchStart = if (candidate.abi == X64) 1 else 0
        val currentPatch = original.copyOfRange(patchStart, patchStart + 4).hex()
        require(currentPatch == candidate.expectedPatchHex) {
            "Expected original bytes do not match the candidate body"
        }
        require(candidate.patchVirtualAddress ==
            candidate.binaryVirtualAddress + patchStart) {
            "Candidate patch address does not match the instruction"
        }
        val replacement = if (candidate.abi == ARM64) {
            val movz = 0x52800000 or (newValue shl 5)
            le32(movz)
        } else {
            le32(newValue)
        }
        return NativeScalarExportPatch(
            symbol = candidate.symbol,
            abi = candidate.abi,
            patchVirtualAddress = candidate.patchVirtualAddress,
            expectedHex = currentPatch,
            replacementHex = replacement.hex(),
        )
    }

    /** Pure decoder for known exact instruction bodies, used by JVM tests. */
    internal fun recognizeBody(abi: String, body: ByteArray): Int? =
        when (abi) {
            ARM64 -> {
                if (body.size != 8) null else {
                    val word = u32(body, 0)
                    val ret = u32(body, 4)
                    if ((word and 0xffe0001f.toInt()) != 0x52800000 ||
                        ret != 0xd65f03c0.toInt()
                    ) null else (word ushr 5) and 0xffff
                }
            }
            X64 -> {
                if (body.size != 6 ||
                    (body[0].toInt() and 0xff) != 0xb8 ||
                    (body[5].toInt() and 0xff) != 0xc3 ||
                    body[3] != 0.toByte() || body[4] != 0.toByte()
                ) null else (body[1].toInt() and 0xff) or
                    ((body[2].toInt() and 0xff) shl 8)
            }
            else -> null
        }

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun le32(value: Int): ByteArray =
        ByteArray(4) { index -> (value ushr (index * 8)).toByte() }

    private fun ByteArray.hex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.toByteArrayFromHex(): ByteArray {
        require(length in 12..16 && length % 2 == 0 && all { it.isHexDigit() }) {
            "Invalid bounded machine-code body"
        }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
