package io.github.ffenuss.modkit.patch

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.TargetShaVerification
import io.github.ffenuss.modkit.analysis.TargetShaVerifier
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MutationApplyOutcome(
    val verification: TargetShaVerification,
    val preflight: MutationPreflightResult,
    val staging: MutationApplyResult?,
    val stagingVerification: StagingVerificationResult?,
    val blockers: List<String>,
) {
    val applied: Boolean
        get() = staging != null &&
            stagingVerification?.verified == true &&
            blockers.isEmpty()
}

/**
 * Internal apply coordinator used after a concrete mutation has passed
 * preparation. It revalidates SHA, opens a fresh workspace, runs mutation
 * preflight, writes staging APK/APK-set files, and verifies the source again
 * before exposing the staging result.
 */
object MutationApplyCoordinator {
    suspend fun apply(
        context: Context,
        target: AnalysisTargetDescriptor,
        analysis: FastAnalysisResult,
        preparation: PatchPreparationPlan,
        requests: List<MutationRequest>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): MutationApplyOutcome {
        val cache = EngineResultCache(File(context.filesDir, "analysis-cache"))
        var verification = withContext(Dispatchers.IO) {
            TargetShaVerifier.verify(
                context = context,
                target = target,
                expectedSha256 = analysis.index.artifactSha256,
                cancellation = cancellation,
                progress = progress,
            )
        }

        val preflight = MutationPreflightEngine.validate(
            preparation = preparation,
            requests = requests,
        )
        if (!verification.matches || !preflight.readyForApply) {
            return MutationApplyOutcome(
                verification = verification,
                preflight = preflight,
                staging = null,
                stagingVerification = null,
                blockers = buildList {
                    verification.blockerMessage?.let(::add)
                    addAll(preflight.globalBlockers)
                    addAll(preflight.blockedItems.flatMap { it.blockers })
                }.distinct(),
            )
        }

        val snapshot = withContext(Dispatchers.IO) {
            PatchWorkspaceProvider.open(
                context = context,
                target = target,
                expected = analysis,
                cache = cache,
                cancellation = cancellation,
                progress = progress,
            )
        }

        val staging = snapshot.use { opened ->
            withContext(Dispatchers.IO) {
                ArchiveMutationApplier.apply(
                    workspace = opened.workspace,
                    preflight = preflight,
                    outputDir = File(
                        context.filesDir,
                        "patch-staging/" +
                            analysis.index.artifactSha256 +
                            "/unsigned",
                    ),
                    cancellation = cancellation,
                    progress = progress,
                )
            }
        }
        val stagingVerification = withContext(Dispatchers.IO) {
            StagingApkVerifier.verify(
                apply = staging,
                cancellation = cancellation,
                progress = progress,
            )
        }
        if (!stagingVerification.verified) {
            staging.outputFiles.forEach(File::delete)
            return MutationApplyOutcome(
                verification = verification,
                preflight = preflight,
                staging = null,
                stagingVerification = stagingVerification,
                blockers = stagingVerification.blockers.ifEmpty {
                    listOf("Staging APK не прошёл внутреннюю проверку.")
                },
            )
        }

        verification = withContext(Dispatchers.IO) {
            TargetShaVerifier.verify(
                context = context,
                target = target,
                expectedSha256 = analysis.index.artifactSha256,
                cancellation = cancellation,
                progress = progress,
            )
        }
        if (!verification.matches) {
            staging.outputFiles.forEach(File::delete)
            return MutationApplyOutcome(
                verification = verification,
                preflight = preflight,
                staging = null,
                stagingVerification = stagingVerification,
                blockers = listOf(
                    verification.blockerMessage
                        ?: "Исходная цель изменилась во время применения; staging удалён.",
                ),
            )
        }

        return MutationApplyOutcome(
            verification = verification,
            preflight = preflight,
            staging = staging,
            stagingVerification = stagingVerification,
            blockers = emptyList(),
        )
    }
}
