package io.github.ffenuss.modkit.runtime

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.security.MessageDigest

data class RootFastScanStats(
    val scannedBytes: Long,
    val scannedRegions: Int,
    val helperTruncated: Boolean,
)

internal data class RootFastScanRawResult(
    val hits: List<Pair<Long, Long>>,
    val stats: RootFastScanStats,
    val errors: List<String>,
)

object RootFastValueScanCoordinator {
    private const val MARKER =
        "MODKIT_ROOT_FAST_SCAN_V1"
    private const val MAX_HITS =
        50_000
    const val QUICK_MAX_BYTES =
        128L * 1024L * 1024L

    fun scanExact(
        context: Context,
        packageName: String,
        pid: Int,
        valueType: RuntimeValueType,
        query: String,
        cancellation: CancellationSignal,
        maxScanBytes: Long? =
            QUICK_MAX_BYTES,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(
                timeoutMs =
                    if (maxScanBytes == null) {
                        90_000L
                    } else {
                        30_000L
                    },
            ),
    ): RootRuntimeValueScanResult {
        require(pid > 0) {
            "PID должен быть положительным."
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
        require(capture.pid == pid) {
            "PID изменился до начала native scan."
        }
        val ranges =
            RootRuntimeValueScanCoordinator
                .candidateRanges(
                    ProcMapsParser.parse(
                        capture.capture.text,
                    ),
                )
        require(ranges.isNotEmpty()) {
            "Нет доступных writable private диапазонов."
        }

        val abi =
            detectTargetAbi(
                pid = pid,
                cancellation =
                    cancellation,
                runner = runner,
            )
        val payload =
            materializePayload(
                context = context,
                abi = abi,
                cancellation =
                    cancellation,
            )
        val remote =
            installRootPayload(
                local = payload,
                abi = abi,
                cancellation =
                    cancellation,
                runner = runner,
            )

        val bits =
            valueType
                .parseQuery(query)
        val maxBytesArg =
            maxScanBytes
                ?.coerceAtLeast(
                    valueType
                        .byteWidth
                        .toLong(),
                )
                ?: 0L
        val command =
            shellQuote(remote) +
                " " +
                pid +
                " " +
                valueType.byteWidth +
                " 0x" +
                bits.toULong()
                    .toString(16) +
                " " +
                maxBytesArg +
                " " +
                MAX_HITS

        val result =
            runner.run(
                command = command,
                maxOutputBytes =
                    8 * 1024 * 1024,
                cancellation =
                    cancellation,
            )
        val parsed =
            parseOutput(
                result.output
                    .toString(
                        Charsets.UTF_8,
                    ),
            )
        require(
            result.exitCode == 0 &&
                !result.truncated &&
                parsed.errors
                    .isEmpty()
        ) {
            parsed.errors
                .firstOrNull()
                ?: (
                    "Native fast scan завершился с кодом " +
                        result.exitCode +
                        "."
                    )
        }

        val hits =
            parsed.hits
                .asSequence()
                .mapNotNull {
                    (address, rawBits) ->
                    val region =
                        ranges
                            .firstOrNull {
                                address >=
                                    it.start &&
                                    address +
                                        valueType
                                            .byteWidth <=
                                    it.endExclusive
                            }
                            ?: return@mapNotNull null
                    RuntimeValueHit(
                        address = address,
                        bits = rawBits,
                        regionStart =
                            region.start,
                        regionEndExclusive =
                            region.endExclusive,
                        regionFileOffset =
                            region.fileOffset,
                        regionPath =
                            region.path,
                    )
                }
                .distinctBy {
                    it.address
                }
                .take(
                    MAX_HITS,
                )
                .toList()

        return RootRuntimeValueScanResult(
            packageName =
                packageName,
            pid = pid,
            capturedAtEpochMs =
                System.currentTimeMillis(),
            snapshot =
                RuntimeValueScanSnapshot(
                    valueType =
                        valueType,
                    hits = hits,
                    alignment =
                        RuntimeScanAlignment
                            .NATURAL,
                    scannedBytes =
                        parsed.stats
                            .scannedBytes,
                    scannedRegions =
                        parsed.stats
                            .scannedRegions,
                    truncatedByHitLimit =
                        hits.size >=
                            MAX_HITS,
                    truncatedByByteLimit =
                        parsed.stats
                            .helperTruncated &&
                            maxScanBytes !=
                            null,
                ),
        )
    }

    internal fun parseOutput(
        text: String,
    ): RootFastScanRawResult {
        var markerSeen =
            false
        var scannedBytes = 0L
        var scannedRegions = 0
        var truncated = false
        val hits =
            mutableListOf<
                Pair<Long, Long>
            >()
        val errors =
            mutableListOf<String>()

        text.lineSequence()
            .filter {
                it.isNotBlank()
            }
            .forEach {
                line ->
                if (line == MARKER) {
                    markerSeen = true
                    return@forEach
                }
                val fields =
                    line.split(
                        '\t',
                    )
                when (
                    fields.firstOrNull()
                ) {
                    "HIT" -> {
                        if (
                            fields.size >= 3
                        ) {
                            val address =
                                fields[1]
                                    .removePrefix(
                                        "0x",
                                    )
                                    .toLongOrNull(
                                        16,
                                    )
                            val bits =
                                fields[2]
                                    .removePrefix(
                                        "0x",
                                    )
                                    .toULongOrNull(
                                        16,
                                    )
                                    ?.toLong()
                            if (
                                address !=
                                null &&
                                bits != null
                            ) {
                                hits +=
                                    address to
                                        bits
                            }
                        }
                    }

                    "END" -> {
                        if (
                            fields.size >= 5
                        ) {
                            scannedBytes =
                                fields[2]
                                    .toLongOrNull()
                                    ?: 0L
                            scannedRegions =
                                fields[3]
                                    .toIntOrNull()
                                    ?: 0
                            truncated =
                                fields[4] ==
                                    "1"
                        }
                    }

                    "ERROR" ->
                        errors +=
                            fields
                                .drop(1)
                                .joinToString(
                                    ": ",
                                )
                }
            }

        if (!markerSeen) {
            errors +=
                "Native fast scanner marker is missing."
        }

        return RootFastScanRawResult(
            hits = hits,
            stats =
                RootFastScanStats(
                    scannedBytes =
                        scannedBytes,
                    scannedRegions =
                        scannedRegions,
                    helperTruncated =
                        truncated,
                ),
            errors = errors,
        )
    }

    private fun detectTargetAbi(
        pid: Int,
        cancellation: CancellationSignal,
        runner: RootCommandRunner,
    ): String {
        val result =
            runner.run(
                command =
                    "od -An -tu1 -j4 -N1 /proc/" +
                        pid +
                        "/exe 2>/dev/null; " +
                        "od -An -tu2 -j18 -N2 /proc/" +
                        pid +
                        "/exe 2>/dev/null",
                maxOutputBytes =
                    1024,
                cancellation =
                    cancellation,
            )
        require(
            result.exitCode == 0 &&
                !result.truncated
        ) {
            "Не удалось определить ABI процесса."
        }
        val values =
            result.output
                .toString(
                    Charsets.UTF_8,
                )
                .trim()
                .lineSequence()
                .mapNotNull {
                    it.trim()
                        .split(
                            Regex(
                                "\\s+",
                            ),
                        )
                        .firstOrNull()
                        ?.toIntOrNull()
                }
                .toList()
        require(values.size >= 2) {
            "ELF ABI процесса не распознан."
        }
        val elfClass =
            values[0]
        val machine =
            values[1]
        return when (machine) {
            183 -> "arm64-v8a"
            40 -> "armeabi-v7a"
            62 -> "x86_64"
            3 ->
                if (elfClass == 2) {
                    "x86_64"
                } else {
                    "x86"
                }

            else ->
                error(
                    "Native fast scan не поддерживает ELF machine=" +
                        machine +
                        ".",
                )
        }
    }

    private fun materializePayload(
        context: Context,
        abi: String,
        cancellation: CancellationSignal,
    ): File {
        val bytes =
            context.assets
                .open(
                    "modkit-root-fast-scan/" +
                        abi +
                        "/modkit_root_fast_value_scan",
                )
                .use {
                    input ->
                    val out =
                        java.io
                            .ByteArrayOutputStream()
                    val buffer =
                        ByteArray(
                            64 * 1024,
                        )
                    while (true) {
                        if (
                            cancellation
                                .isCancelled()
                        ) {
                            throw AnalysisCancelledException()
                        }
                        val read =
                            input.read(
                                buffer,
                            )
                        if (read < 0) {
                            break
                        }
                        out.write(
                            buffer,
                            0,
                            read,
                        )
                    }
                    out.toByteArray()
                }
        require(
            bytes.size in
                1..4 * 1024 * 1024
        ) {
            "Native scanner asset has invalid size."
        }
        require(
            bytes.size >= 4 &&
                bytes[0] ==
                0x7f.toByte() &&
                bytes[1] ==
                0x45.toByte() &&
                bytes[2] ==
                0x4c.toByte() &&
                bytes[3] ==
                0x46.toByte()
        ) {
            "Native scanner asset is not ELF."
        }

        val sha =
            sha256(bytes)
        val root =
            File(
                context.noBackupFilesDir,
                "root-fast-scan/" +
                    abi +
                    "/" +
                    sha.take(16),
            ).apply {
                mkdirs()
            }
        val output =
            File(
                root,
                "modkit_root_fast_value_scan",
            )
        if (
            !output.isFile ||
            output.length() !=
            bytes.size.toLong()
        ) {
            val temp =
                File(
                    root,
                    output.name +
                        ".tmp",
                )
            temp.writeBytes(
                bytes,
            )
            if (output.exists()) {
                output.delete()
            }
            check(
                temp.renameTo(
                    output,
                ),
            ) {
                temp.delete()
                "Не удалось сохранить native scanner."
            }
        }
        return output
    }

    private fun installRootPayload(
        local: File,
        abi: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner,
    ): String {
        val sha =
            sha256(
                local.readBytes(),
            )
        val remote =
            "/data/local/tmp/modkit-fast-scan-" +
                abi.replace(
                    Regex(
                        "[^A-Za-z0-9_-]",
                    ),
                    "_",
                ) +
                "-" +
                android.os.Process
                    .myUid() +
                "-" +
                sha.take(16)
        val command =
            "if [ ! -x " +
                shellQuote(remote) +
                " ]; then cp " +
                shellQuote(
                    local.absolutePath,
                ) +
                " " +
                shellQuote(
                    remote +
                        ".tmp",
                ) +
                " && chmod 700 " +
                shellQuote(
                    remote +
                        ".tmp",
                ) +
                " && mv -f " +
                shellQuote(
                    remote +
                        ".tmp",
                ) +
                " " +
                shellQuote(remote) +
                "; fi; test -x " +
                shellQuote(remote)
        val result =
            runner.run(
                command = command,
                maxOutputBytes =
                    4096,
                cancellation =
                    cancellation,
            )
        require(
            result.exitCode == 0 &&
                !result.truncated
        ) {
            "Не удалось установить native scanner."
        }
        return remote
    }

    private fun shellQuote(
        value: String,
    ): String =
        "'" +
            value.replace(
                "'",
                "'\\''",
            ) +
            "'"

    private fun sha256(
        bytes: ByteArray,
    ): String =
        MessageDigest
            .getInstance(
                "SHA-256",
            )
            .digest(bytes)
            .joinToString("") {
                "%02x".format(
                    it.toInt() and
                        0xff,
                )
            }
}
