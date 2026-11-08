package io.github.ffenuss.modkit.analysis

import org.junit.Assert.*
import org.junit.Test

class NativeVisibleMetricExperimentTest {
    private val candidate = NativeScalarExportCandidate(
        abi = "x86_64",
        symbol = "obfuscated_native_export",
        binaryVirtualAddress = 0x700,
        fileOffset = 0x700,
        patchVirtualAddress = 0x701,
        value = 2,
        originalBodyHex = "b802000000c3",
        expectedPatchHex = "02000000",
    )
    private val sha = "ab".repeat(32)

    private fun plan(
        game: String = "com.example.arcade",
        label: String = "Distance",
        action: String = "Move",
        replacementValue: Int = 7,
        export: NativeScalarExportCandidate = candidate,
    ) = NativeVisibleMetricExperiment.prepare(
        sha, game, "libgame.so", action, label, export, replacementValue,
    )

    private fun samples(
        plan: NativeMetricExperimentPlan,
        values: List<Long>,
    ): List<NativeVisibleMetricObservation> {
        require(values.size == NativeScalarEffectPhase.entries.size)
        return values.mapIndexed { i, value ->
            NativeVisibleMetricObservation(
                phase = NativeScalarEffectPhase.entries[i],
                sourcePackage = plan.packageName,
                nodeText = plan.metricLabel + ": " + value,
                elapsedRealtimeMs = 100L * (i + 1),
            )
        }
    }

    private fun denied(block: () -> Unit) {
        try {
            block()
            fail("Expected fail-closed rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun parsesOnlyUnambiguousExactUiMetricLabelsWithoutFuzzyMatching() {
        assertEquals(17L, NativeVisibleCounterParser.parseSingle("HP", "HP: 17"))
        assertEquals(-3L, NativeVisibleCounterParser.parseSingle("Speed", "Speed = -3"))
        assertEquals(2048L, NativeVisibleCounterParser.parseUnique(
            "XP", listOf("name: 42", "XP: 2048", "Enemy XP: 99"),
        ))
        denied { NativeVisibleCounterParser.parseSingle("HP", "Enemy HP: 17") }
        denied { NativeVisibleCounterParser.parseSingle("HP", "HP: 1.7") }
        denied { NativeVisibleCounterParser.parseSingle("HP", "HP: 1e10") }
        denied { NativeVisibleCounterParser.parseSingle("HP", "HP: 1000000001") }
        denied { NativeVisibleCounterParser.parseSingle("HP", "HP: 2\nAccount: 19") }
        denied { NativeVisibleCounterParser.parseSingle("HP", "HP: --2") }
        denied { NativeVisibleCounterParser.parseSingle("HP", "HP: 1,000") }
        denied { NativeVisibleCounterParser.parseUnique("HP", listOf("HP: 1", "HP: 2")) }
        denied { NativeVisibleCounterParser.parseUnique("HP", listOf("player: 1")) }
        denied { NativeVisibleCounterParser.parseSingle(".*", ".*: 123") }
    }

    @Test fun recognizesReplicatedEffectForNonFixturePackageWithoutEnablingPatches() {
        val p = plan()
        val values = listOf(0, 2, 4, 11, 13, 20, 22, 24).map(Int::toLong)
        val observed = NativeVisibleMetricExperiment.assess(p, samples(p, values))
        assertEquals("com.example.arcade", observed.packageName)
        assertEquals(2L, observed.baselineDelta)
        assertEquals(7L, observed.interventionDelta)
        assertEquals(listOf(2L, 2L, 7L, 2L, 7L, 2L, 2L), observed.observedDeltas)
        assertEquals(2, observed.repeatedOnOffCycles)
        assertFalse(observed.selectable)
        assertTrue(observed.status.contains("ASSOCIATION_ONLY"))
        assertTrue(observed.blocker.contains("not verified code-to-gameplay causality"))
        assertEquals(64, observed.fingerprint.length)
    }

    @Test fun supportsHealthLikeDecreasingCountersAndZeroBaseline() {
        val p = plan(game = "com.example.offline", label = "Health", action = "Take hit")
        val result = NativeVisibleMetricExperiment.assess(
            p, samples(p, listOf(100, 98, 96, 90, 88, 82, 80, 78).map(Int::toLong))
        )
        assertEquals(-2L, result.baselineDelta)
        assertEquals(-6L, result.interventionDelta)
        val q = plan(game = "com.example.idle", label = "Charges", action = "Idle turn")
        val zero = NativeVisibleMetricExperiment.assess(
            q, samples(q, listOf(4, 4, 4, 7, 7, 10, 10, 10).map(Int::toLong))
        )
        assertEquals(0L, zero.baselineDelta)
        assertEquals(3L, zero.interventionDelta)
    }

    @Test fun rejectsOneOffEffectsAndFailureToRestoreAfterClose() {
        val p = plan()
        val good = listOf(0L, 2, 4, 11, 13, 20, 22, 24)
        for (at in 1..7) {
            val altered = good.toMutableList()
            altered[at] += 1
            denied { NativeVisibleMetricExperiment.assess(p, samples(p, altered)) }
        }
        denied { NativeVisibleMetricExperiment.assess(p, samples(
            p, listOf(0,2,4,6,8,10,12,14).map(Int::toLong),
        )) }
        denied { NativeVisibleMetricExperiment.assess(p, samples(p, good).dropLast(1)) }
    }

    @Test fun refusesOtherPackageWrongOrNonMonotonicUiSamples() {
        val p = plan()
        val good = samples(p, listOf(0, 2, 4, 11, 13, 20, 22, 24).map(Int::toLong))
        denied { NativeVisibleMetricExperiment.assess(p, good.mapIndexed { i, sample ->
            if (i == 3) sample.copy(sourcePackage = "com.example.other") else sample
        }) }
        denied { NativeVisibleMetricExperiment.assess(p, good.mapIndexed { i, sample ->
            if (i == 3) sample.copy(elapsedRealtimeMs = 200) else sample
        }) }
        denied { NativeVisibleMetricExperiment.assess(p, good.mapIndexed { i, sample ->
            if (i == 4) sample.copy(phase = NativeScalarEffectPhase.PATCH_ENABLED_ACTION) else sample
        }) }
        denied { NativeVisibleMetricExperiment.assess(p, good.mapIndexed { i, sample ->
            if (i == 5) sample.copy(nodeText = "Enemy Distance: 20") else sample
        }) }
    }

    @Test fun refusesForgedOrStaleNativeIdentityAndNeverCreatesRunnableMenuItem() {
        denied { plan(game = "wrong package") }
        denied { plan(label = ".*") }
        denied { plan(action = "  Move") }
        denied { plan(export = candidate.copy(selectable = true)) }
        denied { plan(export = candidate.copy(expectedPatchHex = "ffffffff")) }
        denied { plan(replacementValue = 2) }
        denied { plan(replacementValue = 70000) }
        val p = plan()
        val observations = samples(p, listOf(0,2,4,11,13,20,22,24).map(Int::toLong))
        denied { NativeVisibleMetricExperiment.assess(
            p.copy(originalArtifactSha256 = "de".repeat(32)), observations,
        ) }
        denied { NativeVisibleMetricExperiment.assess(
            p.copy(proposedPatch = p.proposedPatch.copy(replacementHex = "ffffffff")),
            observations,
        ) }
        denied { NativeVisibleMetricExperiment.assess(
            p.copy(fingerprint = "aa".repeat(32)), observations,
        ) }
        denied { NativeVisibleMetricExperiment.assess(
            p.copy(metricLabel = "Coins"), observations,
        ) }
    }
}
