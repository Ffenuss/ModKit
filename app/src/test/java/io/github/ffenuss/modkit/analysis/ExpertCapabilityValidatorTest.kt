package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertCapabilityValidatorTest {
    @Test
    fun acceptsCurrentRegisteredAvailableBackends() {
        val report = ExpertCapabilityValidator.validate(
            EngineRoutingPlan(
                engines = listOf(
                    PlannedEngine(
                        id = "artifact.fast-index",
                        scheduleClass = EngineScheduleClass.FAST,
                        availableNow = true,
                        reason = "index",
                    ),
                    PlannedEngine(
                        id = "il2cpp.fast-dump",
                        scheduleClass = EngineScheduleClass.TARGETED,
                        availableNow = true,
                        reason = "dump",
                    ),
                    PlannedEngine(
                        id = "il2cpp.codegen-bind",
                        scheduleClass = EngineScheduleClass.CONFIRMATION,
                        availableNow = true,
                        reason = "bind",
                    ),
                ),
                missingCapabilities = emptyList(),
            ),
        )

        assertTrue(report.valid)
        assertTrue(report.blockers.isEmpty())
    }

    @Test
    fun rejectsRouterClaimWithoutSchedulerExecutor() {
        val report = ExpertCapabilityValidator.validate(
            EngineRoutingPlan(
                engines = listOf(
                    PlannedEngine(
                        id = "future.backend",
                        scheduleClass = EngineScheduleClass.TARGETED,
                        availableNow = true,
                        reason = "incorrect claim",
                    ),
                ),
                missingCapabilities = emptyList(),
            ),
        )

        assertFalse(report.valid)
        assertTrue(report.blockers.single().contains("future.backend"))
    }

    @Test
    fun unavailableBackendIsNotAConsistencyFailure() {
        val report = ExpertCapabilityValidator.validate(
            EngineRoutingPlan(
                engines = listOf(
                    PlannedEngine(
                        id = "future.backend",
                        scheduleClass = EngineScheduleClass.TARGETED,
                        availableNow = false,
                        reason = "pending",
                    ),
                ),
                missingCapabilities = listOf("pending"),
            ),
        )

        assertTrue(report.valid)
    }
}
