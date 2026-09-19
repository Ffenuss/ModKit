package io.github.ffenuss.modkit.build

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ZipAlignmentRecord(
    val entryName: String,
    val alignment: Int,
    val dataOffset: Long,
)

data class ZipAlignmentVerification(
    val verified: Boolean,
    val records: List<ZipAlignmentRecord>,
    val blockers: List<String>,
)

data class ZipAlignResult(
    val outputFile: File,
    val verification: ZipAlignmentVerification,
)

/**
 * Pure-Java APK zip alignment stage.
 *
 * STORED entries are aligned to 4 bytes. Uncompressed native libraries under
 * lib/*.so are aligned to 16 KiB for modern Android page-size compatibility.
 * The stage is fail-closed for ZIP64 until an explicit ZIP64 writer/parser is
 * added.
 */
object ApkZipAligner {
    private const val BUFFER_BYTES = 128 * 1024
    private const val PAGE_ALIGNMENT = 16 * 1024
    private const val DEFAULT_ALIGNMENT = 4
    private const val ALIGN_EXTRA_ID = 0xD935

    fun align(
        input: File,
        output: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): ZipAlignResult {
        require(input.isFile && input.canRead()) { "Staging APK is unavailable." }
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.delete()
        output.delete()

        try {
            ZipFile(input).use { zip ->
                val raw = FileOutputStream(temp)
                val buffered = BufferedOutputStream(raw, BUFFER_BYTES)
                val counting = CountingOutputStream(buffered)
                ZipOutputStream(counting, StandardCharsets.UTF_8).use { out ->
                    val entries = zip.entries()
                    var processed = 0L
                    while (entries.hasMoreElements()) {
                        checkCancelled(cancellation)
                        val source = entries.nextElement()
                        require(source.size < 0xffffffffL) {
                            "ZIP64 entry is not supported by the current aligner: " +
                                source.name
                        }
                        val target = cloneForAlignment(
                            source = source,
                            localHeaderOffset = counting.count,
                        )
                        out.putNextEntry(target)

                        val alignment = requiredAlignment(target)
                        if (alignment != null) {
                            require(counting.count % alignment == 0L) {
                                "Could not align " + target.name +
                                    " to " + alignment + " bytes."
                            }
                        }

                        if (!source.isDirectory) {
                            zip.getInputStream(source).use { inputStream ->
                                val buffer = ByteArray(BUFFER_BYTES)
                                while (true) {
                                    checkCancelled(cancellation)
                                    val read = inputStream.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                        }
                        out.closeEntry()
                        processed++
                        progress.publish(
                            EngineProgress(
                                engineId = "build.zipalign",
                                scheduleClass = EngineScheduleClass.CONFIRMATION,
                                state = RunState.RUNNING,
                                currentTask = "Выравнивание APK",
                                currentArtifact = source.name,
                                processed = processed,
                                total = null,
                                lastHeartbeatEpochMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }

            check(temp.renameTo(output)) {
                "Could not finalize aligned APK."
            }
            val verification = ZipAlignmentVerifier.verify(output)
            if (!verification.verified) {
                output.delete()
                error(
                    verification.blockers.firstOrNull()
                        ?: "Aligned APK verification failed.",
                )
            }
            return ZipAlignResult(
                outputFile = output,
                verification = verification,
            )
        } catch (failure: Throwable) {
            temp.delete()
            output.delete()
            throw failure
        }
    }

    private fun cloneForAlignment(
        source: ZipEntry,
        localHeaderOffset: Long,
    ): ZipEntry {
        val target = ZipEntry(source.name)
        target.comment = source.comment
        if (source.time >= 0L) target.time = source.time

        val method = if (source.method == ZipEntry.STORED) {
            ZipEntry.STORED
        } else {
            ZipEntry.DEFLATED
        }
        target.method = method

        if (method == ZipEntry.STORED) {
            require(source.size >= 0L && source.crc >= 0L) {
                "STORED entry lacks size/CRC: " + source.name
            }
            target.size = source.size
            target.compressedSize = source.size
            target.crc = source.crc
        }

        val baseExtra = stripAlignmentExtra(source.extra)
        val alignment = requiredAlignment(target)
        target.extra = if (alignment == null) {
            baseExtra
        } else {
            alignedExtra(
                baseExtra = baseExtra,
                localHeaderOffset = localHeaderOffset,
                nameByteCount = source.name.toByteArray(StandardCharsets.UTF_8).size,
                alignment = alignment,
            )
        }
        return target
    }

    private fun requiredAlignment(entry: ZipEntry): Int? {
        if (entry.isDirectory || entry.method != ZipEntry.STORED) return null
        val low = entry.name.lowercase()
        return if (
            low.startsWith("lib/") &&
            low.endsWith(".so")
        ) {
            PAGE_ALIGNMENT
        } else {
            DEFAULT_ALIGNMENT
        }
    }

    private fun alignedExtra(
        baseExtra: ByteArray?,
        localHeaderOffset: Long,
        nameByteCount: Int,
        alignment: Int,
    ): ByteArray? {
        val base = baseExtra ?: ByteArray(0)
        val withoutPaddingOffset =
            localHeaderOffset + 30L + nameByteCount + base.size
        val remainder = (withoutPaddingOffset % alignment).toInt()
        if (remainder == 0) return base.takeIf { it.isNotEmpty() }

        var totalFieldBytes = alignment - remainder
        if (totalFieldBytes < 4) totalFieldBytes += alignment
        val payloadBytes = totalFieldBytes - 4
        require(payloadBytes in 0..0xffff) {
            "Alignment padding exceeds ZIP extra-field limit."
        }

        val field = ByteArray(totalFieldBytes)
        field[0] = (ALIGN_EXTRA_ID and 0xff).toByte()
        field[1] = ((ALIGN_EXTRA_ID ushr 8) and 0xff).toByte()
        field[2] = (payloadBytes and 0xff).toByte()
        field[3] = ((payloadBytes ushr 8) and 0xff).toByte()
        return base + field
    }

    private fun stripAlignmentExtra(extra: ByteArray?): ByteArray? {
        if (extra == null || extra.isEmpty()) return extra
        val out = ArrayList<Byte>(extra.size)
        var offset = 0
        while (offset + 4 <= extra.size) {
            val id = u16(extra, offset)
            val size = u16(extra, offset + 2)
            val end = offset + 4 + size
            if (end > extra.size) {
                for (i in offset until extra.size) out += extra[i]
                break
            }
            if (id != ALIGN_EXTRA_ID) {
                for (i in offset until end) out += extra[i]
            }
            offset = end
        }
        while (offset < extra.size) {
            out += extra[offset]
            offset++
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            delegate.write(buffer, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }
}

object ZipAlignmentVerifier {
    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val MAX_EOCD_SEARCH = 65_557L
    private const val PAGE_ALIGNMENT = 16 * 1024
    private const val DEFAULT_ALIGNMENT = 4

    fun verify(file: File): ZipAlignmentVerification {
        val blockers = mutableListOf<String>()
        val records = mutableListOf<ZipAlignmentRecord>()

        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val eocd = findEocd(raf)
                val entryCount = readU16(raf, eocd + 10)
                val centralOffset = readU32(raf, eocd + 16)
                if (entryCount == 0xffff || centralOffset == 0xffffffffL) {
                    error("ZIP64 is not supported by the current alignment verifier.")
                }

                var cursor = centralOffset
                repeat(entryCount) {
                    require(readU32(raf, cursor) == CENTRAL_SIGNATURE) {
                        "Invalid central directory signature."
                    }
                    val method = readU16(raf, cursor + 10)
                    val compressedSize = readU32(raf, cursor + 20)
                    val uncompressedSize = readU32(raf, cursor + 24)
                    val nameLength = readU16(raf, cursor + 28)
                    val extraLength = readU16(raf, cursor + 30)
                    val commentLength = readU16(raf, cursor + 32)
                    val localOffset = readU32(raf, cursor + 42)
                    if (
                        compressedSize == 0xffffffffL ||
                        uncompressedSize == 0xffffffffL ||
                        localOffset == 0xffffffffL
                    ) {
                        error("ZIP64 entry is not supported.")
                    }

                    val nameBytes = ByteArray(nameLength)
                    raf.seek(cursor + 46)
                    raf.readFully(nameBytes)
                    val name = String(nameBytes, StandardCharsets.UTF_8)

                    if (
                        method == ZipEntry.STORED &&
                        !name.endsWith("/")
                    ) {
                        require(readU32(raf, localOffset) == LOCAL_SIGNATURE) {
                            "Invalid local header for " + name
                        }
                        val localNameLength = readU16(raf, localOffset + 26)
                        val localExtraLength = readU16(raf, localOffset + 28)
                        val dataOffset =
                            localOffset + 30L + localNameLength + localExtraLength
                        val alignment = if (
                            name.lowercase().startsWith("lib/") &&
                            name.lowercase().endsWith(".so")
                        ) {
                            PAGE_ALIGNMENT
                        } else {
                            DEFAULT_ALIGNMENT
                        }
                        records += ZipAlignmentRecord(
                            entryName = name,
                            alignment = alignment,
                            dataOffset = dataOffset,
                        )
                        if (dataOffset % alignment != 0L) {
                            blockers += name + ": data offset " +
                                dataOffset + " is not aligned to " + alignment
                        }
                    }

                    cursor +=
                        46L + nameLength + extraLength + commentLength
                }
            }
        }.onFailure { failure ->
            blockers += failure.message ?: failure.javaClass.simpleName
        }

        return ZipAlignmentVerification(
            verified = blockers.isEmpty(),
            records = records,
            blockers = blockers.distinct(),
        )
    }

    private fun findEocd(raf: RandomAccessFile): Long {
        val end = raf.length()
        val start = (end - MAX_EOCD_SEARCH).coerceAtLeast(0L)
        var cursor = end - 22L
        while (cursor >= start) {
            if (readU32(raf, cursor) == EOCD_SIGNATURE) return cursor
            cursor--
        }
        error("ZIP end-of-central-directory record not found.")
    }

    private fun readU16(raf: RandomAccessFile, offset: Long): Int {
        raf.seek(offset)
        val a = raf.read()
        val b = raf.read()
        require(a >= 0 && b >= 0) { "Unexpected EOF." }
        return a or (b shl 8)
    }

    private fun readU32(raf: RandomAccessFile, offset: Long): Long {
        raf.seek(offset)
        var value = 0L
        repeat(4) { index ->
            val byte = raf.read()
            require(byte >= 0) { "Unexpected EOF." }
            value = value or ((byte.toLong() and 0xffL) shl (8 * index))
        }
        return value
    }
}
