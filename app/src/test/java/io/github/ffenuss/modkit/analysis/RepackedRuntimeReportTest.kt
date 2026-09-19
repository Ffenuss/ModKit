package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.ProofLevel
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeReportTest {
    @Test
    fun technicalReportShowsOnlyActuallyRegisteredRepackedCapabilities() {
        val root = Files.createTempDirectory("modkit-repacked-report-").toFile()
        try {
            val result = FastAnalysisResult(
                index = ArtifactIndex(
                    artifactSha256 = SHA,
                    sources = listOf(
                        ArtifactSource("base.apk", 1, SOURCE_SHA),
                    ),
                    entries = emptyList(),
                ),
                routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
                elapsedMs = 1,
                confirmationQueue = listOf(
                    ConfirmationRequest(
                        targetId = "target",
                        engineId = "runtime.il2cpp-confirm",
                        requiredProofLevel = ProofLevel.RUNTIME_CONFIRMED,
                        reason = "Runtime confirmation required.",
                        availableNow = false,
                    ),
                ),
            )

            val report = ExpertLabReportWriter.write(
                outputDir = root,
                label = "sample.apk",
                result = result,
            )
            val text = report.readText()

            assertTrue(text.contains("REPACKED TEST RUNTIME PLAN"))
            assertTrue(text.contains("sourcePolicy: READ_ONLY_COPY_ONLY"))
            assertTrue(text.contains("readyToBuildTestCopy: true"))
            assertTrue(text.contains("registeredCapabilities: SOURCE_COPY"))
            assertTrue(text.contains("MANIFEST_IDENTITY_INSPECTION"))
            assertTrue(text.contains("OLD_SIGNATURE_REMOVAL"))
            assertTrue(text.contains("APK_ALIGNMENT"))
            assertTrue(text.contains("APK_SIGNING"))
            assertTrue(text.contains("PACKAGE_VERIFY"))
            assertTrue(text.contains("REPORT_WRITE"))
            assertTrue(text.contains("CLEANUP"))
            assertTrue(text.contains("BINARY_MANIFEST_REWRITE"))
            assertTrue(
                !text.contains(
                    "BINARY_MANIFEST_REWRITE_NOT_REGISTERED",
                ),
            )
            assertTrue(text.contains("PROBE_PAYLOAD_INJECTION"))
            assertTrue(
                !text.contains(
                    "PROBE_PAYLOAD_INJECTION_NOT_REGISTERED",
                ),
            )
            assertTrue(text.contains("TEST_LAUNCH"))
            assertTrue(text.contains("RUNTIME_EVIDENCE_CAPTURE"))
            assertTrue(!text.contains("TEST_LAUNCH_NOT_REGISTERED"))
            assertTrue(!text.contains("RUNTIME_EVIDENCE_CAPTURE_NOT_REGISTERED"))
            assertTrue(text.contains("fallbackStage: NON_ROOT_RUNTIME"))
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SOURCE_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
