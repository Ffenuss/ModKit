package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import kotlin.math.abs
import kotlin.math.min

object RootRuntimeAdvancedValueScanCoordinator {
    fun scanRange(
        packageName: String,
        expectedPid: Int,
        valueType: RuntimeValueType,
        minText: String,
        maxText: String,
        cancellation: CancellationSignal,
        alignment: RuntimeScanAlignment =
            RuntimeScanAlignment.NATURAL,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxScanBytes: Long? = null,
    ): RootRuntimeValueScanResult {
        val predicate =
            rangePredicate(
                valueType =
                    valueType,
                minText =
                    minText,
                maxText =
                    maxText,
            )
        return scan(
            packageName =
                packageName,
            expectedPid =
                expectedPid,
            valueType =
                valueType,
            cancellation =
                cancellation,
            alignment =
                alignment,
            runner = runner,
            maxScanBytes =
                maxScanBytes,
            predicate =
                predicate,
        )
    }

    fun scanFuzzy(
        packageName: String,
        expectedPid: Int,
        valueType: RuntimeValueType,
        queryText: String,
        toleranceText: String,
        cancellation: CancellationSignal,
        alignment: RuntimeScanAlignment =
            RuntimeScanAlignment.NATURAL,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxScanBytes: Long? = null,
    ): RootRuntimeValueScanResult {
        val target =
            queryText.trim()
                .toDoubleOrNull()
                ?: error(
                    "Некорректное значение fuzzy-поиска.",
                )
        val tolerance =
            toleranceText.trim()
                .toDoubleOrNull()
                ?: error(
                    "Некорректный допуск fuzzy-поиска.",
                )
        require(
            target.isFinite() &&
                tolerance.isFinite() &&
                tolerance >= 0.0
        ) {
            "Fuzzy-поиск требует конечное значение и неотрицательный допуск."
        }

        return scan(
            packageName =
                packageName,
            expectedPid =
                expectedPid,
            valueType =
                valueType,
            cancellation =
                cancellation,
            alignment =
                alignment,
            runner = runner,
            maxScanBytes =
                maxScanBytes,
        ) {
            bits ->
            val value =
                numericDouble(
                    valueType,
                    bits,
                )
            value.isFinite() &&
                abs(
                    value -
                        target,
                ) <=
                tolerance
        }
    }

    fun scanGroup(
        packageName: String,
        expectedPid: Int,
        valueType: RuntimeValueType,
        queryTexts: List<String>,
        cancellation: CancellationSignal,
        alignment: RuntimeScanAlignment =
            RuntimeScanAlignment.NATURAL,
        maxGapBytes: Int = 32,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxScanBytes: Long? = null,
    ): RootRuntimeValueScanResult {
        require(
            queryTexts.size in 2..8
        ) {
            "Группа должна содержать от 2 до 8 значений."
        }
        require(
            maxGapBytes in
                valueType.byteWidth..256
        ) {
            "Некорректный максимальный интервал группы."
        }

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid =
                        expectedPid,
                )
        val ranges =
            RootRuntimeValueScanCoordinator
                .candidateRanges(
                    ProcMapsParser.parse(
                        capture.capture.text,
                    ),
                )
        require(
            ranges.isNotEmpty(),
        ) {
            "В процессе нет диапазонов для group scan."
        }

        val first =
            RuntimeValueScanner
                .scanExact(
                    ranges = ranges,
                    reader =
                        RootProcMemRuntimeMemoryReader(
                            pid =
                                expectedPid,
                            runner = runner,
                        ),
                    valueType =
                        valueType,
                    query =
                        queryTexts.first(),
                    cancellation =
                        cancellation,
                    alignment =
                        alignment,
                    maxHits = 50_000,
                    maxScanBytes =
                        maxScanBytes
                            ?: totalRangeBytes(
                                ranges,
                            ),
                )
        if (first.hits.isEmpty()) {
            return RootRuntimeValueScanResult(
                packageName =
                    packageName,
                pid = expectedPid,
                capturedAtEpochMs =
                    System.currentTimeMillis(),
                snapshot = first,
            )
        }

        val expectedBits =
            queryTexts
                .drop(1)
                .map {
                    valueType
                        .parseQuery(it)
                }
        val reader =
            RootProcMemRuntimeMemoryReader(
                pid =
                    expectedPid,
                runner = runner,
            )
        val retained =
            ArrayList<
                RuntimeValueHit
            >()
        val step =
            alignment.step(
                valueType,
            )
        val windowBytes =
            (
                maxGapBytes *
                    expectedBits.size +
                    valueType.byteWidth
                ).coerceAtMost(
                2048,
            )

        for (hit in first.hits) {
            checkCancelled(
                cancellation,
            )
            val maxReadable =
                (
                    hit.regionEndExclusive -
                        hit.address
                    ).coerceAtMost(
                    windowBytes.toLong(),
                ).toInt()
            if (
                maxReadable <
                valueType.byteWidth
            ) {
                continue
            }
            val bytes =
                reader.read(
                    address =
                        hit.address,
                    size = maxReadable,
                    cancellation =
                        cancellation,
                ) ?: continue

            var previousOffset = 0
            var matched = true
            for (
                expected in
                    expectedBits
            ) {
                val start =
                    previousOffset +
                        valueType.byteWidth
                val end =
                    min(
                        bytes.size -
                            valueType.byteWidth,
                        previousOffset +
                            maxGapBytes,
                    )
                var foundOffset = -1
                var offset = start
                while (
                    offset <= end
                ) {
                    if (
                        valueType
                            .readBits(
                                bytes,
                                offset,
                            ) ==
                        expected
                    ) {
                        foundOffset =
                            offset
                        break
                    }
                    offset +=
                        step
                }
                if (
                    foundOffset < 0
                ) {
                    matched = false
                    break
                }
                previousOffset =
                    foundOffset
            }
            if (matched) {
                retained += hit
                if (
                    retained.size >=
                    10_000
                ) {
                    break
                }
            }
        }

        return RootRuntimeValueScanResult(
            packageName =
                packageName,
            pid = expectedPid,
            capturedAtEpochMs =
                System.currentTimeMillis(),
            snapshot =
                first.copy(
                    hits = retained,
                    truncatedByHitLimit =
                        retained.size >=
                            10_000,
                ),
        )
    }

    private fun scan(
        packageName: String,
        expectedPid: Int,
        valueType: RuntimeValueType,
        cancellation: CancellationSignal,
        alignment: RuntimeScanAlignment,
        runner: RootCommandRunner,
        maxScanBytes: Long?,
        predicate: (Long) -> Boolean,
    ): RootRuntimeValueScanResult {
        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid =
                        expectedPid,
                )
        val ranges =
            RootRuntimeValueScanCoordinator
                .candidateRanges(
                    ProcMapsParser.parse(
                        capture.capture.text,
                    ),
                )
        require(
            ranges.isNotEmpty(),
        ) {
            "В процессе нет диапазонов для расширенного поиска."
        }

        val reader =
            RootProcMemRuntimeMemoryReader(
                pid =
                    expectedPid,
                runner = runner,
            )
        val step =
            alignment.step(
                valueType,
            )
        val hits =
            ArrayList<
                RuntimeValueHit
            >()
        val byteLimit =
            maxScanBytes
                ?: totalRangeBytes(
                    ranges,
                )
        var scannedBytes = 0L
        var scannedRegions = 0
        var hitLimit = false
        var byteLimitReached =
            false

        rangeLoop@ for (
            region in ranges
        ) {
            checkCancelled(
                cancellation,
            )
            if (
                scannedBytes >=
                byteLimit
            ) {
                byteLimitReached =
                    true
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
            var touched = false
            while (
                address +
                    valueType.byteWidth <=
                region.endExclusive
            ) {
                checkCancelled(
                    cancellation,
                )
                val budget =
                    byteLimit -
                        scannedBytes
                if (budget <= 0L) {
                    byteLimitReached =
                        true
                    break@rangeLoop
                }
                val overlap =
                    if (
                        alignment ==
                        RuntimeScanAlignment.BYTE
                    ) {
                        valueType.byteWidth -
                            1
                    } else {
                        0
                    }
                val budgetWithOverlap =
                    if (
                        budget >
                        Long.MAX_VALUE -
                            overlap.toLong()
                    ) {
                        Long.MAX_VALUE
                    } else {
                        budget +
                            overlap
                    }
                val request =
                    min(
                        min(
                            region.endExclusive -
                                address,
                            RuntimeValueScanner
                                .DEFAULT_CHUNK_BYTES
                                .toLong(),
                        ),
                        budgetWithOverlap,
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
                    bytes.size <
                        valueType.byteWidth
                ) {
                    address +=
                        min(
                            4096L,
                            region.endExclusive -
                                address,
                        )
                    continue
                }
                touched = true
                val usable =
                    bytes.size
                var offset = 0
                while (
                    offset +
                        valueType.byteWidth <=
                    usable
                ) {
                    val bits =
                        valueType
                            .readBits(
                                bytes,
                                offset,
                            )
                    if (
                        predicate(bits)
                    ) {
                        hits +=
                            RuntimeValueHit(
                                address =
                                    address +
                                        offset,
                                bits = bits,
                                regionStart =
                                    region.start,
                                regionEndExclusive =
                                    region
                                        .endExclusive,
                                regionFileOffset =
                                    region.fileOffset,
                                regionPath =
                                    region.path,
                            )
                        if (
                            hits.size >=
                            50_000
                        ) {
                            hitLimit = true
                            break@rangeLoop
                        }
                    }
                    offset += step
                }
                val advance =
                    if (
                        alignment ==
                            RuntimeScanAlignment.BYTE &&
                        usable >
                            valueType.byteWidth -
                                1
                    ) {
                        usable -
                            (
                                valueType.byteWidth -
                                    1
                                )
                    } else {
                        usable
                    }
                if (advance <= 0) {
                    break
                }
                scannedBytes +=
                    advance
                address +=
                    advance
            }
            if (touched) {
                scannedRegions++
            }
        }

        return RootRuntimeValueScanResult(
            packageName =
                packageName,
            pid = expectedPid,
            capturedAtEpochMs =
                System.currentTimeMillis(),
            snapshot =
                RuntimeValueScanSnapshot(
                    valueType =
                        valueType,
                    hits = hits,
                    alignment =
                        alignment,
                    scannedBytes =
                        scannedBytes,
                    scannedRegions =
                        scannedRegions,
                    truncatedByHitLimit =
                        hitLimit,
                    truncatedByByteLimit =
                        byteLimitReached,
                ),
        )
    }

    private fun rangePredicate(
        valueType: RuntimeValueType,
        minText: String,
        maxText: String,
    ): (Long) -> Boolean =
        when (valueType) {
            RuntimeValueType.INT32 -> {
                val low =
                    minText.trim()
                        .toIntOrNull()
                        ?: error(
                            "Некорректная нижняя граница Int32.",
                        )
                val high =
                    maxText.trim()
                        .toIntOrNull()
                        ?: error(
                            "Некорректная верхняя граница Int32.",
                        )
                require(low <= high) {
                    "Нижняя граница больше верхней."
                }
                {
                    bits ->
                    bits.toInt() in
                        low..high
                }
            }

            RuntimeValueType.INT64 -> {
                val low =
                    minText.trim()
                        .toLongOrNull()
                        ?: error(
                            "Некорректная нижняя граница Int64.",
                        )
                val high =
                    maxText.trim()
                        .toLongOrNull()
                        ?: error(
                            "Некорректная верхняя граница Int64.",
                        )
                require(low <= high) {
                    "Нижняя граница больше верхней."
                }
                {
                    bits ->
                    bits in
                        low..high
                }
            }

            RuntimeValueType.FLOAT32 -> {
                val low =
                    minText.trim()
                        .toFloatOrNull()
                        ?: error(
                            "Некорректная нижняя граница Float.",
                        )
                val high =
                    maxText.trim()
                        .toFloatOrNull()
                        ?: error(
                            "Некорректная верхняя граница Float.",
                        )
                require(
                    low.isFinite() &&
                        high.isFinite() &&
                        low <= high
                ) {
                    "Некорректный Float-диапазон."
                }
                {
                    bits ->
                    val value =
                        Float.fromBits(
                            bits.toInt(),
                        )
                    value.isFinite() &&
                        value >= low &&
                        value <= high
                }
            }

            RuntimeValueType.FLOAT64 -> {
                val low =
                    minText.trim()
                        .toDoubleOrNull()
                        ?: error(
                            "Некорректная нижняя граница Double.",
                        )
                val high =
                    maxText.trim()
                        .toDoubleOrNull()
                        ?: error(
                            "Некорректная верхняя граница Double.",
                        )
                require(
                    low.isFinite() &&
                        high.isFinite() &&
                        low <= high
                ) {
                    "Некорректный Double-диапазон."
                }
                {
                    bits ->
                    val value =
                        Double.fromBits(
                            bits,
                        )
                    value.isFinite() &&
                        value >= low &&
                        value <= high
                }
            }
        }

    private fun numericDouble(
        type: RuntimeValueType,
        bits: Long,
    ): Double =
        when (type) {
            RuntimeValueType.INT32 ->
                bits.toInt()
                    .toDouble()
            RuntimeValueType.INT64 ->
                bits.toDouble()
            RuntimeValueType.FLOAT32 ->
                Float.fromBits(
                    bits.toInt(),
                ).toDouble()
            RuntimeValueType.FLOAT64 ->
                Double.fromBits(
                    bits,
                )
        }

    private fun totalRangeBytes(
        ranges: List<ProcMapRegion>,
    ): Long {
        var total = 0L
        ranges.forEach {
            region ->
            if (region.size <= 0L) {
                return@forEach
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
        return total.coerceAtLeast(
            1L,
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
