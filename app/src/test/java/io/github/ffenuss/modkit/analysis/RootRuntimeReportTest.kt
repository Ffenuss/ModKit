package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.ProofLevel
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeReportTest {
    @Test
    fun technicalReportKeepsRootUnavailableWithoutLowerStageAttemptsAndExecutor() {
        val root = Files.createTempDirectory("modkit-root-report-").toFile()
        try {
            val result = FastAnalysisResult(
                index = ArtifactIndex(
                    artifactSha256 = SHA,
                    sources = listOf(
                        ArtifactSource("base.apk", 1, SHA),
                    ),
                    entries = emptyList(),
                ),
                routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
                elapsedMs = 1,
                confirmationQueue = listOf(
                    ConfirmationRequest(
                        targetId = "target",
                        engineId = "runtime.native-confirm",
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

            assertTrue(text.contains("ROOT RUNTIME POLICY"))
            assertTrue(text.contains("lastResortOnly: true"))
            assertTrue(text.contains("executorRegistered: false"))
            assertTrue(text.contains("evidenceRequiresRoot: false"))
            assertTrue(text.contains("readyToRunRoot: false"))
            assertTrue(text.contains("REPACKED_TEST_RUNTIME_NOT_ATTEMPTED"))
            assertTrue(text.contains("NON_ROOT_RUNTIME_NOT_ATTEMPTED"))
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
