package io.github.ffenuss.modkit.analysis

/**
 * Shared single-pass index consumed by analysis engines.
 * v0.0.1 defines the contract; scanners populate it from v0.0.2 onward.
 */
data class ArtifactIndex(
    val artifactSha256: String,
    val entries: List<ArtifactEntry>,
    val detectedAbis: Set<String> = emptySet(),
    val runtimeTags: Set<String> = emptySet(),
)

data class ArtifactEntry(
    val path: String,
    val size: Long,
    val compressedSize: Long? = null,
    val sha256: String? = null,
    val tags: Set<String> = emptySet(),
)
