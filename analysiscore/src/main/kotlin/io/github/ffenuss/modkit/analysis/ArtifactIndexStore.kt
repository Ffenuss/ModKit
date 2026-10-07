package io.github.ffenuss.modkit.analysis

/** Optional content-addressed persistence supplied by each application. */
interface ArtifactIndexStore {
    fun loadArtifactIndex(artifactSha256: String): ArtifactIndex?
    fun saveArtifactIndex(artifactSha256: String, index: ArtifactIndex)
}

data class ArtifactIndexBuildResult(val index: ArtifactIndex, val elapsedMs: Long, val cacheHit: Boolean)
