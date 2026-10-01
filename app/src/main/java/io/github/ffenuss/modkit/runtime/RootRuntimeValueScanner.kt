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

    internal fun encodeQuery(
        text: String,
    ): ByteArray {
        val bits =
            parseQuery(text)
        return ByteArray(byteWidth) {
                index,
            ->
            (bits ushr (index * 8))
                .toByte()
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

enum class RuntimeScanAlignment(
    val title: String,
) {
    NATURAL("По размеру типа"),
    BYTE("По каждому байту");

    fun next(): RuntimeScanAlignment {
        val values = entries
        return values[
            (ordinal + 1) %
                values.size
        ]
    }

    fun step(
        valueType:
            RuntimeValueType,
    ): Int =
        if (this == BYTE) {
            1
        } else {
            valueType.byteWidth
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
    val regionFileOffset: Long,
    val regionPath: String?,
) {
    val offsetInRegion: Long
        get() =
            address - regionStart

    val mappedFileOffset: Long
        get() =
            regionFileOffset +
                offsetInRegion
    fun displayValue(
        type: RuntimeValueType,
    ): String =
        type.display(bits)
}

data class RuntimeValueScanSnapshot(
    val valueType: RuntimeValueType,
    val hits: List<RuntimeValueHit>,
    val alignment: RuntimeScanAlignment =
        RuntimeScanAlignment.NATURAL,
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

data class RootRuntimePointerScanResult(
    val packageName: String,
    val pid: Int,
    val targetAddress: Long,
    val depth: Int,
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
    const val DEFAULT_MAX_HITS = 50_000
    private const val REFINE_WINDOW_BYTES =
        256 * 1024
    const val DEFAULT_MAX_SCAN_BYTES =
        128L * 1024L * 1024L
    const val DEFAULT_CHUNK_BYTES =
        2 * 1024 * 1024

    fun scanExact(
        ranges: List<ProcMapRegion>,
        reader: RuntimeMemoryReader,
        valueType: RuntimeValueType,
        query: String,
        cancellation: CancellationSignal,
        alignment: RuntimeScanAlignment =
            RuntimeScanAlignment.NATURAL,
        maxHits: Int = DEFAULT_MAX_HITS,
        maxScanBytes: Long =
            DEFAULT_MAX_SCAN_BYTES,
        chunkBytes: Int =
            DEFAULT_CHUNK_BYTES,
    ): RuntimeValueScanSnapshot {
        require(maxHits in 1..100_000) {
            "Некорректный лимит результатов."
        }
        require(maxScanBytes > 0L) {
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
        val step =
            alignment.step(
                valueType,
            )
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
                if (
                    alignment ==
                    RuntimeScanAlignment
                        .NATURAL
                ) {
                    alignUp(
                        region.start,
                        valueType.byteWidth,
                    )
                } else {
                    region.start
                }
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

                val overlap =
                    if (
                        alignment ==
                        RuntimeScanAlignment.BYTE
                    ) {
                        valueType.byteWidth - 1
                    } else {
                        0
                    }
                val budgetWithOverlap =
                    if (
                        remainingBudget >
                        Long.MAX_VALUE -
                            overlap.toLong()
                    ) {
                        Long.MAX_VALUE
                    } else {
                        remainingBudget +
                            overlap
                    }
                var requestBytes =
                    min(
                        remainingRegion,
                        min(
                            chunkBytes.toLong(),
                            budgetWithOverlap,
                        ),
                    ).toInt()
                if (
                    alignment ==
                    RuntimeScanAlignment
                        .NATURAL
                ) {
                    requestBytes -=
                        requestBytes %
                            valueType.byteWidth
                }
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
                    if (
                        alignment ==
                        RuntimeScanAlignment
                            .NATURAL
                    ) {
                        bytes.size -
                            (
                                bytes.size %
                                    valueType.byteWidth
                                )
                    } else {
                        bytes.size
                    }
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
                                regionFileOffset =
                                    region.fileOffset,
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
                        step
                }

                val advance =
                    if (
                        alignment ==
                            RuntimeScanAlignment.BYTE &&
                        usable >
                            valueType.byteWidth - 1
                    ) {
                        usable -
                            (
                                valueType.byteWidth -
                                    1
                                )
                    } else {
                        usable
                    }
                scannedBytes +=
                    advance
                address +=
                    advance
                if (advance <= 0) {
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
            alignment = alignment,
            scannedBytes = scannedBytes,
            scannedRegions =
                scannedRegions,
            truncatedByHitLimit =
                hitLimitReached,
            truncatedByByteLimit =
                byteLimitReached,
        )
    }

    fun refineExact(
        previous: RuntimeValueScanSnapshot,
        reader: RuntimeMemoryReader,
        query: String,
        cancellation: CancellationSignal,
    ): RuntimeValueScanSnapshot {
        val type =
            previous.valueType
        val queryBits =
            type.parseQuery(query)
        return filterAndRefresh(
            previous = previous,
            reader = reader,
            cancellation = cancellation,
        ) {
                _,
                now,
            ->
            now == queryBits
        }
    }

    fun refresh(
        previous: RuntimeValueScanSnapshot,
        reader: RuntimeMemoryReader,
        cancellation: CancellationSignal,
    ): RuntimeValueScanSnapshot =
        filterAndRefresh(
            previous = previous,
            reader = reader,
            cancellation = cancellation,
        ) {
                _,
                _,
            ->
            true
        }

    fun refine(
        previous: RuntimeValueScanSnapshot,
        reader: RuntimeMemoryReader,
        refinement: RuntimeValueRefinement,
        cancellation: CancellationSignal,
    ): RuntimeValueScanSnapshot {
        return filterAndRefresh(
            previous = previous,
            reader = reader,
            cancellation = cancellation,
        ) {
                old,
                now,
            ->
            val comparison =
                previous.valueType.compare(
                    old,
                    now,
                )
            when (refinement) {
                RuntimeValueRefinement.CHANGED ->
                    now != old
                RuntimeValueRefinement.UNCHANGED ->
                    now == old
                RuntimeValueRefinement.INCREASED ->
                    comparison < 0
                RuntimeValueRefinement.DECREASED ->
                    comparison > 0
            }
        }
    }

    private fun filterAndRefresh(
        previous: RuntimeValueScanSnapshot,
        reader: RuntimeMemoryReader,
        cancellation: CancellationSignal,
        keep:
            (
                oldBits: Long,
                newBits: Long,
            ) -> Boolean,
    ): RuntimeValueScanSnapshot {
        val type =
            previous.valueType
        val retained =
            ArrayList<RuntimeValueHit>(
                previous.hits.size,
            )

        /*
         * Refinement must not issue one root command per address. Hits are
         * sorted and coalesced into bounded windows inside their original map,
         * so tens of thousands of candidates can be refreshed with a small
         * number of /proc/<pid>/mem reads.
         */
        val groups =
            previous.hits
                .groupBy {
                    Triple(
                        it.regionStart,
                        it.regionEndExclusive,
                        it.regionPath,
                    )
                }
        var processed = 0
        groups.values.forEach {
                rawGroup,
            ->
            val group =
                rawGroup.sortedBy {
                    it.address
                }
            var index = 0
            while (index < group.size) {
                checkCancelled(
                    cancellation,
                )
                val first =
                    group[index]
                val windowStart =
                    first.address
                var endIndex =
                    index
                while (
                    endIndex + 1 <
                    group.size
                ) {
                    val next =
                        group[endIndex + 1]
                    val span =
                        next.address +
                            type.byteWidth -
                            windowStart
                    if (
                        span >
                        REFINE_WINDOW_BYTES
                    ) {
                        break
                    }
                    endIndex++
                }

                val last =
                    group[endIndex]
                val readSize =
                    (
                        last.address +
                            type.byteWidth -
                            windowStart
                        ).toInt()
                val bytes =
                    reader.read(
                        address =
                            windowStart,
                        size =
                            readSize,
                        cancellation =
                            cancellation,
                    )
                if (
                    bytes != null &&
                    bytes.size >=
                    type.byteWidth
                ) {
                    for (
                        hitIndex in
                            index..endIndex
                    ) {
                        if (
                            processed % 256 ==
                            0
                        ) {
                            checkCancelled(
                                cancellation,
                            )
                        }
                        processed++
                        val hit =
                            group[hitIndex]
                        val offset =
                            (
                                hit.address -
                                    windowStart
                                ).toInt()
                        if (
                            offset < 0 ||
                            offset +
                                type.byteWidth >
                            bytes.size
                        ) {
                            continue
                        }
                        val now =
                            type.readBits(
                                bytes,
                                offset,
                            )
                        if (
                            keep(
                                hit.bits,
                                now,
                            )
                        ) {
                            retained +=
                                hit.copy(
                                    bits = now,
                                )
                        }
                    }
                }
                index =
                    endIndex + 1
            }
        }

        return previous.copy(
            hits = retained,
            scannedBytes =
                retained.size.toLong() *
                    type.byteWidth,
            scannedRegions =
                retained
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
        alignment: RuntimeScanAlignment =
            RuntimeScanAlignment.NATURAL,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxScanBytes: Long? = null,
        expectedPid: Int? = null,
    ): RootRuntimeValueScanResult {
        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = expectedPid,
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
                alignment = alignment,
                maxScanBytes =
                    maxScanBytes
                        ?: totalRangeBytes(
                            ranges,
                        ),
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

    fun refineExact(
        previous: RootRuntimeValueScanResult,
        query: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootRuntimeValueScanResult =
        withVerifiedProcess(
            previous = previous,
            cancellation = cancellation,
            runner = runner,
        ) {
                reader,
            ->
            RuntimeValueScanner
                .refineExact(
                    previous =
                        previous.snapshot,
                    reader = reader,
                    query = query,
                    cancellation =
                        cancellation,
                )
        }

    fun refresh(
        previous: RootRuntimeValueScanResult,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootRuntimeValueScanResult =
        withVerifiedProcess(
            previous = previous,
            cancellation = cancellation,
            runner = runner,
        ) {
                reader,
            ->
            RuntimeValueScanner.refresh(
                previous =
                    previous.snapshot,
                reader = reader,
                cancellation =
                    cancellation,
            )
        }

    fun refine(
        previous: RootRuntimeValueScanResult,
        refinement: RuntimeValueRefinement,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootRuntimeValueScanResult {
        return withVerifiedProcess(
            previous = previous,
            cancellation = cancellation,
            runner = runner,
        ) {
                reader,
            ->
            RuntimeValueScanner.refine(
                previous =
                    previous.snapshot,
                reader = reader,
                refinement =
                    refinement,
                cancellation =
                    cancellation,
            )
        }
    }

    private fun withVerifiedProcess(
        previous: RootRuntimeValueScanResult,
        cancellation: CancellationSignal,
        runner: RootCommandRunner,
        operation:
            (
                reader: RuntimeMemoryReader,
            ) -> RuntimeValueScanSnapshot,
    ): RootRuntimeValueScanResult {
        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        previous.packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = previous.pid,
                )
        require(
            capture.pid ==
                previous.pid,
        ) {
            "PID процесса изменился. Начните новый поиск значений."
        }
        val updated =
            operation(
                RootProcMemRuntimeMemoryReader(
                    pid = capture.pid,
                    runner = runner,
                ),
            )
        return previous.copy(
            capturedAtEpochMs =
                System.currentTimeMillis(),
            snapshot = updated,
        )
    }

    fun findPointersTo(
        packageName: String,
        expectedPid: Int,
        targetAddress: Long,
        depth: Int = 1,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootRuntimePointerScanResult {
        require(targetAddress >= 0L) {
            "Некорректный runtime-адрес."
        }
        require(depth in 1..16) {
            "Некорректная глубина pointer scan."
        }

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = expectedPid,
                )
        require(
            capture.pid ==
                expectedPid,
        ) {
            "PID процесса изменился. Pointer scan остановлен."
        }

        val ranges =
            candidateRanges(
                ProcMapsParser.parse(
                    capture.capture.text,
                ),
            )
        val snapshot =
            RuntimeValueScanner.scanExact(
                ranges = ranges,
                reader =
                    RootProcMemRuntimeMemoryReader(
                        pid = capture.pid,
                        runner = runner,
                    ),
                valueType =
                    RuntimeValueType.INT64,
                query =
                    targetAddress.toString(),
                cancellation =
                    cancellation,
                maxHits = 2048,
                maxScanBytes =
                    totalRangeBytes(
                        ranges,
                    ),
                chunkBytes =
                    RuntimeValueScanner
                        .DEFAULT_CHUNK_BYTES,
            )

        return RootRuntimePointerScanResult(
            packageName = packageName,
            pid = capture.pid,
            targetAddress =
                targetAddress,
            depth = depth,
            capturedAtEpochMs =
                System.currentTimeMillis(),
            snapshot = snapshot,
        )
    }

    private fun totalRangeBytes(
        ranges: List<ProcMapRegion>,
    ): Long {
        var total = 0L
        for (region in ranges) {
            if (
                region.size <= 0L
            ) {
                continue
            }
            total =
                if (
                    Long.MAX_VALUE -
                        total <
                    region.size
                ) {
                    Long.MAX_VALUE
                } else {
                    total +
                        region.size
                }
        }
        return total.coerceAtLeast(1L)
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
