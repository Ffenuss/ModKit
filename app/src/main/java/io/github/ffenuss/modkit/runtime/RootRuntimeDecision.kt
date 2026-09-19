package io.github.ffenuss.modkit.runtime

import java.io.Serializable

enum class RuntimeStageAttemptState {
    NOT_ATTEMPTED,
    COMPLETED,
    BLOCKED,
    FAILED,
}

enum class RuntimeStageBlockerCategory {
    TARGET_ENVIRONMENT,
    EVIDENCE_GAP,
    EXECUTOR_GAP,
    INVALID_INPUT,
}

data class RuntimeStageBlocker(
    val code: String,
    val message: String,
    val category: RuntimeStageBlockerCategory,
) : Serializable

data class RuntimeStageAttempt(
    val stage: RuntimeEscalationStage,
    val state: RuntimeStageAttemptState,
    val attemptedAtEpochMs: Long?,
    val requestedTargetIds: Set<String>,
    val resolvedTargetIds: Set<String>,
    val blockers: List<RuntimeStageBlocker>,
) : Serializable {
    val unresolvedTargetIds: Set<String>
        get() = requestedTargetIds - resolvedTargetIds
}

data class RootRuntimeDecision(
    val artifactSha256: String,
    val unresolvedTargetIds: Set<String>,
    val evidenceRequiresRoot: Boolean,
    val rootExecutorRegistered: Boolean,
    val readyToRunRoot: Boolean,
    val reasons: List<String>,
    val blockers: List<RuntimeStageBlocker>,
) : Serializable

object RootRuntimeCapabilityRegistry {
    /**
     * No su/Magisk/root command executor is registered in clean ModKit yet.
     * Keep this false until a concrete executor and its tests exist.
     */
    const val executorRegistered: Boolean = false
}

/**
 * Root is a last-resort evidence stage, not a fallback for unfinished ModKit
 * implementation.
 *
 * The decision is fail-closed:
 * - every unresolved runtime target must have passed through repacked and
 *   non-root attempts when those stages are applicable;
 * - EXECUTOR_GAP and INVALID_INPUT never justify root;
 * - root can be evidence-required only for target/environment restrictions or
 *   evidence that remains unresolved after real lower-privilege attempts;
 * - even then, execution is unavailable until a concrete root executor exists.
 */
object RootRuntimeDecisionEngine {
    fun decide(
        plan: RuntimeEscalationPlan,
        attempts: List<RuntimeStageAttempt>,
        rootExecutorRegistered: Boolean =
            RootRuntimeCapabilityRegistry.executorRegistered,
    ): RootRuntimeDecision {
        val unresolved = plan.needs.map { it.targetId }.toSet()
        if (unresolved.isEmpty()) {
            return RootRuntimeDecision(
                artifactSha256 = plan.artifactSha256,
                unresolvedTargetIds = emptySet(),
                evidenceRequiresRoot = false,
                rootExecutorRegistered = rootExecutorRegistered,
                readyToRunRoot = false,
                reasons = listOf("No unresolved runtime evidence target remains."),
                blockers = emptyList(),
            )
        }

        val byStage = attempts.groupBy { it.stage }
        val decisionBlockers = mutableListOf<RuntimeStageBlocker>()
        val reasons = mutableListOf<String>()

        val requiredLowerStages = listOf(
            RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
            RuntimeEscalationStage.NON_ROOT_RUNTIME,
        )

        for (stage in requiredLowerStages) {
            val relevantTargets = unresolved.filterTo(linkedSetOf()) { targetId ->
                plan.needs.any { need ->
                    need.targetId == targetId &&
                        need.firstStage.ordinal <= stage.ordinal
                }
            }
            if (relevantTargets.isEmpty()) continue

            val stageAttempts = byStage[stage].orEmpty()
            val coveredTargets = stageAttempts
                .flatMap { it.requestedTargetIds }
                .toSet()
            val missingTargets = relevantTargets - coveredTargets
            if (missingTargets.isNotEmpty()) {
                decisionBlockers += RuntimeStageBlocker(
                    code = stage.name + "_NOT_ATTEMPTED",
                    message = stage.name +
                        " has no recorded attempt for: " +
                        missingTargets.sorted().joinToString(),
                    category = RuntimeStageBlockerCategory.EVIDENCE_GAP,
                )
            }

            stageAttempts.forEach { attempt ->
                if (attempt.state == RuntimeStageAttemptState.NOT_ATTEMPTED) {
                    decisionBlockers += RuntimeStageBlocker(
                        code = stage.name + "_NOT_ATTEMPTED",
                        message = "A lower-privilege runtime stage was not attempted.",
                        category = RuntimeStageBlockerCategory.EVIDENCE_GAP,
                    )
                }
                attempt.blockers.forEach { blocker ->
                    when (blocker.category) {
                        RuntimeStageBlockerCategory.EXECUTOR_GAP,
                        RuntimeStageBlockerCategory.INVALID_INPUT,
                        -> decisionBlockers += blocker
                        RuntimeStageBlockerCategory.TARGET_ENVIRONMENT,
                        RuntimeStageBlockerCategory.EVIDENCE_GAP,
                        -> reasons += stage.name + ": " + blocker.message
                    }
                }
            }
        }

        val hardBlockers = decisionBlockers.filter {
            it.category == RuntimeStageBlockerCategory.EXECUTOR_GAP ||
                it.category == RuntimeStageBlockerCategory.INVALID_INPUT ||
                it.code.endsWith("_NOT_ATTEMPTED")
        }

        val lowerStageResolved = attempts
            .asSequence()
            .filter {
                it.stage == RuntimeEscalationStage.REPACKED_TEST_RUNTIME ||
                    it.stage == RuntimeEscalationStage.NON_ROOT_RUNTIME
            }
            .flatMap { it.resolvedTargetIds.asSequence() }
            .toSet()
        val stillUnresolved = unresolved - lowerStageResolved

        if (stillUnresolved.isEmpty()) {
            return RootRuntimeDecision(
                artifactSha256 = plan.artifactSha256,
                unresolvedTargetIds = emptySet(),
                evidenceRequiresRoot = false,
                rootExecutorRegistered = rootExecutorRegistered,
                readyToRunRoot = false,
                reasons = listOf(
                    "Lower-privilege runtime stages resolved all requested targets.",
                ),
                blockers = decisionBlockers.distinctBy { it.code to it.message },
            )
        }

        val evidenceRequiresRoot =
            hardBlockers.isEmpty() &&
                reasons.isNotEmpty() &&
                stillUnresolved.isNotEmpty()

        val finalBlockers = decisionBlockers.toMutableList()
        if (evidenceRequiresRoot && !rootExecutorRegistered) {
            finalBlockers += RuntimeStageBlocker(
                code = "ROOT_EXECUTOR_NOT_REGISTERED",
                message =
                    "Evidence may require root, but no concrete root executor is registered.",
                category = RuntimeStageBlockerCategory.EXECUTOR_GAP,
            )
        }

        return RootRuntimeDecision(
            artifactSha256 = plan.artifactSha256,
            unresolvedTargetIds = stillUnresolved,
            evidenceRequiresRoot = evidenceRequiresRoot,
            rootExecutorRegistered = rootExecutorRegistered,
            readyToRunRoot = evidenceRequiresRoot && rootExecutorRegistered,
            reasons = reasons.distinct(),
            blockers = finalBlockers.distinctBy { it.code to it.message },
        )
    }
}
