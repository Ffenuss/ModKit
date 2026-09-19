package io.github.ffenuss.modkit.analysis

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ExpertLabReportWriter {
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
            appendLine("Format: 1")
            appendLine("Generated: " + formatter.format(Date()))
            appendLine("Target: " + label)
            appendLine("Artifact SHA-256: " + result.index.artifactSha256)
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
                    .ifBlank { "none" },
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
                appendLine("  artifact: " + (target.artifact ?: "null"))
                appendLine("  abi: " + (target.abi ?: "null"))
                appendLine(
                    "  token: " + target.metadataToken?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank { "null" },
                )
                appendLine(
                    "  rva: " + target.rva?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank { "null" },
                )
                appendLine(
                    "  binaryVA: " + target.binaryVirtualAddress?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank { "null" },
                )
                appendLine(
                    "  runtimeVA: " + target.runtimeVirtualAddress?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank { "null" },
                )
                appendLine(
                    "  fileOffset: " + target.fileOffset?.let {
                        "0x" + it.toString(16)
                    }.orEmpty().ifBlank { "null" },
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
                            item.codeRegistrationVirtualAddress?.let {
                                "0x" + it.toString(16)
                            },
                    )
                    appendLine(
                        "  metadataRegistrationVA: " +
                            item.metadataRegistrationVirtualAddress?.let {
                                "0x" + it.toString(16)
                            },
                    )
                    appendLine(
                        "  codegenRegisterVA: " +
                            item.codegenRegisterVirtualAddress?.let {
                                "0x" + it.toString(16)
                            },
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
                                } ?: ""),
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
                appendLine("procMapsSha256: " + runtime.procMapsSha256)
                runtime.moduleMappings.forEach { mapping ->
                    appendLine("- module: " + mapping.moduleName)
                    appendLine("  path: " + mapping.mappedPath)
                    appendLine("  confirmed: " + mapping.confirmed)
                    appendLine(
                        "  loadBias: " + mapping.loadBias?.let {
                            "0x" + it.toString(16)
                        },
                    )
                    appendLine(
                        "  imageBaseVA: " +
                            mapping.elfImageBaseVirtualAddress?.let {
                                "0x" + it.toString(16)
                            },
                    )
                    appendLine("  pageSize: " + mapping.pageSize)
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
}
