package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class RootBehavioralScanCoordinatorTest {
    @Test
    fun repeatedActionWithStableWindowsRanksAboveBackgroundChurn() {
        val action =
            RootBehavioralScanCoordinator
                .confidence(
                    changeCount = 4,
                    stableCount = 5,
                    increaseCount = 0,
                    decreaseCount = 3,
                    observedSamples = 9,
                    distinctValueCount = 4,
                    preferredRegion = true,
                    plausibleValue = true,
                    training = true,
                )

        val churn =
            RootBehavioralScanCoordinator
                .confidence(
                    changeCount = 9,
                    stableCount = 0,
                    increaseCount = 4,
                    decreaseCount = 5,
                    observedSamples = 9,
                    distinctValueCount = 14,
                    preferredRegion = false,
                    plausibleValue = true,
                    training = false,
                )

        assertTrue(
            action > churn,
        )
    }

    @Test
    fun implausibleValuesAreNotRanked() {
        val score =
            RootBehavioralScanCoordinator
                .confidence(
                    changeCount = 5,
                    stableCount = 2,
                    increaseCount = 3,
                    decreaseCount = 2,
                    observedSamples = 7,
                    distinctValueCount = 5,
                    preferredRegion = true,
                    plausibleValue = false,
                    training = false,
                )

        assertTrue(
            score == 0,
        )
    }
}
