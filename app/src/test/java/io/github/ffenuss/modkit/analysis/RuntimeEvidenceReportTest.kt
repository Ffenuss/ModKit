package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.runtime.ProcMapsCaptureSource
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceBundle
import io.github.ffenuss.modkit.runtime.RuntimeMemoryMappingCandidate
import io.github.ffenuss.modkit.runtime.RuntimeModuleMappingEvidence
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEvidenceReportTest {
    @Test
    fun runtimeReportIsVersionedAndExplainsUnavailableValues() {
        val root = Files.createTempDirectory("modkit-runtime-report-").toFile()
        try {
            val result = FastAnalysisResult(
                index = ArtifactIndex(
                    artifactSha256 = SHA,
                    sources = listOf(
                        ArtifactSource("base.apk", 123, SHA),
                    ),
                    entries = emptyList(),
                ),
                routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
                elapsedMs = 1,
                runtimeEvidence = RuntimeEvidenceBundle(
                    artifactSha256 = SHA,
                    procMapsSha256 = MAPS_SHA,
                    moduleMappings = listOf(
                        RuntimeModuleMappingEvidence(
                            moduleName = "libsample.so",
                            mappedPath = "",
                            loadBias = null,
                            elfImageBaseVirtualAddress = null,
                            pageSize = null,
                            matchedLoadSegments = 0,
                            matchedExecutableSegments = 0,
                            zeroOffsetMappingMatched = false,
                            confirmed = false,
                            blockers = listOf(
                                "Process maps do not provide enough independent PT_LOAD matches.",
                            ),
                        ),
                    ),
                    addressConfirmations = emptyList(),
                    blockers = listOf("Runtime mapping is not confirmed."),
                    memoryMappingCandidates = listOf(
                        RuntimeMemoryMappingCandidate(
                            start = 0x70000000,
                            endExclusive = 0x70001000,
                            permissions = "r-xp",
                            path = null,
                            fileOffset = 0,
                            fileZeroAddressCandidate = 0x70000000,
                            reason = "Executable mapping has no file path.",
                        ),
                    ),
                    captureSource = ProcMapsCaptureSource.IMPORTED_SNAPSHOT,
                    capturePid = null,
                    capturedAtEpochMs = 1234,
                ),
            )

            val report = ExpertLabReportWriter.write(
                outputDir = root,
                label = "sample.apk",
                result = result,
            )
            val text = report.readText()

            assertTrue(text.contains("schemaVersion: 2"))
            assertTrue(text.contains("engineVersion: expert-lab-report/2"))
            assertTrue(text.contains("engineVersion: runtime.evidence/3"))
            assertTrue(text.contains("captureSource: IMPORTED_SNAPSHOT"))
            assertTrue(text.contains("processIdentity: not_confirmed"))
            assertTrue(text.contains("processIdentityConfirmed: false"))
            assertTrue(
                text.contains(
                    "capturePid: not_available_imported_snapshot",
                ),
            )
            assertTrue(text.contains("RUNTIME EVIDENCE CONTRACT"))
            assertTrue(text.contains("PROCESS_OBSERVED"))
            assertTrue(text.contains("not_resolved"))
            assertTrue(text.contains("anonymous_or_special_mapping"))
            assertTrue(text.contains("fileZeroAddressCandidate: 0x70000000"))
            assertTrue(text.contains("MEMORY-BACKED ELF VALIDATION"))
            assertFalse(text.contains("loadBias: null"))
            assertFalse(text.contains("path: null"))
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val MAPS_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
