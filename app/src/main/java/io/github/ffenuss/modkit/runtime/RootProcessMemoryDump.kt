package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class RootProcessMemoryDumpResult(
    val packageName: String,
    val pid: Int,
    val file: File,
    val mappedRegions: Int,
    val dumpedRegions: Int,
    val skippedRegions: Int,
    val dumpedBytes: Long,
    val truncatedByByteLimit: Boolean,
    val detectedArtifacts: Int = 0,
)

data class RootProcessMemoryDumpProgress(
    val phase: String,
    val plannedBytes: Long,
    val processedBytes: Long,
    val dumpedBytes: Long,
    val totalSlices: Int,
    val processedSlices: Int,
    val totalRegions: Int,
    val processedRegions: Int,
    val elapsedMs: Long,
) {
    val fraction: Float
        get() =
            if (plannedBytes <= 0L) {
                0f
            } else {
                (
                    processedBytes
                        .toDouble() /
                        plannedBytes
                            .toDouble()
                    )
                    .coerceIn(
                        0.0,
                        1.0,
                    )
                    .toFloat()
            }
}

private data class DumpSlice(
    val region: ProcMapRegion,
    val address: Long,
    val length: Int,
) {
    val endExclusive: Long
        get() =
            address +
                length.toLong()

    val fastAligned: Boolean
        get() =
            address % FAST_DD_BLOCK_BYTES ==
                0L &&
                length %
                    FAST_DD_BLOCK_BYTES ==
                0
}

private const val FAST_DD_BLOCK_BYTES =
    4 * 1024
private const val FAST_BATCH_BYTES =
    8 * 1024 * 1024
private const val MAX_SLICES_PER_BATCH =
    48
private const val DUMP_COMMAND_TIMEOUT_MS =
    30_000L

/**
 * Creates a bounded ZIP snapshot from the live, root-readable process image.
 *
 * Important performance property: normal /proc/<pid>/maps ranges are page
 * aligned, so dump reads use 4 KiB dd blocks and batch multiple mappings into
 * one privileged command. The old bs=1 path caused millions of tiny reads and
 * could make a 256 MiB snapshot appear hung for many minutes.
 */
object RootProcessMemoryDumpCoordinator {
    const val QUICK_MAX_DUMP_BYTES =
        256L * 1024L * 1024L
    private const val STORAGE_RESERVE_BYTES =
        128L * 1024L * 1024L

    fun dump(
        packageName: String,
        outputFile: File,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(
                timeoutMs =
                    DUMP_COMMAND_TIMEOUT_MS,
            ),
        maxDumpBytes: Long? = null,
        includeSystemMappings: Boolean = true,
        progress:
            (
                RootProcessMemoryDumpProgress,
            ) -> Unit = { },
    ): RootProcessMemoryDumpResult {
        require(
            maxDumpBytes == null ||
                maxDumpBytes > 0L
        ) {
            "Некорректный лимит runtime dump."
        }

        val startedAt =
            System.currentTimeMillis()
        progress(
            RootProcessMemoryDumpProgress(
                phase =
                    "Проверка процесса и maps",
                plannedBytes = 0,
                processedBytes = 0,
                dumpedBytes = 0,
                totalSlices = 0,
                processedSlices = 0,
                totalRegions = 0,
                processedRegions = 0,
                elapsedMs = 0,
            ),
        )

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                )
        val regions =
            ProcMapsParser.parse(
                capture.capture.text,
            )
        val candidates =
            regions
                .asSequence()
                .filter {
                    it.readable &&
                        it.size > 0L &&
                        (
                            includeSystemMappings &&
                                !isUnsafeSpecialMapping(
                                    it,
                                ) ||
                                !includeSystemMappings &&
                                includeRegion(
                                    it,
                                    packageName,
                                )
                            )
                }
                .sortedWith(
                    compareBy<ProcMapRegion> {
                        regionPriority(
                            it,
                            packageName,
                        )
                    }.thenBy {
                        it.start
                    },
                )
                .toList()
        require(
            candidates.isNotEmpty(),
        ) {
            "В процессе нет подходящих readable runtime mappings."
        }

        val plan =
            buildPlan(
                candidates =
                    candidates,
                maxDumpBytes =
                    maxDumpBytes,
            )
        require(
            plan.slices.isNotEmpty(),
        ) {
            "Не удалось сформировать план runtime dump."
        }

        outputFile.parentFile
            ?.mkdirs()
        val parent =
            outputFile.parentFile
                ?: error(
                    "Не удалось определить каталог runtime dump.",
                )
        val usableSpace =
            parent.usableSpace
        val requiredSpace =
            plan.plannedBytes +
                STORAGE_RESERVE_BYTES
        if (
            usableSpace > 0L &&
            requiredSpace > 0L &&
            usableSpace <
                requiredSpace
        ) {
            error(
                "Недостаточно свободного места для runtime dump: нужно примерно " +
                    (plan.plannedBytes / (1024L * 1024L)) +
                    " MiB + резерв, доступно " +
                    (usableSpace / (1024L * 1024L)) +
                    " MiB. Переключите режим на быстрый 256 MiB или освободите место.",
            )
        }
        val temp =
            File(
                outputFile.parentFile,
                outputFile.name +
                    ".tmp",
            )
        temp.delete()
        outputFile.delete()

        val slowReader =
            RootProcMemRuntimeMemoryReader(
                pid = capture.pid,
                runner = runner,
            )
        var dumpedBytes = 0L
        var processedBytes = 0L
        var processedSlices = 0
        val successfulRegions =
            linkedSetOf<
                Pair<Long, Long>
            >()
        val attemptedRegions =
            linkedSetOf<
                Pair<Long, Long>
            >()
        val artifacts =
            LinkedHashMap<
                Pair<
                    RootRuntimeArtifactKind,
                    Long
                >,
                RootRuntimeArtifactCandidate
            >()
        val index =
            StringBuilder().apply {
                appendLine(
                    "sliceStart\tsliceEnd\tmapStart\tmapEnd\tpermissions\tfileOffset\tpath\tdumpedBytes\tentry",
                )
            }

        fun publish(
            phase: String,
        ) {
            progress(
                RootProcessMemoryDumpProgress(
                    phase = phase,
                    plannedBytes =
                        plan.plannedBytes,
                    processedBytes =
                        processedBytes,
                    dumpedBytes =
                        dumpedBytes,
                    totalSlices =
                        plan.slices.size,
                    processedSlices =
                        processedSlices,
                    totalRegions =
                        plan.plannedRegions,
                    processedRegions =
                        attemptedRegions.size,
                    elapsedMs =
                        System.currentTimeMillis() -
                            startedAt,
                ),
            )
        }

        try {
            ZipOutputStream(
                BufferedOutputStream(
                    FileOutputStream(
                        temp,
                    ),
                ),
            ).use {
                zip ->
                zip.setLevel(
                    Deflater.BEST_SPEED,
                )

                putText(
                    zip = zip,
                    name = "process.txt",
                    text =
                        "package=" +
                            packageName +
                            "\n" +
                            "pid=" +
                            capture.pid +
                            "\n" +
                            "capturedAtEpochMs=" +
                            capture.capture
                                .capturedAtEpochMs +
                            "\n" +
                            "mapsSha256=" +
                            capture.capture
                                .sha256 +
                            "\n" +
                            "maxDumpBytes=" +
                            (
                                maxDumpBytes
                                    ?.toString()
                                    ?: "FULL"
                                ) +
                            "\n" +
                            "plannedBytes=" +
                            plan.plannedBytes +
                            "\n" +
                            "includeSystemMappings=" +
                            includeSystemMappings +
                            "\n" +
                            "fastDdBlockBytes=" +
                            FAST_DD_BLOCK_BYTES +
                            "\n" +
                            "fastBatchBytes=" +
                            FAST_BATCH_BYTES +
                            "\n",
                )
                putText(
                    zip = zip,
                    name = "maps.txt",
                    text =
                        capture.capture.text,
                )

                publish(
                    "Чтение runtime memory",
                )

                var cursor = 0
                while (
                    cursor <
                    plan.slices.size
                ) {
                    checkCancelled(
                        cancellation,
                    )
                    val first =
                        plan.slices[cursor]

                    if (
                        first.fastAligned
                    ) {
                        val batch =
                            mutableListOf<
                                DumpSlice
                            >()
                        var batchBytes = 0
                        var scan = cursor
                        while (
                            scan <
                            plan.slices.size &&
                            batch.size <
                            MAX_SLICES_PER_BATCH
                        ) {
                            val slice =
                                plan.slices[scan]
                            if (
                                !slice.fastAligned ||
                                batchBytes +
                                    slice.length >
                                    FAST_BATCH_BYTES
                            ) {
                                break
                            }
                            batch += slice
                            batchBytes +=
                                slice.length
                            scan++
                        }

                        if (
                            batch.isNotEmpty()
                        ) {
                            val bytes =
                                readFastBatch(
                                    pid =
                                        capture.pid,
                                    slices =
                                        batch,
                                    runner =
                                        runner,
                                    cancellation =
                                        cancellation,
                                )
                            if (
                                bytes != null
                            ) {
                                var offset = 0
                                batch.forEach {
                                    slice ->
                                    attemptedRegions +=
                                        slice.region
                                            .start to
                                            slice.region
                                                .endExclusive
                                    writeSlice(
                                        zip = zip,
                                        index =
                                            index,
                                        slice =
                                            slice,
                                        bytes =
                                            bytes,
                                        offset =
                                            offset,
                                        artifacts =
                                            artifacts,
                                    )
                                    successfulRegions +=
                                        slice.region
                                            .start to
                                            slice.region
                                                .endExclusive
                                    offset +=
                                        slice.length
                                    dumpedBytes +=
                                        slice.length
                                    processedBytes +=
                                        slice.length
                                    processedSlices++
                                }
                                cursor +=
                                    batch.size
                                publish(
                                    "Чтение runtime memory",
                                )
                                continue
                            }

                            // Batch-level failure can happen when one mapping
                            // changes while the game is running. Retry every
                            // planned slice independently instead of throwing
                            // away the whole dump.
                            batch.forEach {
                                slice ->
                                checkCancelled(
                                    cancellation,
                                )
                                attemptedRegions +=
                                    slice.region
                                        .start to
                                        slice.region
                                            .endExclusive
                                val individual =
                                    readFastSlice(
                                        pid =
                                            capture.pid,
                                        slice =
                                            slice,
                                        runner =
                                            runner,
                                        cancellation =
                                            cancellation,
                                    )
                                if (
                                    individual !=
                                    null
                                ) {
                                    writeSlice(
                                        zip = zip,
                                        index =
                                            index,
                                        slice =
                                            slice,
                                        bytes =
                                            individual,
                                        offset = 0,
                                        artifacts =
                                            artifacts,
                                    )
                                    successfulRegions +=
                                        slice.region
                                            .start to
                                            slice.region
                                                .endExclusive
                                    dumpedBytes +=
                                        individual.size
                                }
                                processedBytes +=
                                    slice.length
                                processedSlices++
                                publish(
                                    "Чтение runtime memory",
                                )
                            }
                            cursor +=
                                batch.size
                            continue
                        }
                    }

                    attemptedRegions +=
                        first.region.start to
                            first.region
                                .endExclusive
                    val bytes =
                        readSlowSlice(
                            slice = first,
                            reader =
                                slowReader,
                            cancellation =
                                cancellation,
                        )
                    if (
                        bytes != null
                    ) {
                        writeSlice(
                            zip = zip,
                            index =
                                index,
                            slice = first,
                            bytes = bytes,
                            offset = 0,
                            artifacts =
                                artifacts,
                        )
                        successfulRegions +=
                            first.region
                                .start to
                                first.region
                                    .endExclusive
                        dumpedBytes +=
                            bytes.size
                    }
                    processedBytes +=
                        first.length
                    processedSlices++
                    cursor++
                    publish(
                        "Чтение runtime memory",
                    )
                }

                publish(
                    "Запись индекса",
                )
                putText(
                    zip = zip,
                    name =
                        "memory/index.tsv",
                    text =
                        index.toString(),
                )
                putText(
                    zip = zip,
                    name =
                        "runtime-artifacts/index.tsv",
                    text =
                        artifactIndex(
                            artifacts.values,
                        ),
                )
            }

            checkCancelled(
                cancellation,
            )

            // Re-prove the main process after the potentially long read. A
            // replaced/reused PID invalidates the snapshot.
            val after =
                RootRuntimeCaptureCoordinator
                    .captureMaps(
                        packageName =
                            packageName,
                        cancellation =
                            cancellation,
                        runner = runner,
                    )
            require(
                after.pid ==
                    capture.pid,
            ) {
                "PID процесса изменился во время runtime dump."
            }

            require(
                temp.isFile &&
                    temp.length() > 0L,
            ) {
                "Runtime dump не был создан."
            }
            check(
                temp.renameTo(
                    outputFile,
                ),
            ) {
                "Не удалось завершить runtime dump."
            }

            publish(
                "Готово",
            )
            return RootProcessMemoryDumpResult(
                packageName =
                    packageName,
                pid =
                    capture.pid,
                file =
                    outputFile,
                mappedRegions =
                    candidates.size,
                dumpedRegions =
                    successfulRegions
                        .size,
                skippedRegions =
                    attemptedRegions
                        .count {
                            it !in
                                successfulRegions
                        },
                dumpedBytes =
                    dumpedBytes,
                truncatedByByteLimit =
                    plan.truncated,
                detectedArtifacts =
                    artifacts.size,
            )
        } catch (failure: Throwable) {
            temp.delete()
            outputFile.delete()
            throw failure
        }
    }

    private data class DumpPlan(
        val slices: List<DumpSlice>,
        val plannedBytes: Long,
        val plannedRegions: Int,
        val truncated: Boolean,
    )

    private fun buildPlan(
        candidates:
            List<ProcMapRegion>,
        maxDumpBytes: Long?,
    ): DumpPlan {
        val slices =
            ArrayList<DumpSlice>()
        val plannedRegions =
            linkedSetOf<
                Pair<Long, Long>
            >()
        var remainingBudget =
            maxDumpBytes
                ?: Long.MAX_VALUE
        var plannedBytes = 0L
        var truncated = false

        for (
            region in candidates
        ) {
            if (
                remainingBudget <= 0L
            ) {
                truncated = true
                break
            }
            var regionRemaining =
                minOf(
                    region.size,
                    remainingBudget,
                )
            if (
                regionRemaining <
                region.size
            ) {
                truncated = true
            }
            var address =
                region.start
            while (
                regionRemaining > 0L
            ) {
                val chunk =
                    minOf(
                        regionRemaining,
                        FAST_BATCH_BYTES
                            .toLong(),
                    ).toInt()
                if (chunk <= 0) {
                    break
                }
                slices +=
                    DumpSlice(
                        region = region,
                        address =
                            address,
                        length =
                            chunk,
                    )
                plannedRegions +=
                    region.start to
                        region.endExclusive
                address +=
                    chunk
                regionRemaining -=
                    chunk
                remainingBudget -=
                    chunk
                plannedBytes +=
                    chunk
                if (
                    remainingBudget <=
                    0L
                ) {
                    if (
                        address <
                        region.endExclusive
                    ) {
                        truncated =
                            true
                    }
                    break
                }
            }
        }

        if (
            plannedRegions.size <
            candidates.size
        ) {
            truncated = true
        }

        return DumpPlan(
            slices = slices,
            plannedBytes =
                plannedBytes,
            plannedRegions =
                plannedRegions.size,
            truncated = truncated,
        )
    }

    private fun readFastBatch(
        pid: Int,
        slices: List<DumpSlice>,
        runner: RootCommandRunner,
        cancellation:
            CancellationSignal,
    ): ByteArray? {
        if (
            slices.isEmpty() ||
            slices.any {
                !it.fastAligned
            }
        ) {
            return null
        }
        val expected =
            slices.sumOf {
                it.length
            }
        if (
            expected !in
            1..FAST_BATCH_BYTES
        ) {
            return null
        }

        val command =
            slices.joinToString(
                separator = "; ",
            ) {
                slice ->
                fastDdCommand(
                    pid = pid,
                    address =
                        slice.address,
                    length =
                        slice.length,
                ) +
                    " || exit 91"
            }
        val result =
            try {
                runner.run(
                    command = command,
                    maxOutputBytes =
                        expected,
                    cancellation =
                        cancellation,
                )
            } catch (
                failure:
                    AnalysisCancelledException,
            ) {
                throw failure
            } catch (_: Throwable) {
                return null
            }
        if (
            result.exitCode != 0 ||
            result.truncated ||
            result.output.size !=
                expected
        ) {
            return null
        }
        return result.output
    }

    private fun readFastSlice(
        pid: Int,
        slice: DumpSlice,
        runner: RootCommandRunner,
        cancellation:
            CancellationSignal,
    ): ByteArray? {
        if (!slice.fastAligned) {
            return null
        }
        val result =
            try {
                runner.run(
                    command =
                        fastDdCommand(
                            pid = pid,
                            address =
                                slice.address,
                            length =
                                slice.length,
                        ),
                    maxOutputBytes =
                        slice.length,
                    cancellation =
                        cancellation,
                )
            } catch (
                failure:
                    AnalysisCancelledException,
            ) {
                throw failure
            } catch (_: Throwable) {
                return null
            }
        return result.output
            .takeIf {
                result.exitCode == 0 &&
                    !result.truncated &&
                    it.size ==
                        slice.length
            }
    }

    private fun fastDdCommand(
        pid: Int,
        address: Long,
        length: Int,
    ): String {
        require(
            address %
                FAST_DD_BLOCK_BYTES ==
                0L &&
                length %
                    FAST_DD_BLOCK_BYTES ==
                    0
        )
        return "dd if=/proc/" +
            pid +
            "/mem bs=" +
            FAST_DD_BLOCK_BYTES +
            " skip=" +
            (
                address /
                    FAST_DD_BLOCK_BYTES
                ) +
            " count=" +
            (
                length /
                    FAST_DD_BLOCK_BYTES
                ) +
            " status=none 2>/dev/null"
    }

    private fun readSlowSlice(
        slice: DumpSlice,
        reader: RuntimeMemoryReader,
        cancellation:
            CancellationSignal,
    ): ByteArray? {
        val output =
            ByteArrayOutputStream(
                slice.length,
            )
        var address =
            slice.address
        var remaining =
            slice.length
        while (
            remaining > 0
        ) {
            val request =
                minOf(
                    remaining,
                    ProcMemRuntimeMemoryReader
                        .MAX_READ_BYTES,
                )
            val bytes =
                reader.read(
                    address =
                        address,
                    size = request,
                    cancellation =
                        cancellation,
                ) ?: return null
            if (
                bytes.size !=
                request
            ) {
                return null
            }
            output.write(bytes)
            remaining -=
                bytes.size
            address +=
                bytes.size
        }
        return output.toByteArray()
    }

    private fun writeSlice(
        zip: ZipOutputStream,
        index: StringBuilder,
        slice: DumpSlice,
        bytes: ByteArray,
        offset: Int,
        artifacts:
            MutableMap<
                Pair<
                    RootRuntimeArtifactKind,
                    Long
                >,
                RootRuntimeArtifactCandidate
            >,
    ) {
        require(
            offset >= 0 &&
                offset +
                    slice.length <=
                bytes.size
        )
        val entryName =
            "memory/" +
                slice.address
                    .toString(16) +
                "-" +
                slice.endExclusive
                    .toString(16) +
                "-" +
                sanitize(
                    slice.region.path
                        ?: "anonymous",
                ) +
                ".bin"
        zip.putNextEntry(
            ZipEntry(
                entryName,
            ),
        )
        zip.write(
            bytes,
            offset,
            slice.length,
        )
        zip.closeEntry()

        RootRuntimeArtifactDetector
            .detect(
                region =
                    slice.region,
                sliceAddress =
                    slice.address,
                bytes = bytes,
                offset = offset,
                length =
                    slice.length,
            )
            .forEach {
                candidate ->
                artifacts.putIfAbsent(
                    candidate.kind to
                        candidate.address,
                    candidate,
                )
            }

        index.append(
            "0x",
        ).append(
            slice.address
                .toString(16),
        ).append(
            '\t',
        ).append(
            "0x",
        ).append(
            slice.endExclusive
                .toString(16),
        ).append(
            '\t',
        ).append(
            "0x",
        ).append(
            slice.region.start
                .toString(16),
        ).append(
            '\t',
        ).append(
            "0x",
        ).append(
            slice.region
                .endExclusive
                .toString(16),
        ).append(
            '\t',
        ).append(
            slice.region
                .permissions,
        ).append(
            '\t',
        ).append(
            "0x",
        ).append(
            (
                slice.region.fileOffset +
                    (
                        slice.address -
                            slice.region.start
                        )
                ).toString(16),
        ).append(
            '\t',
        ).append(
            slice.region.path
                ?: "",
        ).append(
            '\t',
        ).append(
            slice.length,
        ).append(
            '\t',
        ).append(
            entryName,
        ).appendLine()
    }

    private fun artifactIndex(
        artifacts:
            Collection<
                RootRuntimeArtifactCandidate
            >,
    ): String =
        buildString {
            appendLine(
                "kind\taddress\tregionStart\tregionEnd\testimatedSize\tpath\tevidence",
            )
            artifacts
                .sortedWith(
                    compareBy<
                        RootRuntimeArtifactCandidate
                    > {
                        it.address
                    }.thenBy {
                        it.kind.name
                    },
                )
                .forEach {
                    artifact ->
                    append(
                        artifact.kind.name,
                    ).append(
                        '\t',
                    ).append(
                        "0x" +
                            artifact.address
                                .toString(16),
                    ).append(
                        '\t',
                    ).append(
                        "0x" +
                            artifact.regionStart
                                .toString(16),
                    ).append(
                        '\t',
                    ).append(
                        "0x" +
                            artifact.regionEndExclusive
                                .toString(16),
                    ).append(
                        '\t',
                    ).append(
                        artifact.estimatedSize
                            ?.toString()
                            .orEmpty(),
                    ).append(
                        '\t',
                    ).append(
                        artifact.regionPath
                            .orEmpty()
                            .replace(
                                '\t',
                                ' ',
                            )
                            .replace(
                                '\n',
                                ' ',
                            ),
                    ).append(
                        '\t',
                    ).append(
                        artifact.evidence
                            .replace(
                                '\t',
                                ' ',
                            )
                            .replace(
                                '\n',
                                ' ',
                            ),
                    ).appendLine()
                }
        }

    private fun isUnsafeSpecialMapping(
        region: ProcMapRegion,
    ): Boolean {
        val path =
            region.path.orEmpty()
        return path == "[vvar]" ||
            path == "[vdso]" ||
            path == "[vsyscall]"
    }

    private fun includeRegion(
        region: ProcMapRegion,
        packageName: String,
    ): Boolean {
        val path =
            region.path.orEmpty()
        if (
            isUnsafeSpecialMapping(
                region,
            )
        ) {
            return false
        }
        if (
            path.startsWith(
                "/system/",
            ) ||
            path.startsWith(
                "/apex/",
            ) ||
            path.startsWith(
                "/vendor/",
            ) ||
            path.startsWith(
                "/product/",
            ) ||
            path.startsWith(
                "/system_ext/",
            )
        ) {
            return false
        }
        return path.isBlank() ||
            path.startsWith("[") ||
            path.contains(
                packageName,
                ignoreCase = true,
            ) ||
            path.startsWith(
                "/data/",
            ) ||
            path.startsWith(
                "/mnt/",
            ) ||
            path.startsWith(
                "/memfd:",
            )
    }

    private fun regionPriority(
        region: ProcMapRegion,
        packageName: String,
    ): Int {
        val path =
            region.path.orEmpty()
        return when {
            path.contains(
                "libil2cpp.so",
                ignoreCase = true,
            ) ->
                0
            path.contains(
                packageName,
                ignoreCase = true,
            ) ->
                0
            path == "[heap]" ||
                path.startsWith(
                    "[anon",
                ) ||
                path.isBlank() ->
                1
            path.startsWith(
                "/data/",
            ) ->
                2
            else ->
                3
        }
    }

    private fun putText(
        zip: ZipOutputStream,
        name: String,
        text: String,
    ) {
        zip.putNextEntry(
            ZipEntry(name),
        )
        zip.write(
            text.toByteArray(
                Charsets.UTF_8,
            ),
        )
        zip.closeEntry()
    }

    private fun sanitize(
        value: String,
    ): String =
        value
            .substringAfterLast('/')
            .ifBlank {
                "mapping"
            }
            .replace(
                Regex(
                    "[^A-Za-z0-9._-]",
                ),
                "_",
            )
            .take(80)

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
