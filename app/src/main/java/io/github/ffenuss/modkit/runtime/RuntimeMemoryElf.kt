package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.RandomAccessFile
import java.io.Serializable
import java.security.MessageDigest

enum class RuntimeMemoryElfValidationStatus {
    VALIDATED,
    NOT_READABLE,
    NOT_ELF,
    BLOCKED,
}

data class RuntimeMemoryElfEvidence(
    val candidateStart: Long,
    val candidateEndExclusive: Long,
    val candidatePath: String?,
    val headerAddress: Long,
    val status: RuntimeMemoryElfValidationStatus,
    val is64Bit: Boolean?,
    val machine: Int?,
    val elfType: Int?,
    val programHeaderCount: Int?,
    val loadSegmentCount: Int,
    val executableLoadSegmentCount: Int,
    val executableCandidateSegmentMatched: Boolean,
    val headerSha256: String?,
    val bytesRead: Int,
    val blockers: List<String>,
) : Serializable {
    val validated: Boolean
        get() =
            status == RuntimeMemoryElfValidationStatus.VALIDATED &&
                blockers.isEmpty() &&
                executableCandidateSegmentMatched
}

fun interface RuntimeMemoryReader {
    fun read(
        address: Long,
        size: Int,
        cancellation: CancellationSignal,
    ): ByteArray?
}

/**
 * Bounded reader for /proc/<pid>/mem.
 *
 * Linux/Android access controls decide whether the read is permitted. Failure
 * returns null and is never interpreted as negative proof. This class does not
 * request root or change ptrace state.
 */
class ProcMemRuntimeMemoryReader(
    private val pid: Int,
) : RuntimeMemoryReader {
    override fun read(
        address: Long,
        size: Int,
        cancellation: CancellationSignal,
    ): ByteArray? {
        if (pid <= 0 || address < 0L || size !in 1..MAX_READ_BYTES) return null
        checkCancelled(cancellation)

        return runCatching {
            RandomAccessFile("/proc/$pid/mem", "r").use { raf ->
                raf.seek(address)
                val out = ByteArray(size)
                raf.readFully(out)
                checkCancelled(cancellation)
                out
            }
        }.getOrNull()
    }

    companion object {
        const val MAX_READ_BYTES = 256 * 1024
    }
}

/**
 * Validates whether a special/anonymous executable mapping is backed by a
 * structurally valid in-memory ELF image.
 *
 * Validation is intentionally narrow:
 * - ELF magic/class/data/version must be valid;
 * - only little-endian ELF is accepted by the current parser;
 * - program headers are read through a bounded second read;
 * - at least one PT_LOAD and one executable PT_LOAD must exist;
 * - the candidate mapping's file offset must reconcile with an executable
 *   PT_LOAD for one supported Android page size.
 *
 * This evidence describes the mapped image only. It does not identify a method,
 * does not resolve symbols, and never grants RUNTIME_CONFIRMED by itself.
 */
object RuntimeMemoryElfValidator {
    private const val ELF32_HEADER_BYTES = 52
    private const val ELF64_HEADER_BYTES = 64
    private const val PT_LOAD = 1L
    private const val PF_X = 1L
    private const val ET_EXEC = 2
    private const val ET_DYN = 3
    private const val MAX_PROGRAM_HEADERS = 1024
    private const val MAX_PROGRAM_HEADER_TABLE_BYTES = 256 * 1024
    private const val MAX_PROGRAM_HEADER_OFFSET = 1024 * 1024L
    private val pageSizes = listOf(4L * 1024L, 16L * 1024L, 64L * 1024L)

    fun validate(
        candidate: RuntimeMemoryMappingCandidate,
        reader: RuntimeMemoryReader,
        cancellation: CancellationSignal,
    ): RuntimeMemoryElfEvidence {
        checkCancelled(cancellation)

        val headerAddress = candidate.fileZeroAddressCandidate
        if (headerAddress < 0L || headerAddress > candidate.start) {
            return blocked(
                candidate = candidate,
                headerAddress = headerAddress,
                reason = "File-zero address candidate is outside the valid address range.",
            )
        }

        val headerBytes = reader.read(
            address = headerAddress,
            size = ELF64_HEADER_BYTES,
            cancellation = cancellation,
        ) ?: return unreadable(candidate, headerAddress)

        if (!hasElfMagic(headerBytes)) {
            return RuntimeMemoryElfEvidence(
                candidateStart = candidate.start,
                candidateEndExclusive = candidate.endExclusive,
                candidatePath = candidate.path,
                headerAddress = headerAddress,
                status = RuntimeMemoryElfValidationStatus.NOT_ELF,
                is64Bit = null,
                machine = null,
                elfType = null,
                programHeaderCount = null,
                loadSegmentCount = 0,
                executableLoadSegmentCount = 0,
                executableCandidateSegmentMatched = false,
                headerSha256 = sha256(headerBytes),
                bytesRead = headerBytes.size,
                blockers = listOf(
                    "ELF magic is absent at the mapping's file-zero address.",
                ),
            )
        }

        val elfClass = u8(headerBytes, 4)
        val is64 = when (elfClass) {
            1 -> false
            2 -> true
            else -> {
                return blocked(
                    candidate,
                    headerAddress,
                    "Unsupported ELF class: $elfClass.",
                    headerSha = sha256(headerBytes),
                    bytesRead = headerBytes.size,
                )
            }
        }
        if (u8(headerBytes, 5) != 1) {
            return blocked(
                candidate,
                headerAddress,
                "Only little-endian in-memory ELF is currently supported.",
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size,
            )
        }
        if (u8(headerBytes, 6) != 1) {
            return blocked(
                candidate,
                headerAddress,
                "Invalid ELF identification version.",
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size,
            )
        }

        val minHeaderBytes = if (is64) ELF64_HEADER_BYTES else ELF32_HEADER_BYTES
        if (headerBytes.size < minHeaderBytes) {
            return blocked(
                candidate,
                headerAddress,
                "ELF header is truncated.",
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size,
            )
        }

        val elfType = u16(headerBytes, 16)
        val machine = u16(headerBytes, 18)
        if (elfType !in setOf(ET_EXEC, ET_DYN)) {
            return blocked(
                candidate,
                headerAddress,
                "Mapped ELF type is not ET_EXEC or ET_DYN.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size,
            )
        }
        if (machine == 0) {
            return blocked(
                candidate,
                headerAddress,
                "ELF machine is EM_NONE.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size,
            )
        }

        val phoff = if (is64) {
            u64(headerBytes, 32)
        } else {
            u32(headerBytes, 28)
        } ?: return blocked(
            candidate,
            headerAddress,
            "Program-header offset exceeds the supported address range.",
            is64 = is64,
            machine = machine,
            elfType = elfType,
            headerSha = sha256(headerBytes),
            bytesRead = headerBytes.size,
        )
        val phentsize = u16(headerBytes, if (is64) 54 else 42)
        val phnum = u16(headerBytes, if (is64) 56 else 44)
        val minimumPhent = if (is64) 56 else 32

        if (
            phnum !in 1..MAX_PROGRAM_HEADERS ||
            phentsize < minimumPhent ||
            phentsize > 256 ||
            phoff < minHeaderBytes ||
            phoff > MAX_PROGRAM_HEADER_OFFSET
        ) {
            return blocked(
                candidate,
                headerAddress,
                "ELF program-header table metadata is outside bounded parser limits.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                phnum = phnum,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size,
            )
        }

        val tableBytesLong = phentsize.toLong() * phnum.toLong()
        if (tableBytesLong !in 1..MAX_PROGRAM_HEADER_TABLE_BYTES.toLong()) {
            return blocked(
                candidate,
                headerAddress,
                "ELF program-header table exceeds the bounded read limit.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                phnum = phnum,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size,
            )
        }

        val tableAddress = safeAdd(headerAddress, phoff)
            ?: return blocked(
                candidate,
                headerAddress,
                "ELF program-header address overflow.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                phnum = phnum,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size,
            )

        val tableBytes = reader.read(
            address = tableAddress,
            size = tableBytesLong.toInt(),
            cancellation = cancellation,
        ) ?: return RuntimeMemoryElfEvidence(
            candidateStart = candidate.start,
            candidateEndExclusive = candidate.endExclusive,
            candidatePath = candidate.path,
            headerAddress = headerAddress,
            status = RuntimeMemoryElfValidationStatus.NOT_READABLE,
            is64Bit = is64,
            machine = machine,
            elfType = elfType,
            programHeaderCount = phnum,
            loadSegmentCount = 0,
            executableLoadSegmentCount = 0,
            executableCandidateSegmentMatched = false,
            headerSha256 = sha256(headerBytes),
            bytesRead = headerBytes.size,
            blockers = listOf(
                "Program-header table is not readable through the current runtime memory reader.",
            ),
        )

        var loadSegments = 0
        var executableLoadSegments = 0
        var candidateExecutableMatch = false

        repeat(phnum) { index ->
            if (index % 64 == 0) checkCancelled(cancellation)
            val base = index * phentsize
            val type = u32(tableBytes, base) ?: return blocked(
                candidate,
                headerAddress,
                "Program-header table is truncated.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                phnum = phnum,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size + tableBytes.size,
            )
            if (type != PT_LOAD) return@repeat

            val flags = if (is64) {
                u32(tableBytes, base + 4)
            } else {
                u32(tableBytes, base + 24)
            } ?: return blocked(
                candidate,
                headerAddress,
                "PT_LOAD flags are truncated.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                phnum = phnum,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size + tableBytes.size,
            )

            val fileOffset = if (is64) {
                u64(tableBytes, base + 8)
            } else {
                u32(tableBytes, base + 4)
            } ?: return blocked(
                candidate,
                headerAddress,
                "PT_LOAD file offset exceeds supported range.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                phnum = phnum,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size + tableBytes.size,
            )

            val fileSize = if (is64) {
                u64(tableBytes, base + 32)
            } else {
                u32(tableBytes, base + 16)
            } ?: return blocked(
                candidate,
                headerAddress,
                "PT_LOAD file size exceeds supported range.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                phnum = phnum,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size + tableBytes.size,
            )
            val memorySize = if (is64) {
                u64(tableBytes, base + 40)
            } else {
                u32(tableBytes, base + 20)
            } ?: return blocked(
                candidate,
                headerAddress,
                "PT_LOAD memory size exceeds supported range.",
                is64 = is64,
                machine = machine,
                elfType = elfType,
                phnum = phnum,
                headerSha = sha256(headerBytes),
                bytesRead = headerBytes.size + tableBytes.size,
            )

            if (memorySize < fileSize) {
                return blocked(
                    candidate,
                    headerAddress,
                    "PT_LOAD memory size is smaller than file size.",
                    is64 = is64,
                    machine = machine,
                    elfType = elfType,
                    phnum = phnum,
                    headerSha = sha256(headerBytes),
                    bytesRead = headerBytes.size + tableBytes.size,
                )
            }

            loadSegments++
            val executable = flags and PF_X != 0L
            if (executable) {
                executableLoadSegments++
                if (
                    pageSizes.any { pageSize ->
                        alignDown(fileOffset, pageSize) ==
                            alignDown(candidate.fileOffset, pageSize)
                    }
                ) {
                    candidateExecutableMatch = true
                }
            }
        }

        val blockers = buildList {
            if (loadSegments == 0) {
                add("In-memory ELF has no PT_LOAD segments.")
            }
            if (executableLoadSegments == 0) {
                add("In-memory ELF has no executable PT_LOAD segment.")
            }
            if (!candidateExecutableMatch) {
                add(
                    "Executable candidate mapping file offset does not match an executable PT_LOAD segment.",
                )
            }
        }

        return RuntimeMemoryElfEvidence(
            candidateStart = candidate.start,
            candidateEndExclusive = candidate.endExclusive,
            candidatePath = candidate.path,
            headerAddress = headerAddress,
            status = if (blockers.isEmpty()) {
                RuntimeMemoryElfValidationStatus.VALIDATED
            } else {
                RuntimeMemoryElfValidationStatus.BLOCKED
            },
            is64Bit = is64,
            machine = machine,
            elfType = elfType,
            programHeaderCount = phnum,
            loadSegmentCount = loadSegments,
            executableLoadSegmentCount = executableLoadSegments,
            executableCandidateSegmentMatched = candidateExecutableMatch,
            headerSha256 = sha256(headerBytes),
            bytesRead = headerBytes.size + tableBytes.size,
            blockers = blockers,
        )
    }

    fun validateCandidates(
        candidates: List<RuntimeMemoryMappingCandidate>,
        reader: RuntimeMemoryReader,
        cancellation: CancellationSignal,
        maxCandidates: Int = 128,
    ): List<RuntimeMemoryElfEvidence> {
        require(maxCandidates in 1..1024) {
            "Invalid memory-ELF candidate limit."
        }
        return candidates
            .take(maxCandidates)
            .map { validate(it, reader, cancellation) }
    }

    private fun unreadable(
        candidate: RuntimeMemoryMappingCandidate,
        headerAddress: Long,
    ) = RuntimeMemoryElfEvidence(
        candidateStart = candidate.start,
        candidateEndExclusive = candidate.endExclusive,
        candidatePath = candidate.path,
        headerAddress = headerAddress,
        status = RuntimeMemoryElfValidationStatus.NOT_READABLE,
        is64Bit = null,
        machine = null,
        elfType = null,
        programHeaderCount = null,
        loadSegmentCount = 0,
        executableLoadSegmentCount = 0,
        executableCandidateSegmentMatched = false,
        headerSha256 = null,
        bytesRead = 0,
        blockers = listOf(
            "ELF header address is not readable through the current runtime memory reader.",
        ),
    )

    private fun blocked(
        candidate: RuntimeMemoryMappingCandidate,
        headerAddress: Long,
        reason: String,
        is64: Boolean? = null,
        machine: Int? = null,
        elfType: Int? = null,
        phnum: Int? = null,
        headerSha: String? = null,
        bytesRead: Int = 0,
    ) = RuntimeMemoryElfEvidence(
        candidateStart = candidate.start,
        candidateEndExclusive = candidate.endExclusive,
        candidatePath = candidate.path,
        headerAddress = headerAddress,
        status = RuntimeMemoryElfValidationStatus.BLOCKED,
        is64Bit = is64,
        machine = machine,
        elfType = elfType,
        programHeaderCount = phnum,
        loadSegmentCount = 0,
        executableLoadSegmentCount = 0,
        executableCandidateSegmentMatched = false,
        headerSha256 = headerSha,
        bytesRead = bytesRead,
        blockers = listOf(reason),
    )

    private fun hasElfMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x7f.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    private fun u8(bytes: ByteArray, offset: Int): Int =
        if (offset in bytes.indices) {
            bytes[offset].toInt() and 0xff
        } else {
            -1
        }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        if (offset >= 0 && offset + 1 < bytes.size) {
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)
        } else {
            -1
        }

    private fun u32(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 3 >= bytes.size) return null
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }

    private fun u64(bytes: ByteArray, offset: Int): Long? {
        val lo = u32(bytes, offset) ?: return null
        val hi = u32(bytes, offset + 4) ?: return null
        if (hi and 0x80000000L != 0L) return null
        return lo or (hi shl 32)
    }

    private fun alignDown(value: Long, alignment: Long): Long =
        value - Math.floorMod(value, alignment)

    private fun safeAdd(a: Long, b: Long): Long? =
        runCatching { Math.addExact(a, b) }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
