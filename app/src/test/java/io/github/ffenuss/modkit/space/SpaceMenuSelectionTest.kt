package io.github.ffenuss.modkit.space

import org.junit.Assert.*
import org.junit.Test

class SpaceMenuSelectionTest {
    data class Entry(val id: Int, val ready: Boolean)
    @Test fun executableRecipesSurviveAFullCandidatePrefix() {
        val candidates = (0 until 260).map { Entry(it, false) }
        val ready = listOf(Entry(300, true), Entry(301, true))
        val selected = SpaceMenuSelection.select(candidates + ready, 128) { it.ready }
        assertEquals(128, selected.size)
        assertEquals(ready, selected.take(2))
        assertEquals(candidates.take(126), selected.drop(2))
    }
    @Test fun capacityAndGenreOrderRemainStableWithoutInventingReadiness() {
        val items = listOf(Entry(1, false), Entry(2, true), Entry(3, false), Entry(4, true))
        assertEquals(listOf(items[1], items[3], items[0]), SpaceMenuSelection.select(items, 3) { it.ready })
        assertTrue(SpaceMenuSelection.select(items, 0) { it.ready }.isEmpty())
        assertEquals(items, SpaceMenuSelection.select(items, 128) { false })
        assertEquals(items.take(2), SpaceMenuSelection.select(items, 2) { true })
    }
}
