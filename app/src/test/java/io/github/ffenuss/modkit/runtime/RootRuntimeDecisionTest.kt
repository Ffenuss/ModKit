package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeDecisionTest {
    @Test
    fun privilegedMapsExecutorIsRegistered() {
        assertTrue(RootRuntimeCapabilityRegistry.executorRegistered)
    }

    @Test
    fun implementationGapNeverJustifiesRoot() {
        val decision = RootRuntimeDecisionEngine.decide(
            plan = plan(),
            attempts = listOf(
                attempt(
                    stage = RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
                    blockers = listOf(
                        blocker(
                            "PROBE_EXECUTOR_MISSING",
                            RuntimeStageBlockerCategory.EXECUTOR_GAP,
                        ),
                    ),
                ),
                attempt(
                    stage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
                    blockers = listOf(
                        blocker(
                            "PROC_MEM_DENIED",
                            RuntimeStageBlockerCategory.TARGET_ENVIRONMENT,
                        ),
                    ),
                ),
            ),
            rootExecutorRegistered = true,
        )

        assertFalse(decision.evidenceRequiresRoot)
        assertFalse(decision.readyToRunRoot)
        assertTrue(
            decision.blockers.any {
                it.code == "PROBE_EXECUTOR_MISSING"
            },
        )
    }

    @Test
    fun targetRestrictionsAfterLowerPrivilegeAttemptsCanRequireRoot() {
        val decision = RootRuntimeDecisionEngine.decide(
            plan = plan(),
            attempts = listOf(
                attempt(
                    stage = RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
                    blockers = listOf(
                        blocker(
                            "REPACKED_RUNTIME_RESTRICTED",
                            RuntimeStageBlockerCategory.TARGET_ENVIRONMENT,
                        ),
                    ),
                ),
                attempt(
                    stage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
                    blockers = listOf(
                        blocker(
                            "PROC_MEM_DENIED",
                            RuntimeStageBlockerCategory.TARGET_ENVIRONMENT,
                        ),
                    ),
                ),
            ),
            rootExecutorRegistered = false,
        )

        assertTrue(decision.evidenceRequiresRoot)
        assertFalse(decision.readyToRunRoot)
        assertTrue(
            decision.blockers.any {
                it.code == "ROOT_EXECUTOR_NOT_REGISTERED"
            },
        )
        assertTrue(
            decision.reasons.any {
                "PROC_MEM_DENIED" !in it && "Process memory" in it
            },
        )
    }

    @Test
    fun missingNonRootAttemptBlocksRootEscalation() {
        val decision = RootRuntimeDecisionEngine.decide(
            plan = plan(),
            attempts = listOf(
                attempt(
                    stage = RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
                    blockers = listOf(
                        blocker(
                            "REPACKED_RUNTIME_RESTRICTED",
                            RuntimeStageBlockerCategory.TARGET_ENVIRONMENT,
                        ),
                    ),
                ),
            ),
            rootExecutorRegistered = true,
        )

        assertFalse(decision.evidenceRequiresRoot)
        assertFalse(decision.readyToRunRoot)
        assertTrue(
            decision.blockers.any {
                it.code == "NON_ROOT_RUNTIME_NOT_ATTEMPTED"
            },
        )
    }

    @Test
    fun rootBecomesRunnableOnlyWithEvidenceNeedAndConcreteExecutor() {
        val decision = RootRuntimeDecisionEngine.decide(
            plan = plan(),
            attempts = listOf(
                attempt(
                    stage = RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
                    blockers = listOf(
                        blocker(
                            "REPACKED_TARGET_LIMIT",
                            RuntimeStageBlockerCategory.TARGET_ENVIRONMENT,
                        ),
                    ),
                ),
                attempt(
                    stage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
                    blockers = listOf(
                        blocker(
                            "NON_ROOT_PERMISSION_LIMIT",
                            RuntimeStageBlockerCategory.TARGET_ENVIRONMENT,
                        ),
                    ),
                ),
            ),
            rootExecutorRegistered = true,
        )

        assertTrue(decision.evidenceRequiresRoot)
        assertTrue(decision.readyToRunRoot)
        assertTrue(decision.rootExecutorRegistered)
    }

    @Test
    fun resolvedLowerPrivilegeTargetNeedsNoRoot() {
        val decision = RootRuntimeDecisionEngine.decide(
            plan = plan(),
            attempts = listOf(
                attempt(
                    stage = RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
                    resolved = setOf(TARGET),
                ),
                attempt(
                    stage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
                    resolved = setOf(TARGET),
                ),
            ),
            rootExecutorRegistered = true,
        )

        assertFalse(decision.evidenceRequiresRoot)
        assertFalse(decision.readyToRunRoot)
        assertTrue(decision.unresolvedTargetIds.isEmpty())
    }

    private fun plan() =
        RuntimeEscalationPlan(
            artifactSha256 = SHA,
            needs = listOf(
                RuntimeEscalationNeed(
                    targetId = TARGET,
                    engineId = "runtime.native-confirm",
                    requiredProofLevel = ProofLevel.RUNTIME_CONFIRMED,
                    reason = "Runtime evidence required.",
                    firstStage = RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
                ),
            ),
        )

    private fun attempt(
        stage: RuntimeEscalationStage,
        blockers: List<RuntimeStageBlocker> = emptyList(),
        resolved: Set<String> = emptySet(),
    ) = RuntimeStageAttempt(
        stage = stage,
        state = if (blockers.isEmpty()) {
            RuntimeStageAttemptState.COMPLETED
        } else {
            RuntimeStageAttemptState.BLOCKED
        },
        attemptedAtEpochMs = 1234,
        requestedTargetIds = setOf(TARGET),
        resolvedTargetIds = resolved,
        blockers = blockers,
    )

    private fun blocker(
        code: String,
        category: RuntimeStageBlockerCategory,
    ) = RuntimeStageBlocker(
        code = code,
        message = when (code) {
            "PROC_MEM_DENIED" ->
                "Process memory remains unreadable without elevated privileges."
            else ->
                "Runtime stage blocker: $code"
        },
        category = category,
    )

    companion object {
        private const val TARGET = "target"
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
