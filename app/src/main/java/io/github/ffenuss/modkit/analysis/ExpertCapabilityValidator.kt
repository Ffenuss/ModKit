package io.github.ffenuss.modkit.analysis

data class ExpertBackendCapability(
    val engineId: String,
    val routerAvailable: Boolean,
    val executorRegistered: Boolean,
    val consistent: Boolean,
    val reason: String,
)

data class ExpertCapabilityReport(
    val capabilities: List<ExpertBackendCapability>,
    val blockers: List<String>,
) {
    val valid: Boolean
        get() = blockers.isEmpty()
}

/**
 * Detects router/executor drift before Expert Lab offers a direct action.
 */
object ExpertCapabilityValidator {
    fun validate(plan: EngineRoutingPlan): ExpertCapabilityReport {
        val rows = plan.engines.map { engine ->
            val registered = when (engine.id) {
                "artifact.fast-index" -> true
                else -> RoutedEngineScheduler.supportsEngine(engine.id)
            }
            ExpertBackendCapability(
                engineId = engine.id,
                routerAvailable = engine.availableNow,
                executorRegistered = registered,
                consistent = !engine.availableNow || registered,
                reason = engine.reason,
            )
        }
        val blockers = rows
            .filter { !it.consistent }
            .map {
                it.engineId +
                    ": router marks backend available, but no scheduler executor is registered."
            }
        return ExpertCapabilityReport(
            capabilities = rows,
            blockers = blockers,
        )
    }
}
