package io.github.ffenuss.modkit.analysis

import org.junit.Assert.*
import org.junit.Test

/** No device needed: gate correctness is tested independently of UIAutomator. */
class NativeScalarEffectCorrelationTest {
    private val native = NativeScalarExportCandidate(
        abi = "x86_64",
        symbol = "example_native_scalar",
        binaryVirtualAddress = 0x700,
        fileOffset = 0x700,
        patchVirtualAddress = 0x701,
        value = 2,
        originalBodyHex = "b802000000c3",
        expectedPatchHex = "02000000",
    )
    private val patch = NativeScalarExportDiscovery.propose(native, 7)
    private val sha = "ae".repeat(32)

    private fun samples(
        values: List<Long> = listOf(0, 2, 4, 11, 13, 20, 22, 24),
        times: List<Long> = (1..8).map { it * 100L },
        phases: List<NativeScalarEffectPhase> = NativeScalarEffectPhase.entries,
    ): List<NativeScalarUiObservation> =
        values.mapIndexed { index, counter ->
            NativeScalarUiObservation(phases[index], counter, times[index])
        }

    private fun check(
        candidate: NativeScalarExportCandidate = native,
        proposed: NativeScalarExportPatch = patch,
        observations: List<NativeScalarUiObservation> = samples(),
        expectedSha: String = sha,
        gamePackage: String = "dev.modkit.nativefixture",
        module: String = "libmodkit_native_fixture.so",
        label: String = "Native move",
        targetValue: Int = 7,
    ): NativeScalarEffectConfirmation =
        NativeScalarEffectCorrelation.confirm(
            originalArtifactSha256 = expectedSha,
            packageName = gamePackage,
            moduleName = module,
            actionLabel = label,
            candidate = candidate,
            patch = proposed,
            modifiedStep = targetValue,
            observations = observations,
        )

    private fun denied(task: () -> Unit) {
        try {
            task()
            fail("Invalid experiment evidence must fail closed")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun acceptsTwoBaselineControlsAndTwoIndependentOnOffCycles() {
        val result = check()
        assertEquals("CI-owned fixture; independent UI hierarchy samples", result.source)
        assertEquals(2, result.baselineControlActions)
        assertEquals(2, result.completedPatchOnOffCycles)
        assertEquals(sha, result.originalArtifactSha256)
        assertEquals(patch.patchVirtualAddress, result.patchVirtualAddress)
        assertEquals(listOf(0L, 2L, 4L, 11L, 13L, 20L, 22L, 24L),
            result.observations.map { it.displayedCounter })
        assertEquals(listOf(2L, 2L, 7L, 2L, 7L, 2L, 2L),
            result.observedDeltas)
        assertEquals(2, result.unmodifiedStep)
        assertEquals(7, result.modifiedStep)
    }

    @Test fun decodesArm64CandidateWithoutClaimingPhysicalDeviceEffect() {
        val arm = NativeScalarExportCandidate(
            abi = "arm64-v8a",
            symbol = "example_arm_scalar",
            binaryVirtualAddress = 0x688,
            fileOffset = 0x688,
            patchVirtualAddress = 0x688,
            value = 2,
            originalBodyHex = "40008052c0035fd6",
            expectedPatchHex = "40008052",
        )
        val result = check(
            candidate = arm,
            proposed = NativeScalarExportDiscovery.propose(arm, 7),
        )
        assertEquals("arm64-v8a", result.abi)
        assertEquals("e0008052", result.replacementHex)
    }

    @Test fun rejectsMissingControlOrOneTimeCoincidentalEffect() {
        denied { check(observations = samples(values = listOf(0, 2, 9, 16, 18, 25, 27, 29))) }
        denied { check(observations = samples(values = listOf(0, 2, 4, 9, 11, 18, 20, 22))) }
        denied { check(observations = samples(values = listOf(0, 2, 4, 11, 13, 18, 20, 22))) }
        denied { check(observations = samples(values = listOf(0, 2, 4, 11, 20, 27, 29, 31))) }
        denied { check(observations = samples(values = listOf(0, 2, 4, 11, 13, 20, 27, 29))) }
        denied { check(observations = samples(values = listOf(0, 2, 4, 11, 13, 20, 22, 29))) }
        denied { check(observations = samples(values = listOf(0, 2, 4, 11, 13, 20, 22))) }
        denied { check(observations = samples() +
            NativeScalarUiObservation(NativeScalarEffectPhase.OVERLAY_CLOSED_ACTION, 26, 900)) }
    }

    @Test fun rejectsRepeatedOutOfOrderOrSpoofedUiObservationTimestamps() {
        val all = NativeScalarEffectPhase.entries
        denied { check(observations = samples(phases = listOf(
            all[0], all[1], all[2], all[3], all[3], all[5], all[6], all[7]))) }
        denied { check(observations = samples(phases = listOf(
            all[0], all[1], all[2], all[3], all[5], all[4], all[6], all[7]))) }
        denied { check(observations = samples(times = listOf(100, 200, 300, 400, 400, 600, 700, 800))) }
        denied { check(observations = samples(times = listOf(100, 200, 300, 400, 399, 600, 700, 800))) }
        denied { check(observations = samples(times = listOf(100, 200, 0, 400, 500, 600, 700, 800))) }
        denied { check(observations = samples(values = listOf(0, 2, 4, -11, 13, 20, 22, 24))) }
        denied { check(observations = samples(values = listOf(0, 2, 4, 11, 13, 20, 22, 1_000_000_001))) }
    }

    @Test fun rejectsStalePatchSourceOrUnexpectedSelectableCandidate() {
        denied { check(expectedSha = "garbage") }
        denied { check(expectedSha = "AB".repeat(32)) }
        denied { check(gamePackage = "bad package") }
        denied { check(gamePackage = "com.example.unrelatedgame") }
        denied { check(module = "libother_game.so") }
        denied { check(module = "../lib.so") }
        denied { check(module = "not-a-native-module") }
        denied { check(label = " ") }
        denied { check(candidate = native.copy(selectable = true)) }
        denied { check(candidate = native.copy(expectedPatchHex = "ffffffff")) }
        denied { check(candidate = native.copy(symbol = "")) }
        denied { check(proposed = patch.copy(patchVirtualAddress = patch.patchVirtualAddress + 1)) }
        denied { check(proposed = patch.copy(replacementHex = "ffffffff")) }
        denied { check(targetValue = 2) }
        denied { check(targetValue = 999_999) }
    }
}
