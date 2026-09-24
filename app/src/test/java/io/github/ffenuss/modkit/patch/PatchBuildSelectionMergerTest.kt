package io.github.ffenuss.modkit.patch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PatchBuildSelectionMergerTest {
    private data class Draft(val target: String, val label: String)

    @Test
    fun oneClickSelectionOverridesSameTargetWithoutDroppingOtherDrafts() {
        val queued = listOf(
            Draft("health", "manual-old"),
            Draft("speed", "manual-speed"),
        )
        val selected = listOf(
            Draft("health", "checkbox-new"),
            Draft("damage", "checkbox-damage"),
        )
        val result = PatchBuildSelectionMerger.merge(
            queued, selected, Draft::target,
        )
        assertEquals(
            listOf("manual-speed", "checkbox-new", "checkbox-damage"),
            result.map(Draft::label),
        )
    }

    @Test
    fun noNewCheckboxSelectionsPreservesPreviouslyQueuedChanges() {
        val queued = listOf(Draft("health", "manual"))
        assertEquals(
            queued,
            PatchBuildSelectionMerger.merge(queued, emptyList(), Draft::target),
        )
    }

    @Test
    fun duplicateSelectedTargetFailsBeforeMutatingAnyApk() {
        assertThrows(IllegalArgumentException::class.java) {
            PatchBuildSelectionMerger.merge(
                emptyList(),
                listOf(Draft("health", "first"), Draft("health", "second")),
                Draft::target,
            )
        }
    }

    @Test
    fun duplicateQueuedTargetIsReportedRatherThanSilentlyDiscarded() {
        assertThrows(IllegalArgumentException::class.java) {
            PatchBuildSelectionMerger.merge(
                listOf(Draft("health", "old"), Draft("health", "old-again")),
                listOf(Draft("health", "replacement")),
                Draft::target,
            )
        }
    }
}
