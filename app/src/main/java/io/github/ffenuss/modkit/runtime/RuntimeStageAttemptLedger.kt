package io.github.ffenuss.modkit.runtime

import java.io.Serializable

data class RuntimeStageAttemptLedger(
    val artifactSha256: String,
    val attempts: List<RuntimeStageAttempt>,
) : Serializable

object RuntimeStageAttemptRecorder {
    private const val MAX_ATTEMPTS = 64

    fun append(
        artifactSha256: String,
        existing: List<RuntimeStageAttempt>,
        attempt: RuntimeStageAttempt,
    ): RuntimeStageAttemptLedger {
        require(artifactSha256.isNotBlank()) {
            "Artifact SHA is required for runtime attempt persistence."
        }
        require(attempt.stage != RuntimeEscalationStage.STATIC) {
            "Static analysis is not stored as a runtime-stage attempt."
        }
        val retained = (existing + attempt).takeLast(MAX_ATTEMPTS)
        return RuntimeStageAttemptLedger(
            artifactSha256 = artifactSha256,
            attempts = retained,
        )
    }

    fun blockedAttempt(
        stage: RuntimeEscalationStage,
        requestedTargetIds: Set<String>,
        failure: Throwable,
        attemptedAtEpochMs: Long = System.currentTimeMillis(),
    ): RuntimeStageAttempt {
        val message = failure.message
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: failure.javaClass.simpleName

        val category = when {
            "installed-app target" in message ->
                RuntimeStageBlockerCategory.INVALID_INPUT
            "executor" in message.lowercase() &&
                ("not registered" in message.lowercase() ||
                    "not implemented" in message.lowercase()) ->
                RuntimeStageBlockerCategory.EXECUTOR_GAP
            "not readable" in message.lowercase() ||
                "permission" in message.lowercase() ||
                "no unambiguous readable main process" in message.lowercase() ->
                RuntimeStageBlockerCategory.TARGET_ENVIRONMENT
            else ->
                RuntimeStageBlockerCategory.EVIDENCE_GAP
        }

        return RuntimeStageAttempt(
            stage = stage,
            state = RuntimeStageAttemptState.BLOCKED,
            attemptedAtEpochMs = attemptedAtEpochMs,
            requestedTargetIds = requestedTargetIds,
            resolvedTargetIds = emptySet(),
            blockers = listOf(
                RuntimeStageBlocker(
                    code = stage.name + "_BLOCKED",
                    message = message,
                    category = category,
                ),
            ),
        )
    }
}
