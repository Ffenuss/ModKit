package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import java.io.File
import java.io.RandomAccessFile

data class Il2CppArm64CallerHit(
    val callerTargetIds: List<String>,
    val callerDisplayNames: List<String>,
    val callerFileOffset: Long,
    val callerBinaryVirtualAddress: Long,
    val callSiteBinaryVirtualAddress: Long,
)

data class Il2CppArm64CallerScanResult(
    val targetId: String,
    val targetBinaryVirtualAddress: Long,
    val callers: List<Il2CppArm64CallerHit>,
    val scannedBodies: Int,
    val scannedBytes: Long,
    val skippedUnboundedBodies: Int,
    val truncatedByMethodLimit: Boolean,
    val truncatedByResultLimit: Boolean,
)

/**
 * On-demand reverse direct-call scan for one exact ARM64 IL2CPP method.
 *
 * It scans only bodies with two proven consecutive file offsets, uses the
 * fixed-width BL encoding directly, and never treats arbitrary 4-byte patterns
 * outside those proven method spans as call evidence.
 */
object Il2CppArm64CallerScanner {
    const val DEFAULT_MAX_METHODS = 75_000
    const val DEFAULT_MAX_METHOD_BYTES = 16 * 1024
    const val DEFAULT_MAX_RESULTS = 512

    fun scan(
        result: FastAnalysisResult,
        target: EvidenceTarget,
        analysisResultsRoot: File,
        cancellation: CancellationSignal,
        maxMethods: Int = DEFAULT_MAX_METHODS,
        maxMethodBytes: Int = DEFAULT_MAX_METHOD_BYTES,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): Il2CppArm64CallerScanResult {
        require(maxMethods in 1..300_000)
        require(maxMethodBytes in 4..256 * 1024)
        require(maxResults in 1..10_000)
        require(
            target.runtimeId == "unity_il2cpp" &&
                target.kind == EvidenceTargetKind.METHOD,
        ) {
            "Reverse caller scan requires an exact IL2CPP method."
        }
        val abi =
            requireNotNull(target.abi) {
                "Target ABI is unknown."
            }
        require(
            abi.equals(
                "arm64-v8a",
                ignoreCase = true,
            ),
        ) {
            "Reverse caller scan currently supports arm64-v8a only."
        }
        val artifact =
            requireNotNull(target.artifact) {
                "Target native artifact is unknown."
            }
        val targetAddress =
            requireNotNull(
                target.binaryVirtualAddress,
            ) {
                "Target binary virtual address is not proven."
            }

        val safeAbi =
            abi.replace(
                Regex("[^A-Za-z0-9._-]"),
                "_",
            )
        val library =
            File(
                analysisResultsRoot,
                result.index.artifactSha256 +
                    "/il2cpp/native/" +
                    safeAbi +
                    "-libil2cpp.so",
            )
        require(
            library.isFile &&
                library.canRead(),
        ) {
            "Extracted libil2cpp.so for reverse caller scan is unavailable."
        }

        val grouped =
            result.evidenceGraph
                ?.targets
                .orEmpty()
                .asSequence()
                .filter {
                    it.runtimeId ==
                        "unity_il2cpp" &&
                        it.kind ==
                        EvidenceTargetKind.METHOD &&
                        it.artifact ==
                        artifact &&
                        it.abi == abi &&
                        it.fileOffset != null &&
                        it.binaryVirtualAddress != null
                }
                .groupBy {
                    requireNotNull(
                        it.fileOffset,
                    )
                }
                .toSortedMap()
        val offsets =
            grouped.keys.toList()
        val callerHits =
            mutableListOf<
                Il2CppArm64CallerHit,
            >()
        var scannedBodies = 0
        var scannedBytes = 0L
        var skippedUnbounded = 0
        var resultLimit = false
        val bodyLimit =
            minOf(
                offsets.size,
                maxMethods,
            )

        RandomAccessFile(
            library,
            "r",
        ).use {
            raf ->
            for (
                index in
                    0 until bodyLimit
            ) {
                if (
                    cancellation.isCancelled()
                ) {
                    throw AnalysisCancelledException()
                }
                val offset =
                    offsets[index]
                val next =
                    offsets.getOrNull(
                        index + 1,
                    )
                if (next == null) {
                    skippedUnbounded++
                    continue
                }
                val rawSpan =
                    next - offset
                if (rawSpan < 4L) {
                    continue
                }

                val aliases =
                    grouped.getValue(offset)
                val sourceAddresses =
                    aliases
                        .mapNotNull {
                            it.binaryVirtualAddress
                        }
                        .distinct()
                if (
                    sourceAddresses.size !=
                    1
                ) {
                    continue
                }
                val sourceAddress =
                    sourceAddresses.single()
                var length =
                    minOf(
                        rawSpan,
                        maxMethodBytes
                            .toLong(),
                        library.length() -
                            offset,
                    ).toInt()
                length -=
                    length % 4
                if (length < 4) {
                    continue
                }

                val bytes =
                    ByteArray(length)
                raf.seek(offset)
                raf.readFully(bytes)
                scannedBodies++
                scannedBytes +=
                    bytes.size

                var local = 0
                while (
                    local + 4 <=
                    bytes.size
                ) {
                    val word =
                        u32(
                            bytes,
                            local,
                        )
                    if (
                        (
                            word and
                                0xFC000000L
                            ) ==
                            0x94000000L
                    ) {
                        val imm26 =
                            word and
                                0x03FFFFFFL
                        val displacement =
                            signExtend(
                                imm26 shl 2,
                                28,
                            )
                        val callSite =
                            sourceAddress +
                                local
                        val destination =
                            callSite +
                                displacement
                        if (
                            destination ==
                            targetAddress
                        ) {
                            callerHits +=
                                Il2CppArm64CallerHit(
                                    callerTargetIds =
                                        aliases
                                            .map {
                                                it.id
                                            },
                                    callerDisplayNames =
                                        aliases
                                            .map {
                                                it.displayName
                                            },
                                    callerFileOffset =
                                        offset,
                                    callerBinaryVirtualAddress =
                                        sourceAddress,
                                    callSiteBinaryVirtualAddress =
                                        callSite,
                                )
                            if (
                                callerHits.size >=
                                maxResults
                            ) {
                                resultLimit = true
                                break
                            }
                        }
                    }
                    local += 4
                }
                if (resultLimit) {
                    break
                }
            }
        }

        return Il2CppArm64CallerScanResult(
            targetId = target.id,
            targetBinaryVirtualAddress =
                targetAddress,
            callers =
                callerHits,
            scannedBodies =
                scannedBodies,
            scannedBytes =
                scannedBytes,
            skippedUnboundedBodies =
                skippedUnbounded,
            truncatedByMethodLimit =
                offsets.size >
                    maxMethods,
            truncatedByResultLimit =
                resultLimit,
        )
    }

    private fun u32(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun signExtend(
        value: Long,
        bits: Int,
    ): Long {
        val shift =
            64 - bits
        return (value shl shift) shr shift
    }
}
