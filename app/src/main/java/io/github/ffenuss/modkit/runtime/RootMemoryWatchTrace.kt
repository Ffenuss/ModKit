package io.github.ffenuss.modkit.runtime

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.nativecode.AArch64DecodedInstruction
import io.github.ffenuss.modkit.analysis.nativecode.AArch64Disassembler
import java.io.File
import java.security.MessageDigest

enum class RuntimeCodeAccessKind {
    READ,
    WRITE,
    UNKNOWN,
}

data class RootMemoryWatchRawHit(
    val pc: Long,
    val count: Int,
    val tid: Int,
    val faultAddress: Long,
)

data class RootCodeAccessSite(
    val pc: Long,
    val instructionAddress: Long,
    val count: Int,
    val tid: Int,
    val faultAddress: Long,
    val moduleName: String,
    val mappedPath: String?,
    val moduleFileOffset: Long?,
    val instructionText: String?,
    val instructionWord: Long?,
    val accessKind: RuntimeCodeAccessKind,
    val managedMethodCandidate: String?,
)

data class RootMemoryWatchTraceResult(
    val packageName: String,
    val pid: Int,
    val targetAddress: Long,
    val width: Int,
    val durationMs: Int,
    val watchedThreads: Int,
    val totalTraps: Int,
    val helperTruncated: Boolean,
    val sites: List<RootCodeAccessSite>,
    val rawOutput: String,
)

internal data class ParsedRootMemoryWatchOutput(
    val watchedThreads: Int,
    val totalTraps: Int,
    val truncated: Boolean,
    val hits: List<RootMemoryWatchRawHit>,
    val errors: List<String>,
)

object RootMemoryWatchOutputParser {
    private const val MARKER =
        "MODKIT_ROOT_WATCH_V1"

    fun parse(
        text: String,
    ): ParsedRootMemoryWatchOutput {
        var markerSeen = false
        var threads = 0
        var traps = 0
        var truncated = false
        val hits =
            mutableListOf<
                RootMemoryWatchRawHit
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
                    "INFO" -> {
                        if (
                            fields.size == 3 &&
                            fields[1] ==
                            "THREADS"
                        ) {
                            threads =
                                fields[2]
                                    .toIntOrNull()
                                    ?.coerceAtLeast(
                                        0,
                                    )
                                    ?: threads
                        }
                    }

                    "HIT" -> {
                        if (
                            fields.size ==
                            5
                        ) {
                            val pc =
                                parseHex(
                                    fields[1],
                                )
                            val count =
                                fields[2]
                                    .toIntOrNull()
                            val tid =
                                fields[3]
                                    .toIntOrNull()
                            val address =
                                parseHex(
                                    fields[4],
                                )
                            if (
                                pc != null &&
                                pc > 0L &&
                                count != null &&
                                count > 0 &&
                                tid != null &&
                                tid > 0 &&
                                address != null
                            ) {
                                hits +=
                                    RootMemoryWatchRawHit(
                                        pc = pc,
                                        count =
                                            count,
                                        tid = tid,
                                        faultAddress =
                                            address,
                                    )
                            }
                        }
                    }

                    "ERROR" -> {
                        errors +=
                            fields
                                .drop(1)
                                .joinToString(
                                    ": ",
                                )
                                .ifBlank {
                                    line
                                }
                    }

                    "END" -> {
                        if (
                            fields.size >=
                            4
                        ) {
                            traps =
                                fields[2]
                                    .toIntOrNull()
                                    ?.coerceAtLeast(
                                        0,
                                    )
                                    ?: traps
                            truncated =
                                fields[3] ==
                                    "1"
                        }
                    }
                }
            }

        if (!markerSeen) {
            errors +=
                "Root watch helper marker is missing."
        }

        return ParsedRootMemoryWatchOutput(
            watchedThreads =
                threads,
            totalTraps =
                traps,
            truncated =
                truncated,
            hits =
                hits.sortedWith(
                    compareByDescending<
                        RootMemoryWatchRawHit
                    > {
                        it.count
                    }.thenBy {
                        it.pc
                    },
                ),
            errors =
                errors,
        )
    }

    private fun parseHex(
        value: String,
    ): Long? =
        value
            .removePrefix(
                "0x",
            )
            .removePrefix(
                "0X",
            )
            .toLongOrNull(
                16,
            )
}

object RootMemoryWatchCoordinator {
    private const val ASSET_PATH =
        "modkit-root-memory-watch/arm64-v8a/modkit_root_memory_watch"
    private const val DEFAULT_DURATION_MS =
        5_000
    private const val MAX_DURATION_MS =
        12_000
    private const val MAX_EVENTS =
        2_000
    private const val MAX_DECODED_SITES =
        24
    private const val MAX_RAW_OUTPUT =
        512 * 1024
    private const val MAX_METHOD_SPAN =
        64L * 1024L

    fun trace(
        context: Context,
        packageName: String,
        pid: Int,
        targetAddress: Long,
        width: Int,
        cancellation: CancellationSignal,
        durationMs: Int =
            DEFAULT_DURATION_MS,
    ): RootMemoryWatchTraceResult {
        require(pid > 0) {
            "Trace PID must be positive."
        }
        require(targetAddress > 0L) {
            "Trace target address must be positive."
        }
        require(
            width == 1 ||
                width == 2 ||
                width == 4 ||
                width == 8
        ) {
            "Trace width must be 1, 2, 4 or 8 bytes."
        }
        require(
            durationMs in
                250..MAX_DURATION_MS
        ) {
            "Trace duration is out of bounds."
        }

        val runner =
            AndroidRootCommandRunner(
                timeoutMs =
                    durationMs +
                        10_000L,
            )
        val initial =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = pid,
                )
        require(
            initial.pid == pid
        ) {
            "Selected process identity changed before tracing."
        }

        val regions =
            ProcMapsParser.parse(
                initial.capture.text,
            )
        require(
            regions.any {
                targetAddress >=
                    it.start &&
                    targetAddress +
                        width <=
                    it.endExclusive &&
                    it.readable &&
                    it.writable &&
                    !it.executable
            },
        ) {
            "The selected value is no longer inside a writable data mapping."
        }

        requireArm64Target(
            pid = pid,
            cancellation =
                cancellation,
            runner = runner,
        )
        val payload =
            materializePayload(
                context =
                    context,
                cancellation =
                    cancellation,
            )
        val remote =
            installRootPayload(
                local =
                    payload,
                cancellation =
                    cancellation,
                runner = runner,
            )

        val command =
            shellQuote(remote) +
                " " +
                pid +
                " 0x" +
                targetAddress
                    .toString(16) +
                " " +
                width +
                " " +
                durationMs +
                " " +
                MAX_EVENTS

        val execution =
            runner.run(
                command =
                    command,
                maxOutputBytes =
                    MAX_RAW_OUTPUT,
                cancellation =
                    cancellation,
            )
        val output =
            execution.output
                .toString(
                    Charsets.UTF_8,
                )
        val parsed =
            RootMemoryWatchOutputParser
                .parse(output)

        if (
            execution.truncated
        ) {
            error(
                "Root watch helper output exceeded the bounded capture limit.",
            )
        }
        if (
            execution.exitCode != 0 ||
            parsed.errors
                .isNotEmpty()
        ) {
            val reason =
                parsed.errors
                    .joinToString(
                        "; ",
                    )
                    .ifBlank {
                        "exitCode=" +
                            execution.exitCode
                    }
            error(
                "Root hardware watch trace failed: " +
                    reason,
            )
        }

        val finalCapture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = pid,
                )
        require(
            finalCapture.pid == pid
        ) {
            "Selected process identity changed after tracing."
        }

        val finalRegions =
            ProcMapsParser.parse(
                finalCapture
                    .capture
                    .text,
            )
        val artifactSha =
            runCatching {
                BehavioralProfileStore(
                    context,
                ).computeIdentity(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                ).artifactSha256
            }.getOrNull()
        val cachedAnalysis =
            artifactSha
                ?.let {
                    EngineResultCache(
                        File(
                            context.filesDir,
                            "analysis-cache",
                        ),
                    ).restorePartialResult(
                        it,
                    )
                }

        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = pid,
                runner = runner,
            )
        val sites =
            parsed.hits
                .asSequence()
                .take(
                    MAX_DECODED_SITES,
                )
                .mapNotNull {
                    raw ->
                    correlateHit(
                        raw = raw,
                        regions =
                            finalRegions,
                        reader = reader,
                        cancellation =
                            cancellation,
                        cachedAnalysis =
                            cachedAnalysis,
                    )
                }
                .sortedWith(
                    compareByDescending<
                        RootCodeAccessSite
                    > {
                        it.accessKind ==
                            RuntimeCodeAccessKind
                                .WRITE
                    }
                        .thenByDescending {
                            it.count
                        }
                        .thenBy {
                            it.instructionAddress
                        },
                )
                .toList()

        return RootMemoryWatchTraceResult(
            packageName =
                packageName,
            pid = pid,
            targetAddress =
                targetAddress,
            width = width,
            durationMs =
                durationMs,
            watchedThreads =
                parsed.watchedThreads,
            totalTraps =
                parsed.totalTraps,
            helperTruncated =
                parsed.truncated,
            sites = sites,
            rawOutput =
                output,
        )
    }

    private fun correlateHit(
        raw: RootMemoryWatchRawHit,
        regions: List<ProcMapRegion>,
        reader: RuntimeMemoryReader,
        cancellation: CancellationSignal,
        cachedAnalysis:
            io.github.ffenuss.modkit.analysis.FastAnalysisResult?,
    ): RootCodeAccessSite? {
        if (
            cancellation.isCancelled()
        ) {
            throw AnalysisCancelledException()
        }

        val pcRegion =
            regions.singleOrNull {
                raw.pc >=
                    it.start &&
                    raw.pc <
                    it.endExclusive &&
                    it.executable
            } ?: regions.singleOrNull {
                raw.pc >=
                    it.start &&
                    raw.pc <
                    it.endExclusive
            }
                ?: return null

        val candidates =
            listOf(
                raw.pc - 4L,
                raw.pc,
            )
                .filter {
                    it >=
                        pcRegion.start &&
                        it + 4L <=
                        pcRegion
                            .endExclusive
                }
                .distinct()

        var chosenAddress =
            raw.pc
        var decoded:
            AArch64DecodedInstruction? =
            null

        candidates.forEach {
            address ->
            if (
                decoded != null &&
                decoded!!
                    .recognized
            ) {
                return@forEach
            }
            val bytes =
                reader.read(
                    address =
                        address,
                    size = 4,
                    cancellation =
                        cancellation,
                ) ?: return@forEach
            if (bytes.size != 4) {
                return@forEach
            }
            val instruction =
                AArch64Disassembler
                    .disassemble(
                        code = bytes,
                        startAddress =
                            address,
                        maxInstructions =
                            1,
                    )
                    .instructions
                    .singleOrNull()
                    ?: return@forEach
            if (
                decoded == null ||
                memoryAccessKind(
                    instruction,
                ) !=
                RuntimeCodeAccessKind
                    .UNKNOWN
            ) {
                chosenAddress =
                    address
                decoded =
                    instruction
            }
        }

        val chosenRegion =
            regions.singleOrNull {
                chosenAddress >=
                    it.start &&
                    chosenAddress <
                    it.endExclusive &&
                    it.executable
            } ?: pcRegion

        val moduleName =
            moduleName(
                chosenRegion.path,
            )
        val fileOffset =
            if (
                chosenRegion.path
                    .isNullOrBlank() ||
                chosenRegion.path
                    ?.startsWith(
                        "[",
                    ) == true
            ) {
                null
            } else {
                chosenRegion
                    .fileOffset +
                    (
                        chosenAddress -
                            chosenRegion.start
                        )
            }

        return RootCodeAccessSite(
            pc = raw.pc,
            instructionAddress =
                chosenAddress,
            count = raw.count,
            tid = raw.tid,
            faultAddress =
                raw.faultAddress,
            moduleName =
                moduleName,
            mappedPath =
                chosenRegion.path,
            moduleFileOffset =
                fileOffset,
            instructionText =
                decoded?.text,
            instructionWord =
                decoded?.word,
            accessKind =
                decoded
                    ?.let {
                        memoryAccessKind(
                            it,
                        )
                    }
                    ?: RuntimeCodeAccessKind
                        .UNKNOWN,
            managedMethodCandidate =
                if (
                    fileOffset !=
                    null
                ) {
                    correlateManagedMethod(
                        cachedAnalysis =
                            cachedAnalysis,
                        moduleName =
                            moduleName,
                        fileOffset =
                            fileOffset,
                    )
                } else {
                    null
                },
        )
    }

    internal fun correlateManagedMethod(
        cachedAnalysis:
            io.github.ffenuss.modkit.analysis.FastAnalysisResult?,
        moduleName: String,
        fileOffset: Long,
    ): String? {
        val bindings =
            cachedAnalysis
                ?.il2cppBinaryBinding
                ?.evidence
                .orEmpty()
                .asSequence()
                .filter {
                    evidence ->
                    val binaryName =
                        evidence.libraryEntry
                            .substringAfterLast(
                                ':',
                            )
                            .substringAfterLast(
                                '/',
                            )
                    binaryName ==
                        moduleName
                }
                .flatMap {
                    it.bindings
                        .asSequence()
                }
                .mapNotNull {
                    binding ->
                    binding.functionFileOffset
                        ?.let {
                            offset ->
                            offset to
                                binding
                        }
                }
                .sortedBy {
                    it.first
                }
                .toList()

        if (bindings.isEmpty()) {
            return null
        }

        var bestIndex = -1
        for (
            index in
            bindings.indices
        ) {
            if (
                bindings[index]
                    .first <=
                fileOffset
            ) {
                bestIndex = index
            } else {
                break
            }
        }
        if (bestIndex < 0) {
            return null
        }

        val start =
            bindings[bestIndex]
                .first
        val next =
            bindings
                .drop(
                    bestIndex +
                        1,
                )
                .firstOrNull {
                    it.first >
                        start
                }
                ?.first
        val upper =
            minOf(
                next
                    ?: (
                        start +
                            MAX_METHOD_SPAN
                        ),
                start +
                    MAX_METHOD_SPAN,
            )
        if (
            fileOffset !in
            start until upper
        ) {
            return null
        }
        return bindings[
            bestIndex
        ].second
            .managedIdentity
    }

    private fun memoryAccessKind(
        instruction:
            AArch64DecodedInstruction,
    ): RuntimeCodeAccessKind =
        when {
            instruction.mnemonic
                .startsWith(
                    "str",
                ) ||
                instruction.mnemonic ==
                "stp" ->
                RuntimeCodeAccessKind
                    .WRITE
            instruction.mnemonic
                .startsWith(
                    "ldr",
                ) ||
                instruction.mnemonic ==
                "ldp" ->
                RuntimeCodeAccessKind
                    .READ
            else ->
                RuntimeCodeAccessKind
                    .UNKNOWN
        }

    private fun moduleName(
        path: String?,
    ): String =
        path
            ?.removeSuffix(
                " (deleted)",
            )
            ?.substringAfterLast(
                '/',
            )
            ?.takeIf {
                it.isNotBlank()
            }
            ?: (
                path
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "<anonymous>"
                )

    private fun requireArm64Target(
        pid: Int,
        cancellation:
            CancellationSignal,
        runner: RootCommandRunner,
    ) {
        val result =
            runner.run(
                command =
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
            "Could not determine target process architecture."
        }
        val machine =
            result.output
                .toString(
                    Charsets.UTF_8,
                )
                .trim()
                .split(
                    Regex(
                        "\\s+",
                    ),
                )
                .firstOrNull {
                    it.isNotBlank()
                }
                ?.toIntOrNull()
        require(
            machine == 183
        ) {
            "Root code-access tracing currently supports ARM64 targets only."
        }
    }

    private fun materializePayload(
        context: Context,
        cancellation:
            CancellationSignal,
    ): File {
        val bytes =
            context.assets
                .open(
                    ASSET_PATH,
                )
                .use {
                    input ->
                    val output =
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
                        output.write(
                            buffer,
                            0,
                            read,
                        )
                    }
                    output
                        .toByteArray()
                }
        require(
            bytes.size in
                1..4 * 1024 * 1024
        ) {
            "Root watch helper asset has an invalid size."
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
            "Root watch helper asset is not ELF."
        }

        val sha =
            sha256(bytes)
        val root =
            File(
                context.noBackupFilesDir,
                "root-watch-payload/" +
                    sha.take(
                        16,
                    ),
            ).apply {
                mkdirs()
            }
        val output =
            File(
                root,
                "modkit_root_memory_watch",
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
                "Could not materialize root watch helper."
            }
        }
        return output
    }

    private fun installRootPayload(
        local: File,
        cancellation:
            CancellationSignal,
        runner: RootCommandRunner,
    ): String {
        val sha =
            sha256(
                local.readBytes(),
            )
        val remote =
            "/data/local/tmp/modkit-root-watch-" +
                android.os.Process
                    .myUid() +
                "-" +
                sha.take(
                    16,
                )
        val command =
            "if [ ! -x " +
                shellQuote(
                    remote,
                ) +
                " ]; then " +
                "cp " +
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
                shellQuote(
                    remote,
                ) +
                "; fi; " +
                "test -x " +
                shellQuote(
                    remote,
                )
        val result =
            runner.run(
                command =
                    command,
                maxOutputBytes =
                    4096,
                cancellation =
                    cancellation,
            )
        require(
            result.exitCode == 0 &&
                !result.truncated
        ) {
            "Could not install the root watch helper in /data/local/tmp."
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
            .digest(
                bytes,
            )
            .joinToString(
                "",
            ) {
                "%02x".format(
                    it.toInt() and
                        0xff,
                )
            }
}
