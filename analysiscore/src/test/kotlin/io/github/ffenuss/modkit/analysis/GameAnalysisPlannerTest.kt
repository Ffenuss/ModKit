package io.github.ffenuss.modkit.analysis

import org.junit.Assert.*
import org.junit.Test

class GameAnalysisPlannerTest {
    private val index = ArtifactIndex("a".repeat(64), emptyList(), emptyList())
    @Test fun genreNeedsIndependentTermsAndPreservesActualEvidence() {
        val plan = GameAnalysisPlanner.plan(index, listOf("GetAmmo", "GetAmmo", "ReloadWeapon"))
        assertEquals(GameGenre.SHOOTER, plan.genre.genre)
        assertEquals(listOf("GetAmmo", "ReloadWeapon"), plan.genre.evidence)
        assertTrue("Боезапас" in plan.searchPriorities)
    }
    @Test fun genericOrAmbiguousSignalsDoNotAssertGenre() {
        assertEquals(GameGenre.UNKNOWN, GameAnalysisPlanner.plan(index, List(1000) { "GetAmmo" }).genre.genre)
        assertEquals(GameGenre.UNKNOWN, GameAnalysisPlanner.plan(index, listOf("Ammo", "Reload", "Quest", "Inventory")).genre.genre)
        assertEquals(GameGenre.UNKNOWN, GameAnalysisPlanner.plan(index, listOf("Health", "Speed", "Update")).genre.genre)
    }
    @Test fun engineDetectionRemainsIndependentFromGenreAndCoverageIsExplicit() {
        val runtime = RuntimeProfile("flutter", "Flutter", DetectionStatus.LIKELY, DetectionConfidence.MEDIUM, listOf("base:libflutter.so"))
        val plan = GameAnalysisPlanner.plan(index.copy(runtimeProfiles = listOf(runtime), truncated = true), emptyList(), GameGenre.APPLICATION)
        assertEquals(listOf(runtime), plan.engines)
        assertEquals(GameGenre.APPLICATION, plan.genre.genre)
        assertTrue(plan.limitations.contains("Индекс файлов неполный"))
    }
}
