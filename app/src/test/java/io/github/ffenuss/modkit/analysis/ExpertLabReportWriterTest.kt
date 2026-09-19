package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.ProofLevel
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertLabReportWriterTest {
    @Test
    fun exportsTechnicalRoutingAndEvidenceWithoutSimpleModeReduction() {
        val root = Files.createTempDirectory("modkit-expert-report-").toFile()
        try {
            val index = ArtifactIndex(
                artifactSha256 = SHA,
                sources = listOf(
                    ArtifactSource(
                        displayName = "base.apk",
                        size = 123,
                        sha256 = SHA,
                    ),
                ),
                entries = emptyList(),
                detectedAbis = setOf("arm64-v8a"),
                runtimeProfiles = listOf(
                    RuntimeProfile(
                        runtimeId = "flutter",
                        title = "Flutter",
                        status = DetectionStatus.CONFIRMED,
                        confidence = DetectionConfidence.HIGH,
                        evidence = listOf("base.apk:lib/arm64-v8a/libflutter.so"),
                    ),
                ),
            )
            val plan = EngineRoutingPlan(
                engines = listOf(
                    PlannedEngine(
                        id = "artifact.fast-index",
                        scheduleClass = EngineScheduleClass.FAST,
                        availableNow = true,
                        reason = "index",
                    ),
                    PlannedEngine(
                        id = "flutter.dart-aot",
                        scheduleClass = EngineScheduleClass.TARGETED,
                        availableNow = false,
                        reason = "Flutter evidence",
                    ),
                ),
                missingCapabilities = listOf("Flutter backend pending review"),
            )
            val result = FastAnalysisResult(
                index = index,
                routingPlan = plan,
                elapsedMs = 1,
                evidenceGraph = EvidenceGraph(
                    artifactSha256 = SHA,
                    targets = listOf(
                        EvidenceTarget(
                            id = "runtime:flutter",
                            runtimeId = "flutter",
                            kind = EvidenceTargetKind.ANALYSIS_SCOPE,
                            displayName = "Flutter",
                            artifact = null,
                            abi = "arm64-v8a",
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
                                    message = "Need deep evidence",
                                    requiredFor = ProofLevel.STRUCTURAL,
                                ),
                            ),
                            facts = listOf(
                                EvidenceFact(
                                    engineId = "artifact.fast-index",
                                    kind = "runtime-signal",
                                    summary = "libflutter.so",
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val report = ExpertLabReportWriter.write(
                outputDir = root,
                label = "sample.apk",
                result = result,
            )
            val text = report.readText()

            assertTrue(text.contains("ModKit Expert Lab technical report"))
            assertTrue(text.contains("Artifact SHA-256: " + SHA))
            assertTrue(text.contains("flutter.dart-aot"))
            assertTrue(text.contains("executorRegistered: false"))
            assertTrue(text.contains("runtime:flutter"))
            assertTrue(text.contains("DEEP_EVIDENCE_NOT_AVAILABLE"))
            assertTrue(text.contains("Flutter backend pending review"))
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
