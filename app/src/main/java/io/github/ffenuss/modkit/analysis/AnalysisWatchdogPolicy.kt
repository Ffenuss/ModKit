package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass

object AnalysisWatchdogPolicy {
    const val FAST_STALLED_AFTER_MS = 20_000L
    const val TARGETED_STALLED_AFTER_MS = 30_000L
    const val CONFIRMATION_STALLED_AFTER_MS = 60_000L
    const val BACKGROUND_STALLED_AFTER_MS = 60_000L

    fun stalledAfterMs(
        scheduleClass: EngineScheduleClass?,
    ): Long =
        when (scheduleClass) {
            EngineScheduleClass.FAST,
            null -> FAST_STALLED_AFTER_MS
            EngineScheduleClass.TARGETED ->
                TARGETED_STALLED_AFTER_MS
            EngineScheduleClass.CONFIRMATION ->
                CONFIRMATION_STALLED_AFTER_MS
            EngineScheduleClass.BACKGROUND ->
                BACKGROUND_STALLED_AFTER_MS
        }
}
