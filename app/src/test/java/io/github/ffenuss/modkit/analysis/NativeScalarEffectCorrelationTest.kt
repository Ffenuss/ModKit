package io.github.ffenuss.modkit.analysis

import org.junit.Assert.*
import org.junit.Test

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
        values: List<Long> = listOf(0, 2, 9, 11, 13),
        times: List<Long> = listOf(100, 200, 300, 400, 500),
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
            fail("Invalid evidence must fail closed")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun acceptsMeasuredAndReversibleGameCounter() {
        val result = check()
        assertEquals("CI-owned fixture; independent UI hierarchy samples", result.source)
        assertEquals(sha, result.originalArtifactSha256)
        assertEquals(patch.patchVirtualAddress, result.patchVirtualAddress)
        assertEquals(listOf(0L, 2L, 9L, 11L, 13L),
            result.observations.map { it.displayedCounter })
        assertEquals(listOf(2L, 7L, 2L, 2L), result.observedDeltas)
        assertEquals(2, result.unmodifiedStep)
        assertEquals(7, result.modifiedStep)
    }

    @Test fun supportsRealArm64ScalarCandidateWithoutClaimingDeviceExecution() {
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

    @Test fun rejectsMissingOrForgedGameEffect() {
        denied { check(observations = samples(values = listOf(0, 2, 4, 6, 8))) }
        denied { check(observations = samples(values = listOf(0, 2, 8, 10, 12))) }
        denied { check(observations = samples(values = listOf(0, 2, 9, 16, 18))) }
        denied { check(observations = samples(values = listOf(0, 2, 9, 11, 18))) }
        denied { check(observations = samples(values = listOf(0, 2, 9, 11, 12))) }
        denied { check(observations = samples(values = listOf(0, 2, 9, 11, 13, 15))) }
    }

    @Test fun rejectsWrongSequenceAndNonMonotonicObservations() {
        val all = NativeScalarEffectPhase.entries
        denied { check(observations = samples(phases = listOf(all[0], all[1], all[2], all[2], all[4]))) }
        denied { check(observations = samples(phases = listOf(all[0], all[1], all[3], all[2], all[4]))) }
        denied { check(observations = samples(times = listOf(100, 200, 300, 300, 500))) }
        denied { check(observations = samples(times = listOf(100, 200, 300, 299, 500))) }
        denied { check(observations = samples(times = listOf(100, 200, 0, 400, 500))) }
        denied { check(observations = samples(values = listOf(0, 2, -9, 11, 13))) }
        denied { check(observations = samples(values = listOf(0, 2, 9, 11, 1_000_000_001))) }
    }

    @Test fun rejectsStaleOrMutatedNativePatchIdentity() {
        denied { check(expectedSha = "garbage") }
        denied { check(expectedSha = "AB".repeat(32)) }
        denied { check(gamePackage = "bad package") }
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
