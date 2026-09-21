package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.BufferedOutputStream
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
)

/**
 * Creates a bounded ZIP snapshot from the live, root-readable process image.
 *
 * The snapshot is deliberately runtime-first: maps are captured from the
 * selected running process and memory bytes come from /proc/<pid>/mem. This is
 * useful for unpacked/decrypted runtime state without pretending that it is the
 * original source code.
 */
object RootProcessMemoryDumpCoordinator {
    const val DEFAULT_MAX_DUMP_BYTES =
        256L * 1024L * 1024L

    fun dump(
        packageName: String,
        outputFile: File,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
        maxDumpBytes: Long =
            DEFAULT_MAX_DUMP_BYTES,
    ): RootProcessMemoryDumpResult {
        require(
            maxDumpBytes in
                1L..
                    (2L * 1024L * 1024L * 1024L),
        ) {
            "Некорректный лимит runtime dump."
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
                        includeRegion(
                            it,
                            packageName,
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

        outputFile.parentFile
            ?.mkdirs()
        val temp =
            File(
                outputFile.parentFile,
                outputFile.name +
                    ".tmp",
            )
        temp.delete()
        outputFile.delete()

        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = capture.pid,
                runner = runner,
            )
        var dumpedBytes = 0L
        var dumpedRegions = 0
        var skippedRegions = 0
        var truncated = false
        val index =
            StringBuilder().apply {
                appendLine(
                    "start\tend\tpermissions\tfileOffset\tpath\tdumpedBytes\tentry",
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
                            capture.capture.sha256 +
                            "\n" +
                            "maxDumpBytes=" +
                            maxDumpBytes +
                            "\n",
                )
                putText(
                    zip = zip,
                    name = "maps.txt",
                    text =
                        capture.capture.text,
                )

                regionLoop@ for (
                    region in candidates
                ) {
                    checkCancelled(
                        cancellation,
                    )
                    val remainingBudget =
                        maxDumpBytes -
                            dumpedBytes
                    if (
                        remainingBudget <= 0L
                    ) {
                        truncated = true
                        break
                    }

                    val maxForRegion =
                        minOf(
                            region.size,
                            remainingBudget,
                        )
                    val entryName =
                        "memory/" +
                            region.start
                                .toString(16) +
                            "-" +
                            region.endExclusive
                                .toString(16) +
                            "-" +
                            sanitize(
                                region.path
                                    ?: "anonymous",
                            ) +
                            ".bin"

                    var address =
                        region.start
                    var written =
                        0L
                    var entryOpened =
                        false
                    while (
                        written <
                        maxForRegion
                    ) {
                        checkCancelled(
                            cancellation,
                        )
                        val request =
                            minOf(
                                maxForRegion -
                                    written,
                                ProcMemRuntimeMemoryReader
                                    .MAX_READ_BYTES
                                    .toLong(),
                            ).toInt()
                        if (request <= 0) {
                            break
                        }
                        val bytes =
                            reader.read(
                                address =
                                    address,
                                size =
                                    request,
                                cancellation =
                                    cancellation,
                            )
                        if (
                            bytes == null ||
                            bytes.isEmpty()
                        ) {
                            break
                        }
                        if (!entryOpened) {
                            zip.putNextEntry(
                                ZipEntry(
                                    entryName,
                                ),
                            )
                            entryOpened = true
                        }
                        zip.write(bytes)
                        written +=
                            bytes.size
                        dumpedBytes +=
                            bytes.size
                        address +=
                            bytes.size

                        if (
                            dumpedBytes >=
                            maxDumpBytes
                        ) {
                            truncated = true
                            break
                        }
                    }

                    if (entryOpened) {
                        zip.closeEntry()
                        dumpedRegions++
                    } else {
                        skippedRegions++
                    }

                    index.append(
                        "0x",
                    ).append(
                        region.start
                            .toString(16),
                    ).append(
                        '\t',
                    ).append(
                        "0x",
                    ).append(
                        region.endExclusive
                            .toString(16),
                    ).append(
                        '\t',
                    ).append(
                        region.permissions,
                    ).append(
                        '\t',
                    ).append(
                        "0x",
                    ).append(
                        region.fileOffset
                            .toString(16),
                    ).append(
                        '\t',
                    ).append(
                        region.path
                            ?: "",
                    ).append(
                        '\t',
                    ).append(
                        written,
                    ).append(
                        '\t',
                    ).append(
                        if (
                            entryOpened
                        ) {
                            entryName
                        } else {
                            ""
                        },
                    ).appendLine()

                    if (
                        truncated
                    ) {
                        break@regionLoop
                    }
                }

                putText(
                    zip = zip,
                    name =
                        "memory/index.tsv",
                    text =
                        index.toString(),
                )
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
                    dumpedRegions,
                skippedRegions =
                    skippedRegions,
                dumpedBytes =
                    dumpedBytes,
                truncatedByByteLimit =
                    truncated,
            )
        } catch (failure: Throwable) {
            temp.delete()
            outputFile.delete()
            throw failure
        }
    }

    private fun includeRegion(
        region: ProcMapRegion,
        packageName: String,
    ): Boolean {
        val path =
            region.path.orEmpty()
        if (
            path == "[vvar]" ||
            path == "[vdso]"
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
