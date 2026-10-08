package io.github.ffenuss.modkit.analysis

import java.io.File

/** App adapter retains the existing routing, cache and evidence graph API. */
object FastArtifactIndexer {
    data class Limits(val maxEntries: Int = 100_000, val probeBytes: Int = 64)

    fun index(
        files: List<File>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        knownSha256: Map<String, String> = emptyMap(),
        limits: Limits = Limits(),
        cache: EngineResultCache? = null,
    ): FastAnalysisResult {
        val store = cache?.let { appCache -> object : ArtifactIndexStore {
            override fun loadArtifactIndex(artifactSha256: String) = appCache.loadArtifactIndex(artifactSha256)
            override fun saveArtifactIndex(artifactSha256: String, index: ArtifactIndex) {
                appCache.saveArtifactIndex(artifactSha256, index)
            }
        } }
        val result = PortableArtifactIndexer.index(files, cancellation, progress, knownSha256,
            PortableArtifactIndexer.Limits(limits.maxEntries, limits.probeBytes), store)
        return FastAnalysisResult(
            index = result.index,
            routingPlan = EngineRouter.plan(result.index),
            elapsedMs = result.elapsedMs,
            engineCacheHits = if (result.cacheHit) setOf(EngineResultCache.ARTIFACT_INDEX_ENGINE_ID) else emptySet(),
        ).withEvidenceGraph()
    }
}
