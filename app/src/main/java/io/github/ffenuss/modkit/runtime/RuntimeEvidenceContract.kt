package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.domain.ProofLevel
import java.io.Serializable

/**
 * Typed runtime-evidence contract.
 *
 * Runtime observations describe what was actually observed. They do not by
 * themselves grant CHANGE_READY and must not be used to skip static mutation
 * preflight. Only the dedicated EvidenceGraph integrator may promote an exact
 * binary target to RUNTIME_CONFIRMED after all consistency checks pass.
 */
enum class RuntimeEvidenceObservationKind {
    PROCESS_OBSERVED,
    MODULE_OBSERVED,
    MODULE_MAPPING_CONFIRMED,
    RUNTIME_ADDRESS_CONFIRMED,
    METHOD_EXECUTION_CONFIRMED,
    FIELD_VALUE_OBSERVED,
    JNI_DLSYM_OBSERVED,
    JNI_REGISTER_NATIVE_OBSERVED,
    JNI_ON_LOAD_INVOCATION_OBSERVED,
    MEMORY_ELF_VALIDATED,
}

enum class RuntimeEvidenceObservationStrength {
    OBSERVED,
    CONFIRMED,
}

data class RuntimeEvidenceObservation(
    val id: String,
    val kind: RuntimeEvidenceObservationKind,
    val strength: RuntimeEvidenceObservationStrength,
    val subjectId: String?,
    val artifactSha256: String,
    val captureSha256: String?,
    val captureSource: ProcMapsCaptureSource,
    val capturedAtEpochMs: Long?,
    val summary: String,
    val supportingFacts: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val proofLevel: ProofLevel? = null,
) : Serializable {
    val independentlyConfirmed: Boolean
        get() =
            strength == RuntimeEvidenceObservationStrength.CONFIRMED &&
                blockers.isEmpty()
}

/**
 * Projects the current specialized runtime evidence into a stable contract.
 *
 * Imported snapshots deliberately remain OBSERVED at process level because
 * ModKit did not capture the process identity itself. Module mapping and
 * runtime-address facts are emitted as CONFIRMED only when their own
 * independent proof conditions have already passed.
 */
object RuntimeEvidenceContract {
    fun observations(
        bundle: RuntimeEvidenceBundle,
    ): List<RuntimeEvidenceObservation> {
        val projected = mutableListOf<RuntimeEvidenceObservation>()

        val processIdentityConfirmed =
            bundle.captureSource != ProcMapsCaptureSource.IMPORTED_SNAPSHOT &&
                bundle.capturePid != null &&
                !bundle.processIdentity.isNullOrBlank() &&
                bundle.processIdentityConfirmed

        projected += RuntimeEvidenceObservation(
            id = "runtime:process:" +
                bundle.captureSource.name.lowercase() + ":" +
                (bundle.capturePid?.toString() ?: "unknown") + ":" +
                bundle.procMapsSha256.take(16),
            kind = RuntimeEvidenceObservationKind.PROCESS_OBSERVED,
            strength = if (processIdentityConfirmed) {
                RuntimeEvidenceObservationStrength.CONFIRMED
            } else {
                RuntimeEvidenceObservationStrength.OBSERVED
            },
            subjectId = bundle.capturePid?.let { "pid:$it" },
            artifactSha256 = bundle.artifactSha256,
            captureSha256 = bundle.procMapsSha256,
            captureSource = bundle.captureSource,
            capturedAtEpochMs = bundle.capturedAtEpochMs,
            summary = when {
                bundle.captureSource == ProcMapsCaptureSource.IMPORTED_SNAPSHOT ->
                    "Imported process-maps snapshot observed; process identity was not captured by ModKit."
                processIdentityConfirmed ->
                    "Process identity and process-maps snapshot were independently confirmed by ModKit."
                else ->
                    "Process maps were captured by ModKit, but process identity was not independently confirmed."
            },
            supportingFacts = buildList {
                add("procMapsSha256=" + bundle.procMapsSha256)
                bundle.capturePid?.let { add("pid=$it") }
                bundle.processIdentity?.let { add("processIdentity=$it") }
            },
            blockers = when {
                processIdentityConfirmed -> emptyList()
                bundle.captureSource == ProcMapsCaptureSource.IMPORTED_SNAPSHOT ->
                    listOf(
                        "Process identity is externally supplied; live-process identity is not independently confirmed.",
                    )
                else ->
                    listOf(
                        "Direct process-map capture alone does not prove that the PID belongs to the requested target.",
                    )
            },
        )

        bundle.moduleInventory.forEach { module ->
            projected += RuntimeEvidenceObservation(
                id = "runtime:module-observed:" +
                    module.device + ":" + module.inode + ":" + module.path,
                kind = RuntimeEvidenceObservationKind.MODULE_OBSERVED,
                strength = RuntimeEvidenceObservationStrength.OBSERVED,
                subjectId = module.path,
                artifactSha256 = bundle.artifactSha256,
                captureSha256 = bundle.procMapsSha256,
                captureSource = bundle.captureSource,
                capturedAtEpochMs = bundle.capturedAtEpochMs,
                summary = "Module is present in the captured process map.",
                supportingFacts = buildList {
                    add("device=" + module.device)
                    add("inode=" + module.inode)
                    add("regions=" + module.regionCount)
                    add("executableRegions=" + module.executableRegionCount)
                    if (module.staticArtifactMatches.isNotEmpty()) {
                        add(
                            "staticArtifactMatches=" +
                                module.staticArtifactMatches.joinToString(),
                        )
                    }
                },
            )
        }

        bundle.moduleMappings
            .filter { it.confirmed }
            .forEach { mapping ->
                projected += RuntimeEvidenceObservation(
                    id = "runtime:module-mapping:" +
                        mapping.moduleName + ":" + bundle.procMapsSha256.take(16),
                    kind = RuntimeEvidenceObservationKind.MODULE_MAPPING_CONFIRMED,
                    strength = RuntimeEvidenceObservationStrength.CONFIRMED,
                    subjectId = mapping.moduleName,
                    artifactSha256 = bundle.artifactSha256,
                    captureSha256 = bundle.procMapsSha256,
                    captureSource = bundle.captureSource,
                    capturedAtEpochMs = bundle.capturedAtEpochMs,
                    summary = "ELF PT_LOAD layout is reconciled with the captured process mapping.",
                    supportingFacts = buildList {
                        mapping.loadBias?.let {
                            add("loadBias=0x" + it.toString(16))
                        }
                        mapping.elfImageBaseVirtualAddress?.let {
                            add("imageBaseVA=0x" + it.toString(16))
                        }
                        mapping.pageSize?.let { add("pageSize=$it") }
                        add("matchedLoadSegments=" + mapping.matchedLoadSegments)
                        add(
                            "matchedExecutableSegments=" +
                                mapping.matchedExecutableSegments,
                        )
                        add(
                            "zeroOffsetMappingMatched=" +
                                mapping.zeroOffsetMappingMatched,
                        )
                    },
                )
            }

        bundle.addressConfirmations
            .filter { it.executableMappingContainsAddress }
            .forEach { address ->
                projected += RuntimeEvidenceObservation(
                    id = "runtime:address:" + address.targetId,
                    kind = RuntimeEvidenceObservationKind.RUNTIME_ADDRESS_CONFIRMED,
                    strength = RuntimeEvidenceObservationStrength.CONFIRMED,
                    subjectId = address.targetId,
                    artifactSha256 = bundle.artifactSha256,
                    captureSha256 = bundle.procMapsSha256,
                    captureSource = bundle.captureSource,
                    capturedAtEpochMs = bundle.capturedAtEpochMs,
                    summary = "Exact binary target resolves into an executable runtime mapping.",
                    supportingFacts = listOf(
                        "module=" + address.moduleName,
                        "binaryVA=0x" + address.binaryVirtualAddress.toString(16),
                        "rva=0x" + address.rva.toString(16),
                        "runtimeVA=0x" + address.runtimeVirtualAddress.toString(16),
                    ),
                    proofLevel = ProofLevel.RUNTIME_CONFIRMED,
                )
            }

        bundle.memoryElfEvidence
            .filter { it.validated }
            .forEach { memoryElf ->
                projected += RuntimeEvidenceObservation(
                    id = "runtime:memory-elf:" +
                        memoryElf.headerAddress.toString(16),
                    kind = RuntimeEvidenceObservationKind.MEMORY_ELF_VALIDATED,
                    strength = RuntimeEvidenceObservationStrength.CONFIRMED,
                    subjectId = memoryElf.candidatePath,
                    artifactSha256 = bundle.artifactSha256,
                    captureSha256 = bundle.procMapsSha256,
                    captureSource = bundle.captureSource,
                    capturedAtEpochMs = bundle.capturedAtEpochMs,
                    summary =
                        "Executable special mapping contains a structurally validated in-memory ELF image.",
                    supportingFacts = buildList {
                        add(
                            "headerAddress=0x" +
                                memoryElf.headerAddress.toString(16),
                        )
                        memoryElf.machine?.let { add("machine=$it") }
                        memoryElf.elfType?.let { add("elfType=$it") }
                        memoryElf.programHeaderCount?.let {
                            add("programHeaders=$it")
                        }
                        add("loadSegments=" + memoryElf.loadSegmentCount)
                        add(
                            "executableLoadSegments=" +
                                memoryElf.executableLoadSegmentCount,
                        )
                    },
                )
            }

        projected += bundle.additionalObservations

        return projected.distinctBy { it.id }
    }
}
