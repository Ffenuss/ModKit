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
    fun trainingHidesPointerLikeLargeIntegers() {
        val noisy =
            BehavioralRuntimeCandidate(
                id = "n",
                address = 0x1000,
                valueType =
                    RuntimeValueType.INT32,
                value =
                    "1895537896",
                previousValue = null,
                title = "Кандидат движения",
                confidence = 70,
                changeCount = 4,
                stableCount = 2,
                increaseCount = 2,
                decreaseCount = 2,
                observedSamples = 6,
                regionPath = "[heap]",
                activityTransitions = 2,
            )

        assertTrue(
            !RootBehavioralScanCoordinator
                .trainingCandidateRelevant(
                    noisy,
                ),
        )
    }

    @Test
    fun emergingAutoAcceptsRepeatedPlausibleGameplayValue() {
        val plausible =
            BehavioralRuntimeCandidate(
                id = "p",
                address = 0x2000,
                valueType =
                    RuntimeValueType.INT32,
                value = "87",
                previousValue = "91",
                title =
                    "Повторяющееся действие обнаружено",
                confidence = 65,
                changeCount = 3,
                stableCount = 2,
                increaseCount = 0,
                decreaseCount = 3,
                observedSamples = 5,
                regionPath = "[heap]",
                activityTransitions = 2,
            )

        assertTrue(
            RootBehavioralScanCoordinator
                .emergingAutoCandidateRelevant(
                    plausible,
                ),
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
