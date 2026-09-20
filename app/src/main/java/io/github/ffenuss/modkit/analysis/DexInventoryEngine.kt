package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.FileInputStream
import java.io.InputStream
import java.io.Serializable
import java.util.zip.ZipFile

data class DexInventoryRecord(
    val container: String,
    val entryPath: String,
    val size: Long,
    val version: String,
    val declaredFileSize: Long?,
    val headerSize: Long?,
    val standardEndian: Boolean,
    val stringIdsCount: Long?,
    val typeIdsCount: Long?,
    val protoIdsCount: Long?,
    val fieldIdsCount: Long?,
    val methodIdsCount: Long?,
    val classDefsCount: Long?,
    val dataSize: Long?,
    val warnings: List<String>,
) : Serializable

data class DexInventoryResult(
    val records: List<DexInventoryRecord>,
    val warnings: List<String>,
) : Serializable

/**
 * Bounded DEX structural inventory.
 *
 * Only the fixed 0x70-byte DEX header is read. Large classes*.dex entries are
 * never fully materialized merely to obtain table counts.
 */
object DexInventoryEngine {
    const val ID = "dex.inventory"
    const val VERSION = "1"

    private const val HEADER_BYTES = 0x70
    private const val ENDIAN_CONSTANT = 0x12345678L
    private const val REVERSE_ENDIAN_CONSTANT = 0x78563412L

    fun analyze(
        workspace: AnalysisWorkspace,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): DexInventoryResult {
        val candidates = workspace.index.entries.filter {
            it.format == BinaryFormat.DEX
        }
        val records = mutableListOf<DexInventoryRecord>()
        val warnings = mutableListOf<String>()

        candidates.forEachIndexed { index, entry ->
            checkCancelled(cancellation)
            val source = workspace.sources.singleOrNull {
                it.descriptor.displayName == entry.container
            }
            if (source == null) {
                warnings +=
                    entry.container + ":" + entry.path +
                        ": source container unavailable"
                return@forEachIndexed
            }

            val header = runCatching {
                readHeader(
                    source = source,
                    entry = entry,
                    cancellation = cancellation,
                )
            }.getOrElse { failure ->
                if (failure is AnalysisCancelledException) throw failure
                warnings +=
                    entry.container + ":" + entry.path + ": " +
                    (failure.message ?: failure.javaClass.simpleName)
                return@forEachIndexed
            }

            records += parseHeader(entry, header)
            progress.publish(
                EngineProgress(
                    engineId = ID,
                    scheduleClass = EngineScheduleClass.TARGETED,
                    state = RunState.RUNNING,
                    currentTask = "DEX header inventory",
                    currentArtifact =
                        entry.container + ":" + entry.path,
                    processed = (index + 1).toLong(),
                    total = candidates.size.toLong(),
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )
        }

        progress.publish(
            EngineProgress(
                engineId = ID,
                scheduleClass = EngineScheduleClass.TARGETED,
                state = RunState.COMPLETED,
                currentTask = "DEX inventory ready",
                processed = records.size.toLong(),
                total = candidates.size.toLong(),
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )

        return DexInventoryResult(
            records = records,
            warnings = warnings.distinct(),
        )
    }

    private fun readHeader(
        source: WorkspaceSource,
        entry: ArtifactEntry,
        cancellation: CancellationSignal,
    ): ByteArray {
        require(entry.size >= HEADER_BYTES) {
            "DEX entry is smaller than the fixed header."
        }
        if (
            entry.path == source.file.name &&
            entry.container == source.descriptor.displayName
        ) {
            FileInputStream(source.file).use {
                return readExact(it, cancellation)
            }
        }

        ZipFile(source.file).use { zip ->
            val zipEntry = zip.getEntry(entry.path)
                ?: error("DEX entry disappeared from source archive.")
            require(!zipEntry.isDirectory) {
                "DEX entry is a directory."
            }
            require(zipEntry.size == entry.size) {
                "DEX entry size changed after ArtifactIndex creation."
            }
            zip.getInputStream(zipEntry).use {
                return readExact(it, cancellation)
            }
        }
    }

    private fun readExact(
        input: InputStream,
        cancellation: CancellationSignal,
    ): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        var offset = 0
        while (offset < header.size) {
            checkCancelled(cancellation)
            val read = input.read(
                header,
                offset,
                header.size - offset,
            )
            require(read > 0) {
                "DEX header is truncated."
            }
            offset += read
        }
        return header
    }

    private fun parseHeader(
        entry: ArtifactEntry,
        header: ByteArray,
    ): DexInventoryRecord {
        require(
            header[0] == 'd'.code.toByte() &&
                header[1] == 'e'.code.toByte() &&
                header[2] == 'x'.code.toByte() &&
                header[3] == '\n'.code.toByte() &&
                header[7] == 0.toByte(),
        ) {
            "DEX magic is invalid."
        }
        val version = String(
            header,
            4,
            3,
            Charsets.US_ASCII,
        )
        require(version.all { it.isDigit() }) {
            "DEX version field is invalid."
        }

        val declaredFileSize = u32le(header, 32)
        val headerSize = u32le(header, 36)
        val endianTag = u32le(header, 40)
        val standardEndian = endianTag == ENDIAN_CONSTANT
        val reverseEndian = endianTag == REVERSE_ENDIAN_CONSTANT
        val warnings = buildList {
            if (declaredFileSize != entry.size) {
                add(
                    "Declared DEX file_size does not match indexed entry size.",
                )
            }
            if (headerSize != HEADER_BYTES.toLong()) {
                add(
                    "DEX header_size is not the canonical 0x70 bytes.",
                )
            }
            if (!standardEndian) {
                add(
                    if (reverseEndian) {
                        "Reverse-endian DEX is recognized but table counts are not decoded."
                    } else {
                        "DEX endian_tag is invalid."
                    },
                )
            }
            if (
                version !in setOf(
                    "035",
                    "037",
                    "038",
                    "039",
                    "040",
                    "041",
                )
            ) {
                add(
                    "DEX version $version is not in the currently recognized inventory set.",
                )
            }
        }.toMutableList()

        fun section(
            countOffset: Int,
            dataOffset: Int,
            itemSize: Long,
            label: String,
        ): Long? {
            if (!standardEndian) return null
            val count = u32le(header, countOffset)
            val offset = u32le(header, dataOffset)
            if (count == 0L) {
                if (offset != 0L) {
                    warnings +=
                        "$label has zero count but non-zero offset."
                }
                return 0L
            }
            if (offset == 0L) {
                warnings +=
                    "$label has non-zero count but zero offset."
                return count
            }
            val bytes = safeMultiply(count, itemSize)
            val end = bytes?.let { safeAdd(offset, it) }
            if (
                bytes == null ||
                end == null ||
                end > entry.size
            ) {
                warnings +=
                    "$label range exceeds the indexed DEX size."
            }
            return count
        }

        val strings = section(56, 60, 4, "string_ids")
        val types = section(64, 68, 4, "type_ids")
        val protos = section(72, 76, 12, "proto_ids")
        val fields = section(80, 84, 8, "field_ids")
        val methods = section(88, 92, 8, "method_ids")
        val classes = section(96, 100, 32, "class_defs")
        val dataSize = if (standardEndian) u32le(header, 104) else null
        if (standardEndian && dataSize != null) {
            val dataOffset = u32le(header, 108)
            val dataEnd = safeAdd(dataOffset, dataSize)
            if (
                dataSize > 0L &&
                (dataOffset == 0L ||
                    dataEnd == null ||
                    dataEnd > entry.size)
            ) {
                warnings +=
                    "data section range exceeds the indexed DEX size."
            }
        }

        return DexInventoryRecord(
            container = entry.container,
            entryPath = entry.path,
            size = entry.size,
            version = version,
            declaredFileSize = declaredFileSize,
            headerSize = headerSize,
            standardEndian = standardEndian,
            stringIdsCount = strings,
            typeIdsCount = types,
            protoIdsCount = protos,
            fieldIdsCount = fields,
            methodIdsCount = methods,
            classDefsCount = classes,
            dataSize = dataSize,
            warnings = warnings.distinct(),
        )
    }

    private fun u32le(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun safeMultiply(
        left: Long,
        right: Long,
    ): Long? {
        if (left < 0 || right < 0) return null
        if (left == 0L || right == 0L) return 0L
        if (left > Long.MAX_VALUE / right) return null
        return left * right
    }

    private fun safeAdd(
        left: Long,
        right: Long,
    ): Long? {
        if (left < 0 || right < 0) return null
        if (left > Long.MAX_VALUE - right) return null
        return left + right
    }

    private fun checkCancelled(
        cancellation: CancellationSignal,
    ) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
