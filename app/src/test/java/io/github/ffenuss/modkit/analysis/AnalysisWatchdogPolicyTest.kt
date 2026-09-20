package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisWatchdogPolicyTest {
    @Test
    fun confirmationStagesGetLongerHeartbeatWindowThanFastStages() {
        assertTrue(
            AnalysisWatchdogPolicy.stalledAfterMs(
                EngineScheduleClass.CONFIRMATION,
            ) >
                AnalysisWatchdogPolicy.stalledAfterMs(
                    EngineScheduleClass.FAST,
                ),
        )
    }

    @Test
    fun thresholdsAreExplicitForEveryScheduleClass() {
        assertEquals(
            20_000L,
            AnalysisWatchdogPolicy.stalledAfterMs(
                EngineScheduleClass.FAST,
            ),
        )
        assertEquals(
            30_000L,
            AnalysisWatchdogPolicy.stalledAfterMs(
                EngineScheduleClass.TARGETED,
            ),
        )
        assertEquals(
            60_000L,
            AnalysisWatchdogPolicy.stalledAfterMs(
                EngineScheduleClass.CONFIRMATION,
            ),
        )
        assertEquals(
            60_000L,
            AnalysisWatchdogPolicy.stalledAfterMs(
                EngineScheduleClass.BACKGROUND,
            ),
        )
    }

    @Test
    fun missingProgressKeepsConservativeFastThreshold() {
        assertEquals(
            AnalysisWatchdogPolicy.FAST_STALLED_AFTER_MS,
            AnalysisWatchdogPolicy.stalledAfterMs(null),
        )
    }

    @Test
    fun largeIl2CppExtractionGetsIoSpecificWindow() {
        assertEquals(
            180_000L,
            AnalysisWatchdogPolicy.stalledAfterMs(
                scheduleClass =
                    EngineScheduleClass.CONFIRMATION,
                currentTask =
                    "IL2CPP: извлечение libil2cpp.so",
            ),
        )
        assertEquals(
            60_000L,
            AnalysisWatchdogPolicy.stalledAfterMs(
                scheduleClass =
                    EngineScheduleClass.CONFIRMATION,
                currentTask =
                    "IL2CPP: relocated CodeGenModule signatures",
            ),
        )
    }
}
