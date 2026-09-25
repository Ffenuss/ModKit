package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.ProofLevel

enum class EvidenceTargetKind {
    ANALYSIS_SCOPE,
    METHOD,
    FIELD,
    CLASS,
    NATIVE_FUNCTION,
    RESOURCE,
}

enum class UserFindingStatus {
    FOUND,
    CONFIRMING,
    CONFIRMED,
    READY,
    RUNTIME_REQUIRED,
    COULD_NOT_CONFIRM,
}

data class EvidenceFact(
    val engineId: String,
    val kind: String,
    val summary: String,
)

data class EvidenceTarget(
    val id: String,
    val runtimeId: String,
    val kind: EvidenceTargetKind,
    val displayName: String,
    val artifact: String?,
    val abi: String?,
    val declaringType: String?,
    val memberName: String?,
    val metadataToken: Long?,
    val rva: Long?,
    val binaryVirtualAddress: Long?,
    val runtimeVirtualAddress: Long?,
    val fileOffset: Long?,
    val proofLevel: ProofLevel,
    val userStatus: UserFindingStatus,
    val blockers: List<EvidenceBlocker>,
    val facts: List<EvidenceFact>,
)

data class EvidenceGraphSummary(
    val found: Int,
    val confirming: Int,
    val confirmed: Int,
    val ready: Int,
    val runtimeRequired: Int,
    val couldNotConfirm: Int,
)

data class EvidenceGraph(
    val artifactSha256: String,
    val targets: List<EvidenceTarget>,
) {
    val summary: EvidenceGraphSummary
        get() = EvidenceGraphSummary(
            found = targets.count { it.userStatus == UserFindingStatus.FOUND },
            confirming = targets.count { it.userStatus == UserFindingStatus.CONFIRMING },
            confirmed = targets.count { it.userStatus == UserFindingStatus.CONFIRMED },
            ready = targets.count { it.userStatus == UserFindingStatus.READY },
            runtimeRequired = targets.count { it.userStatus == UserFindingStatus.RUNTIME_REQUIRED },
            couldNotConfirm = targets.count { it.userStatus == UserFindingStatus.COULD_NOT_CONFIRM },
        )
}

data class ConfirmationRequest(
    val targetId: String,
    val engineId: String,
    val requiredProofLevel: ProofLevel,
    val reason: String,
    val availableNow: Boolean,
)

data class EvidenceBuildResult(
    val graph: EvidenceGraph,
    val confirmationQueue: List<ConfirmationRequest>,
)

object EvidenceGraphBuilder {
    private const val IL2CPP_RUNTIME = "unity_il2cpp"
    private const val IL2CPP_SCOPE_ID = "il2cpp:executable-binding"

    fun build(result: FastAnalysisResult): EvidenceBuildResult {
        val dump = result.il2cppFastDump
        val genericTargets = runtimeDiscoveryTargets(
            result = result,
            includeIl2Cpp = dump == null,
        )
        if (dump == null) {
            return EvidenceBuildResult(
                graph = EvidenceGraph(
                    result.index.artifactSha256,
                    genericTargets,
                ),
                confirmationQueue = emptyList(),
            )
        }

        val evidence = result.il2cppEvidence ?: EvidenceGate.evaluate(
            artifactSha256 = result.index.artifactSha256,
            metadataIdentityExact = dump.metadata.magicValid &&
                dump.metadata.structuredSupported &&
                !dump.metadata.truncated,
            binaryIdentityExact = false,
            runtimeConfirmed = false,
            mutationValidated = false,
            requestedChangeReady = false,
        )
        val binary = result.il2cppBinaryBinding
        val binaryItems = binary?.evidence.orEmpty()
        val exactBindingCount =
            binaryItems.sumOf { it.bindingIndex?.boundCount ?: it.bindings.size }

        if (exactBindingCount > 0) {
            val targets =
                ArrayList<EvidenceTarget>(
                    genericTargets.size +
                        exactBindingCount,
                )
            targets.addAll(genericTargets)
            binaryItems.forEach { binaryItem ->
                binaryItem.bindings.forEach { binding ->
                    targets += methodTarget(
                        binding = binding,
                        libraryEntry = binaryItem.libraryEntry,
                        evidence = evidence,
                    )
                }
                // The persistent index contains all exact MethodDefs, even
                // after the 30k in-memory UI window. Materialize only
                // semantically relevant late targets; every other token
                // remains accessible through on-demand verified lookup.
                Il2CppOnDemandBindings.lateGameplayBindings(
                    dump.metadata, binaryItem,
                ).forEach { binding ->
                    targets += methodTarget(
                        binding = binding,
                        libraryEntry = binaryItem.libraryEntry,
                        evidence = evidence,
                    )
                }
            }
            return EvidenceBuildResult(
                graph = EvidenceGraph(
                    artifactSha256 =
                        result.index.artifactSha256,
                    targets =
                        targets.distinctBy {
                            it.id
                        },
                ),
                confirmationQueue = emptyList(),
            )
        }

        val binaryAttempted = binary != null
        val status = when {
            evidence.proofLevel == ProofLevel.CHANGE_READY -> UserFindingStatus.READY
            evidence.proofLevel == ProofLevel.RUNTIME_CONFIRMED -> UserFindingStatus.CONFIRMED
            binaryAttempted -> UserFindingStatus.RUNTIME_REQUIRED
            evidence.proofLevel == ProofLevel.EXACT_METADATA -> UserFindingStatus.CONFIRMING
            else -> UserFindingStatus.FOUND
        }
        val scope = EvidenceTarget(
            id = IL2CPP_SCOPE_ID,
            runtimeId = IL2CPP_RUNTIME,
            kind = EvidenceTargetKind.ANALYSIS_SCOPE,
            displayName = "IL2CPP executable binding",
            artifact = dump.libraryEntries.singleOrNull(),
            abi = dump.libraryEntries.mapNotNull(::abiFromPath).distinct().singleOrNull(),
            declaringType = null,
            memberName = null,
            metadataToken = null,
            rva = null,
            binaryVirtualAddress = null,
            runtimeVirtualAddress = null,
            fileOffset = null,
            proofLevel = evidence.proofLevel,
            userStatus = status,
            blockers = evidence.blockers,
            facts = buildList {
                add(
                    EvidenceFact(
                        engineId = "il2cpp.fast-dump",
                        kind = "metadata",
                        summary = "metadata v" + (dump.metadata.metadataVersion ?: "?") +
                            ", methods=" + dump.metadata.methods.size,
                    ),
                )
                binary?.evidence.orEmpty().forEach { item ->
                    add(
                        EvidenceFact(
                            engineId = "il2cpp.codegen-bind",
                            kind = "binary-confirmation",
                            summary = item.libraryEntry +
                                ": modules=" + item.modules.size +
                                ", bindings=" + item.bindings.size,
                        ),
                    )
                }
            },
        )

        val queue = when {
            !binaryAttempted && evidence.proofLevel == ProofLevel.EXACT_METADATA ->
                listOf(
                    ConfirmationRequest(
                        targetId = scope.id,
                        engineId = "il2cpp.codegen-bind",
                        requiredProofLevel = ProofLevel.EXACT_BINARY,
                        reason = scope.blockers.firstOrNull()?.message
                            ?: "Exact executable binding still needs confirmation.",
                        availableNow = true,
                    ),
                )
            binaryAttempted && binary?.exactBindingAvailable == false ->
                listOf(
                    ConfirmationRequest(
                        targetId = scope.id,
                        engineId = "runtime.il2cpp-confirm",
                        requiredProofLevel = ProofLevel.RUNTIME_CONFIRMED,
                        reason = scope.blockers.firstOrNull()?.message
                            ?: "Static executable binding was not proven.",
                        availableNow = false,
                    ),
                )
            else -> emptyList()
        }

        return EvidenceBuildResult(
            graph = EvidenceGraph(
                result.index.artifactSha256,
                (genericTargets + scope).distinctBy { it.id },
            ),
            confirmationQueue = queue,
        )
    }

    private fun runtimeDiscoveryTargets(
        result: FastAnalysisResult,
        includeIl2Cpp: Boolean,
    ): List<EvidenceTarget> =
        result.index.runtimeProfiles
            .asSequence()
            .filter { includeIl2Cpp || it.runtimeId != IL2CPP_RUNTIME }
            .map { runtime ->
                EvidenceTarget(
                    id = "runtime:" + runtime.runtimeId,
                    runtimeId = runtime.runtimeId,
                    kind = EvidenceTargetKind.ANALYSIS_SCOPE,
                    displayName = runtime.title,
                    artifact = null,
                    abi = result.index.detectedAbis.singleOrNull(),
                    declaringType = null,
                    memberName = null,
                    metadataToken = null,
                    rva = null,
                    binaryVirtualAddress = null,
                    runtimeVirtualAddress = null,
                    fileOffset = null,
                    proofLevel = ProofLevel.DISCOVERED,
                    userStatus = UserFindingStatus.FOUND,
                    blockers = listOf(
                        EvidenceBlocker(
                            code = "DEEP_EVIDENCE_NOT_AVAILABLE",
                            message = "Runtime обнаружен, но точная цель метода, поля или функции ещё не подтверждена.",
                            requiredFor = ProofLevel.STRUCTURAL,
                        ),
                    ),
                    facts = runtime.evidence.map { item ->
                        EvidenceFact(
                            engineId = "artifact.fast-index",
                            kind = "runtime-signal",
                            summary = item,
                        )
                    },
                )
            }
            .toList()

    private fun methodTarget(
        binding: Il2CppMethodBinaryBinding,
        libraryEntry: String,
        evidence: ExecutableBindingEvidence,
    ): EvidenceTarget {
        val status = when (evidence.proofLevel) {
            ProofLevel.CHANGE_READY -> UserFindingStatus.READY
            ProofLevel.RUNTIME_CONFIRMED,
            ProofLevel.EXACT_BINARY -> UserFindingStatus.CONFIRMED
            ProofLevel.EXACT_METADATA -> UserFindingStatus.CONFIRMING
            else -> UserFindingStatus.FOUND
        }
        val id = buildString {
            append("il2cpp:method:")
            append(binding.imageName)
            append(':')
            append(binding.metadataToken.toString(16))
            append(':')
            append(binding.moduleName)
        }
        return EvidenceTarget(
            id = id,
            runtimeId = IL2CPP_RUNTIME,
            kind = EvidenceTargetKind.METHOD,
            displayName = binding.managedIdentity,
            artifact = libraryEntry,
            abi = abiFromPath(libraryEntry),
            declaringType = binding.managedIdentity.substringBeforeLast('.', ""),
            memberName = binding.managedIdentity.substringAfterLast('.'),
            metadataToken = binding.metadataToken,
            rva = null,
            binaryVirtualAddress = binding.functionVirtualAddress,
            runtimeVirtualAddress = null,
            fileOffset = binding.functionFileOffset,
            proofLevel = evidence.proofLevel,
            userStatus = status,
            blockers = evidence.blockers,
            facts = listOf(
                EvidenceFact(
                    engineId = "il2cpp.fast-dump",
                    kind = "metadata-token",
                    summary = "MethodDef token 0x" + binding.metadataToken.toString(16),
                ),
                EvidenceFact(
                    engineId = "il2cpp.codegen-bind",
                    kind = "executable-binding",
                    summary = binding.moduleName +
                        " slot " + binding.slotIndex +
                        " → binary VA 0x" + binding.functionVirtualAddress.toString(16),
                ),
            ),
        )
    }

    private fun abiFromPath(value: String): String? =
        listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            .firstOrNull { abi -> "/$abi/" in value.lowercase() }
}

fun FastAnalysisResult.withEvidenceGraph(): FastAnalysisResult {
    val built = EvidenceGraphBuilder.build(this)
    return copy(
        evidenceGraph = built.graph,
        confirmationQueue = built.confirmationQueue,
    )
}
