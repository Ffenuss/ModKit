package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeValueScannerTest {
    @Test
    fun exactInt32ScanFindsAlignedValues() {
        val base = 0x1000L
        val memory = ByteArray(64)
        putInt(memory, 8, 100)
        putInt(memory, 24, 100)
        putInt(memory, 28, 101)

        val result =
            RuntimeValueScanner.scanExact(
                ranges =
                    listOf(
                        region(
                            start = base,
                            size = memory.size,
                        ),
                    ),
                reader =
                    ArrayMemoryReader(
                        base = base,
                        bytes = memory,
                    ),
                valueType =
                    RuntimeValueType.INT32,
                query = "100",
                cancellation =
                    AtomicCancellationSignal(),
                maxHits = 32,
                maxScanBytes = 1024,
                chunkBytes = 32,
            )

        assertEquals(
            listOf(
                base + 8,
                base + 24,
            ),
            result.hits.map {
                it.address
            },
        )
        assertFalse(
            result.truncatedByHitLimit,
        )
    }

    @Test
    fun exactFloatScanUsesRawIeeeValue() {
        val base = 0x2000L
        val memory = ByteArray(32)
        putFloat(memory, 4, 2.5f)
        putFloat(memory, 12, 5.0f)

        val result =
            RuntimeValueScanner.scanExact(
                ranges =
                    listOf(
                        region(
                            start = base,
                            size = memory.size,
                        ),
                    ),
                reader =
                    ArrayMemoryReader(
                        base = base,
                        bytes = memory,
                    ),
                valueType =
                    RuntimeValueType.FLOAT32,
                query = "2.5",
                cancellation =
                    AtomicCancellationSignal(),
                maxHits = 16,
                maxScanBytes = 1024,
                chunkBytes = 32,
            )

        assertEquals(
            1,
            result.hits.size,
        )
        assertEquals(
            "2.5",
            result.hits.single()
                .displayValue(
                    RuntimeValueType.FLOAT32,
                ),
        )
    }

    @Test
    fun refineIncreasedKeepsOnlyValuesThatWentUp() {
        val base = 0x3000L
        val initial = ByteArray(32)
        putInt(initial, 0, 10)
        putInt(initial, 4, 10)
        putInt(initial, 8, 10)

        val first =
            RuntimeValueScanner.scanExact(
                ranges =
                    listOf(
                        region(
                            start = base,
                            size = initial.size,
                        ),
                    ),
                reader =
                    ArrayMemoryReader(
                        base = base,
                        bytes = initial,
                    ),
                valueType =
                    RuntimeValueType.INT32,
                query = "10",
                cancellation =
                    AtomicCancellationSignal(),
                maxHits = 16,
                maxScanBytes = 1024,
                chunkBytes = 32,
            )

        val changed =
            initial.copyOf()
        putInt(changed, 0, 11)
        putInt(changed, 4, 9)
        putInt(changed, 8, 10)

        val refined =
            RuntimeValueScanner.refine(
                previous = first,
                reader =
                    ArrayMemoryReader(
                        base = base,
                        bytes = changed,
                    ),
                refinement =
                    RuntimeValueRefinement.INCREASED,
                cancellation =
                    AtomicCancellationSignal(),
            )

        assertEquals(
            listOf(base),
            refined.hits.map {
                it.address
            },
        )
        assertEquals(
            "11",
            refined.hits.single()
                .displayValue(
                    RuntimeValueType.INT32,
                ),
        )
    }

    @Test
    fun exactRefineAndRefreshKeepLiveValues() {
        val base = 0x3800L
        val initial = ByteArray(32)
        putInt(initial, 0, 25)
        putInt(initial, 4, 25)

        val first =
            RuntimeValueScanner.scanExact(
                ranges =
                    listOf(
                        region(
                            start = base,
                            size = initial.size,
                            fileOffset = 0x4000,
                        ),
                    ),
                reader =
                    ArrayMemoryReader(
                        base = base,
                        bytes = initial,
                    ),
                valueType =
                    RuntimeValueType.INT32,
                query = "25",
                cancellation =
                    AtomicCancellationSignal(),
                maxHits = 16,
                maxScanBytes = 1024,
                chunkBytes = 32,
            )

        val changed =
            initial.copyOf()
        putInt(changed, 0, 30)
        putInt(changed, 4, 40)
        val reader =
            ArrayMemoryReader(
                base = base,
                bytes = changed,
            )

        val exact =
            RuntimeValueScanner.refineExact(
                previous = first,
                reader = reader,
                query = "40",
                cancellation =
                    AtomicCancellationSignal(),
            )
        assertEquals(1, exact.hits.size)
        assertEquals(base + 4, exact.hits.single().address)
        assertEquals(
            0x4004L,
            exact.hits.single().mappedFileOffset,
        )

        val refreshed =
            RuntimeValueScanner.refresh(
                previous = exact,
                reader = reader,
                cancellation =
                    AtomicCancellationSignal(),
            )
        assertEquals(
            "40",
            refreshed.hits.single()
                .displayValue(
                    RuntimeValueType.INT32,
                ),
        )
    }

    @Test
    fun rootCandidateRangesExcludeExecutableAndSystemMappings() {
        val ranges =
            RootRuntimeValueScanCoordinator
                .candidateRanges(
                    listOf(
                        region(
                            start = 0x1000,
                            size = 0x1000,
                            permissions =
                                "rw-p",
                            path = "[heap]",
                        ),
                        region(
                            start = 0x3000,
                            size = 0x1000,
                            permissions =
                                "rwxp",
                            path = null,
                        ),
                        region(
                            start = 0x5000,
                            size = 0x1000,
                            permissions =
                                "rw-p",
                            path =
                                "/system/lib64/libc.so",
                        ),
                    ),
                )

        assertEquals(1, ranges.size)
        assertEquals(
            "[heap]",
            ranges.single().path,
        )
        assertTrue(
            ranges.single().writable,
        )
    }

    private class ArrayMemoryReader(
        private val base: Long,
        private val bytes: ByteArray,
    ) : RuntimeMemoryReader {
        override fun read(
            address: Long,
            size: Int,
            cancellation:
                CancellationSignal,
        ): ByteArray? {
            if (cancellation.isCancelled()) {
                return null
            }
            val offset =
                (address - base)
                    .toInt()
            if (
                offset < 0 ||
                size < 0 ||
                offset + size >
                bytes.size
            ) {
                return null
            }
            return bytes.copyOfRange(
                offset,
                offset + size,
            )
        }
    }

    private fun region(
        start: Long,
        size: Int,
        permissions: String = "rw-p",
        path: String? = "[heap]",
        fileOffset: Long = 0,
    ) =
        ProcMapRegion(
            start = start,
            endExclusive =
                start + size,
            permissions =
                permissions,
            fileOffset = fileOffset,
            device = "00:00",
            inode = 0,
            path = path,
        )

    private fun putInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(offset, value)
    }

    private fun putFloat(
        bytes: ByteArray,
        offset: Int,
        value: Float,
    ) {
        ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(offset, value)
    }

    @Test
    fun refinementBatchesNearbyHitsIntoFewReads() {
        val base = 0x7000L
        val memory =
            ByteArray(400)
        val hits =
            (0 until 100)
                .map {
                    index ->
                    putInt(
                        memory,
                        index * 4,
                        index,
                    )
                    RuntimeValueHit(
                        address =
                            base +
                                index * 4L,
                        bits =
                            index.toLong(),
                        regionStart =
                            base,
                        regionEndExclusive =
                            base +
                                memory.size,
                        regionFileOffset =
                            0,
                        regionPath =
                            "[heap]",
                    )
                }
        val reader =
            CountingArrayMemoryReader(
                base = base,
                bytes = memory,
            )
        val snapshot =
            RuntimeValueScanSnapshot(
                valueType =
                    RuntimeValueType.INT32,
                hits = hits,
                scannedBytes =
                    memory.size.toLong(),
                scannedRegions = 1,
                truncatedByHitLimit =
                    false,
                truncatedByByteLimit =
                    false,
            )

        val refreshed =
            RuntimeValueScanner.refresh(
                previous = snapshot,
                reader = reader,
                cancellation =
                    AtomicCancellationSignal(),
            )

        assertEquals(100, refreshed.hits.size)
        assertTrue(
            reader.readCalls <= 2,
        )
    }

    private class CountingArrayMemoryReader(
        private val base: Long,
        private val bytes: ByteArray,
    ) : RuntimeMemoryReader {
        var readCalls: Int = 0

        override fun read(
            address: Long,
            size: Int,
            cancellation:
                CancellationSignal,
        ): ByteArray? {
            readCalls++
            val offset =
                (address - base)
                    .toInt()
            if (
                offset < 0 ||
                size < 0 ||
                offset + size >
                bytes.size
            ) {
                return null
            }
            return bytes.copyOfRange(
                offset,
                offset + size,
            )
        }
    }


    @Test
    fun byteAlignmentFindsUnalignedValue() {
        val base = 0x8100L
        val memory =
            ByteArray(32)
        putInt(
            memory,
            1,
            0x12345678,
        )
        val reader =
            ArrayMemoryReader(
                base = base,
                bytes = memory,
            )

        val natural =
            RuntimeValueScanner.scanExact(
                ranges =
                    listOf(
                        region(
                            start = base,
                            size = memory.size,
                        ),
                    ),
                reader = reader,
                valueType =
                    RuntimeValueType.INT32,
                query =
                    0x12345678
                        .toString(),
                cancellation =
                    AtomicCancellationSignal(),
                alignment =
                    RuntimeScanAlignment.NATURAL,
                maxHits = 16,
                maxScanBytes = 1024,
                chunkBytes = 16,
            )
        val byteAligned =
            RuntimeValueScanner.scanExact(
                ranges =
                    listOf(
                        region(
                            start = base,
                            size = memory.size,
                        ),
                    ),
                reader = reader,
                valueType =
                    RuntimeValueType.INT32,
                query =
                    0x12345678
                        .toString(),
                cancellation =
                    AtomicCancellationSignal(),
                alignment =
                    RuntimeScanAlignment.BYTE,
                maxHits = 16,
                maxScanBytes = 1024,
                chunkBytes = 16,
            )

        assertTrue(
            natural.hits.none {
                it.address == base + 1
            },
        )
        assertTrue(
            byteAligned.hits.any {
                it.address == base + 1
            },
        )
    }

}
