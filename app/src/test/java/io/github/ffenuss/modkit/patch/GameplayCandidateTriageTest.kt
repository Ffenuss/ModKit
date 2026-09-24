package io.github.ffenuss.modkit.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameplayCandidateTriageTest {
    private fun candidate(
        name: String,
        category: GameplayModificationCategory =
            GameplayModificationCategory.SURVIVABILITY,
        selectable: Boolean = false,
        evidence: GameplayModificationConfidence =
            GameplayModificationConfidence.SEMANTIC_METHOD_SIGNAL,
        replacement: String? = if (selectable) "20 00 80 D2 C0 03 5F D6" else null,
    ) = GameplayModificationOpportunity(
        id = name,
        category = category,
        title = name,
        targetId = name,
        targetDisplayName = name,
        action = if (selectable) GameplayMutationAction.FORCE_TRUE
            else GameplayMutationAction.DISCOVERY_ONLY,
        replacementHex = replacement,
        selectable = selectable,
        blocker = if (selectable) null else "Requires evidence",
        evidenceSummary = "Static analysis, runtime not tested",
        confidence = evidence,
    )

    @Test
    fun distinguishPlayerStateFromDisplayWithSameHealthGetter() {
        val state = candidate("FlickEngine.CharacterSheetHealth.get_MaxHealth")
        val display = candidate("FlickEngine.DiskInfoBoxMain.get_MaxHealth")
        assertEquals(
            GameplayCandidateRole.STATE_READER,
            GameplayCandidateTriage.role(state),
        )
        assertEquals(
            GameplayCandidateRole.PRESENTATION,
            GameplayCandidateTriage.role(display),
        )
        assertEquals(
            listOf(state, display),
            GameplayCandidateTriage.prioritized(listOf(display, state)),
        )
    }

    @Test
    fun stateSetterIsMoreRelevantThanStateGetterButStillNotProven() {
        val setter = candidate("FlickEngine.Health.ChangeMaxHealthByAmount")
        assertEquals(
            GameplayCandidateRole.STATE_MUTATOR,
            GameplayCandidateTriage.role(setter),
        )
        assertEquals(
            GameplayPatchReadiness.NEEDS_RESEARCH,
            GameplayCandidateTriage.readiness(setter),
        )
    }

    @Test
    fun exactPatchDoesNotClaimRuntimeEffect() {
        val ready = candidate(
            "FlickEngine.Health.get_MaxHealth",
            selectable = true,
            evidence = GameplayModificationConfidence.EXACT_ACTION,
        )
        assertEquals(
            GameplayPatchReadiness.STATIC_PATCH_READY,
            GameplayCandidateTriage.readiness(ready),
        )
        assertTrue(
            GameplayCandidateTriage.readiness(ready).label
                .contains("тест в игре"),
        )
    }

    @Test
    fun metadataAndSensitiveCandidatesAreNeverCountedAsReady() {
        val metadata = candidate(
            "FlickEngine.Health.maxHealth",
            evidence = GameplayModificationConfidence.SEMANTIC_MODEL_SIGNAL,
        )
        val sensitive = candidate(
            "FlickEngine.Billing.ReceiptValidator",
            category = GameplayModificationCategory.SENSITIVE_SURFACE,
        )
        assertEquals(
            GameplayPatchReadiness.METADATA_SIGNAL,
            GameplayCandidateTriage.readiness(metadata),
        )
        assertEquals(
            GameplayPatchReadiness.DIAGNOSTIC_ONLY,
            GameplayCandidateTriage.readiness(sensitive),
        )
        assertEquals(
            GameplayCandidateRole.SENSITIVE_DIAGNOSTIC,
            GameplayCandidateTriage.role(sensitive),
        )
    }

    @Test
    fun categoryOverviewShowsReadyResearchAndUiCountsSeparately() {
        val ready = candidate(
            "FlickEngine.CharacterSheetHealth.get_MaxHealth",
            selectable = true,
        )
        val ui = candidate("FlickEngine.DiskInfoBoxMain.get_MaxHealth")
        val setter = candidate("FlickEngine.Health.ChangeMaxHealthByAmount")
        val otherCategory = candidate(
            "FlickEngine.CharacterStamina.get_MaxStamina",
            category = GameplayModificationCategory.STAMINA,
        )
        val categories = GameplayCandidateTriage.overview(
            listOf(otherCategory, ready, ui, setter),
        )
        assertEquals(GameplayModificationCategory.SURVIVABILITY, categories[0].category)
        assertEquals(3, categories[0].total)
        assertEquals(1, categories[0].ready)
        assertEquals(2, categories[0].needsResearch)
        assertEquals(2, categories[0].stateCandidates)
        assertEquals(1, categories[0].displayOnlyCandidates)
        assertEquals(GameplayModificationCategory.STAMINA, categories[1].category)
    }

    @Test
    fun displayOnlyMethodIsNotMadeSelectableByTriage() {
        val ui = candidate("FlickEngine.DiskInfoBoxMain.get_MaxHealth")
        assertFalse(ui.selectable)
        assertEquals(
            GameplayPatchReadiness.NEEDS_RESEARCH,
            GameplayCandidateTriage.readiness(ui),
        )
    }
}
