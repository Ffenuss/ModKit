package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

data class RuntimeUnknownBaselineSegment(
    val runtimeStart: Long,
    val byteLength: Int,
    val snapshotOffset: Long,
    val regionStart: Long,
    val regionEndExclusive: Long,
    val regionFileOffset: Long,
    val regionPath: String?,
)

data class RootRuntimeUnknownBaseline(
    val packageName: String,
    val pid: Int,
    val valueType: RuntimeValueType,
    val alignment: RuntimeScanAlignment,
    val capturedAtEpochMs: Long,
    val snapshotPath: String,
    val capturedBytes: Long,
    val segments: List<RuntimeUnknownBaselineSegment>,
    val truncatedByByteLimit: Boolean,
)

/**
 * Disk-backed baseline for "unknown initial value" scanning.
 *
 * Raw bounded chunks are written to app-private cache rather than expanded into
 * millions of Kotlin objects. The first comparison converts only matching
 * addresses into the normal [RuntimeValueScanSnapshot], after which ordinary
 * exact/changed/increased/decreased refinement is used.
 */
object RootRuntimeUnknownValueCoordinator {
    const val QUICK_MAX_BASELINE_BYTES =
        32L * 1024L * 1024L
    private const val STORAGE_RESERVE_BYTES =
        64L * 1024L * 1024L
    private const val BEHAVIORAL_MAX_BYTES_PER_REGION =
        4L * 1024L * 1024L

    fun captureBaseline(
        packageName: String,
        valueType: RuntimeValueType,
        alignment: RuntimeScanAlignment =
            RuntimeScanAlignment.NATURAL,
        snapshotFile: File,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxBytes: Long? = null,
        expectedPid: Int? = null,
        behavioralMode: Boolean = false,
    ): RootRuntimeUnknownBaseline {
        require(
            maxBytes == null ||
                maxBytes > 0L
        ) {
            "Некорректный лимит unknown-value baseline."
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
        val baseRanges =
            RootRuntimeValueScanCoordinator
                .candidateRanges(
                    ProcMapsParser.parse(
                        capture.capture.text,
                    ),
                )
        val ranges =
            if (behavioralMode) {
                baseRanges
                    .asSequence()
                    .filterNot {
                        volatileBehavioralRegion(
                            it.path,
                        )
                    }
                    .sortedWith(
                        compareBy<ProcMapRegion> {
                            behavioralRangePriority(
                                it.path,
                                packageName,
                            )
                        }.thenByDescending {
                            min(
                                it.size,
                                BEHAVIORAL_MAX_BYTES_PER_REGION,
                            )
                        },
                    )
                    .toList()
            } else {
                baseRanges
            }
        require(ranges.isNotEmpty()) {
            "В процессе нет подходящих writable private диапазонов."
        }
        val totalRangeBytes =
            totalRangeBytes(
                ranges,
            )
        val effectiveMaxBytes =
            minOf(
                maxBytes
                    ?: totalRangeBytes,
                totalRangeBytes,
            )

        snapshotFile.parentFile
            ?.mkdirs()
        val parent =
            snapshotFile.parentFile
                ?: error(
                    "Не удалось определить каталог unknown-value baseline.",
                )
        val usableSpace =
            parent.usableSpace
        if (
            usableSpace > 0L &&
            usableSpace <
                effectiveMaxBytes +
                    STORAGE_RESERVE_BYTES
        ) {
            error(
                "Недостаточно свободного места для полного unknown-value baseline: нужно примерно " +
                    (effectiveMaxBytes / (1024L * 1024L)) +
                    " MiB + резерв, доступно " +
                    (usableSpace / (1024L * 1024L)) +
                    " MiB.",
            )
        }
        val temp =
            File(
                parent,
                snapshotFile.name +
                    ".tmp",
            )
        temp.delete()
        snapshotFile.delete()

        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = capture.pid,
                runner = runner,
            )
        val segments =
            mutableListOf<
                RuntimeUnknownBaselineSegment,
            >()
        var capturedBytes = 0L
        var snapshotOffset = 0L
        var truncated = false

        try {
            RandomAccessFile(
                temp,
                "rw",
            ).use {
                output ->
                rangeLoop@ for (
                    region in ranges
                ) {
                    var address =
                        region.start
                    var regionCaptured =
                        0L
                    val regionLimit =
                        if (behavioralMode) {
                            min(
                                region.size,
                                BEHAVIORAL_MAX_BYTES_PER_REGION,
                            )
                        } else {
                            region.size
                        }
                    while (
                        address <
                        region.endExclusive &&
                        regionCaptured <
                        regionLimit
                    ) {
                        checkCancelled(
                            cancellation,
                        )
                        val budget =
                            effectiveMaxBytes -
                                capturedBytes
                        if (budget <= 0L) {
                            truncated = true
                            break@rangeLoop
                        }
                        val request =
                            min(
                                min(
                                    min(
                                        region.endExclusive -
                                            address,
                                        regionLimit -
                                            regionCaptured,
                                    ),
                                    budget,
                                ),
                                ProcMemRuntimeMemoryReader
                                    .MAX_READ_BYTES
                                    .toLong(),
                            ).toInt()
                        if (
                            request <
                            valueType.byteWidth
                        ) {
                            break
                        }

                        val bytes =
                            reader.read(
                                address =
                                    address,
                                size = request,
                                cancellation =
                                    cancellation,
                            )
                        if (
                            bytes == null ||
                            bytes.isEmpty()
                        ) {
                            // Skip an unreadable page-sized slice instead of
                            // aborting the entire process snapshot.
                            address +=
                                min(
                                    4096L,
                                    region.endExclusive -
                                        address,
                                )
                            continue
                        }

                        output.seek(
                            snapshotOffset,
                        )
                        output.write(bytes)
                        segments +=
                            RuntimeUnknownBaselineSegment(
                                runtimeStart =
                                    address,
                                byteLength =
                                    bytes.size,
                                snapshotOffset =
                                    snapshotOffset,
                                regionStart =
                                    region.start,
                                regionEndExclusive =
                                    region.endExclusive,
                                regionFileOffset =
                                    region.fileOffset,
                                regionPath =
                                    region.path,
                            )
                        val advance =
                            if (
                                alignment ==
                                    RuntimeScanAlignment.BYTE &&
                                bytes.size >
                                    valueType.byteWidth - 1
                            ) {
                                bytes.size -
                                    (
                                        valueType.byteWidth -
                                            1
                                        )
                            } else {
                                bytes.size
                            }
                        address +=
                            advance
                        snapshotOffset +=
                            bytes.size
                        capturedBytes +=
                            bytes.size
                        regionCaptured +=
                            bytes.size
                    }
                }
                output.fd.sync()
            }

            require(
                capturedBytes >=
                    valueType.byteWidth,
            ) {
                "Не удалось прочитать достаточно runtime-памяти для baseline."
            }
            check(
                temp.renameTo(
                    snapshotFile,
                ),
            ) {
                "Не удалось сохранить unknown-value baseline."
            }

            return RootRuntimeUnknownBaseline(
                packageName =
                    packageName,
                pid = capture.pid,
                valueType =
                    valueType,
                alignment =
                    alignment,
                capturedAtEpochMs =
                    System.currentTimeMillis(),
                snapshotPath =
                    snapshotFile.absolutePath,
                capturedBytes =
                    capturedBytes,
                segments =
                    segments,
                truncatedByByteLimit =
                    truncated,
            )
        } catch (failure: Throwable) {
            temp.delete()
            snapshotFile.delete()
            throw failure
        }
    }

    fun compareBaseline(
        baseline: RootRuntimeUnknownBaseline,
        refinement: RuntimeValueRefinement,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxHits: Int =
            RuntimeValueScanner
                .DEFAULT_MAX_HITS,
    ): RootRuntimeValueScanResult {
        require(maxHits in 1..100_000) {
            "Некорректный лимит unknown-value результатов."
        }
        val snapshot =
            File(
                baseline.snapshotPath,
            )
        require(
            snapshot.isFile &&
                snapshot.canRead()
        ) {
            "Unknown-value baseline больше недоступен."
        }

        val capture =
            try {
                RootRuntimeCaptureCoordinator
                    .captureMaps(
                        packageName =
                            baseline.packageName,
                        cancellation =
                            cancellation,
                        runner = runner,
                        expectedPid =
                            baseline.pid,
                    )
            } catch (failure: Throwable) {
                throw IllegalArgumentException(
                    "PID процесса изменился или больше недоступен. Создайте новый unknown-value baseline.",
                    failure,
                )
            }
        require(
            capture.pid ==
                baseline.pid,
        ) {
            "PID процесса изменился. Создайте новый unknown-value baseline."
        }

        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = capture.pid,
                runner = runner,
            )
        val type =
            baseline.valueType
        val hits =
            ArrayList<RuntimeValueHit>()
        val seenAddresses =
            HashSet<Long>()
        var comparedBytes = 0L
        var comparedRegions = 0
        var hitLimit = false

        RandomAccessFile(
            snapshot,
            "r",
        ).use {
            stored ->
            segmentLoop@ for (
                segment in
                    baseline.segments
            ) {
                checkCancelled(
                    cancellation,
                )
                val oldBytes =
                    ByteArray(
                        segment.byteLength,
                    )
                stored.seek(
                    segment.snapshotOffset,
                )
                stored.readFully(
                    oldBytes,
                )
                val newBytes =
                    reader.read(
                        address =
                            segment.runtimeStart,
                        size =
                            segment.byteLength,
                        cancellation =
                            cancellation,
                    ) ?: continue
                val usable =
                    min(
                        oldBytes.size,
                        newBytes.size,
                    )
                if (
                    usable <
                    type.byteWidth
                ) {
                    continue
                }
                comparedRegions++

                var offset =
                    if (
                        baseline.alignment ==
                        RuntimeScanAlignment
                            .NATURAL
                    ) {
                        alignedOffset(
                            runtimeStart =
                                segment.runtimeStart,
                            width =
                                type.byteWidth,
                        )
                    } else {
                        0
                    }
                val step =
                    baseline.alignment
                        .step(type)
                while (
                    offset +
                        type.byteWidth <=
                    usable
                ) {
                    val oldBits =
                        type.readBits(
                            oldBytes,
                            offset,
                        )
                    val newBits =
                        type.readBits(
                            newBytes,
                            offset,
                        )
                    val comparison =
                        type.compare(
                            oldBits,
                            newBits,
                        )
                    val keep =
                        when (
                            refinement
                        ) {
                            RuntimeValueRefinement
                                .CHANGED ->
                                oldBits !=
                                    newBits
                            RuntimeValueRefinement
                                .UNCHANGED ->
                                oldBits ==
                                    newBits
                            RuntimeValueRefinement
                                .INCREASED ->
                                comparison < 0
                            RuntimeValueRefinement
                                .DECREASED ->
                                comparison > 0
                        }
                    if (keep) {
                        val address =
                            segment.runtimeStart +
                                offset
                        if (
                            !seenAddresses.add(
                                address,
                            )
                        ) {
                            offset +=
                                step
                            continue
                        }
                        hits +=
                            RuntimeValueHit(
                                address =
                                    address,
                                bits =
                                    newBits,
                                regionStart =
                                    segment
                                        .regionStart,
                                regionEndExclusive =
                                    segment
                                        .regionEndExclusive,
                                regionFileOffset =
                                    segment
                                        .regionFileOffset,
                                regionPath =
                                    segment
                                        .regionPath,
                            )
                        if (
                            hits.size >=
                            maxHits
                        ) {
                            hitLimit = true
                            comparedBytes +=
                                offset +
                                    type.byteWidth
                            break@segmentLoop
                        }
                    }
                    offset +=
                        step
                }
                comparedBytes +=
                    usable
            }
        }

        return RootRuntimeValueScanResult(
            packageName =
                baseline.packageName,
            pid = baseline.pid,
            capturedAtEpochMs =
                System.currentTimeMillis(),
            snapshot =
                RuntimeValueScanSnapshot(
                    valueType =
                        baseline.valueType,
                    hits = hits,
                    alignment =
                        baseline.alignment,
                    scannedBytes =
                        comparedBytes,
                    scannedRegions =
                        comparedRegions,
                    truncatedByHitLimit =
                        hitLimit,
                    truncatedByByteLimit =
                        baseline
                            .truncatedByByteLimit,
                ),
        )
    }

    fun deleteBaseline(
        baseline:
            RootRuntimeUnknownBaseline?,
    ) {
        baseline
            ?.snapshotPath
            ?.let(::File)
            ?.delete()
    }

    private fun volatileBehavioralRegion(
        path: String?,
    ): Boolean {
        val normalized =
            path.orEmpty()
                .lowercase()
        return normalized.startsWith("[stack") ||
            normalized.contains("jit-cache") ||
            normalized.contains("dalvik-jit") ||
            normalized.contains("gralloc") ||
            normalized.contains("kgsl") ||
            normalized.contains("dmabuf") ||
            normalized.startsWith("/dev/")
    }

    private fun behavioralRangePriority(
        path: String?,
        packageName: String,
    ): Int {
        if (path == null) {
            return 0
        }
        val normalized =
            path.lowercase()
        return when {
            normalized == "[heap]" ->
                0
            normalized.startsWith("[anon:") ->
                0
            normalized.contains(packageName.lowercase()) ->
                1
            normalized.startsWith("/data/app/") ||
                normalized.startsWith("/data/user/") ->
                2
            else ->
                3
        }
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

    private fun alignedOffset(
        runtimeStart: Long,
        width: Int,
    ): Int {
        val remainder =
            Math.floorMod(
                runtimeStart,
                width.toLong(),
            )
        return if (remainder == 0L) {
            0
        } else {
            (width - remainder)
                .toInt()
        }
    }

    private fun checkCancelled(
        cancellation:
            CancellationSignal,
    ) {
        if (
            cancellation.isCancelled()
        ) {
            throw AnalysisCancelledException()
        }
    }
}
