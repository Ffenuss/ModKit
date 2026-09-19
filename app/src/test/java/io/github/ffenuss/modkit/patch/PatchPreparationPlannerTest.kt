package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.ConfirmationRequest
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.TargetShaVerification
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchPreparationPlannerTest {
    @Test
    fun shaMismatchBlocksEveryTarget() {
        val result = resultWith(
            target(
                proof = ProofLevel.EXACT_BINARY,
                status = UserFindingStatus.CONFIRMED,
            ),
        )
        val plan = PatchPreparationPlanner.prepare(
            result = result,
            shaVerification = TargetShaVerification(
                expectedSha256 = SHA,
                actualSha256 = OTHER_SHA,
                matches = false,
                sources = emptyList(),
                blockerCode = "TARGET_SHA_MISMATCH",
                blockerMessage = "Target changed.",
            ),
        )

        assertFalse(plan.sourceShaVerified)
        assertFalse(plan.automaticApplyAllowed)
        assertEquals(1, plan.summary.blocked)
        assertEquals(PreparationTargetStatus.BLOCKED, plan.targets.single().status)
    }

    @Test
    fun exactBinaryIsConfirmedButNotChangeReadyWithoutConcreteMutation() {
        val result = resultWith(
            target(
                proof = ProofLevel.EXACT_BINARY,
                status = UserFindingStatus.CONFIRMED,
            ),
        )
        val plan = PatchPreparationPlanner.prepare(
            result = result,
            shaVerification = matchingVerification(),
        )

        assertTrue(plan.sourceShaVerified)
        assertEquals(1, plan.summary.confirmed)
        assertEquals(0, plan.summary.ready)
        assertEquals(
            PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE,
            plan.targets.single().status,
        )
        assertFalse(plan.automaticApplyAllowed)
    }

    @Test
    fun onlyChangeReadyTargetCanPassAutomaticApplyGate() {
        val result = resultWith(
            target(
                proof = ProofLevel.CHANGE_READY,
                status = UserFindingStatus.READY,
            ),
        )
        val plan = PatchPreparationPlanner.prepare(
            result = result,
            shaVerification = matchingVerification(),
        )

        assertEquals(1, plan.summary.ready)
        assertTrue(plan.automaticApplyAllowed)
    }

    @Test
    fun runtimeRequiredRemainsRuntimeRequired() {
        val result = resultWith(
            target(
                proof = ProofLevel.EXACT_METADATA,
                status = UserFindingStatus.RUNTIME_REQUIRED,
            ),
        )
        val plan = PatchPreparationPlanner.prepare(
            result = result,
            shaVerification = matchingVerification(),
        )

        assertEquals(1, plan.summary.runtimeRequired)
        assertEquals(
            PreparationTargetStatus.RUNTIME_REQUIRED,
            plan.targets.single().status,
        )
    }

    private fun matchingVerification() = TargetShaVerification(
        expectedSha256 = SHA,
        actualSha256 = SHA,
        matches = true,
        sources = listOf(ArtifactSource("base.apk", 1, SHA)),
        blockerCode = null,
        blockerMessage = null,
    )

    private fun resultWith(vararg targets: EvidenceTarget): FastAnalysisResult {
        val index = ArtifactIndex(
            artifactSha256 = SHA,
            sources = listOf(ArtifactSource("base.apk", 1, SHA)),
            entries = emptyList(),
        )
        return FastAnalysisResult(
            index = index,
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
            evidenceGraph = EvidenceGraph(SHA, targets.toList()),
            confirmationQueue = emptyList<ConfirmationRequest>(),
        )
    }

    private fun target(
        proof: ProofLevel,
        status: UserFindingStatus,
    ) = EvidenceTarget(
        id = "target-1",
        runtimeId = "unity_il2cpp",
        kind = EvidenceTargetKind.METHOD,
        displayName = "Game.Player.Hit",
        artifact = "base.apk:lib/arm64-v8a/libil2cpp.so",
        abi = "arm64-v8a",
        declaringType = "Game.Player",
        memberName = "Hit",
        metadataToken = 0x06000001,
        rva = null,
        binaryVirtualAddress = 0x1000,
        runtimeVirtualAddress = null,
        fileOffset = 0x200,
        proofLevel = proof,
        userStatus = status,
        blockers = emptyList(),
        facts = emptyList(),
    )

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val OTHER_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
