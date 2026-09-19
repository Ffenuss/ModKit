package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceBlocker
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.TargetShaVerification
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel

enum class PreparationTargetStatus {
    READY,
    CONFIRMED_NEEDS_CHANGE,
    NEEDS_CONFIRMATION,
    RUNTIME_REQUIRED,
    BLOCKED,
}

data class PreparedTarget(
    val target: EvidenceTarget,
    val status: PreparationTargetStatus,
    val blockers: List<String>,
)

data class PatchPreparationSummary(
    val found: Int,
    val confirmed: Int,
    val ready: Int,
    val requireConfirmation: Int,
    val runtimeRequired: Int,
    val blocked: Int,
)

data class PatchPreparationPlan(
    val artifactSha256: String,
    val sourceShaVerified: Boolean,
    val preparedAtEpochMs: Long,
    val targets: List<PreparedTarget>,
    val globalBlockers: List<String>,
) {
    val summary: PatchPreparationSummary
        get() = PatchPreparationSummary(
            found = targets.size,
            confirmed = targets.count {
                it.status == PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE ||
                    it.status == PreparationTargetStatus.READY
            },
            ready = targets.count { it.status == PreparationTargetStatus.READY },
            requireConfirmation = targets.count {
                it.status == PreparationTargetStatus.NEEDS_CONFIRMATION
            },
            runtimeRequired = targets.count {
                it.status == PreparationTargetStatus.RUNTIME_REQUIRED
            },
            blocked = targets.count { it.status == PreparationTargetStatus.BLOCKED },
        )

    val automaticApplyAllowed: Boolean
        get() = sourceShaVerified &&
            globalBlockers.isEmpty() &&
            targets.any { it.status == PreparationTargetStatus.READY }
}

/**
 * Fail-closed preparation planner.
 *
 * It does not invent a mutation. EXACT_BINARY means the executable target is
 * confirmed, not that a concrete byte/code change has been validated.
 */
object PatchPreparationPlanner {
    fun prepare(
        result: FastAnalysisResult,
        shaVerification: TargetShaVerification,
    ): PatchPreparationPlan {
        val globalBlockers = buildList {
            if (!shaVerification.matches) {
                add(
                    shaVerification.blockerMessage
                        ?: "Не удалось подтвердить SHA исходной цели.",
                )
            }
            if (result.evidenceGraph == null) {
                add("Evidence Graph ещё не сформирован для этой цели.")
            }
        }.distinct()

        val sourceValid = shaVerification.matches &&
            shaVerification.actualSha256 == result.index.artifactSha256

        val targets = result.evidenceGraph
            ?.targets
            .orEmpty()
            .map { target ->
                prepareTarget(
                    target = target,
                    sourceValid = sourceValid,
                    globalBlockers = globalBlockers,
                )
            }

        return PatchPreparationPlan(
            artifactSha256 = result.index.artifactSha256,
            sourceShaVerified = sourceValid,
            preparedAtEpochMs = System.currentTimeMillis(),
            targets = targets,
            globalBlockers = globalBlockers,
        )
    }

    private fun prepareTarget(
        target: EvidenceTarget,
        sourceValid: Boolean,
        globalBlockers: List<String>,
    ): PreparedTarget {
        if (!sourceValid) {
            return PreparedTarget(
                target = target,
                status = PreparationTargetStatus.BLOCKED,
                blockers = globalBlockers.ifEmpty {
                    listOf("SHA исходной цели не подтверждён.")
                },
            )
        }

        val status = when {
            target.proofLevel == ProofLevel.CHANGE_READY ||
                target.userStatus == UserFindingStatus.READY ->
                PreparationTargetStatus.READY

            target.userStatus == UserFindingStatus.RUNTIME_REQUIRED ->
                PreparationTargetStatus.RUNTIME_REQUIRED

            target.proofLevel == ProofLevel.EXACT_BINARY ||
                target.proofLevel == ProofLevel.RUNTIME_CONFIRMED ->
                PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE

            target.proofLevel == ProofLevel.EXACT_METADATA ||
                target.userStatus == UserFindingStatus.CONFIRMING ->
                PreparationTargetStatus.NEEDS_CONFIRMATION

            else ->
                PreparationTargetStatus.BLOCKED
        }

        val blockers = when (status) {
            PreparationTargetStatus.READY -> emptyList()
            PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE ->
                listOf(
                    "Исполняемая цель подтверждена, но конкретное изменение ещё не выбрано и не прошло внутреннюю проверку.",
                )
            PreparationTargetStatus.NEEDS_CONFIRMATION ->
                target.blockers.map(EvidenceBlocker::message).ifEmpty {
                    listOf("Нужно дополнительное статическое подтверждение.")
                }
            PreparationTargetStatus.RUNTIME_REQUIRED ->
                target.blockers.map(EvidenceBlocker::message).ifEmpty {
                    listOf("Для этой цели требуется runtime-подтверждение.")
                }
            PreparationTargetStatus.BLOCKED ->
                target.blockers.map(EvidenceBlocker::message).ifEmpty {
                    listOf("Недостаточно подтверждений для безопасной подготовки изменения.")
                }
        }

        return PreparedTarget(
            target = target,
            status = status,
            blockers = blockers.distinct(),
        )
    }
}
