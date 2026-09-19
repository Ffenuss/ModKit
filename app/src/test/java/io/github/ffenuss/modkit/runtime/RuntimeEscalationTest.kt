package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.ConfirmationRequest
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEscalationTest {
    @Test
    fun unavailableRuntimeConfirmationEscalatesToRepackedTestBeforeRoot() {
        val result = FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = SHA,
                sources = listOf(ArtifactSource("base.apk", 1, SHA)),
                entries = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
            confirmationQueue = listOf(
                ConfirmationRequest(
                    targetId = "il2cpp:executable-binding",
                    engineId = "runtime.il2cpp-confirm",
                    requiredProofLevel = ProofLevel.RUNTIME_CONFIRMED,
                    reason = "Static binding unresolved",
                    availableNow = false,
                ),
            ),
        )

        val plan = RuntimeEscalationPlanner.plan(result)

        assertTrue(plan.required)
        assertTrue(plan.rootAllowedOnlyAsLastResort)
        assertEquals(
            RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
            plan.nextStage,
        )
        assertEquals(1, plan.needs.size)
    }

    @Test
    fun noUnavailableConfirmationMeansNoRuntimeEscalation() {
        val result = FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = SHA,
                sources = listOf(ArtifactSource("base.apk", 1, SHA)),
                entries = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
            confirmationQueue = listOf(
                ConfirmationRequest(
                    targetId = "target",
                    engineId = "il2cpp.codegen-bind",
                    requiredProofLevel = ProofLevel.EXACT_BINARY,
                    reason = "Static confirmation",
                    availableNow = true,
                ),
            ),
        )

        val plan = RuntimeEscalationPlanner.plan(result)

        assertFalse(plan.required)
        assertEquals(null, plan.nextStage)
    }

    @Test
    fun parsesProcMapsAndKeepsBaseAsCandidateNotExactProof() {
        val maps = """
            70000000-70001000 r--p 00000000 103:02 42 /data/app/pkg/lib/arm64/libil2cpp.so
            70001000-70100000 r-xp 00001000 103:02 42 /data/app/pkg/lib/arm64/libil2cpp.so
            72000000-72001000 rw-p 00000000 00:00 0 [anon:dalvik]
        """.trimIndent()

        val regions = ProcMapsParser.parse(maps)
        assertEquals(3, regions.size)

        val module = ProcMapsParser.matchingModule(
            regions,
            "libil2cpp.so",
        )
        assertEquals(2, module.size)
        assertTrue(module.any { it.executable })

        val candidates = ProcMapsParser.fileZeroBaseCandidates(
            regions,
            "libil2cpp.so",
        )
        assertEquals(setOf(0x70000000L), candidates)
    }

    @Test
    fun malformedProcMapsLinesAreIgnored() {
        val parsed = ProcMapsParser.parse(
            """
            nonsense
            1000-2000 r-xp BAD 00:00 0 /x.so
            3000-2000 r-xp 0000 00:00 0 /bad.so
            4000-5000 r-xp 0000 00:00 1 /ok.so
            """.trimIndent(),
        )

        assertEquals(1, parsed.size)
        assertEquals("/ok.so", parsed.single().path)
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
