package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertLabInventoryFilterTest {
    @Test
    fun backendSearchMatchesAcrossIdScheduleAndReason() {
        val engines = listOf(
            PlannedEngine(
                id = "il2cpp.codegen-bind",
                scheduleClass = EngineScheduleClass.CONFIRMATION,
                availableNow = true,
                reason = "Exact executable binding",
            ),
            PlannedEngine(
                id = "godot.deep",
                scheduleClass = EngineScheduleClass.TARGETED,
                availableNow = false,
                reason = "Godot evidence",
            ),
        )

        assertEquals(
            listOf("il2cpp.codegen-bind"),
            ExpertLabInventoryFilter
                .filterEngines(engines, "confirmation executable")
                .map { it.id },
        )
        assertEquals(
            listOf("godot.deep"),
            ExpertLabInventoryFilter
                .filterEngines(engines, "missing godot")
                .map { it.id },
        )
    }

    @Test
    fun targetSearchMatchesIdentityProofBlockersAndFacts() {
        val targets = listOf(
            target(
                id = "method:1",
                displayName = "Player::TakeDamage",
                proof = ProofLevel.EXACT_BINARY,
                blocker = "Runtime confirmation required.",
                fact = "libil2cpp.so exact method pointer",
            ),
            target(
                id = "resource:1",
                displayName = "res://main.tscn",
                proof = ProofLevel.STRUCTURAL,
                blocker = "Binary resource parser pending.",
                fact = "Godot scene evidence",
            ),
        )

        assertEquals(
            listOf("method:1"),
            ExpertLabInventoryFilter
                .filterTargets(
                    targets,
                    "TakeDamage exact_binary runtime",
                )
                .map { it.id },
        )
        assertEquals(
            listOf("resource:1"),
            ExpertLabInventoryFilter
                .filterTargets(targets, "godot parser")
                .map { it.id },
        )
    }

    @Test
    fun blankQueryPreservesOriginalOrder() {
        val engines = listOf(
            PlannedEngine(
                id = "b",
                scheduleClass = EngineScheduleClass.BACKGROUND,
                availableNow = false,
                reason = "B",
            ),
            PlannedEngine(
                id = "a",
                scheduleClass = EngineScheduleClass.FAST,
                availableNow = true,
                reason = "A",
            ),
        )

        assertTrue(
            ExpertLabInventoryFilter
                .filterEngines(engines, "   ") === engines,
        )
    }

    private fun target(
        id: String,
        displayName: String,
        proof: ProofLevel,
        blocker: String,
        fact: String,
    ) = EvidenceTarget(
        id = id,
        runtimeId = if ("method" in id) "unity_il2cpp" else "godot",
        kind = if ("method" in id) {
            EvidenceTargetKind.METHOD
        } else {
            EvidenceTargetKind.RESOURCE
        },
        displayName = displayName,
        artifact = if ("method" in id) "libil2cpp.so" else "game.pck",
        abi = if ("method" in id) "arm64-v8a" else null,
        declaringType = if ("method" in id) "Player" else null,
        memberName = if ("method" in id) "TakeDamage" else null,
        metadataToken = null,
        rva = null,
        binaryVirtualAddress = null,
        runtimeVirtualAddress = null,
        fileOffset = null,
        proofLevel = proof,
        userStatus = UserFindingStatus.CONFIRMING,
        blockers = listOf(
            EvidenceBlocker(
                code = "BLOCKER",
                message = blocker,
                requiredFor = ProofLevel.RUNTIME_CONFIRMED,
            ),
        ),
        facts = listOf(
            EvidenceFact(
                engineId = "test",
                kind = "evidence",
                summary = fact,
            ),
        ),
    )
}
