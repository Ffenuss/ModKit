package io.github.ffenuss.modkit.runtime

enum class RootRuntimeArtifactKind(
    val title: String,
) {
    ELF("ELF"),
    DEX("DEX"),
    COMPACT_DEX("CompactDEX"),
    IL2CPP_METADATA("IL2CPP metadata"),
    WASM("WebAssembly"),
    SQLITE("SQLite"),
    ZIP("ZIP/APK/JAR"),
    PE("PE/CLI candidate"),
}

data class RootRuntimeArtifactCandidate(
    val kind: RootRuntimeArtifactKind,
    val address: Long,
    val regionStart: Long,
    val regionEndExclusive: Long,
    val regionPath: String?,
    val estimatedSize: Long?,
    val evidence: String,
)

/**
 * Lightweight runtime artifact inventory for root process snapshots.
 *
 * This does not invent source code. It records validated magic/header
 * candidates that are actually present in the live address space so later
 * runtime-specific parsers can consume them (DEX/ART, native ELF, IL2CPP,
 * Mono/CLI, WASM, SQLite, archives, etc.).
 */
object RootRuntimeArtifactDetector {
    private const val PAGE_BYTES = 4096
    private const val DENSE_PREFIX_BYTES =
        64 * 1024
    private const val MAX_REASONABLE_ARTIFACT_BYTES =
        2L * 1024L * 1024L * 1024L

    fun detect(
        region: ProcMapRegion,
        sliceAddress: Long,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): List<RootRuntimeArtifactCandidate> {
        require(
            offset >= 0 &&
                length >= 0 &&
                offset + length <=
                bytes.size,
        )
        if (length < 4) {
            return emptyList()
        }

        val out =
            LinkedHashMap<
                Pair<
                    RootRuntimeArtifactKind,
                    Long
                >,
                RootRuntimeArtifactCandidate
            >()

        fun inspect(
            relative: Int,
        ) {
            if (
                relative < 0 ||
                relative >= length
            ) {
                return
            }
            val absolute =
                offset + relative
            val address =
                sliceAddress +
                    relative.toLong()

            fun add(
                kind:
                    RootRuntimeArtifactKind,
                estimatedSize:
                    Long? = null,
                evidence: String,
            ) {
                val key =
                    kind to address
                out.putIfAbsent(
                    key,
                    RootRuntimeArtifactCandidate(
                        kind = kind,
                        address = address,
                        regionStart =
                            region.start,
                        regionEndExclusive =
                            region.endExclusive,
                        regionPath =
                            region.path,
                        estimatedSize =
                            estimatedSize,
                        evidence =
                            evidence,
                    ),
                )
            }

            if (
                has(
                    bytes,
                    absolute,
                    byteArrayOf(
                        0x7f,
                        0x45,
                        0x4c,
                        0x46,
                    ),
                )
            ) {
                add(
                    RootRuntimeArtifactKind.ELF,
                    evidence =
                        "ELF magic",
                )
            }
            if (
                hasAscii(
                    bytes,
                    absolute,
                    "dex\n",
                ) &&
                absolute + 8 <=
                bytes.size &&
                bytes[absolute + 7] ==
                    0.toByte()
            ) {
                add(
                    RootRuntimeArtifactKind.DEX,
                    estimatedSize =
                        u32le(
                            bytes,
                            absolute + 32,
                        )
                            ?.takeIf {
                                it in
                                    112L..
                                        MAX_REASONABLE_ARTIFACT_BYTES
                            },
                    evidence =
                        "DEX magic/header",
                )
            }
            if (
                hasAscii(
                    bytes,
                    absolute,
                    "cdex",
                )
            ) {
                add(
                    RootRuntimeArtifactKind
                        .COMPACT_DEX,
                    evidence =
                        "CompactDEX magic",
                )
            }
            if (
                has(
                    bytes,
                    absolute,
                    byteArrayOf(
                        0xaf.toByte(),
                        0x1b,
                        0xb1.toByte(),
                        0xfa.toByte(),
                    ),
                )
            ) {
                val version =
                    u32le(
                        bytes,
                        absolute + 4,
                    )
                if (
                    version != null &&
                    version in 15L..100L
                ) {
                    add(
                        RootRuntimeArtifactKind
                            .IL2CPP_METADATA,
                        estimatedSize =
                            estimateIl2CppMetadataSize(
                                bytes,
                                absolute,
                            ),
                        evidence =
                            "global-metadata sanity + version " +
                                version,
                    )
                }
            }
            if (
                has(
                    bytes,
                    absolute,
                    byteArrayOf(
                        0x00,
                        0x61,
                        0x73,
                        0x6d,
                    ),
                )
            ) {
                add(
                    RootRuntimeArtifactKind.WASM,
                    evidence =
                        "WASM magic",
                )
            }
            if (
                hasAscii(
                    bytes,
                    absolute,
                    "SQLite format 3\u0000",
                )
            ) {
                add(
                    RootRuntimeArtifactKind.SQLITE,
                    evidence =
                        "SQLite header",
                )
            }
            if (
                has(
                    bytes,
                    absolute,
                    byteArrayOf(
                        0x50,
                        0x4b,
                        0x03,
                        0x04,
                    ),
                )
            ) {
                add(
                    RootRuntimeArtifactKind.ZIP,
                    evidence =
                        "ZIP local-file magic",
                )
            }
            if (
                has(
                    bytes,
                    absolute,
                    byteArrayOf(
                        0x4d,
                        0x5a,
                    ),
                )
            ) {
                add(
                    RootRuntimeArtifactKind.PE,
                    evidence =
                        "MZ header candidate",
                )
            }
        }

        // Dense scan only near each slice beginning, where loaders commonly
        // place image/header starts. The rest is sampled at page boundaries to
        // keep multi-gigabyte dumps practical on a phone.
        val dense =
            minOf(
                length,
                DENSE_PREFIX_BYTES,
            )
        var relative = 0
        while (
            relative <
            dense
        ) {
            inspect(relative)
            relative += 4
        }

        val pageOffset =
            Math.floorMod(
                -sliceAddress,
                PAGE_BYTES.toLong(),
            ).toInt()
        relative =
            if (
                pageOffset <
                dense
            ) {
                dense +
                    (
                        PAGE_BYTES -
                            dense %
                                PAGE_BYTES
                        ) %
                        PAGE_BYTES
            } else {
                pageOffset
            }
        while (
            relative <
            length
        ) {
            inspect(relative)
            relative +=
                PAGE_BYTES
        }

        // A named file-zero mapping is important evidence even when a loader
        // modified a few magic bytes in memory.
        if (
            sliceAddress ==
                region.start &&
            region.fileOffset ==
                0L
        ) {
            val path =
                region.path
                    .orEmpty()
                    .lowercase()
            when {
                path.endsWith(
                    ".so",
                ) ->
                    out.putIfAbsent(
                        RootRuntimeArtifactKind.ELF to
                            sliceAddress,
                        RootRuntimeArtifactCandidate(
                            kind =
                                RootRuntimeArtifactKind
                                    .ELF,
                            address =
                                sliceAddress,
                            regionStart =
                                region.start,
                            regionEndExclusive =
                                region.endExclusive,
                            regionPath =
                                region.path,
                            estimatedSize = null,
                            evidence =
                                "file-zero .so mapping",
                        ),
                    )
                path.endsWith(
                    "global-metadata.dat",
                ) ->
                    out.putIfAbsent(
                        RootRuntimeArtifactKind
                            .IL2CPP_METADATA to
                            sliceAddress,
                        RootRuntimeArtifactCandidate(
                            kind =
                                RootRuntimeArtifactKind
                                    .IL2CPP_METADATA,
                            address =
                                sliceAddress,
                            regionStart =
                                region.start,
                            regionEndExclusive =
                                region.endExclusive,
                            regionPath =
                                region.path,
                            estimatedSize = null,
                            evidence =
                                "named global-metadata.dat mapping",
                        ),
                    )
            }
        }

        return out.values.toList()
    }

    private fun estimateIl2CppMetadataSize(
        bytes: ByteArray,
        base: Int,
    ): Long? {
        if (
            base + 16 >
            bytes.size
        ) {
            return null
        }
        val scanEnd =
            minOf(
                bytes.size,
                base + 512,
            )
        var cursor =
            base + 8
        var maxEnd = 0L
        while (
            cursor + 8 <=
            scanEnd
        ) {
            val tableOffset =
                u32le(
                    bytes,
                    cursor,
                ) ?: break
            val byteCount =
                u32le(
                    bytes,
                    cursor + 4,
                ) ?: break
            if (
                tableOffset in
                    0L..
                        MAX_REASONABLE_ARTIFACT_BYTES &&
                byteCount in
                    0L..
                        MAX_REASONABLE_ARTIFACT_BYTES &&
                tableOffset +
                    byteCount <=
                    MAX_REASONABLE_ARTIFACT_BYTES
            ) {
                maxEnd =
                    maxOf(
                        maxEnd,
                        tableOffset +
                            byteCount,
                    )
            }
            cursor += 8
        }
        return maxEnd
            .takeIf {
                it >= 64L
            }
    }

    private fun has(
        bytes: ByteArray,
        offset: Int,
        magic: ByteArray,
    ): Boolean {
        if (
            offset < 0 ||
            offset + magic.size >
            bytes.size
        ) {
            return false
        }
        for (
            index in
            magic.indices
        ) {
            if (
                bytes[offset + index] !=
                magic[index]
            ) {
                return false
            }
        }
        return true
    }

    private fun hasAscii(
        bytes: ByteArray,
        offset: Int,
        value: String,
    ): Boolean =
        has(
            bytes,
            offset,
            value.toByteArray(
                Charsets.US_ASCII,
            ),
        )

    private fun u32le(
        bytes: ByteArray,
        offset: Int,
    ): Long? {
        if (
            offset < 0 ||
            offset + 4 >
            bytes.size
        ) {
            return null
        }
        return (
            (bytes[offset]
                .toLong() and
                0xffL) or
                (
                    (bytes[offset + 1]
                        .toLong() and
                        0xffL) shl 8
                    ) or
                (
                    (bytes[offset + 2]
                        .toLong() and
                        0xffL) shl 16
                    ) or
                (
                    (bytes[offset + 3]
                        .toLong() and
                        0xffL) shl 24
                    )
            )
    }
}
