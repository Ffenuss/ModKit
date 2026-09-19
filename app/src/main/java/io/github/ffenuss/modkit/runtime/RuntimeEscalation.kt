package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.domain.ProofLevel

enum class RuntimeEscalationStage {
    STATIC,
    REPACKED_TEST_RUNTIME,
    NON_ROOT_RUNTIME,
    ROOT_RUNTIME,
}

data class RuntimeEscalationNeed(
    val targetId: String,
    val engineId: String,
    val requiredProofLevel: ProofLevel,
    val reason: String,
    val firstStage: RuntimeEscalationStage,
)

data class RuntimeEscalationPlan(
    val artifactSha256: String,
    val needs: List<RuntimeEscalationNeed>,
    val rootAllowedOnlyAsLastResort: Boolean = true,
) {
    val required: Boolean
        get() = needs.isNotEmpty()

    val nextStage: RuntimeEscalationStage?
        get() = needs.minByOrNull { it.firstStage.ordinal }?.firstStage
}

/**
 * Static analysis remains the default. Runtime is requested only for unresolved
 * ConfirmationQueue items, with repacked/non-root stages preceding root.
 */
object RuntimeEscalationPlanner {
    fun plan(result: FastAnalysisResult): RuntimeEscalationPlan {
        val needs = result.confirmationQueue
            .asSequence()
            .filter { !it.availableNow }
            .map { request ->
                RuntimeEscalationNeed(
                    targetId = request.targetId,
                    engineId = request.engineId,
                    requiredProofLevel = request.requiredProofLevel,
                    reason = request.reason,
                    firstStage = when {
                        request.engineId.startsWith("runtime.") ->
                            RuntimeEscalationStage.REPACKED_TEST_RUNTIME
                        else ->
                            RuntimeEscalationStage.NON_ROOT_RUNTIME
                    },
                )
            }
            .distinctBy { it.targetId to it.engineId }
            .toList()

        return RuntimeEscalationPlan(
            artifactSha256 = result.index.artifactSha256,
            needs = needs,
        )
    }
}

data class ProcMapRegion(
    val start: Long,
    val endExclusive: Long,
    val permissions: String,
    val fileOffset: Long,
    val device: String,
    val inode: Long,
    val path: String?,
) {
    val size: Long
        get() = endExclusive - start

    val readable: Boolean get() = permissions.getOrNull(0) == 'r'
    val writable: Boolean get() = permissions.getOrNull(1) == 'w'
    val executable: Boolean get() = permissions.getOrNull(2) == 'x'
    val privateMapping: Boolean get() = permissions.getOrNull(3) == 'p'

    /**
     * Candidate address corresponding to file offset zero. This is map evidence,
     * not by itself a proven ELF load bias.
     */
    val fileZeroBaseCandidate: Long
        get() = start - fileOffset
}

object ProcMapsParser {
    fun parse(text: String): List<ProcMapRegion> =
        text.lineSequence()
            .mapNotNull(::parseLine)
            .toList()

    fun parseLine(line: String): ProcMapRegion? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        val fields = trimmed.split(Regex("\\s+"), limit = 6)
        if (fields.size < 5) return null

        val range = fields[0].split('-', limit = 2)
        if (range.size != 2) return null
        val start = range[0].toLongOrNull(16) ?: return null
        val end = range[1].toLongOrNull(16) ?: return null
        if (end <= start) return null

        val permissions = fields[1]
        if (permissions.length < 4) return null
        val fileOffset = fields[2].toLongOrNull(16) ?: return null
        val device = fields[3]
        val inode = fields[4].toLongOrNull() ?: return null
        val path = fields.getOrNull(5)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return ProcMapRegion(
            start = start,
            endExclusive = end,
            permissions = permissions,
            fileOffset = fileOffset,
            device = device,
            inode = inode,
            path = path,
        )
    }

    fun matchingModule(
        regions: List<ProcMapRegion>,
        moduleName: String,
    ): List<ProcMapRegion> {
        val normalized = moduleName.substringAfterLast('/')
        return regions.filter { region ->
            val mappedName = region.path
                ?.substringAfterLast('/')
                ?.substringBefore(" (deleted)")
            mappedName == normalized
        }
    }

    fun fileZeroBaseCandidates(
        regions: List<ProcMapRegion>,
        moduleName: String,
    ): Set<Long> =
        matchingModule(regions, moduleName)
            .asSequence()
            .filter { it.readable }
            .map { it.fileZeroBaseCandidate }
            .toSet()
}
