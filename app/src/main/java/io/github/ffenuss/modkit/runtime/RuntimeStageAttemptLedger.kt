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

        val lowerMessage = message.lowercase()
        val category = when {
            "installed-app target" in message ||
                "installed repacked test probe provider was not found" in lowerMessage ||
                "installed package signer does not match" in lowerMessage ||
                "probe package does not match" in lowerMessage ||
                "probe provider class does not match" in lowerMessage ->
                RuntimeStageBlockerCategory.INVALID_INPUT
            "executor" in lowerMessage &&
                ("not registered" in lowerMessage ||
                    "not implemented" in lowerMessage) ->
                RuntimeStageBlockerCategory.EXECUTOR_GAP
            "not readable" in lowerMessage ||
                "permission" in lowerMessage ||
                "no unambiguous readable main process" in lowerMessage ||
                "root access was not granted" in lowerMessage ||
                "root shell is unavailable" in lowerMessage ||
                "root process discovery" in lowerMessage ->
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
