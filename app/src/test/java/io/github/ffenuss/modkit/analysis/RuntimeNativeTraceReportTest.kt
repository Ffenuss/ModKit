package io.github.ffenuss.modkit.analysis

import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNativeTraceReportTest {
    @Test
    fun technicalReportNeverAdvertisesUnregisteredNativeTraceCapture() {
        val root = Files.createTempDirectory("modkit-native-trace-report-").toFile()
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
            )

            val report = ExpertLabReportWriter.write(
                outputDir = root,
                label = "sample.apk",
                result = result,
            )
            val text = report.readText()

            assertTrue(text.contains("RUNTIME NATIVE TRACE CAPABILITIES"))
            assertTrue(text.contains("- TRACE_PARSER: true"))
            assertTrue(text.contains("- EXECUTABLE_ADDRESS_VALIDATOR: true"))
            assertTrue(text.contains("- REPACKED_TRACE_CAPTURE: false"))
            assertTrue(text.contains("- NON_ROOT_TRACE_CAPTURE: false"))
            assertTrue(text.contains("- ROOT_TRACE_CAPTURE: false"))
            assertTrue(
                text.contains(
                    "- REPACKED_TARGETED_DLSYM_PROBE: true",
                ),
            )
            assertTrue(text.contains("capture-REPACKED_TEST_RUNTIME: false"))
            assertTrue(text.contains("capture-NON_ROOT_RUNTIME: false"))
            assertTrue(text.contains("capture-ROOT_RUNTIME: false"))
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
