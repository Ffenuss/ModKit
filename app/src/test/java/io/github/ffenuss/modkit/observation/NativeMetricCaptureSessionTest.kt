package io.github.ffenuss.modkit.observation

import io.github.ffenuss.modkit.analysis.NativeScalarExportCandidate
import io.github.ffenuss.modkit.analysis.NativeScalarEffectPhase
import io.github.ffenuss.modkit.analysis.NativeVisibleMetricExperiment
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NativeMetricCaptureSessionTest {
    private val candidate = NativeScalarExportCandidate(
        abi = "x86_64", symbol = "sample_native",
        binaryVirtualAddress = 0x700, fileOffset = 0x700,
        patchVirtualAddress = 0x701, value = 2,
        originalBodyHex = "b802000000c3", expectedPatchHex = "02000000",
    )
    private val plan = NativeVisibleMetricExperiment.prepare(
        originalArtifactSha256 = "cf".repeat(32),
        packageName = "com.example.offlinegame",
        moduleName = "libgame.so",
        actionLabel = "Move",
        metricLabel = "Distance",
        candidate = candidate,
        replacementValue = 7,
    )

    @Before fun resetBefore() = NativeMetricCaptureSession.clear()
    @After fun resetAfter() = NativeMetricCaptureSession.clear()

    @Test fun recordsEightExplicitMeasurementsWithNoAutomaticModAuthorization() {
        NativeMetricCaptureSession.begin(plan)
        assertTrue(NativeMetricCaptureSession.state.value.active)
        val values = listOf(0, 2, 4, 11, 13, 20, 22, 24)
        values.forEachIndexed { index, value ->
            assertEquals(NativeScalarEffectPhase.entries[index],
                NativeMetricCaptureSession.state.value.nextPhase)
            assertTrue(NativeMetricCaptureSession.recordFromUserTap(
                plan.packageName, "Distance: " + value,
                elapsedRealtimeMs = (index + 1) * 1000L,
            ))
        }
        val state = NativeMetricCaptureSession.state.value
        assertTrue(state.complete)
        assertFalse(state.active)
        assertNull(state.nextPhase)
        assertEquals(values.map(Int::toLong), state.observations.map {
            it.nodeText.substringAfter(": ").toLong()
        })
        val result = requireNotNull(NativeMetricCaptureSession.evaluate())
        assertFalse(result.selectable)
        assertEquals("REPLICATED_UI_METRIC_ASSOCIATION_ONLY", result.status)
        assertEquals(listOf(2L, 2L, 7L, 2L, 7L, 2L, 2L), result.observedDeltas)
        assertFalse(NativeMetricCaptureSession.recordFromUserTap(
            plan.packageName, "Distance: 26", 9000L))
    }

    @Test fun rejectsWrongPackageUnrelatedMetricAndRapidDuplicateCapture() {
        NativeMetricCaptureSession.begin(plan)
        assertFalse(NativeMetricCaptureSession.recordFromUserTap(
            "com.example.other", "Distance: 0", 1000L))
        assertEquals(0, NativeMetricCaptureSession.state.value.observations.size)
        assertFalse(NativeMetricCaptureSession.recordFromUserTap(
            plan.packageName, "Enemy Distance: 0", 1000L))
        assertEquals(0, NativeMetricCaptureSession.state.value.observations.size)
        assertTrue(NativeMetricCaptureSession.recordFromUserTap(
            plan.packageName, "Distance: 0", 1000L))
        assertFalse(NativeMetricCaptureSession.recordFromUserTap(
            plan.packageName, "Distance: 2", 1001L))
        assertEquals(1, NativeMetricCaptureSession.state.value.observations.size)
        assertEquals(NativeScalarEffectPhase.UNMODIFIED_ACTION,
            NativeMetricCaptureSession.state.value.nextPhase)
        assertTrue(NativeMetricCaptureSession.recordFromUserTap(
            plan.packageName, "Distance: 2", 1300L))
        assertEquals(2, NativeMetricCaptureSession.state.value.observations.size)
    }

    @Test fun stopPurgesAllInMemoryMetricSamplesAndProhibitsPostStopCapture() {
        NativeMetricCaptureSession.begin(plan)
        NativeMetricCaptureSession.recordFromUserTap(plan.packageName, "Distance: 0", 1000L)
        assertEquals(1, NativeMetricCaptureSession.state.value.observations.size)
        NativeMetricCaptureSession.clear()
        assertNull(NativeMetricCaptureSession.state.value.plan)
        assertEquals(0, NativeMetricCaptureSession.state.value.observations.size)
        assertFalse(NativeMetricCaptureSession.recordFromUserTap(
            plan.packageName, "Distance: 2", 2000L))
        assertNull(NativeMetricCaptureSession.evaluate())
    }

    @Test fun acceptsOnlyOneActiveSessionAndRejectsForgedPlan() {
        NativeMetricCaptureSession.begin(plan)
        try {
            NativeMetricCaptureSession.begin(plan)
            fail("Cannot launch a second concurrent capture")
        } catch (_: IllegalStateException) { }
        NativeMetricCaptureSession.clear()
        try {
            NativeMetricCaptureSession.begin(plan.copy(
                proposedPatch = plan.proposedPatch.copy(replacementHex = "ffffffff")))
            fail("Cannot start with stale or fabricated exact native bytes")
        } catch (_: IllegalArgumentException) { }
        assertNull(NativeMetricCaptureSession.state.value.plan)
    }

    @Test fun noInterventionCannotPassEvenWithEightUserSamples() {
        NativeMetricCaptureSession.begin(plan)
        for (index in 0 until 8) {
            assertTrue(NativeMetricCaptureSession.recordFromUserTap(
                plan.packageName, "Distance: " + index * 2, index * 1000L + 1000L))
        }
        assertTrue(NativeMetricCaptureSession.state.value.complete)
        assertNull(NativeMetricCaptureSession.evaluate())
        assertNull(NativeMetricCaptureSession.state.value.association)
        assertTrue(NativeMetricCaptureSession.state.value.error!!.isNotBlank())
    }

    @Test fun errorsRemainBoundedAndPhasePromptsCoverAllActions() {
        NativeMetricCaptureSession.begin(plan)
        NativeMetricCaptureSession.reportError("x".repeat(10_000))
        assertEquals(180, NativeMetricCaptureSession.state.value.error?.length)
        assertEquals(8, NativeScalarEffectPhase.entries.size)
        assertTrue(NativeScalarEffectPhase.entries.all { it.userInstruction().isNotBlank() })
        assertTrue(NativeMetricCaptureSession.state.value.observations.isEmpty())
    }
}
