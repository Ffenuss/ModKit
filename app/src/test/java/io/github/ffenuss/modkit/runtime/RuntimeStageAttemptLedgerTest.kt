package io.github.ffenuss.modkit.runtime

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStageAttemptLedgerTest {
    @Test
    fun ledgerRetainsOnlyMostRecentBoundedAttemptHistory() {
        var attempts = emptyList<RuntimeStageAttempt>()
        repeat(70) { index ->
            attempts = RuntimeStageAttemptRecorder.append(
                artifactSha256 = SHA,
                existing = attempts,
                attempt = RuntimeStageAttempt(
                    stage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
                    state = RuntimeStageAttemptState.BLOCKED,
                    attemptedAtEpochMs = index.toLong(),
                    requestedTargetIds = setOf("target"),
                    resolvedTargetIds = emptySet(),
                    blockers = emptyList(),
                ),
            ).attempts
        }

        assertEquals(64, attempts.size)
        assertEquals(6L, attempts.first().attemptedAtEpochMs)
        assertEquals(69L, attempts.last().attemptedAtEpochMs)
    }

    @Test
    fun unreadableRuntimeFailureIsRecordedAsTargetEnvironmentNotExecutorGap() {
        val attempt = RuntimeStageAttemptRecorder.blockedAttempt(
            stage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
            requestedTargetIds = setOf("target"),
            failure = IOException("Process maps are not readable without permission."),
            attemptedAtEpochMs = 1234,
        )

        assertEquals(RuntimeStageAttemptState.BLOCKED, attempt.state)
        assertEquals(
            RuntimeStageBlockerCategory.TARGET_ENVIRONMENT,
            attempt.blockers.single().category,
        )
    }

    @Test
    fun deniedRootManagerAccessIsTargetEnvironment() {
        val attempt = RuntimeStageAttemptRecorder.blockedAttempt(
            stage = RuntimeEscalationStage.ROOT_RUNTIME,
            requestedTargetIds = setOf("target"),
            failure = IllegalArgumentException(
                "Root access was not granted by the device root manager.",
            ),
            attemptedAtEpochMs = 1234,
        )

        assertEquals(
            RuntimeStageBlockerCategory.TARGET_ENVIRONMENT,
            attempt.blockers.single().category,
        )
    }

    @Test
    fun missingExecutorIsRecordedAsImplementationGap() {
        val attempt = RuntimeStageAttemptRecorder.blockedAttempt(
            stage = RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
            requestedTargetIds = setOf("target"),
            failure = IllegalStateException(
                "Probe executor is not registered.",
            ),
            attemptedAtEpochMs = 1234,
        )

        assertEquals(
            RuntimeStageBlockerCategory.EXECUTOR_GAP,
            attempt.blockers.single().category,
        )
        assertTrue(
            attempt.blockers.single().code
                .startsWith("REPACKED_TEST_RUNTIME"),
        )
    }

    @Test
    fun missingInstalledRepackedTestBuildIsInvalidInputNotRootEvidence() {
        val attempt = RuntimeStageAttemptRecorder.blockedAttempt(
            stage = RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
            requestedTargetIds = setOf("target"),
            failure = IllegalStateException(
                "Installed repacked test probe provider was not found.",
            ),
            attemptedAtEpochMs = 1234,
        )

        assertEquals(
            RuntimeStageBlockerCategory.INVALID_INPUT,
            attempt.blockers.single().category,
        )
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
