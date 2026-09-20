package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

enum class RuntimeValueType(
    val byteWidth: Int,
    val title: String,
) {
    INT32(4, "Int32"),
    INT64(8, "Int64"),
    FLOAT32(4, "Float"),
    FLOAT64(8, "Double");

    fun next(): RuntimeValueType {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    internal fun parseQuery(
        text: String,
    ): Long {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) {
            "Введите значение для поиска."
        }
        return when (this) {
            INT32 ->
                normalized.toIntOrNull()
                    ?.toLong()
                    ?.and(0xffffffffL)
                    ?: error(
                        "Значение не помещается в Int32.",
                    )
            INT64 ->
                normalized.toLongOrNull()
                    ?: error(
                        "Значение не помещается в Int64.",
                    )
            FLOAT32 -> {
                val value =
                    normalized.toFloatOrNull()
                        ?: error(
                            "Некорректное Float-значение.",
                        )
                require(value.isFinite()) {
                    "NaN/Infinity пока не поддерживаются."
                }
                value.toRawBits()
                    .toLong()
                    .and(0xffffffffL)
            }
            FLOAT64 -> {
                val value =
                    normalized.toDoubleOrNull()
                        ?: error(
                            "Некорректное Double-значение.",
                        )
                require(value.isFinite()) {
                    "NaN/Infinity пока не поддерживаются."
                }
                value.toRawBits()
            }
        }
    }

    internal fun readBits(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        when (this) {
            INT32,
            FLOAT32,
            ->
                (
                    (bytes[offset].toLong() and 0xffL) or
                        ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
                        ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
                        ((bytes[offset + 3].toLong() and 0xffL) shl 24)
                    )
                    .and(0xffffffffL)
            INT64,
            FLOAT64,
            -> {
                var out = 0L
                repeat(8) { index ->
                    out =
                        out or
                            (
                                (bytes[offset + index].toLong() and 0xffL) shl
                                    (index * 8)
                                )
                }
                out
            }
        }

    fun display(
        bits: Long,
    ): String =
        when (this) {
            INT32 ->
                bits.toInt().toString()
            INT64 ->
                bits.toString()
            FLOAT32 ->
                Float.fromBits(bits.toInt())
                    .toString()
            FLOAT64 ->
                Double.fromBits(bits)
                    .toString()
        }

    internal fun compare(
        oldBits: Long,
        newBits: Long,
    ): Int =
        when (this) {
            INT32 ->
                oldBits.toInt()
                    .compareTo(newBits.toInt())
            INT64 ->
                oldBits.compareTo(newBits)
            FLOAT32 ->
                Float.fromBits(oldBits.toInt())
                    .compareTo(
                        Float.fromBits(
                            newBits.toInt(),
                        ),
                    )
            FLOAT64 ->
                Double.fromBits(oldBits)
                    .compareTo(
                        Double.fromBits(newBits),
                    )
        }
}

enum class RuntimeValueRefinement(
    val title: String,
) {
    CHANGED("Изменилось"),
    UNCHANGED("Не изменилось"),
    INCREASED("Увеличилось"),
    DECREASED("Уменьшилось"),
}

data class RuntimeValueHit(
    val address: Long,
    val bits: Long,
    val regionStart: Long,
    val regionEndExclusive: Long,
    val regionPath: String?,
) {
    fun displayValue(
        type: RuntimeValueType,
    ): String =
        type.display(bits)
}

data class RuntimeValueScanSnapshot(
    val valueType: RuntimeValueType,
    val hits: List<RuntimeValueHit>,
    val scannedBytes: Long,
    val scannedRegions: Int,
    val truncatedByHitLimit: Boolean,
    val truncatedByByteLimit: Boolean,
)

data class RootRuntimeValueScanResult(
    val packageName: String,
    val pid: Int,
    val capturedAtEpochMs: Long,
    val snapshot: RuntimeValueScanSnapshot,
)

/**
 * Read-only exact-value scanner for bounded runtime ranges.
 *
 * It is intentionally separate from any write path. The same scanner can be
 * used with fake readers in tests, non-root readable memory, or an explicit
 * root process connection.
 */
object RuntimeValueScanner {
    const val DEFAULT_MAX_HITS = 2048
    const val DEFAULT_MAX_SCAN_BYTES =
        128L * 1024L * 1024L
    const val DEFAULT_CHUNK_BYTES =
        256 * 1024

    fun scanExact(
        ranges: List<ProcMapRegion>,
        reader: RuntimeMemoryReader,
        valueType: RuntimeValueType,
        query: String,
        cancellation: CancellationSignal,
        maxHits: Int = DEFAULT_MAX_HITS,
        maxScanBytes: Long =
            DEFAULT_MAX_SCAN_BYTES,
        chunkBytes: Int =
            DEFAULT_CHUNK_BYTES,
    ): RuntimeValueScanSnapshot {
        require(maxHits in 1..100_000) {
            "Некорректный лимит результатов."
        }
        require(maxScanBytes in 1..(2L * 1024L * 1024L * 1024L)) {
            "Некорректный лимит сканирования."
        }
        require(
            chunkBytes in
                valueType.byteWidth..
                    ProcMemRuntimeMemoryReader
                        .MAX_READ_BYTES,
        ) {
            "Некорректный размер блока чтения."
        }

        val queryBits =
            valueType.parseQuery(query)
        val hits =
            ArrayList<RuntimeValueHit>()
        var scannedBytes = 0L
        var scannedRegions = 0
        var hitLimitReached = false
        var byteLimitReached = false

        loop@ for (region in ranges) {
            checkCancelled(cancellation)
            if (
                !region.readable ||
                region.size <
                    valueType.byteWidth
            ) {
                continue
            }
            if (
                scannedBytes >=
                maxScanBytes
            ) {
                byteLimitReached = true
                break
            }

            var address =
                alignUp(
                    region.start,
                    valueType.byteWidth,
                )
            var regionScanned = false

            while (
                address +
                    valueType.byteWidth <=
                region.endExclusive
            ) {
                checkCancelled(cancellation)
                val remainingRegion =
                    region.endExclusive -
                        address
                val remainingBudget =
                    maxScanBytes -
                        scannedBytes
                if (remainingBudget <= 0L) {
                    byteLimitReached = true
                    break@loop
                }

                var requestBytes =
                    min(
                        min(
                            remainingRegion,
                            remainingBudget,
                        ),
                        chunkBytes.toLong(),
                    ).toInt()
                requestBytes -=
                    requestBytes %
                        valueType.byteWidth
                if (
                    requestBytes <
                    valueType.byteWidth
                ) {
                    break
                }

                val bytes =
                    reader.read(
                        address = address,
                        size = requestBytes,
                        cancellation =
                            cancellation,
                    )
                if (
                    bytes == null ||
                    bytes.size <
                        valueType.byteWidth
                ) {
                    break
                }

                regionScanned = true
                val usable =
                    bytes.size -
                        (
                            bytes.size %
                                valueType.byteWidth
                            )
                var offset = 0
                while (
                    offset +
                        valueType.byteWidth <=
                    usable
                ) {
                    val bits =
                        valueType.readBits(
                            bytes,
                            offset,
                        )
                    if (bits == queryBits) {
                        hits +=
                            RuntimeValueHit(
                                address =
                                    address +
                                        offset,
                                bits = bits,
                                regionStart =
                                    region.start,
                                regionEndExclusive =
                                    region.endExclusive,
                                regionPath =
                                    region.path,
                            )
                        if (
                            hits.size >=
                            maxHits
                        ) {
                            hitLimitReached =
                                true
                            scannedBytes +=
                                offset +
                                    valueType
                                        .byteWidth
                            break@loop
                        }
                    }
                    offset +=
                        valueType.byteWidth
                }

                scannedBytes += usable
                address += usable
                if (usable == 0) {
                    break
                }
            }

            if (regionScanned) {
                scannedRegions++
            }
        }

        return RuntimeValueScanSnapshot(
            valueType = valueType,
            hits = hits,
            scannedBytes = scannedBytes,
            scannedRegions =
                scannedRegions,
            truncatedByHitLimit =
                hitLimitReached,
            truncatedByByteLimit =
                byteLimitReached,
        )
    }

    fun refine(
        previous: RuntimeValueScanSnapshot,
        reader: RuntimeMemoryReader,
        refinement: RuntimeValueRefinement,
        cancellation: CancellationSignal,
    ): RuntimeValueScanSnapshot {
        val type =
            previous.valueType
        val kept =
            ArrayList<RuntimeValueHit>(
                previous.hits.size,
            )

        previous.hits.forEachIndexed {
                index,
                hit,
            ->
            if (index % 128 == 0) {
                checkCancelled(cancellation)
            }
            val bytes =
                reader.read(
                    address = hit.address,
                    size = type.byteWidth,
                    cancellation = cancellation,
                ) ?: return@forEachIndexed
            if (
                bytes.size <
                type.byteWidth
            ) {
                return@forEachIndexed
            }

            val now =
                type.readBits(
                    bytes,
                    0,
                )
            val comparison =
                type.compare(
                    hit.bits,
                    now,
                )
            val keep =
                when (refinement) {
                    RuntimeValueRefinement.CHANGED ->
                        now != hit.bits
                    RuntimeValueRefinement.UNCHANGED ->
                        now == hit.bits
                    RuntimeValueRefinement.INCREASED ->
                        comparison < 0
                    RuntimeValueRefinement.DECREASED ->
                        comparison > 0
                }
            if (keep) {
                kept +=
                    hit.copy(
                        bits = now,
                    )
            }
        }

        return previous.copy(
            hits = kept,
            scannedBytes =
                kept.size.toLong() *
                    type.byteWidth,
            scannedRegions =
                kept
                    .map {
                        it.regionStart to
                            it.regionEndExclusive
                    }
                    .distinct()
                    .size,
            truncatedByHitLimit = false,
            truncatedByByteLimit = false,
        )
    }

    private fun alignUp(
        value: Long,
        alignment: Int,
    ): Long {
        val a = alignment.toLong()
        val remainder =
            Math.floorMod(value, a)
        return if (remainder == 0L) {
            value
        } else {
            value + (a - remainder)
        }
    }

    private fun checkCancelled(
        cancellation: CancellationSignal,
    ) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}

/**
 * Explicit root coordinator for read-only live-value analysis.
 *
 * Exact main-process identity is re-proven through [RootRuntimeCaptureCoordinator]
 * before every scan/refinement. Only private readable+writable, non-executable
 * mappings are scanned automatically.
 */
object RootRuntimeValueScanCoordinator {
    fun scanExact(
        packageName: String,
        valueType: RuntimeValueType,
        query: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootRuntimeValueScanResult {
        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                )
        val ranges =
            candidateRanges(
                ProcMapsParser.parse(
                    capture.capture.text,
                ),
            )
        require(ranges.isNotEmpty()) {
            "В процессе нет подходящих readable+writable private диапазонов для сканирования."
        }

        val snapshot =
            RuntimeValueScanner.scanExact(
                ranges = ranges,
                reader =
                    RootProcMemRuntimeMemoryReader(
                        pid = capture.pid,
                        runner = runner,
                    ),
                valueType = valueType,
                query = query,
                cancellation = cancellation,
            )
        return RootRuntimeValueScanResult(
            packageName =
                packageName,
            pid = capture.pid,
            capturedAtEpochMs =
                System.currentTimeMillis(),
            snapshot = snapshot,
        )
    }

    fun refine(
        previous: RootRuntimeValueScanResult,
        refinement: RuntimeValueRefinement,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootRuntimeValueScanResult {
        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        previous.packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                )
        require(
            capture.pid ==
                previous.pid,
        ) {
            "PID процесса изменился. Начните новый поиск значений."
        }

        val refined =
            RuntimeValueScanner.refine(
                previous =
                    previous.snapshot,
                reader =
                    RootProcMemRuntimeMemoryReader(
                        pid = capture.pid,
                        runner = runner,
                    ),
                refinement =
                    refinement,
                cancellation =
                    cancellation,
            )
        return previous.copy(
            capturedAtEpochMs =
                System.currentTimeMillis(),
            snapshot = refined,
        )
    }

    internal fun candidateRanges(
        regions: List<ProcMapRegion>,
    ): List<ProcMapRegion> =
        regions
            .asSequence()
            .filter {
                it.readable &&
                    it.writable &&
                    !it.executable &&
                    it.privateMapping &&
                    it.size > 0L
            }
            .filterNot {
                val path =
                    it.path.orEmpty()
                path.startsWith("/system/") ||
                    path.startsWith("/apex/") ||
                    path.startsWith("/vendor/") ||
                    path == "[vvar]" ||
                    path == "[vdso]"
            }
            .sortedBy {
                it.start
            }
            .toList()
}
