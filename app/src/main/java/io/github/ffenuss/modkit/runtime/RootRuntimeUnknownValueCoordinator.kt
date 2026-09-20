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
    const val DEFAULT_MAX_BASELINE_BYTES =
        32L * 1024L * 1024L

    fun captureBaseline(
        packageName: String,
        valueType: RuntimeValueType,
        alignment: RuntimeScanAlignment =
            RuntimeScanAlignment.NATURAL,
        snapshotFile: File,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxBytes: Long =
            DEFAULT_MAX_BASELINE_BYTES,
    ): RootRuntimeUnknownBaseline {
        require(
            maxBytes in
                1..
                    (512L * 1024L * 1024L),
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
                )
        val ranges =
            RootRuntimeValueScanCoordinator
                .candidateRanges(
                    ProcMapsParser.parse(
                        capture.capture.text,
                    ),
                )
        require(ranges.isNotEmpty()) {
            "В процессе нет подходящих writable private диапазонов."
        }

        snapshotFile.parentFile
            ?.mkdirs()
        val temp =
            File(
                snapshotFile.parentFile,
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
                    while (
                        address <
                        region.endExclusive
                    ) {
                        checkCancelled(
                            cancellation,
                        )
                        val budget =
                            maxBytes -
                                capturedBytes
                        if (budget <= 0L) {
                            truncated = true
                            break@rangeLoop
                        }
                        val request =
                            min(
                                min(
                                    region.endExclusive -
                                        address,
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
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        baseline.packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                )
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
