package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.nativecode.AArch64ReadOnlyBody
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
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PatchLabDiagnosticReportWriter {
    private const val SCHEMA_VERSION = 2
    private const val ENGINE_VERSION =
        "patch-lab-diagnostic/3"
    private const val COPY_BUFFER_BYTES =
        128 * 1024

    fun write(
        outputDir: File,
        label: String,
        result: FastAnalysisResult,
        preparation: PatchPreparationPlan?,
        recipes: List<AutoModRecipe>? = null,
        analysisRoot: File? = null,
        cancellation: CancellationSignal? = null,
    ): File {
        outputDir.mkdirs()
        val output =
            File(
                outputDir,
                (if (recipes == null) "ModKit-PatchLab-report-" else "ModKit-AutoMod-report-") +
                    result.index.artifactSha256.take(12) +
                    ".zip",
            )
        val fingerprint =
            buildString {
                append(ENGINE_VERSION)
                append('|')
                append(result.index.artifactSha256)
                append('|')
                append(
                    preparation
                        ?.preparedAtEpochMs
                        ?: -1L,
                )
                append('|')
                append(
                    result.il2cppBinaryBinding
                        ?.exactBindingCount
                        ?: 0,
                )
                append('|')
                append(
                    result.il2cppFastDump
                        ?.dumpFilePath
                        .orEmpty(),
                )
            }
        val fingerprintFile =
            File(
                outputDir,
                output.name + ".fingerprint",
            )
        if (
            recipes == null &&
            output.isFile &&
            output.length() > 0L &&
            fingerprintFile.isFile &&
            fingerprintFile.readText(
                StandardCharsets.UTF_8,
            ) == fingerprint
        ) {
            return output
        }

        val temporary = File.createTempFile("diagnostic-", ".tmp", outputDir)
        try {
            ZipOutputStream(
                BufferedOutputStream(
                    object : java.io.FilterOutputStream(FileOutputStream(temporary)) {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            if (cancellation?.isCancelled() == true) throw AnalysisCancelledException()
                            out.write(bytes, offset, length)
                        }
                        override fun write(value: Int) {
                            if (cancellation?.isCancelled() == true) throw AnalysisCancelledException()
                            out.write(value)
                        }
                    },
                    COPY_BUFFER_BYTES,
                ),
            ).apply {
                setLevel(Deflater.BEST_SPEED)
            }.use { zip ->
                writeSummary(
                    zip = zip,
                    label = label,
                    result = result,
                    preparation = preparation,
                )
                writeMetadataTables(zip, result)
                writeBindings(zip, result)
                writeUnrealInventory(zip, result)
                writeFlutterInventory(zip, result)
                writeEvidenceTargets(zip, result)
                writeSharedBodies(zip, result)
                writeCurrentAutoModSnapshot(
                    zip = zip,
                    result = result,
                    preparation = preparation,
                )
                if (recipes != null) writeRecipeSnapshot(zip, result, recipes, analysisRoot, cancellation)
                writeSemanticNeighborhoods(
                    zip = zip,
                    result = result,
                    preparation = preparation,
                )
                writeDump(zip, result)
            }
            require(temporary.renameTo(output)) { "Не удалось сохранить диагностический отчёт." }
        } finally { temporary.delete() }

        require(output.isFile && output.length() > 0L) {
            "Diagnostic report was not created."
        }
        fingerprintFile.writeText(
            fingerprint,
            StandardCharsets.UTF_8,
        )
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
            binding?.evidence.orEmpty().forEach { item ->
                writer.line(
                    "diskBindingIndex: " + item.libraryEntry +
                        " methods=" + (item.bindingIndex?.methodCount ?: 0) +
                        " exact=" + (item.bindingIndex?.boundCount ?: item.bindings.size) +
                        " shaVerified=" + (item.bindingIndex?.verify() ?: false) +
                        " memoryPreview=" + item.bindings.size,
                )
            }
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
            result.flutterAssetInventory?.let { inventory ->
                writer.line()
                writer.line("FLUTTER")
                writer.line("validatedManifestCount: " + inventory.validatedManifestCount)
                writer.line("listedAssetCount: " + inventory.listedAssetCount)
                writer.line("packagedAssetEntries: " + inventory.packagedAssetCount)
                writer.line("aotGameLogicDecoder: NOT_IMPLEMENTED")
                inventory.warnings.forEach { writer.line("warning: " + it) }
            }
            result.unrealAssetInventory?.let { inventory ->
                writer.line()
                writer.line("UNREAL")
                writer.line("indexedPakAndAssetEntries: " + inventory.records.size)
                writer.line("pakIndexSha1Verified: " + inventory.verifiedPakCount)
                writer.line("cookAssetHeadersSeen: " + inventory.packagedContentCount)
                writer.line("blueprintGameplayDecoder: NOT_IMPLEMENTED")
                inventory.warnings.forEach { writer.line("warning: " + it) }
            }
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
            writer.line(
                "automod/semantic-neighborhoods.tsv - model-field signals linked to methods in the same IL2CPP type",
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
        val bodyCounts = methodBodyCounts(result)
        val metadata = result.il2cppFastDump?.metadata
        writeTextEntry(zip, "il2cpp/bindings.tsv") { writer ->
            writer.line(
                "library\timage\tmodule\tmanagedIdentity\tmetadataToken\tmethodIndex\t" +
                    "slotIndex\treturnTypeIndex\treturnKind\treturnTypeProof\t" +
                    "functionVA\tfileOffset\tsharedBodyCount",
            )

            fun emit(
                evidence: io.github.ffenuss.modkit.analysis.Il2CppBinaryEvidence,
                binding: io.github.ffenuss.modkit.analysis.Il2CppMethodBinaryBinding,
                shared: Int?,
            ) {
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
                        binding.returnTypeProof ?: "",
                        hex(binding.functionVirtualAddress),
                        binding.functionFileOffset?.let(::hex) ?: "",
                        shared ?: "",
                    ).joinToString("\t") { tsv(it) },
                )
            }

            result.il2cppBinaryBinding?.evidence.orEmpty().forEach { evidence ->
                val disk = evidence.bindingIndex
                if (disk != null && metadata != null && disk.verify()) {
                    // 156k+ MethodDefs are exported in a bounded-memory stream,
                    // not silently truncated to the 30k in-memory preview.
                    val methods = arrayOfNulls<io.github.ffenuss.modkit.analysis.Il2CppMethodDefinition>(
                        disk.methodCount,
                    )
                    metadata.methods.forEach { method ->
                        if (method.index in methods.indices) methods[method.index] = method
                    }
                    val typeSize = (metadata.types.maxOfOrNull { it.index } ?: -1) + 1
                    val imageByType =
                        arrayOfNulls<io.github.ffenuss.modkit.analysis.Il2CppImageDefinition>(typeSize)
                    metadata.images.forEach { image ->
                        val last = minOf(
                            typeSize.toLong(),
                            image.typeStart.toLong() + image.typeCount,
                        ).toInt()
                        for (type in maxOf(0, image.typeStart) until last) {
                            if (imageByType[type] == null) imageByType[type] = image
                        }
                    }
                    val previewMethods = evidence.bindings.mapTo(HashSet()) { it.methodIndex }
                    disk.forEachBound { methodIndex, indexed ->
                        val method = methods.getOrNull(methodIndex) ?: return@forEachBound
                        val image = imageByType.getOrNull(method.declaringTypeIndex)
                            ?: return@forEachBound
                        val binding = io.github.ffenuss.modkit.analysis.Il2CppOnDemandBindings
                            .resolveIndexed(evidence, method, image, indexed)
                            ?: return@forEachBound
                        // The existing graph's body counts cover only its
                        // materialized subset. Never present these sample
                        // counts as proof of uniqueness for late methods.
                        val shared = if (methodIndex in previewMethods) {
                            binding.functionFileOffset?.let {
                                bodyCounts[bodyKey(evidence.libraryEntry, it)]
                            }
                        } else null
                        emit(evidence, binding, shared)
                    }
                } else {
                    // Older persisted analysis results have no full index.
                    evidence.bindings.forEach { binding ->
                        emit(
                            evidence, binding,
                            binding.functionFileOffset?.let {
                                bodyCounts[bodyKey(evidence.libraryEntry, it)]
                            },
                        )
                    }
                }
            }
        }
    }

    private fun writeFlutterInventory(
        zip: ZipOutputStream,
        result: FastAnalysisResult,
    ) {
        writeTextEntry(zip, "flutter/asset-inventory.tsv") { writer ->
            writer.line("container\tpath\tstatus\tassetCount\tsampledAssets\tnote")
            result.flutterAssetInventory?.manifests.orEmpty().forEach { item ->
                writer.line(
                    listOf(
                        item.container, item.path, item.status,
                        item.assetCount, item.sampledAssets.joinToString(";"), item.note ?: "",
                    ).joinToString("\t") { tsv(it) },
                )
            }
        }
        writeTextEntry(zip, "flutter/runtime-libraries.tsv") { writer ->
            writer.line("container\tpath\tabi\tcomponent\telfValidated")
            result.flutterAssetInventory?.runtimeLibraries.orEmpty().forEach { item ->
                writer.line(
                    listOf(
                        item.container, item.path, item.abi ?: "", item.component, item.validatedElf,
                    ).joinToString("\t") { tsv(it) },
                )
            }
        }
    }

    private fun writeUnrealInventory(
        zip: ZipOutputStream,
        result: FastAnalysisResult,
    ) {
        writeTextEntry(zip, "unreal/asset-inventory.tsv") { writer ->
            writer.line(
                "container\tpath\tsize\tkind\tstatus\tpakVersion\tindexOffset\t" +
                    "indexSize\tindexSha1Verified\tencryptedIndex\tnote",
            )
            result.unrealAssetInventory?.records.orEmpty().forEach { item ->
                writer.line(
                    listOf(
                        item.container, item.path, item.size, item.kind,
                        item.status, item.pakVersion ?: "", item.pakIndexOffset ?: "",
                        item.pakIndexSize ?: "", item.pakIndexSha1Verified ?: "",
                        item.encryptedIndex ?: "", item.note ?: "",
                    ).joinToString("\t") { tsv(it) },
                )
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

    /** The actual simple-mode decisions, not a fresh name-only Finder result. */
    private fun writeRecipeSnapshot(zip: ZipOutputStream, result: FastAnalysisResult,
        recipes: List<AutoModRecipe>, analysisRoot: File?, cancellation: CancellationSignal?) {
        writeTextEntry(zip, "automod/recipe-summary.txt") { writer ->
            writer.line("This snapshot is the current simple AutoMod catalog; candidate purpose is not implied by a ready patch.")
            writer.line("recipes=${recipes.size}")
            writer.line("selectable=${recipes.count { it.selectable }}")
            writer.line("recipePrepared=${recipes.count { it.verification.recipePrepared }}")
            writer.line("purposeConfirmed=${recipes.count { it.verification.purposeConfirmed }}")
            writer.line("staticVerified=${recipes.count { it.verification.staticVerified }}")
            writer.line("apkBuilt=${recipes.count { it.verification.apkBuilt }}")
            writer.line("runtimeConfirmed=${recipes.count { it.verification.runtimeConfirmed }}")
            writer.line("Native windows contain up to 1024 bytes per candidate, not complete APKs/libraries or runtime memory.")
        }
        writeTextEntry(zip, "automod/recipes.tsv") { writer ->
            writer.line("id\tcategory\ttitle\ttarget\tselectable\tblocker\tvalue\tchoices\tmethods\tprepared\tstaticVerified\tapkBuilt\truntimeConfirmed\tpurposeConfirmed")
            recipes.forEach { recipe ->
                if (cancellation?.isCancelled() == true) throw AnalysisCancelledException()
                writer.line(listOf(recipe.id, recipe.category, recipe.title, recipe.targetLabel,
                    recipe.selectable, recipe.blocker, recipe.scalarValue, recipe.scalarValues.joinToString(",") { it.value },
                    recipe.native?.targetId ?: recipe.dex.joinToString(";") { it.className + "->" + it.methodName + it.signature },
                    recipe.verification.recipePrepared, recipe.verification.staticVerified, recipe.verification.apkBuilt,
                    recipe.verification.runtimeConfirmed, recipe.verification.purposeConfirmed).joinToString("\t") { tsv(it) })
            }
        }
        if (analysisRoot == null) return
        writeTextEntry(zip, "automod/native-windows.tsv") { writer ->
            writer.line("recipeId\ttargetId\tfileOffset\twindowBytes\tnextMethodOffset\treadOnlyProof\tvisitedInstructions\treturnSites\toriginalHex\treplacementHex\terror")
            recipes.filter { it.native != null }.forEach { recipe ->
                if (cancellation?.isCancelled() == true) throw AnalysisCancelledException()
                val targetId = requireNotNull(recipe.native).targetId
                try {
                    val window = Il2CppNativeMutationDraftBuilder.readCodeWindow(result, targetId, analysisRoot, 1024)
                    val proof = AArch64ReadOnlyBody.inspect(Il2CppNativeMutationDraftBuilder.parseHex(window.originalHex))
                    writer.line(listOf(recipe.id, targetId, window.fileOffset, window.byteLength, window.nextMethodFileOffset,
                        proof.reason, proof.visitedInstructions, proof.returnSites, window.originalHex,
                        recipe.native.replacementHex, "").joinToString("\t") { tsv(it) })
                } catch (failure: AnalysisCancelledException) { throw failure }
                catch (failure: Exception) {
                    writer.line(listOf(recipe.id, targetId, "", "", "", "", "", "", "", "", failure.message).joinToString("\t") { tsv(it) })
                }
            }
        }
    }

    private fun writeSemanticNeighborhoods(
        zip: ZipOutputStream,
        result: FastAnalysisResult,
        preparation: PatchPreparationPlan?,
    ) {
        writeTextEntry(
            zip,
            "automod/semantic-neighborhoods.tsv",
        ) { writer ->
            writer.line(
                "category\tfield\tdeclaringTypeIndex\tfieldToken\tmethod\tmethodToken\tparameterCount\treturnTypeIndex\texactBinding\treturnKind\tfileOffset",
            )
            if (preparation == null) {
                writer.line(
                    "NOT_AVAILABLE\tRun preparation before export",
                )
                return@writeTextEntry
            }
            val model =
                result.il2cppFastDump
                    ?.metadata
                    ?: return@writeTextEntry
            val signals =
                GameplayModificationFinder.find(
                    result = result,
                    preparation = preparation,
                    projectCodeOnly = true,
                    limit = 256,
                    perCategoryLimit = 32,
                ).filter {
                    it.confidence ==
                        GameplayModificationConfidence
                            .SEMANTIC_MODEL_SIGNAL
                }
            if (signals.isEmpty()) return@writeTextEntry

            val bindingsByToken =
                result.il2cppBinaryBinding
                    ?.evidence
                    .orEmpty()
                    .asSequence()
                    .flatMap { evidence ->
                        evidence.bindings.asSequence().map {
                            it.metadataToken to it
                        }
                    }
                    .groupBy(
                        keySelector = { it.first },
                        valueTransform = { it.second },
                    )

            signals.forEach { signal ->
                val field =
                    model.fields.firstOrNull {
                        signal.targetDisplayName ==
                            it.declaringType + "." +
                            it.name
                    } ?: return@forEach

                val methods =
                    model.methods
                        .asSequence()
                        .filter {
                            it.declaringTypeIndex ==
                                field.declaringTypeIndex
                        }
                        .take(64)
                        .toList()

                if (methods.isEmpty()) {
                    writer.line(
                        listOf(
                            signal.category.name,
                            signal.targetDisplayName,
                            field.declaringTypeIndex,
                            hex(field.token),
                            "",
                            "",
                            "",
                            "",
                            false,
                            "",
                            "",
                        ).joinToString("\t") {
                            tsv(it)
                        },
                    )
                    return@forEach
                }

                methods.forEach { method ->
                    val binding =
                        bindingsByToken[
                            method.token
                        ]
                            ?.singleOrNull()
                    writer.line(
                        listOf(
                            signal.category.name,
                            signal.targetDisplayName,
                            field.declaringTypeIndex,
                            hex(field.token),
                            method.declaringType + "." +
                                method.name,
                            hex(method.token),
                            method.parameterCount,
                            method.returnTypeIndex,
                            binding != null,
                            binding?.returnKind?.name
                                ?: "",
                            binding?.functionFileOffset
                                ?.let(::hex)
                                ?: "",
                        ).joinToString("\t") {
                            tsv(it)
                        },
                    )
                }
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
