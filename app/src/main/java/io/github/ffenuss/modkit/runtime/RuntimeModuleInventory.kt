package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.ArtifactEntry
import io.github.ffenuss.modkit.analysis.BinaryFormat
import java.io.Serializable

data class RuntimeMappedModule(
    val path: String,
    val fileName: String,
    val device: String,
    val inode: Long,
    val regionCount: Int,
    val executableRegionCount: Int,
    val fileZeroBaseCandidates: Set<Long>,
    val staticArtifactMatches: List<String>,
) : Serializable {
    val presentInStaticArtifact: Boolean
        get() = staticArtifactMatches.isNotEmpty()

    val runtimeOnlyRelativeToArtifact: Boolean
        get() = !presentInStaticArtifact
}

/**
 * Process-map module inventory for Expert/runtime evidence.
 *
 * This is deliberately descriptive. A runtime-only module is not automatically
 * suspicious and is not promoted to an exact target merely because it is mapped.
 */
object RuntimeModuleInventoryBuilder {
    fun build(
        regions: List<ProcMapRegion>,
        artifactEntries: List<ArtifactEntry>,
    ): List<RuntimeMappedModule> {
        val staticElfNames = artifactEntries
            .asSequence()
            .filter {
                it.format == BinaryFormat.ELF ||
                    it.path.lowercase().endsWith(".so")
            }
            .groupBy { it.path.substringAfterLast('/') }

        return regions
            .asSequence()
            .filter { region ->
                val path = region.path ?: return@filter false
                path.startsWith("/") && !path.startsWith("/dev/")
            }
            .groupBy { region ->
                val normalizedPath = region.path
                    ?.substringBefore(" (deleted)")
                    .orEmpty()
                Triple(
                    region.device,
                    region.inode,
                    normalizedPath,
                )
            }
            .map { (identity, mappedRegions) ->
                val path = identity.third
                val fileName = path.substringAfterLast('/')
                RuntimeMappedModule(
                    path = path,
                    fileName = fileName,
                    device = identity.first,
                    inode = identity.second,
                    regionCount = mappedRegions.size,
                    executableRegionCount = mappedRegions.count {
                        it.executable
                    },
                    fileZeroBaseCandidates = mappedRegions
                        .filter { it.readable }
                        .map { it.fileZeroBaseCandidate }
                        .toSet(),
                    staticArtifactMatches = staticElfNames[fileName]
                        .orEmpty()
                        .map { it.container + ":" + it.path }
                        .distinct()
                        .sorted(),
                )
            }
            .sortedWith(
                compareByDescending<RuntimeMappedModule> {
                    it.presentInStaticArtifact
                }.thenBy { it.fileName }
                    .thenBy { it.path },
            )
    }
}
