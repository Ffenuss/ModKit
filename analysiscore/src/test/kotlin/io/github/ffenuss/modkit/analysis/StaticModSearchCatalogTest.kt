package io.github.ffenuss.modkit.analysis

import org.junit.Assert.*
import org.junit.Test

class StaticModSearchCatalogTest {
    private val neverCancel = object : CancellationSignal { override fun isCancelled() = false }

    @Test fun everyGenreRunsEverySetExactlyOnce() {
        val ids = StaticModSearchCatalog.rules.map { it.id }
        assertEquals(30, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        GameGenre.values().forEach { genre ->
            assertEquals(ids.toSet(), StaticModSearchCatalog.orderedFor(genre).map { it.id }.toSet())
            assertEquals(ids.size, StaticModSearchCatalog.orderedFor(genre).size)
        }
        assertEquals("rhythm", StaticModSearchCatalog.orderedFor(GameGenre.RHYTHM).first().id)
    }

    @Test fun allConfiguredPhrasesAreReachable() {
        StaticModSearchCatalog.rules.forEach { rule -> rule.phrases.forEach { phrase ->
            assertTrue("${rule.id}: $phrase", StaticModSearchCatalog.signals(phrase).any { it.id == rule.id })
        } }
    }

    @Test fun camelCaseAcronymsAndSeparatorsMatchWholeTokens() {
        fun ids(name: String) = StaticModSearchCatalog.signals(name).map { it.id }.toSet()
        assertTrue("health" in ids("get_CurrentHP"))
        assertTrue("vehicle" in ids("getNitro"))
        assertTrue("survival-needs" in ids("get_hunger"))
        assertTrue("rhythm" in ids("getHitWindow"))
        assertTrue("movement" in ids("getMovementSpeed"))
        assertTrue("puzzle-budget" in ids("get_remaining_moves"))
        listOf("Coincidence", "GoldenRatio", "UIGradient", "Breathless", "Damageable", "XPander").forEach {
            assertTrue(it, ids(it).isEmpty())
        }
    }

    @Test fun surveyIsBoundedAndCountsEachRuleOncePerMember() {
        val survey = StaticModSearchSurvey(neverCancel)
        repeat(10000) { survey.observe("getHealthAndMaxHP") }
        assertEquals(10000L, survey.symbolsExamined)
        assertEquals(10000L, survey.hitCount("health"))
        assertEquals(0L, survey.hitCount("vehicle"))
    }

    @Test(expected = AnalysisCancelledException::class)
    fun cancelledSurveyDoesNotReadAnotherSymbol() {
        StaticModSearchSurvey(object : CancellationSignal { override fun isCancelled() = true }).observe("getHealth")
    }
}
