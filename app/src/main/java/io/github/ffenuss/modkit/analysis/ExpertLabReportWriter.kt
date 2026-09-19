package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.runtime.RuntimeEvidenceContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ExpertLabReportWriter {
    private const val REPORT_SCHEMA_VERSION = 2
    private const val REPORT_ENGINE_VERSION = "expert-lab-report/2"

    fun write(
        outputDir: File,
        label: String,
        result: FastAnalysisResult,
    ): File {
        outputDir.mkdirs()
        val report = File(outputDir, "ModKit-Expert-Lab-report.txt")
        val formatter = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.US,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val capability = ExpertCapabilityValidator.validate(result.routingPlan)
        val content = buildString {
            appendLine("ModKit Expert Lab technical report")
            appendLine("schemaVersion: " + REPORT_SCHEMA_VERSION)
            appendLine("engineVersion: " + REPORT_ENGINE_VERSION)
            appendLine("Generated: " + formatter.format(Date()))
            appendLine("Target: " + label)
            appendLine("Artifact SHA-256: " + result.index.artifactSha256)
            appendLine(
                "Input relationships: " +
                    result.index.sources.joinToString { source ->
                        source.displayName + "@" + source.sha256.take(12)
                    }.ifBlank { "not_available" },
            )
            appendLine()

            appendLine("SOURCES")
            result.index.sources.forEach { source ->
                appendLine("- " + source.displayName)
                appendLine("  size: " + source.size)
                appendLine("  SHA-256: " + source.sha256)
            }
            appendLine()

            appendLine("FAST INDEX")
            appendLine("entries: " + result.index.entries.size)
            appendLine("truncated: " + result.index.truncated)
            appendLine(
                "ABIs: " + result.index.detectedAbis.sorted().joinToString()
                    .ifBlank { "none_detected" },
            )
            result.index.warnings.forEach {
                appendLine("warning: " + it)
            }
            appendLine()

            appendLine("RUNTIME PROFILER")
            result.index.runtimeProfiles.forEach { runtime ->
                appendLine(
                    "- " + runtime.runtimeId +
                        " [" + runtime.status.name + "/" +
                        runtime.confidence.name + "] " + runtime.title,
                )
                runtime.evidence.forEach {
                    appendLine("  evidence: " + it)
                }
            }
            appendLine()

            appendLine("BACKEND ROUTING")
            result.routingPlan.engines.forEach { engine ->
                val row = capability.capabilities.singleOrNull {
                    it.engineId == engine.id
                }
                appendLine("- " + engine.id)
                appendLine("  class: " + engine.scheduleClass.name)
                appendLine("  routerAvailable: " + engine.availableNow)
                appendLine("  executorRegistered: " + (row?.executorRegistered ?: false))
                appendLine("  consistent: " + (row?.consistent ?: false))
                appendLine("  reason: " + engine.reason)
            }
            result.routingPlan.missingCapabilities.forEach {
                appendLine("missing: " + it)
            }
            capability.blockers.forEach {
                appendLine("capability-blocker: " + it)
            }
            appendLine()

            appendLine("EVIDENCE GRAPH")
            result.evidenceGraph?.targets.orEmpty().forEach { target ->
                appendLine("- " + target.id)
                appendLine("  runtime: " + target.runtimeId)
                appendLine("  kind: " + target.kind.name)
                appendLine("  display: " + target.displayName)
                appendLine("  proof: " + target.proofLevel.name)
                appendLine("  status: " + target.userStatus.name)
                appendLine(
                    "  artifact: " +
                        (target.artifact ?: "not_applicable_or_not_resolved"),
                )
                appendLine(
                    "  abi: " +
                        (target.abi ?: "not_applicable_or_not_resolved"),
                )
                appendLine(
                    "  token: " + target.metadataToken?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank { "not_applicable_or_not_resolved" },
                )
                appendLine(
                    "  rva: " + target.rva?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank { "not_resolved" },
                )
                appendLine(
                    "  binaryVA: " + target.binaryVirtualAddress?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank { "not_resolved" },
                )
                appendLine(
                    "  runtimeVA: " + target.runtimeVirtualAddress?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank {
                        if (target.proofLevel.ordinal <
                            io.github.ffenuss.modkit.domain.ProofLevel.RUNTIME_CONFIRMED.ordinal
                        ) {
                            "runtime_not_confirmed"
                        } else {
                            "not_resolved"
                        }
                    },
                )
                appendLine(
                    "  fileOffset: " + target.fileOffset?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank { "not_resolved" },
                )
                target.facts.forEach { fact ->
                    appendLine(
                        "  fact: " + fact.engineId + " / " +
                            fact.kind + " / " + fact.summary,
                    )
                }
                target.blockers.forEach { blocker ->
                    appendLine(
                        "  blocker: " + blocker.code + " / " +
                            blocker.requiredFor.name + " / " +
                            blocker.message,
                    )
                }
            }
            appendLine()

            appendLine("CONFIRMATION QUEUE")
            result.confirmationQueue.forEach { request ->
                appendLine("- target: " + request.targetId)
                appendLine("  engine: " + request.engineId)
                appendLine("  requiredProof: " + request.requiredProofLevel.name)
                appendLine("  availableNow: " + request.availableNow)
                appendLine("  reason: " + request.reason)
            }
            appendLine()

            result.il2cppFastDump?.let { dump ->
                appendLine("IL2CPP FAST DUMP")
                appendLine("metadataEntry: " + dump.metadataEntry)
                appendLine("libraryEntries:")
                dump.libraryEntries.forEach { appendLine("- " + it) }
                appendLine("metadataVersion: " + dump.metadata.metadataVersion)
                appendLine("layoutProfile: " + dump.metadata.layoutProfile)
                appendLine("types: " + dump.metadata.types.size)
                appendLine("methods: " + dump.metadata.methods.size)
                appendLine("fields: " + dump.metadata.fields.size)
                appendLine("images: " + dump.metadata.images.size)
                appendLine("dumpFile: " + dump.dumpFilePath)
                dump.warnings.forEach { appendLine("warning: " + it) }
                appendLine()
            }

            result.il2cppBinaryBinding?.let { binding ->
                appendLine("IL2CPP BINARY BINDING")
                appendLine("exactBindingCount: " + binding.exactBindingCount)
                binding.evidence.forEach { item ->
                    appendLine("- library: " + item.libraryEntry)
                    appendLine("  machine: " + item.machine)
                    appendLine("  pointerSize: " + item.pointerSize)
                    appendLine(
                        "  codeRegistrationVA: " +
                            hexOrReason(
                                item.codeRegistrationVirtualAddress,
                                "not_resolved",
                            ),
                    )
                    appendLine(
                        "  metadataRegistrationVA: " +
                            hexOrReason(
                                item.metadataRegistrationVirtualAddress,
                                "not_resolved",
                            ),
                    )
                    appendLine(
                        "  codegenRegisterVA: " +
                            hexOrReason(
                                item.codegenRegisterVirtualAddress,
                                "not_resolved",
                            ),
                    )
                    appendLine("  modules: " + item.modules.size)
                    appendLine("  bindings: " + item.bindings.size)
                    item.bindings.forEach { method ->
                        appendLine(
                            "  binding: " + method.managedIdentity +
                                " token=0x" + method.metadataToken.toString(16) +
                                " VA=0x" + method.functionVirtualAddress.toString(16) +
                                (method.functionFileOffset?.let {
                                    " file+0x" + it.toString(16)
                                } ?: " fileOffset=not_resolved"),
                        )
                    }
                    item.blockers.forEach {
                        appendLine("  blocker: " + it)
                    }
                }
                binding.warnings.forEach { appendLine("warning: " + it) }
                appendLine()
            }

            result.runtimeEvidence?.let { runtime ->
                appendLine("RUNTIME EVIDENCE")
                appendLine("engineVersion: runtime.evidence/1")
                appendLine("artifactSha256: " + runtime.artifactSha256)
                appendLine("procMapsSha256: " + runtime.procMapsSha256)
                appendLine("captureSource: " + runtime.captureSource.name)
                appendLine(
                    "capturePid: " +
                        (runtime.capturePid?.toString()
                            ?: if (
                                runtime.captureSource ==
                                io.github.ffenuss.modkit.runtime.ProcMapsCaptureSource.IMPORTED_SNAPSHOT
                            ) {
                                "not_available_imported_snapshot"
                            } else {
                                "not_recorded"
                            }),
                )
                appendLine(
                    "capturedAtEpochMs: " +
                        (runtime.capturedAtEpochMs?.toString() ?: "not_recorded"),
                )
                runtime.moduleMappings.forEach { mapping ->
                    val firstBlocker = mapping.blockers.firstOrNull()
                        ?: "not_resolved"
                    appendLine("- module: " + mapping.moduleName)
                    appendLine(
                        "  path: " +
                            mapping.mappedPath.ifBlank { "not_resolved" },
                    )
                    appendLine("  confirmed: " + mapping.confirmed)
                    appendLine(
                        "  loadBias: " +
                            hexOrReason(mapping.loadBias, firstBlocker),
                    )
                    appendLine(
                        "  imageBaseVA: " +
                            hexOrReason(
                                mapping.elfImageBaseVirtualAddress,
                                firstBlocker,
                            ),
                    )
                    appendLine(
                        "  pageSize: " +
                            (mapping.pageSize?.toString() ?: firstBlocker),
                    )
                    appendLine(
                        "  matchedLoadSegments: " +
                            mapping.matchedLoadSegments,
                    )
                    appendLine(
                        "  matchedExecutableSegments: " +
                            mapping.matchedExecutableSegments,
                    )
                    appendLine(
                        "  zeroOffsetMappingMatched: " +
                            mapping.zeroOffsetMappingMatched,
                    )
                    mapping.blockers.forEach {
                        appendLine("  blocker: " + it)
                    }
                }
                runtime.addressConfirmations.forEach { address ->
                    appendLine("- runtime-address: " + address.targetId)
                    appendLine("  module: " + address.moduleName)
                    appendLine(
                        "  binaryVA: 0x" +
                            address.binaryVirtualAddress.toString(16),
                    )
                    appendLine("  rva: 0x" + address.rva.toString(16))
                    appendLine(
                        "  runtimeVA: 0x" +
                            address.runtimeVirtualAddress.toString(16),
                    )
                    appendLine(
                        "  executableMappingContainsAddress: " +
                            address.executableMappingContainsAddress,
                    )
                }

                appendLine("RUNTIME EVIDENCE CONTRACT")
                RuntimeEvidenceContract.observations(runtime).forEach { observation ->
                    appendLine("- id: " + observation.id)
                    appendLine("  kind: " + observation.kind.name)
                    appendLine("  strength: " + observation.strength.name)
                    appendLine(
                        "  independentlyConfirmed: " +
                            observation.independentlyConfirmed,
                    )
                    appendLine(
                        "  subject: " +
                            (observation.subjectId ?: "not_applicable"),
                    )
                    appendLine(
                        "  proofLevel: " +
                            (observation.proofLevel?.name ?: "not_applicable"),
                    )
                    appendLine("  summary: " + observation.summary)
                    observation.supportingFacts.forEach {
                        appendLine("  fact: " + it)
                    }
                    observation.blockers.forEach {
                        appendLine("  blocker: " + it)
                    }
                }

                appendLine("RUNTIME MODULE INVENTORY")
                runtime.moduleInventory.forEach { module ->
                    appendLine("- " + module.path)
                    appendLine("  fileName: " + module.fileName)
                    appendLine("  device: " + module.device)
                    appendLine("  inode: " + module.inode)
                    appendLine("  regions: " + module.regionCount)
                    appendLine("  executableRegions: " + module.executableRegionCount)
                    appendLine(
                        "  staticArtifactMatch: " +
                            module.presentInStaticArtifact,
                    )
                    module.staticArtifactMatches.forEach {
                        appendLine("  static: " + it)
                    }
                }
                appendLine("MEMORY-BACKED EXECUTABLE CANDIDATES")
                runtime.memoryMappingCandidates.forEach { candidate ->
                    appendLine(
                        "- 0x" + candidate.start.toString(16) +
                            "-0x" + candidate.endExclusive.toString(16),
                    )
                    appendLine("  permissions: " + candidate.permissions)
                    appendLine(
                        "  path: " +
                            (candidate.path ?: "anonymous_or_special_mapping"),
                    )
                    appendLine("  reason: " + candidate.reason)
                    appendLine(
                        "  status: candidate_only_until_memory_ELF_header_validation",
                    )
                }
                runtime.blockers.forEach {
                    appendLine("runtime-blocker: " + it)
                }
                appendLine()
            }

            appendLine("ENGINE DIAGNOSTICS")
            result.engineCacheHits.sorted().forEach {
                appendLine("cache-hit: " + it)
            }
            result.engineWarnings.forEach {
                appendLine("warning: " + it)
            }
        }

        val temp = File(outputDir, report.name + ".tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (report.exists()) report.delete()
        check(temp.renameTo(report)) {
            temp.delete()
            "Could not finalize Expert Lab report."
        }
        return report
    }

    private fun hexOrReason(
        value: Long?,
        reason: String,
    ): String =
        value?.let { "0x" + it.toString(16) } ?: reason
}
