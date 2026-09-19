package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.ArtifactEntry
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ElfImage
import io.github.ffenuss.modkit.analysis.ElfLoadSegment
import io.github.ffenuss.modkit.analysis.EvidenceFact
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import java.io.File
import java.io.Serializable
import java.security.MessageDigest

data class RuntimeModuleMappingEvidence(
    val moduleName: String,
    val mappedPath: String,
    val loadBias: Long?,
    val elfImageBaseVirtualAddress: Long?,
    val pageSize: Long?,
    val matchedLoadSegments: Int,
    val matchedExecutableSegments: Int,
    val zeroOffsetMappingMatched: Boolean,
    val confirmed: Boolean,
    val blockers: List<String>,
) : Serializable

data class RuntimeAddressConfirmation(
    val targetId: String,
    val moduleName: String,
    val binaryVirtualAddress: Long,
    val rva: Long,
    val runtimeVirtualAddress: Long,
    val executableMappingContainsAddress: Boolean,
) : Serializable

data class RuntimeEvidenceBundle(
    val artifactSha256: String,
    val procMapsSha256: String,
    val moduleMappings: List<RuntimeModuleMappingEvidence>,
    val addressConfirmations: List<RuntimeAddressConfirmation>,
    val blockers: List<String>,
    val moduleInventory: List<RuntimeMappedModule> = emptyList(),
    val memoryMappingCandidates: List<RuntimeMemoryMappingCandidate> = emptyList(),
    val memoryElfEvidence: List<RuntimeMemoryElfEvidence> = emptyList(),
    val captureSource: ProcMapsCaptureSource = ProcMapsCaptureSource.IMPORTED_SNAPSHOT,
    val capturePid: Int? = null,
    val capturedAtEpochMs: Long? = null,
    val processIdentity: String? = null,
    val processIdentityConfirmed: Boolean = false,
    val additionalObservations: List<RuntimeEvidenceObservation> = emptyList(),
) : Serializable

/**
 * Reconciles /proc/<pid>/maps with ELF PT_LOAD segments.
 *
 * A single filename match is not enough. Confirmation requires:
 * - one unique load-bias candidate,
 * - a zero-offset mapping,
 * - an executable PT_LOAD mapping,
 * - at least two independently matched PT_LOAD segments.
 */
object RuntimeModuleMappingResolver {
    private val candidatePageSizes = listOf(
        4L * 1024L,
        16L * 1024L,
        64L * 1024L,
    )

    fun resolve(
        moduleName: String,
        loadSegments: List<ElfLoadSegment>,
        regions: List<ProcMapRegion>,
    ): RuntimeModuleMappingEvidence {
        val moduleRegions = ProcMapsParser.matchingModule(regions, moduleName)
        val mappedPaths = moduleRegions.mapNotNull { it.path }.distinct()
        if (moduleRegions.isEmpty()) {
            return blocked(
                moduleName = moduleName,
                mappedPath = "",
                reason = "Module is absent from process maps.",
            )
        }
        if (mappedPaths.size != 1) {
            return blocked(
                moduleName = moduleName,
                mappedPath = mappedPaths.joinToString(),
                reason = "Module name resolves to multiple mapped paths.",
            )
        }
        val mappedFileIdentities = moduleRegions
            .map { Triple(it.device, it.inode, it.path) }
            .distinct()
        if (mappedFileIdentities.size != 1) {
            return blocked(
                moduleName = moduleName,
                mappedPath = mappedPaths.single(),
                reason = "Module maps belong to multiple device/inode identities.",
            )
        }
        if (loadSegments.isEmpty()) {
            return blocked(
                moduleName = moduleName,
                mappedPath = mappedPaths.single(),
                reason = "ELF has no PT_LOAD segments.",
            )
        }

        data class Candidate(
            val pageSize: Long,
            val loadBias: Long,
            val segmentIndexes: Set<Int>,
            val executableSegmentIndexes: Set<Int>,
            val zeroOffsetMatched: Boolean,
        )

        val candidates = mutableListOf<Candidate>()
        for (pageSize in candidatePageSizes) {
            val grouped = linkedMapOf<Long, MutableList<Pair<Int, ProcMapRegion>>>()
            loadSegments.forEachIndexed { segmentIndex, segment ->
                val segmentMapOffset = alignDown(segment.fileOffset, pageSize)
                val segmentMapVa = alignDown(segment.virtualAddress, pageSize)
                moduleRegions
                    .filter { region -> region.fileOffset == segmentMapOffset }
                    .forEach { region ->
                        val bias = region.start - segmentMapVa
                        grouped.getOrPut(bias) { mutableListOf() }
                            .add(segmentIndex to region)
                    }
            }

            grouped.forEach { (bias, matches) ->
                val indexes = matches.map { it.first }.toSet()
                val executableIndexes = matches
                    .filter { (index, region) ->
                        loadSegments[index].executable && region.executable
                    }
                    .map { it.first }
                    .toSet()
                val zeroOffsetMatched = matches.any { (index, region) ->
                    alignDown(loadSegments[index].fileOffset, pageSize) == 0L &&
                        region.fileOffset == 0L
                }
                candidates += Candidate(
                    pageSize = pageSize,
                    loadBias = bias,
                    segmentIndexes = indexes,
                    executableSegmentIndexes = executableIndexes,
                    zeroOffsetMatched = zeroOffsetMatched,
                )
            }
        }

        val strong = candidates.filter { candidate ->
            candidate.segmentIndexes.size >= 2 &&
                candidate.executableSegmentIndexes.isNotEmpty() &&
                candidate.zeroOffsetMatched
        }

        val uniqueBiases = strong.map { it.loadBias }.distinct()
        if (uniqueBiases.size != 1) {
            return RuntimeModuleMappingEvidence(
                moduleName = moduleName,
                mappedPath = mappedPaths.single(),
                loadBias = uniqueBiases.singleOrNull(),
                elfImageBaseVirtualAddress = loadSegments.minOfOrNull {
                    it.virtualAddress
                },
                pageSize = null,
                matchedLoadSegments = strong.maxOfOrNull {
                    it.segmentIndexes.size
                } ?: 0,
                matchedExecutableSegments = strong.maxOfOrNull {
                    it.executableSegmentIndexes.size
                } ?: 0,
                zeroOffsetMappingMatched = strong.any {
                    it.zeroOffsetMatched
                },
                confirmed = false,
                blockers = listOf(
                    if (uniqueBiases.isEmpty()) {
                        "Process maps do not provide enough independent PT_LOAD matches."
                    } else {
                        "Process maps produce multiple possible ELF load biases."
                    },
                ),
            )
        }

        val bias = uniqueBiases.single()
        val best = strong
            .filter { it.loadBias == bias }
            .maxWithOrNull(
                compareBy<Candidate> { it.segmentIndexes.size }
                    .thenBy { it.executableSegmentIndexes.size }
                    .thenByDescending { it.pageSize },
            )
            ?: error("Strong mapping candidate disappeared.")

        return RuntimeModuleMappingEvidence(
            moduleName = moduleName,
            mappedPath = mappedPaths.single(),
            loadBias = bias,
            elfImageBaseVirtualAddress = loadSegments.minOfOrNull {
                it.virtualAddress
            },
            pageSize = best.pageSize,
            matchedLoadSegments = best.segmentIndexes.size,
            matchedExecutableSegments = best.executableSegmentIndexes.size,
            zeroOffsetMappingMatched = best.zeroOffsetMatched,
            confirmed = true,
            blockers = emptyList(),
        )
    }

    private fun blocked(
        moduleName: String,
        mappedPath: String,
        reason: String,
    ) = RuntimeModuleMappingEvidence(
        moduleName = moduleName,
        mappedPath = mappedPath,
        loadBias = null,
        elfImageBaseVirtualAddress = null,
        pageSize = null,
        matchedLoadSegments = 0,
        matchedExecutableSegments = 0,
        zeroOffsetMappingMatched = false,
        confirmed = false,
        blockers = listOf(reason),
    )

    private fun alignDown(value: Long, alignment: Long): Long =
        value - Math.floorMod(value, alignment)
}

object RuntimeModuleEvidenceCollector {
    fun collect(
        artifactSha256: String,
        moduleFile: File,
        moduleName: String,
        procMapsText: String,
        cancellation: CancellationSignal,
        artifactEntries: List<ArtifactEntry> = emptyList(),
    ): RuntimeEvidenceBundle =
        collect(
            artifactSha256 = artifactSha256,
            moduleFile = moduleFile,
            moduleName = moduleName,
            capture = ProcMapsCaptureReader.imported(procMapsText),
            cancellation = cancellation,
            artifactEntries = artifactEntries,
        )

    fun collect(
        artifactSha256: String,
        moduleFile: File,
        moduleName: String,
        capture: ProcMapsCapture,
        cancellation: CancellationSignal,
        artifactEntries: List<ArtifactEntry> = emptyList(),
    ): RuntimeEvidenceBundle {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
        require(!capture.truncated) {
            "Truncated process maps cannot be used as exact runtime evidence."
        }
        val regions = ProcMapsParser.parse(capture.text)
        val mapping = ElfImage.open(moduleFile, cancellation).use { elf ->
            RuntimeModuleMappingResolver.resolve(
                moduleName = moduleName,
                loadSegments = elf.loadSegments,
                regions = regions,
            )
        }
        return RuntimeEvidenceBundle(
            artifactSha256 = artifactSha256,
            procMapsSha256 = capture.sha256,
            moduleMappings = listOf(mapping),
            addressConfirmations = emptyList(),
            blockers = mapping.blockers,
            moduleInventory = RuntimeModuleInventoryBuilder.build(
                regions = regions,
                artifactEntries = artifactEntries,
            ),
            memoryMappingCandidates = RuntimeMemoryMappingDetector.candidates(
                regions,
            ),
            captureSource = capture.source,
            capturePid = capture.pid,
            capturedAtEpochMs = capture.capturedAtEpochMs,
        )
    }
}

/**
 * Applies already-collected runtime module evidence to exact static targets.
 *
 * It upgrades only a target that already has EXACT_BINARY proof and whose
 * computed runtime address lies inside an executable mapping of the same module.
 * Runtime resolution never makes a target CHANGE_READY.
 */
object RuntimeEvidenceIntegrator {
    fun integrate(
        result: FastAnalysisResult,
        evidence: RuntimeEvidenceBundle,
        procMapsText: String,
    ): FastAnalysisResult {
        if (!artifactMatches(result, evidence)) {
            return artifactMismatch(result)
        }
        if (
            !evidence.procMapsSha256.equals(
                sha256(procMapsText.toByteArray(Charsets.UTF_8)),
                ignoreCase = true,
            )
        ) {
            return result.copy(
                engineWarnings = result.engineWarnings +
                    "runtime.evidence: proc maps snapshot mismatch",
            )
        }
        if (!hasConfirmedProcessIdentity(evidence)) {
            val blockedEvidence = withProcessIdentityBlocker(evidence)
            return result.copy(
                runtimeEvidence = blockedEvidence,
                engineWarnings = result.engineWarnings +
                    "runtime.evidence: process identity not independently confirmed",
            )
        }

        val graph = result.evidenceGraph
            ?: return result.copy(runtimeEvidence = evidence)
        val regions = ProcMapsParser.parse(procMapsText)
        val confirmations = mutableListOf<RuntimeAddressConfirmation>()

        val updatedTargets = graph.targets.map { target ->
            if (
                target.proofLevel != ProofLevel.EXACT_BINARY ||
                target.binaryVirtualAddress == null ||
                target.artifact.isNullOrBlank()
            ) {
                return@map target
            }

            val moduleName = target.artifact.substringAfterLast('/')
            val mapping = evidence.moduleMappings.singleOrNull {
                it.confirmed && it.moduleName == moduleName
            } ?: return@map target
            val loadBias = mapping.loadBias ?: return@map target
            val imageBase = mapping.elfImageBaseVirtualAddress ?: return@map target
            if (target.binaryVirtualAddress < imageBase) return@map target

            val runtimeVa = safeAdd(
                loadBias,
                target.binaryVirtualAddress,
            ) ?: return@map target
            val moduleRegions = ProcMapsParser.matchingModule(
                regions,
                moduleName,
            )
            val executableContains = moduleRegions.any {
                it.executable &&
                    runtimeVa >= it.start &&
                    runtimeVa < it.endExclusive
            }
            if (!executableContains) return@map target

            val rva = target.binaryVirtualAddress - imageBase
            confirmations += RuntimeAddressConfirmation(
                targetId = target.id,
                moduleName = moduleName,
                binaryVirtualAddress = target.binaryVirtualAddress,
                rva = rva,
                runtimeVirtualAddress = runtimeVa,
                executableMappingContainsAddress = true,
            )

            target.copy(
                rva = rva,
                runtimeVirtualAddress = runtimeVa,
                proofLevel = ProofLevel.RUNTIME_CONFIRMED,
                userStatus = UserFindingStatus.CONFIRMED,
                facts = target.facts + EvidenceFact(
                    engineId = "runtime.module-map",
                    kind = "runtime-address",
                    summary = moduleName +
                        " loadBias=0x" + loadBias.toString(16) +
                        " RVA=0x" + rva.toString(16) +
                        " runtimeVA=0x" + runtimeVa.toString(16),
                ),
            )
        }

        val integrated = evidence.copy(
            addressConfirmations =
                (evidence.addressConfirmations + confirmations)
                    .distinctBy { it.targetId },
        )
        return result.copy(
            evidenceGraph = EvidenceGraph(
                artifactSha256 = graph.artifactSha256,
                targets = updatedTargets,
            ),
            runtimeEvidence = integrated,
            confirmationQueue = removeSatisfiedRuntimeRequests(
                result = result,
                confirmations = integrated.addressConfirmations,
            ),
        )
    }

    /**
     * Restores historical runtime confirmation from the SHA-bound cache.
     *
     * No live-process claim is made here. A target is restored to
     * RUNTIME_CONFIRMED only when the persisted module mapping and address
     * confirmation are internally consistent with the freshly restored
     * EXACT_BINARY target. CHANGE_READY is never granted by this path.
     */
    fun restorePersistedSnapshot(
        result: FastAnalysisResult,
        evidence: RuntimeEvidenceBundle,
    ): FastAnalysisResult {
        if (!artifactMatches(result, evidence)) {
            return artifactMismatch(result)
        }
        if (!hasConfirmedProcessIdentity(evidence)) {
            val blockedEvidence = withProcessIdentityBlocker(evidence)
            return result.copy(
                runtimeEvidence = blockedEvidence,
                engineWarnings = result.engineWarnings +
                    "runtime.evidence: cached process identity is not independently confirmed",
            )
        }

        val graph = result.evidenceGraph
            ?: return result.copy(runtimeEvidence = evidence)

        val accepted = mutableListOf<RuntimeAddressConfirmation>()
        val updatedTargets = graph.targets.map { target ->
            if (
                target.proofLevel != ProofLevel.EXACT_BINARY ||
                target.binaryVirtualAddress == null ||
                target.artifact.isNullOrBlank()
            ) {
                return@map target
            }

            val confirmation = evidence.addressConfirmations.singleOrNull {
                it.targetId == target.id &&
                    it.binaryVirtualAddress == target.binaryVirtualAddress &&
                    it.executableMappingContainsAddress
            } ?: return@map target

            val moduleName = target.artifact.substringAfterLast('/')
            if (confirmation.moduleName != moduleName) return@map target

            val mapping = evidence.moduleMappings.singleOrNull {
                it.confirmed && it.moduleName == moduleName
            } ?: return@map target
            val loadBias = mapping.loadBias ?: return@map target
            val imageBase = mapping.elfImageBaseVirtualAddress ?: return@map target
            if (target.binaryVirtualAddress < imageBase) return@map target

            val expectedRva = target.binaryVirtualAddress - imageBase
            val expectedRuntimeVa = safeAdd(
                loadBias,
                target.binaryVirtualAddress,
            ) ?: return@map target
            if (
                confirmation.rva != expectedRva ||
                confirmation.runtimeVirtualAddress != expectedRuntimeVa
            ) {
                return@map target
            }

            accepted += confirmation
            target.copy(
                rva = expectedRva,
                runtimeVirtualAddress = expectedRuntimeVa,
                proofLevel = ProofLevel.RUNTIME_CONFIRMED,
                userStatus = UserFindingStatus.CONFIRMED,
                facts = target.facts + EvidenceFact(
                    engineId = "runtime.module-map",
                    kind = "runtime-address-restored-snapshot",
                    summary = moduleName +
                        " captureSha=" + evidence.procMapsSha256 +
                        " RVA=0x" + expectedRva.toString(16) +
                        " runtimeVA=0x" + expectedRuntimeVa.toString(16),
                ),
            )
        }

        return result.copy(
            evidenceGraph = EvidenceGraph(
                artifactSha256 = graph.artifactSha256,
                targets = updatedTargets,
            ),
            runtimeEvidence = evidence,
            confirmationQueue = removeSatisfiedRuntimeRequests(
                result = result,
                confirmations = accepted,
            ),
        )
    }

    private fun hasConfirmedProcessIdentity(
        evidence: RuntimeEvidenceBundle,
    ): Boolean =
        evidence.captureSource != ProcMapsCaptureSource.IMPORTED_SNAPSHOT &&
            evidence.capturePid != null &&
            !evidence.processIdentity.isNullOrBlank() &&
            evidence.processIdentityConfirmed

    private fun withProcessIdentityBlocker(
        evidence: RuntimeEvidenceBundle,
    ): RuntimeEvidenceBundle =
        evidence.copy(
            blockers = (
                evidence.blockers +
                    "Process identity is not independently confirmed for this runtime capture."
                ).distinct(),
            addressConfirmations = emptyList(),
        )

    private fun artifactMatches(
        result: FastAnalysisResult,
        evidence: RuntimeEvidenceBundle,
    ): Boolean =
        evidence.artifactSha256.equals(
            result.index.artifactSha256,
            ignoreCase = true,
        )

    private fun artifactMismatch(
        result: FastAnalysisResult,
    ): FastAnalysisResult =
        result.copy(
            engineWarnings = result.engineWarnings +
                "runtime.evidence: artifact SHA mismatch",
        )

    private fun removeSatisfiedRuntimeRequests(
        result: FastAnalysisResult,
        confirmations: List<RuntimeAddressConfirmation>,
    ) = result.confirmationQueue.filterNot { request ->
        confirmations.any { it.targetId == request.targetId } &&
            request.requiredProofLevel.ordinal <=
            ProofLevel.RUNTIME_CONFIRMED.ordinal
    }

    private fun safeAdd(a: Long, b: Long): Long? =
        runCatching { Math.addExact(a, b) }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
