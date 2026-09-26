package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.Il2CppBinaryBindingResult
import io.github.ffenuss.modkit.analysis.Il2CppBinaryEvidence
import io.github.ffenuss.modkit.analysis.Il2CppFastDumpResult
import io.github.ffenuss.modkit.analysis.Il2CppMetadataModel
import io.github.ffenuss.modkit.analysis.Il2CppMethodBinaryBinding
import io.github.ffenuss.modkit.analysis.Il2CppMethodDefinition
import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.analysis.Il2CppTypeDefinition
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchLabDiagnosticReportWriterTest {
    @Test fun exportsCurrentRoutingFailuresAndSourceEvidenceEvenWithoutRecipes() {
        val root = Files.createTempDirectory("engine-report-").toFile()
        try {
            val engine = io.github.ffenuss.modkit.analysis.PlannedEngine(
                "flutter.asset-inventory", io.github.ffenuss.modkit.domain.EngineScheduleClass.TARGETED,
                true, "Validated Flutter inputs",
            )
            val result = FastAnalysisResult(
                ArtifactIndex(SHA, listOf(io.github.ffenuss.modkit.analysis.ArtifactSource(
                    "base.apk", 4096, SHA,
                )), emptyList(), runtimeProfiles = listOf(io.github.ffenuss.modkit.analysis.RuntimeProfile(
                    "flutter", "Flutter", io.github.ffenuss.modkit.analysis.DetectionStatus.CONFIRMED,
                    io.github.ffenuss.modkit.analysis.DetectionConfidence.HIGH, listOf("libflutter.so"),
                ))),
                EngineRoutingPlan(listOf(engine), listOf("Dart AOT unavailable")), 7,
                engineWarnings = listOf("flutter.asset-inventory: malformed manifest"),
            )
            val report = PatchLabDiagnosticReportWriter.write(root, "fixture", result, null)
            ZipFile(report).use { zip ->
                assertTrue(read(zip, "analysis/sources.tsv").contains("base.apk\t4096\t$SHA"))
                assertTrue(read(zip, "analysis/runtime-profiles.tsv").contains("flutter\tCONFIRMED\tHIGH"))
                assertTrue(read(zip, "analysis/engines.tsv").contains("true\tfalse\tfalse"))
                assertTrue(read(zip, "analysis/warnings.tsv").contains("malformed manifest"))
                assertTrue(read(zip, "analysis/coverage.tsv").contains("perEngineTiming\tNOT_RECORDED"))
            }
            // Identical APK, dump path and binding count; only engine outcome changes.
            // An expert export must not return an older cached success/failure ZIP.
            PatchLabDiagnosticReportWriter.write(root, "fixture", result.copy(
                engineWarnings = listOf("flutter.asset-inventory: source missing"),
            ), null)
            ZipFile(report).use { zip ->
                assertTrue(read(zip, "analysis/warnings.tsv").contains("source missing"))
                assertFalse(read(zip, "analysis/warnings.tsv").contains("malformed manifest"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun currentRecipeReasonsAndChangedValuesAreNotHiddenByTheFinderCache() {
        val root = Files.createTempDirectory("recipe-report-").toFile()
        try {
            val result = FastAnalysisResult(ArtifactIndex(SHA, emptyList(), emptyList()),
                EngineRoutingPlan(emptyList(), emptyList()), elapsedMs = 1)
            val recipe = AutoModRecipe("candidate", "Здоровье", "Предел", "", "Player",
                blocker = "Выполняется вызов другой функции", scalarValue = "2")
            val report = PatchLabDiagnosticReportWriter.write(root, "fixture", result, null, listOf(recipe))
            ZipFile(report).use {
                assertTrue(read(it, "automod/recipes.tsv").contains(recipe.blocker!!))
                assertTrue(read(it, "automod/recipe-summary.txt").contains("runtimeConfirmed=0"))
            }
            PatchLabDiagnosticReportWriter.write(root, "fixture", result, null, listOf(recipe.copy(scalarValue = "5")))
            ZipFile(report).use { assertTrue(read(it, "automod/recipes.tsv").contains("\t5\t")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellingExportPreservesThePreviousCompletedReport() {
        val root = Files.createTempDirectory("cancel-report-").toFile()
        try {
            val result = FastAnalysisResult(ArtifactIndex(SHA, emptyList(), emptyList()),
                EngineRoutingPlan(emptyList(), emptyList()), elapsedMs = 1)
            val previous = PatchLabDiagnosticReportWriter.write(root, "fixture", result, null, emptyList()).readBytes()
            val signal = object : io.github.ffenuss.modkit.analysis.CancellationSignal { override fun isCancelled() = true }
            val failed = runCatching { PatchLabDiagnosticReportWriter.write(root, "fixture", result, null, emptyList(), cancellation = signal) }
            assertTrue(failed.exceptionOrNull() is io.github.ffenuss.modkit.analysis.AnalysisCancelledException)
            org.junit.Assert.assertArrayEquals(previous, root.listFiles()!!.single { it.extension == "zip" }.readBytes())
            assertFalse(root.listFiles()!!.any { it.extension == "tmp" })
        } finally { root.deleteRecursively() }
    }

    @Test
    fun exportsDumpMetadataBindingsAndSharedBodyEvidence() {
        val root =
            Files.createTempDirectory(
                "modkit-patch-report-",
            ).toFile()
        try {
            val dump =
                root.resolve("dump.cs").apply {
                    writeText(
                        "// sample dump\nclass Player {}\n",
                    )
                }
            val targetA =
                target(
                    id = "a",
                    token = 0x06000001,
                    offset = 0x400,
                    name = "TakeDamage",
                )
            val targetB =
                target(
                    id = "b",
                    token = 0x06000002,
                    offset = 0x400,
                    name = "OnDamage",
                )
            val result =
                FastAnalysisResult(
                    index =
                        ArtifactIndex(
                            artifactSha256 = SHA,
                            sources = emptyList(),
                            entries = emptyList(),
                            detectedAbis =
                                setOf("arm64-v8a"),
                            runtimeProfiles =
                                emptyList(),
                        ),
                    routingPlan =
                        EngineRoutingPlan(
                            emptyList(),
                            emptyList(),
                        ),
                    elapsedMs = 1,
                    il2cppFastDump =
                        Il2CppFastDumpResult(
                            metadataEntry =
                                "base.apk:global-metadata.dat",
                            libraryEntries =
                                listOf(ARTIFACT),
                            metadata =
                                Il2CppMetadataModel(
                                    sizeBytes = 100,
                                    magicValid = true,
                                    metadataVersion = 29,
                                    layoutProfile = "v29",
                                    tableRanges =
                                        emptyList(),
                                    declaredTypeCount = 1,
                                    declaredMethodCount = 1,
                                    declaredFieldCount = 0,
                                    declaredImageCount = 1,
                                    images = emptyList(),
                                    types =
                                        listOf(
                                            Il2CppTypeDefinition(
                                                index = 0,
                                                namespace = "Game",
                                                name = "Player",
                                                fullName = "Game.Player",
                                                methodStart = 0,
                                                methodCount = 1,
                                                fieldStart = 0,
                                                fieldCount = 0,
                                                token = 0x02000001,
                                            ),
                                        ),
                                    methods =
                                        listOf(
                                            Il2CppMethodDefinition(
                                                index = 0,
                                                declaringTypeIndex = 0,
                                                declaringType =
                                                    "Game.Player",
                                                name =
                                                    "TakeDamage",
                                                parameterCount = 1,
                                                token = 0x06000001,
                                                flags = 0,
                                                returnTypeIndex = 7,
                                            ),
                                        ),
                                    fields = emptyList(),
                                    structuredSupported = true,
                                    truncated = false,
                                    warnings = emptyList(),
                                ),
                            dumpFilePath =
                                dump.absolutePath,
                            preview = "sample",
                            warnings = emptyList(),
                        ),
                    il2cppBinaryBinding =
                        Il2CppBinaryBindingResult(
                            evidence =
                                listOf(
                                    Il2CppBinaryEvidence(
                                        libraryEntry =
                                            ARTIFACT,
                                        machine = 183,
                                        pointerSize = 8,
                                        relativeRelocationCount = 1,
                                        codeRegistrationVirtualAddress =
                                            null,
                                        metadataRegistrationVirtualAddress =
                                            0x1000,
                                        codegenRegisterVirtualAddress =
                                            null,
                                        moduleArrayDiscovery =
                                            "TEST",
                                        modules = emptyList(),
                                        bindings =
                                            listOf(
                                                binding(
                                                    targetA,
                                                    0x06000001,
                                                ),
                                                binding(
                                                    targetB,
                                                    0x06000002,
                                                ),
                                            ),
                                        blockers = emptyList(),
                                    ),
                                ),
                            exactBindingCount = 2,
                            warnings = emptyList(),
                        ),
                    evidenceGraph =
                        EvidenceGraph(
                            artifactSha256 = SHA,
                            targets =
                                listOf(
                                    targetA,
                                    targetB,
                                ),
                        ),
                )
            val plan =
                PatchPreparationPlan(
                    artifactSha256 = SHA,
                    sourceShaVerified = true,
                    preparedAtEpochMs = 1,
                    targets =
                        listOf(
                            prepared(targetA),
                            prepared(targetB),
                        ),
                    globalBlockers =
                        emptyList(),
                )

            val report =
                PatchLabDiagnosticReportWriter.write(
                    outputDir =
                        root.resolve(
                            "expert-lab-export",
                        ),
                    label = "sample",
                    result = result,
                    preparation = plan,
                )

            ZipFile(report).use { zip ->
                assertTrue(
                    zip.getEntry("README.txt") != null,
                )
                assertTrue(
                    zip.getEntry(
                        "il2cpp/dump.cs",
                    ) != null,
                )
                assertTrue(
                    zip.getEntry(
                        "il2cpp/methods.tsv",
                    ) != null,
                )
                assertTrue(
                    zip.getEntry(
                        "il2cpp/bindings.tsv",
                    ) != null,
                )
                assertTrue(
                    zip.getEntry(
                        "il2cpp/shared-bodies.tsv",
                    ) != null,
                )
                assertTrue(
                    zip.getEntry(
                        "automod/semantic-neighborhoods.tsv",
                    ) != null,
                )
                assertTrue(
                    read(
                        zip,
                        "il2cpp/dump.cs",
                    ).contains("class Player"),
                )
                assertTrue(
                    read(
                        zip,
                        "il2cpp/bindings.tsv",
                    ).contains("BOOLEAN"),
                )
                assertTrue(
                    read(
                        zip,
                        "il2cpp/shared-bodies.tsv",
                    ).contains("\t2\t"),
                )
                assertFalse(
                    zip.entries()
                        .asSequence()
                        .any {
                            it.name.endsWith(
                                ".apk",
                            ) ||
                                it.name.endsWith(
                                    "libil2cpp.so",
                                )
                        },
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun read(
        zip: ZipFile,
        name: String,
    ): String =
        zip.getInputStream(
            requireNotNull(
                zip.getEntry(name),
            ),
        ).bufferedReader().use {
            it.readText()
        }

    private fun prepared(
        target: EvidenceTarget,
    ) =
        PreparedTarget(
            target = target,
            status =
                PreparationTargetStatus
                    .CONFIRMED_NEEDS_CHANGE,
            blockers = emptyList(),
        )

    private fun binding(
        target: EvidenceTarget,
        token: Long,
    ) =
        Il2CppMethodBinaryBinding(
            methodIndex = 0,
            managedIdentity =
                target.displayName,
            metadataToken = token,
            imageName =
                "Assembly-CSharp.dll",
            moduleName =
                "Assembly-CSharp.dll",
            slotIndex = 0,
            functionVirtualAddress =
                0x100000 +
                    requireNotNull(
                        target.fileOffset,
                    ),
            functionFileOffset =
                target.fileOffset,
            returnTypeIndex = 7,
            returnKind =
                Il2CppNativeReturnKind.BOOLEAN,
            returnTypeProof = "test-proof",
        )

    private fun target(
        id: String,
        token: Long,
        offset: Long,
        name: String,
    ) =
        EvidenceTarget(
            id =
                "il2cpp:method:Assembly-CSharp.dll:" +
                    token.toString(16) +
                    ":Assembly-CSharp.dll:" +
                    id,
            runtimeId = "unity_il2cpp",
            kind =
                EvidenceTargetKind.METHOD,
            displayName =
                "Game.Player." + name,
            artifact = ARTIFACT,
            abi = "arm64-v8a",
            declaringType =
                "Game.Player",
            memberName = name,
            metadataToken = token,
            rva = null,
            binaryVirtualAddress =
                0x100000 + offset,
            runtimeVirtualAddress = null,
            fileOffset = offset,
            proofLevel =
                ProofLevel.EXACT_BINARY,
            userStatus =
                UserFindingStatus.CONFIRMED,
            blockers = emptyList(),
            facts = emptyList(),
        )

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val ARTIFACT =
            "split_config.arm64_v8a.apk:lib/arm64-v8a/libil2cpp.so"
    }
}
