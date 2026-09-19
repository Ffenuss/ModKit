package io.github.ffenuss.modkit.runtime

import java.io.Serializable

data class RuntimeMemoryMappingCandidate(
    val start: Long,
    val endExclusive: Long,
    val permissions: String,
    val path: String?,
    val fileOffset: Long,
    val fileZeroAddressCandidate: Long,
    val reason: String,
) : Serializable {
    val size: Long get() = endExclusive - start
}

/**
 * Finds executable mappings that are not ordinary file-backed modules.
 *
 * This is only a candidate list. No mapping is called an ELF until a later
 * memory-header reader validates the ELF magic/header from a bounded address.
 */
object RuntimeMemoryMappingDetector {
    fun candidates(
        regions: List<ProcMapRegion>,
    ): List<RuntimeMemoryMappingCandidate> =
        regions.asSequence()
            .filter { it.executable }
            .filter { region ->
                val path = region.path
                path == null ||
                    path.startsWith("[") ||
                    path.startsWith("/memfd:") ||
                    path.startsWith("/dev/ashmem") ||
                    path.endsWith(" (deleted)")
            }
            .map { region ->
                RuntimeMemoryMappingCandidate(
                    start = region.start,
                    endExclusive = region.endExclusive,
                    permissions = region.permissions,
                    path = region.path,
                    fileOffset = region.fileOffset,
                    fileZeroAddressCandidate = region.fileZeroBaseCandidate,
                    reason = when {
                        region.path == null ->
                            "Executable mapping has no file path."
                        region.path.startsWith("/memfd:") ->
                            "Executable mapping is backed by memfd."
                        region.path.startsWith("/dev/ashmem") ->
                            "Executable mapping is backed by ashmem."
                        region.path.endsWith(" (deleted)") ->
                            "Executable file-backed mapping points to a deleted file."
                        else ->
                            "Executable anonymous/special mapping."
                    },
                )
            }
            .sortedBy { it.start }
            .toList()
}
