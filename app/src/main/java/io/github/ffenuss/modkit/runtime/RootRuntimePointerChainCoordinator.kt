package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import kotlin.math.min

data class RuntimePointerChainStep(
    val pointerAddress: Long,
    val offsetToNext: Long,
    val regionPath: String?,
    val regionStart: Long,
    val regionFileOffset: Long,
)

data class StableRuntimePointerAnchor(
    val moduleIdentity: String,
    val moduleFileOffset: Long,
    val pointerWidth: Int,
    val offsetsFromAnchor: List<Long>,
)

data class RootRuntimePointerChainResult(
    val packageName: String,
    val pid: Int,
    val targetAddress: Long,
    val pointerWidth: Int,
    val stepsFromTargetOutward: List<RuntimePointerChainStep>,
    val stableAnchor: StableRuntimePointerAnchor?,
    val capturedAtEpochMs: Long,
)

data class ResolvedRuntimePointerTarget(
    val packageName: String,
    val pid: Int,
    val targetAddress: Long,
    val anchorAddress: Long,
    val anchor: StableRuntimePointerAnchor,
)

private data class PointerCellCandidate(
    val pointerAddress: Long,
    val pointedBase: Long,
    val offsetToTarget: Long,
    val region: ProcMapRegion,
)

/**
 * Discovers a restart-stable pointer chain for an already confirmed runtime
 * value. The scanner accepts small positive field offsets instead of requiring
 * a pointer to equal the field address exactly; this matches ordinary object
 * layouts much better than an exact-pointer-only pass.
 *
 * A chain is persistent only when its outermost pointer cell is inside a
 * file-backed mapping. The persisted identity is module + file offset, not an
 * ASLR-dependent virtual address.
 */
object RootRuntimePointerChainCoordinator {
    private const val DEFAULT_MAX_OFFSET =
        0x800L
    private const val DEFAULT_MAX_DEPTH =
        3
    private const val DEFAULT_SCAN_BYTES =
        96L * 1024L * 1024L
    private const val CHUNK_BYTES =
        1024 * 1024
    private const val MAX_CANDIDATES =
        96

    fun discover(
        packageName: String,
        pid: Int,
        targetAddress: Long,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxDepth: Int =
            DEFAULT_MAX_DEPTH,
        maxOffset: Long =
            DEFAULT_MAX_OFFSET,
        maxScanBytesPerDepth: Long =
            DEFAULT_SCAN_BYTES,
    ): RootRuntimePointerChainResult {
        require(pid > 0) {
            "Pointer-chain PID must be positive."
        }
        require(targetAddress > 0L) {
            "Pointer-chain target address must be positive."
        }
        require(maxDepth in 1..6) {
            "Pointer-chain depth is out of bounds."
        }
        require(maxOffset in 0L..0x10000L) {
            "Pointer-chain max field offset is out of bounds."
        }

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = pid,
                )
        val regions =
            ProcMapsParser.parse(
                capture.capture.text,
            )
        val ranges =
            RootRuntimeValueScanCoordinator
                .candidateRanges(
                    regions,
                )
        require(ranges.isNotEmpty()) {
            "No writable private ranges for pointer-chain discovery."
        }

        val pointerWidth =
            if (
                regions.maxOfOrNull {
                    it.endExclusive
                } ?: 0L <=
                0xffff_ffffL
            ) {
                4
            } else {
                8
            }

        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = pid,
                runner = runner,
            )
        val steps =
            mutableListOf<
                RuntimePointerChainStep
            >()
        var currentTarget =
            targetAddress

        repeat(maxDepth) {
            depth ->
            checkCancelled(
                cancellation,
            )
            val candidates =
                scanNearPointers(
                    ranges = ranges,
                    reader = reader,
                    targetAddress =
                        currentTarget,
                    pointerWidth =
                        pointerWidth,
                    maxOffset =
                        maxOffset,
                    maxScanBytes =
                        maxScanBytesPerDepth,
                    cancellation =
                        cancellation,
                )
            if (candidates.isEmpty()) {
                return RootRuntimePointerChainResult(
                    packageName =
                        packageName,
                    pid = pid,
                    targetAddress =
                        targetAddress,
                    pointerWidth =
                        pointerWidth,
                    stepsFromTargetOutward =
                        steps,
                    stableAnchor = null,
                    capturedAtEpochMs =
                        System.currentTimeMillis(),
                )
            }

            val selected =
                candidates
                    .sortedWith(
                        compareBy<
                            PointerCellCandidate
                        > {
                            if (
                                stableModulePath(
                                    it.region.path,
                                )
                            ) {
                                0
                            } else {
                                1
                            }
                        }.thenBy {
                            pointerRegionPriority(
                                it.region.path,
                            )
                        }.thenBy {
                            it.offsetToTarget
                        }.thenBy {
                            it.pointerAddress
                        },
                    )
                    .first()

            val step =
                RuntimePointerChainStep(
                    pointerAddress =
                        selected.pointerAddress,
                    offsetToNext =
                        selected.offsetToTarget,
                    regionPath =
                        selected.region.path,
                    regionStart =
                        selected.region.start,
                    regionFileOffset =
                        selected.region
                            .fileOffset,
                )
            steps += step

            if (
                stableModulePath(
                    selected.region.path,
                )
            ) {
                val identity =
                    moduleIdentity(
                        requireNotNull(
                            selected.region.path,
                        ),
                    )
                val moduleOffset =
                    selected.region
                        .fileOffset +
                        (
                            selected
                                .pointerAddress -
                                selected.region
                                    .start
                            )
                val offsets =
                    steps
                        .asReversed()
                        .map {
                            it.offsetToNext
                        }

                return RootRuntimePointerChainResult(
                    packageName =
                        packageName,
                    pid = pid,
                    targetAddress =
                        targetAddress,
                    pointerWidth =
                        pointerWidth,
                    stepsFromTargetOutward =
                        steps.toList(),
                    stableAnchor =
                        StableRuntimePointerAnchor(
                            moduleIdentity =
                                identity,
                            moduleFileOffset =
                                moduleOffset,
                            pointerWidth =
                                pointerWidth,
                            offsetsFromAnchor =
                                offsets,
                        ),
                    capturedAtEpochMs =
                        System.currentTimeMillis(),
                )
            }

            if (
                selected.pointerAddress ==
                currentTarget
            ) {
                return RootRuntimePointerChainResult(
                    packageName =
                        packageName,
                    pid = pid,
                    targetAddress =
                        targetAddress,
                    pointerWidth =
                        pointerWidth,
                    stepsFromTargetOutward =
                        steps,
                    stableAnchor = null,
                    capturedAtEpochMs =
                        System.currentTimeMillis(),
                )
            }
            currentTarget =
                selected.pointerAddress

            if (depth + 1 >= maxDepth) {
                return RootRuntimePointerChainResult(
                    packageName =
                        packageName,
                    pid = pid,
                    targetAddress =
                        targetAddress,
                    pointerWidth =
                        pointerWidth,
                    stepsFromTargetOutward =
                        steps.toList(),
                    stableAnchor = null,
                    capturedAtEpochMs =
                        System.currentTimeMillis(),
                )
            }
        }

        return RootRuntimePointerChainResult(
            packageName =
                packageName,
            pid = pid,
            targetAddress =
                targetAddress,
            pointerWidth =
                pointerWidth,
            stepsFromTargetOutward =
                steps.toList(),
            stableAnchor = null,
            capturedAtEpochMs =
                System.currentTimeMillis(),
        )
    }

    fun resolve(
        packageName: String,
        pid: Int,
        anchor: StableRuntimePointerAnchor,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): ResolvedRuntimePointerTarget {
        require(
            anchor.pointerWidth == 4 ||
                anchor.pointerWidth == 8
        ) {
            "Unsupported persisted pointer width."
        }
        require(
            anchor.offsetsFromAnchor
                .isNotEmpty()
        ) {
            "Persisted pointer chain is empty."
        }

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = pid,
                )
        val regions =
            ProcMapsParser.parse(
                capture.capture.text,
            )

        val anchorRegion =
            regions
                .asSequence()
                .filter {
                    stableModulePath(
                        it.path,
                    )
                }
                .filter {
                    moduleIdentity(
                        requireNotNull(
                            it.path,
                        ),
                    ) ==
                        anchor.moduleIdentity
                }
                .filter {
                    anchor.moduleFileOffset >=
                        it.fileOffset &&
                        anchor.moduleFileOffset <
                        it.fileOffset +
                            it.size
                }
                .sortedBy {
                    it.start
                }
                .firstOrNull()
                ?: error(
                    "Stable pointer module is not mapped: " +
                        anchor.moduleIdentity,
                )

        val anchorAddress =
            anchorRegion.start +
                (
                    anchor.moduleFileOffset -
                        anchorRegion.fileOffset
                    )

        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = pid,
                runner = runner,
            )

        var pointerCell =
            anchorAddress
        anchor.offsetsFromAnchor
            .forEachIndexed {
                    index,
                    offset,
                ->
                checkCancelled(
                    cancellation,
                )
                val raw =
                    reader.read(
                        address =
                            pointerCell,
                        size =
                            anchor.pointerWidth,
                        cancellation =
                            cancellation,
                    ) ?: error(
                        "Pointer-chain cell became unreadable.",
                    )
                require(
                    raw.size ==
                        anchor.pointerWidth
                ) {
                    "Pointer-chain cell read was truncated."
                }
                val base =
                    readPointer(
                        raw,
                        anchor.pointerWidth,
                    )
                require(base > 0L) {
                    "Pointer-chain cell contains a null/invalid pointer."
                }
                val next =
                    base +
                        offset
                require(next > 0L) {
                    "Pointer-chain offset overflow/underflow."
                }
                pointerCell =
                    next

                if (
                    index <
                    anchor
                        .offsetsFromAnchor
                        .lastIndex
                ) {
                    require(
                        regions.any {
                            pointerCell >=
                                it.start &&
                                pointerCell +
                                    anchor.pointerWidth <=
                                it.endExclusive &&
                                it.readable
                        },
                    ) {
                        "Intermediate pointer-chain address is not readable."
                    }
                }
            }

        val targetAddress =
            pointerCell
        require(
            regions.any {
                targetAddress >=
                    it.start &&
                    targetAddress <
                    it.endExclusive &&
                    it.readable &&
                    it.writable &&
                    !it.executable
            },
        ) {
            "Resolved pointer target is not inside a writable private data mapping."
        }

        return ResolvedRuntimePointerTarget(
            packageName =
                packageName,
            pid = pid,
            targetAddress =
                targetAddress,
            anchorAddress =
                anchorAddress,
            anchor = anchor,
        )
    }

    private fun scanNearPointers(
        ranges: List<ProcMapRegion>,
        reader: RuntimeMemoryReader,
        targetAddress: Long,
        pointerWidth: Int,
        maxOffset: Long,
        maxScanBytes: Long,
        cancellation: CancellationSignal,
    ): List<PointerCellCandidate> {
        val hits =
            ArrayList<
                PointerCellCandidate
            >()
        var scanned = 0L

        val orderedRanges =
            ranges.sortedWith(
                compareBy<
                    ProcMapRegion
                > {
                    pointerRegionPriority(
                        it.path,
                    )
                }.thenByDescending {
                    min(
                        it.size,
                        8L * 1024L * 1024L,
                    )
                },
            )

        rangeLoop@ for (
            region in orderedRanges
        ) {
            var address =
                alignUp(
                    region.start,
                    pointerWidth,
                )
            while (
                address +
                    pointerWidth <=
                region.endExclusive &&
                scanned <
                maxScanBytes
            ) {
                checkCancelled(
                    cancellation,
                )
                val budget =
                    maxScanBytes -
                        scanned
                val request =
                    min(
                        min(
                            region.endExclusive -
                                address,
                            CHUNK_BYTES
                                .toLong(),
                        ),
                        budget,
                    ).toInt()
                if (
                    request <
                    pointerWidth
                ) {
                    break
                }

                val bytes =
                    reader.read(
                        address = address,
                        size = request,
                        cancellation =
                            cancellation,
                    )
                if (
                    bytes == null ||
                    bytes.size <
                        pointerWidth
                ) {
                    address +=
                        min(
                            4096L,
                            region.endExclusive -
                                address,
                        )
                    continue
                }

                val usable =
                    bytes.size -
                        (
                            bytes.size %
                                pointerWidth
                            )
                var offset = 0
                while (
                    offset +
                        pointerWidth <=
                    usable
                ) {
                    val pointed =
                        readPointerAt(
                            bytes =
                                bytes,
                            offset =
                                offset,
                            width =
                                pointerWidth,
                        )
                    if (
                        pointed > 0L &&
                        pointed <=
                            targetAddress
                    ) {
                        val delta =
                            targetAddress -
                                pointed
                        if (
                            delta in
                            0L..maxOffset
                        ) {
                            hits +=
                                PointerCellCandidate(
                                    pointerAddress =
                                        address +
                                            offset,
                                    pointedBase =
                                        pointed,
                                    offsetToTarget =
                                        delta,
                                    region =
                                        region,
                                )
                            if (
                                hits.size >=
                                MAX_CANDIDATES
                            ) {
                                break@rangeLoop
                            }
                        }
                    }
                    offset +=
                        pointerWidth
                }

                scanned +=
                    usable
                address +=
                    usable
            }
        }
        return hits
    }

    private fun pointerRegionPriority(
        path: String?,
    ): Int {
        if (
            stableModulePath(path)
        ) {
            return 0
        }
        if (
            path == null ||
            path == "[heap]" ||
            path.startsWith(
                "[anon:",
            )
        ) {
            return 1
        }
        return 2
    }

    private fun stableModulePath(
        path: String?,
    ): Boolean {
        if (path.isNullOrBlank()) {
            return false
        }
        val normalized =
            path.lowercase()
        return !normalized.startsWith("[") &&
            !normalized.startsWith("/dev/") &&
            !normalized.startsWith("/memfd:") &&
            !normalized.endsWith(
                " (deleted)",
            )
    }

    private fun moduleIdentity(
        path: String,
    ): String =
        if ("!/" in path) {
            path.substringAfter(
                "!/",
            )
        } else {
            path.substringAfterLast(
                '/',
            )
        }

    private fun alignUp(
        value: Long,
        alignment: Int,
    ): Long {
        val remainder =
            Math.floorMod(
                value,
                alignment.toLong(),
            )
        return if (remainder == 0L) {
            value
        } else {
            value +
                (
                    alignment -
                        remainder
                    )
        }
    }

    private fun readPointer(
        bytes: ByteArray,
        width: Int,
    ): Long =
        readPointerAt(
            bytes = bytes,
            offset = 0,
            width = width,
        )

    private fun readPointerAt(
        bytes: ByteArray,
        offset: Int,
        width: Int,
    ): Long {
        return if (width == 4) {
            (
                (bytes[offset]
                    .toLong() and
                    0xffL) or
                    (
                        (bytes[offset + 1]
                            .toLong() and
                            0xffL) shl
                            8
                        ) or
                    (
                        (bytes[offset + 2]
                            .toLong() and
                            0xffL) shl
                            16
                        ) or
                    (
                        (bytes[offset + 3]
                            .toLong() and
                            0xffL) shl
                            24
                        )
                )
        } else {
            var value = 0L
            repeat(8) {
                index ->
                value =
                    value or
                        (
                            (bytes[
                                offset +
                                    index
                                ].toLong() and
                                0xffL) shl
                                (
                                    index *
                                        8
                                    )
                            )
            }
            value
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
