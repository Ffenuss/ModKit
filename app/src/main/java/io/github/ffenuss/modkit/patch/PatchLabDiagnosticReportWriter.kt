package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PatchLabDiagnosticReportWriter {
    private const val SCHEMA_VERSION = 1
    private const val ENGINE_VERSION =
        "patch-lab-diagnostic/1"
    private const val COPY_BUFFER_BYTES =
        128 * 1024

    fun write(
        outputDir: File,
        label: String,
        result: FastAnalysisResult,
        preparation: PatchPreparationPlan?,
    ): File {
        outputDir.mkdirs()
        val output =
            File(
                outputDir,
                "ModKit-PatchLab-report-" +
                    result.index.artifactSha256.take(12) +
                    ".zip",
            )
        if (output.exists()) {
            require(output.delete()) {
                "Не удалось заменить предыдущий diagnostic report."
            }
        }

        ZipOutputStream(
            BufferedOutputStream(
                FileOutputStream(output),
                COPY_BUFFER_BYTES,
            ),
        ).use { zip ->
            writeSummary(
                zip = zip,
                label = label,
                result = result,
                preparation = preparation,
            )
            writeMetadataTables(zip, result)
            writeBindings(zip, result)
            writeEvidenceTargets(zip, result)
            writeSharedBodies(zip, result)
            writeCurrentAutoModSnapshot(
                zip = zip,
                result = result,
                preparation = preparation,
            )
            writeDump(zip, result)
        }

        require(output.isFile && output.length() > 0L) {
            "Diagnostic report was not created."
        }
        return output
    }

    private fun writeSummary(
        zip: ZipOutputStream,
        label: String,
        result: FastAnalysisResult,
        preparation: PatchPreparationPlan?,
    ) {
        writeTextEntry(zip, "README.txt") { writer ->
            val formatter =
                SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    Locale.US,
                ).apply {
                    timeZone =
                        TimeZone.getTimeZone("UTC")
                }
            writer.line("ModKit Patch Lab diagnostic bundle")
            writer.line("schemaVersion: " + SCHEMA_VERSION)
            writer.line("engineVersion: " + ENGINE_VERSION)
            writer.line(
                "generatedUtc: " +
                    formatter.format(Date()),
            )
            writer.line("target: " + sanitize(label))
            writer.line(
                "artifactSha256: " +
                    result.index.artifactSha256,
            )
            writer.line(
                "entries: " +
                    result.index.entries.size,
            )
            writer.line(
                "abis: " +
                    result.index.detectedAbis
                        .sorted()
                        .joinToString(","),
            )
            writer.line(
                "runtimeProfiles: " +
                    result.index.runtimeProfiles
                        .joinToString(",") {
                            it.runtimeId
                        },
            )
            writer.line()
            writer.line("IL2CPP")
            val dump =
                result.il2cppFastDump
            if (dump == null) {
                writer.line("fastDump: unavailable")
            } else {
                writer.line(
                    "metadataEntry: " +
                        dump.metadataEntry,
                )
                writer.line(
                    "metadataVersion: " +
                        (
                            dump.metadata.metadataVersion
                                ?: "unknown"
                            ),
                )
                writer.line(
                    "layoutProfile: " +
                        (
                            dump.metadata.layoutProfile
                                ?: "unknown"
                            ),
                )
                writer.line(
                    "types: " +
                        dump.metadata.types.size +
                        "/" +
                        (
                            dump.metadata.declaredTypeCount
                                ?: 0
                            ),
                )
                writer.line(
                    "methods: " +
                        dump.metadata.methods.size +
                        "/" +
                        (
                            dump.metadata.declaredMethodCount
                                ?: 0
                            ),
                )
                writer.line(
                    "fields: " +
                        dump.metadata.fields.size +
                        "/" +
                        (
                            dump.metadata.declaredFieldCount
                                ?: 0
                            ),
                )
                writer.line(
                    "dumpIncluded: " +
                        File(dump.dumpFilePath).isFile,
                )
            }
            val binding =
                result.il2cppBinaryBinding
            writer.line(
                "exactBinaryBindings: " +
                    (binding?.exactBindingCount ?: 0),
            )
            val targets =
                result.evidenceGraph
                    ?.targets
                    .orEmpty()
                    .count {
                        it.runtimeId ==
                            "unity_il2cpp" &&
                            it.kind ==
                            EvidenceTargetKind.METHOD &&
                            it.fileOffset != null
                    }
            writer.line(
                "exactIl2CppMethodTargets: " +
                    targets,
            )
            writer.line()
            writer.line("PREPARATION")
            if (preparation == null) {
                writer.line(
                    "not available; run «Подготовить изменения» " +
                        "before export for an AutoMod snapshot.",
                )
            } else {
                writer.line(
                    "sourceShaVerified: " +
                        preparation.sourceShaVerified,
                )
                writer.line(
                    "targets: " +
                        preparation.targets.size,
                )
                writer.line(
                    "confirmed: " +
                        preparation.summary.confirmed,
                )
                writer.line(
                    "ready: " +
                        preparation.summary.ready,
                )
                writer.line(
                    "blocked: " +
                        preparation.summary.blocked,
                )
            }
            writer.line()
            writer.line("FILES")
            writer.line(
                "il2cpp/dump.cs - metadata dump, streamed from analysis output",
            )
            writer.line(
                "il2cpp/types.tsv - all reconstructed type definitions",
            )
            writer.line(
                "il2cpp/methods.tsv - all reconstructed method definitions",
            )
            writer.line(
                "il2cpp/fields.tsv - all reconstructed field definitions",
            )
            writer.line(
                "il2cpp/bindings.tsv - exact metadata token -> native address/file offset bindings",
            )
            writer.line(
                "il2cpp/evidence-targets.tsv - Evidence Graph method targets",
            )
            writer.line(
                "il2cpp/shared-bodies.tsv - native offsets used by more than one metadata method",
            )
            writer.line(
                "automod/current-opportunities.tsv - current strict finder output and blockers",
            )
            writer.line()
            writer.line(
                "Raw APK files, libil2cpp.so and global-metadata.dat " +
                    "are intentionally not embedded in this report.",
            )
        }
    }

    private fun writeMetadataTables(
        zip: ZipOutputStream,
        result: FastAnalysisResult,
    ) {
        val model =
            result.il2cppFastDump
                ?.metadata
                ?: return

        writeTextEntry(
            zip,
            "il2cpp/types.tsv",
        ) { writer ->
            writer.line(
                "index\tnamespace\tname\tfullName\tmethodStart\tmethodCount\tfieldStart\tfieldCount\ttoken",
            )
            model.types.forEach { type ->
                writer.line(
                    listOf(
                        type.index,
                        type.namespace,
                        type.name,
                        type.fullName,
                        type.methodStart,
                        type.methodCount,
                        type.fieldStart,
                        type.fieldCount,
                        hex(type.token),
                    ).joinToString("\t") {
                        tsv(it)
                    },
                )
            }
        }

        writeTextEntry(
            zip,
            "il2cpp/methods.tsv",
        ) { writer ->
            writer.line(
                "index\tdeclaringTypeIndex\tdeclaringType\tname\tparameterCount\ttoken\tflags\treturnTypeIndex",
            )
            model.methods.forEach { method ->
                writer.line(
                    listOf(
                        method.index,
                        method.declaringTypeIndex,
                        method.declaringType,
                        method.name,
                        method.parameterCount,
                        hex(method.token),
                        hex(method.flags.toLong()),
                        method.returnTypeIndex,
                    ).joinToString("\t") {
                        tsv(it)
                    },
                )
            }
        }

        writeTextEntry(
            zip,
            "il2cpp/fields.tsv",
        ) { writer ->
            writer.line(
                "index\tdeclaringTypeIndex\tdeclaringType\tname\ttypeIndex\ttoken",
            )
            model.fields.forEach { field ->
                writer.line(
                    listOf(
                        field.index,
                        field.declaringTypeIndex,
                        field.declaringType,
                        field.name,
                        field.typeIndex,
                        hex(field.token),
                    ).joinToString("\t") {
                        tsv(it)
                    },
                )
            }
        }
    }

    private fun writeBindings(
        zip: ZipOutputStream,
        result: FastAnalysisResult,
    ) {
        val bodyCounts =
            methodBodyCounts(result)
        writeTextEntry(
            zip,
            "il2cpp/bindings.tsv",
        ) { writer ->
            writer.line(
                "library\timage\tmodule\tmanagedIdentity\tmetadataToken\tmethodIndex\tslotIndex\treturnTypeIndex\treturnKind\treturnTypeProof\tfunctionVA\tfileOffset\tsharedBodyCount",
            )
            result.il2cppBinaryBinding
                ?.evidence
                .orEmpty()
                .forEach { evidence ->
                    evidence.bindings.forEach { binding ->
                        val offset =
                            binding.functionFileOffset
                        val shared =
                            if (offset == null) {
                                0
                            } else {
                                bodyCounts[
                                    bodyKey(
                                        evidence.libraryEntry,
                                        offset,
                                    )
                                ] ?: 0
                            }
                        writer.line(
                            listOf(
                                evidence.libraryEntry,
                                binding.imageName,
                                binding.moduleName,
                                binding.managedIdentity,
                                hex(binding.metadataToken),
                                binding.methodIndex,
                                binding.slotIndex,
                                binding.returnTypeIndex,
                                binding.returnKind.name,
                                binding.returnTypeProof
                                    ?: "",
                                hex(
                                    binding.functionVirtualAddress,
                                ),
                                offset?.let(::hex)
                                    ?: "",
                                shared,
                            ).joinToString("\t") {
                                tsv(it)
                            },
                        )
                    }
                }
        }
    }

    private fun writeEvidenceTargets(
        zip: ZipOutputStream,
        result: FastAnalysisResult,
    ) {
        writeTextEntry(
            zip,
            "il2cpp/evidence-targets.tsv",
        ) { writer ->
            writer.line(
                "id\tdisplayName\tdeclaringType\tmemberName\tartifact\tabi\tmetadataToken\tbinaryVA\tfileOffset\tproofLevel\tuserStatus\tblockers",
            )
            result.evidenceGraph
                ?.targets
                .orEmpty()
                .asSequence()
                .filter {
                    it.runtimeId ==
                        "unity_il2cpp" &&
                        it.kind ==
                        EvidenceTargetKind.METHOD
                }
                .forEach { target ->
                    writer.line(
                        listOf(
                            target.id,
                            target.displayName,
                            target.declaringType ?: "",
                            target.memberName ?: "",
                            target.artifact ?: "",
                            target.abi ?: "",
                            target.metadataToken
                                ?.let(::hex)
                                ?: "",
                            target.binaryVirtualAddress
                                ?.let(::hex)
                                ?: "",
                            target.fileOffset
                                ?.let(::hex)
                                ?: "",
                            target.proofLevel.name,
                            target.userStatus.name,
                            target.blockers.joinToString(
                                " | ",
                            ) {
                                it.code + ": " +
                                    it.message
                            },
                        ).joinToString("\t") {
                            tsv(it)
                        },
                    )
                }
        }
    }

    private fun writeSharedBodies(
        zip: ZipOutputStream,
        result: FastAnalysisResult,
    ) {
        val targets =
            result.evidenceGraph
                ?.targets
                .orEmpty()
                .asSequence()
                .filter {
                    it.runtimeId ==
                        "unity_il2cpp" &&
                        it.kind ==
                        EvidenceTargetKind.METHOD &&
                        it.artifact != null &&
                        it.fileOffset != null
                }
                .groupBy {
                    bodyKey(
                        requireNotNull(it.artifact),
                        requireNotNull(it.fileOffset),
                    )
                }
                .filterValues {
                    it.size > 1
                }

        writeTextEntry(
            zip,
            "il2cpp/shared-bodies.tsv",
        ) { writer ->
            writer.line(
                "artifact\tfileOffset\taliasCount\ttargetIds\tdisplayNames",
            )
            targets.entries
                .sortedBy { it.key }
                .forEach { (_, aliases) ->
                    val first =
                        aliases.first()
                    writer.line(
                        listOf(
                            requireNotNull(
                                first.artifact,
                            ),
                            hex(
                                requireNotNull(
                                    first.fileOffset,
                                ),
                            ),
                            aliases.size,
                            aliases.joinToString(
                                " | ",
                            ) {
                                it.id
                            },
                            aliases.joinToString(
                                " | ",
                            ) {
                                it.displayName
                            },
                        ).joinToString("\t") {
                            tsv(it)
                        },
                    )
                }
        }
    }

    private fun writeCurrentAutoModSnapshot(
        zip: ZipOutputStream,
        result: FastAnalysisResult,
        preparation: PatchPreparationPlan?,
    ) {
        writeTextEntry(
            zip,
            "automod/current-opportunities.tsv",
        ) { writer ->
            writer.line(
                "category\ttitle\ttargetId\ttargetDisplayName\taction\tselectable\treplacementHex\tconfidence\tblocker\tevidenceSummary",
            )
            if (preparation == null) {
                writer.line(
                    "NOT_AVAILABLE\tRun preparation before export",
                )
                return@writeTextEntry
            }
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation,
                projectCodeOnly = true,
                limit = 256,
                perCategoryLimit = 32,
            ).forEach { opportunity ->
                writer.line(
                    listOf(
                        opportunity.category.name,
                        opportunity.title,
                        opportunity.targetId,
                        opportunity.targetDisplayName,
                        opportunity.action.name,
                        opportunity.selectable,
                        opportunity.replacementHex
                            ?: "",
                        opportunity.confidence.name,
                        opportunity.blocker ?: "",
                        opportunity.evidenceSummary,
                    ).joinToString("\t") {
                        tsv(it)
                    },
                )
            }
        }
    }

    private fun writeDump(
        zip: ZipOutputStream,
        result: FastAnalysisResult,
    ) {
        val path =
            result.il2cppFastDump
                ?.dumpFilePath
                ?: return
        val dump =
            File(path)
        if (!dump.isFile) return

        zip.putNextEntry(
            ZipEntry("il2cpp/dump.cs"),
        )
        dump.inputStream()
            .buffered(COPY_BUFFER_BYTES)
            .use { input ->
                val buffer =
                    ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read =
                        input.read(buffer)
                    if (read < 0) break
                    zip.write(buffer, 0, read)
                }
            }
        zip.closeEntry()
    }

    private fun methodBodyCounts(
        result: FastAnalysisResult,
    ): Map<String, Int> =
        result.evidenceGraph
            ?.targets
            .orEmpty()
            .asSequence()
            .filter {
                it.runtimeId ==
                    "unity_il2cpp" &&
                    it.kind ==
                    EvidenceTargetKind.METHOD
            }
            .mapNotNull { target ->
                val artifact =
                    target.artifact
                        ?: return@mapNotNull null
                val offset =
                    target.fileOffset
                        ?: return@mapNotNull null
                bodyKey(
                    artifact,
                    offset,
                )
            }
            .groupingBy { it }
            .eachCount()

    private fun writeTextEntry(
        zip: ZipOutputStream,
        name: String,
        block: (BufferedWriter) -> Unit,
    ) {
        zip.putNextEntry(
            ZipEntry(name),
        )
        val writer =
            BufferedWriter(
                OutputStreamWriter(
                    zip,
                    StandardCharsets.UTF_8,
                ),
                COPY_BUFFER_BYTES,
            )
        block(writer)
        writer.flush()
        zip.closeEntry()
    }

    private fun BufferedWriter.line(
        value: String = "",
    ) {
        write(value)
        newLine()
    }

    private fun bodyKey(
        artifact: String,
        offset: Long,
    ): String =
        artifact + "@" + offset

    private fun hex(
        value: Long,
    ): String =
        "0x" + value.toString(16)

    private fun tsv(
        value: Any?,
    ): String =
        sanitize(value?.toString().orEmpty())
            .replace("\t", " ")
            .replace("\n", " ")
            .replace("\r", " ")

    private fun sanitize(
        value: String,
    ): String =
        value
            .replace('\u0000', ' ')
            .take(16_384)
}
