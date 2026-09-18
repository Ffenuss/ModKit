package io.github.ffenuss.modkit.analysis

/**
 * Shared single-pass index consumed by analysis engines.
 *
 * The index is created once for a target and reused by all routed engines.
 * Entry hashes are intentionally not required during FAST analysis: the target
 * itself is SHA-bound while deeper per-entry hashing is performed only when an
 * engine actually needs it.
 */
data class ArtifactIndex(
    val artifactSha256: String,
    val sources: List<ArtifactSource>,
    val entries: List<ArtifactEntry>,
    val detectedAbis: Set<String> = emptySet(),
    val runtimeProfiles: List<RuntimeProfile> = emptyList(),
    val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
)

data class ArtifactEntry(
    val container: String,
    val path: String,
    val size: Long,
    val compressedSize: Long? = null,
    val crc32: Long? = null,
    val format: BinaryFormat = BinaryFormat.UNKNOWN,
    val abi: String? = null,
    val tags: Set<String> = emptySet(),
)
