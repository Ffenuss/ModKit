package io.github.ffenuss.modkit.patch

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.EngineSkipController
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.RoutedEngineScheduler
import io.github.ffenuss.modkit.analysis.TargetShaVerification
import io.github.ffenuss.modkit.analysis.TargetShaVerifier
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AutoModPreparationResult(
    val analysisResult: FastAnalysisResult,
    val shaVerification: TargetShaVerification,
    val plan: PatchPreparationPlan,
    val requestedStaticConfirmations: Int,
    val remainingStaticConfirmations: Int,
)

/**
 * Canonical internal "Prepare changes" orchestration.
 *
 * User-facing UX stays one-button:
 *  1) revalidate exact target content,
 *  2) execute only currently requested static confirmation engines,
 *  3) revalidate the source once more,
 *  4) build a fail-closed PatchPreparationPlan.
 *
 * Runtime requests are deliberately left in the queue for the later escalation layer.
 */
object AutoModPreparationCoordinator {
    suspend fun prepare(
        context: Context,
        target: AnalysisTargetDescriptor,
        initial: FastAnalysisResult,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): AutoModPreparationResult {
        val cache = EngineResultCache(File(context.filesDir, "analysis-cache"))
        var current = initial
        var verification = withContext(Dispatchers.IO) {
            TargetShaVerifier.verify(
                context = context,
                target = target,
                expectedSha256 = current.index.artifactSha256,
                cancellation = cancellation,
                progress = progress,
            )
        }

        if (!verification.matches) {
            return finish(
                result = current,
                verification = verification,
                requestedStaticConfirmations = 0,
            )
        }

        val requested = current.confirmationQueue.count { it.availableNow }
        if (requested > 0) {
            val prepared = withContext(Dispatchers.IO) {
                PatchWorkspaceProvider.open(
                    context = context,
                    target = target,
                    expected = current,
                    cache = cache,
                    cancellation = cancellation,
                    progress = progress,
                )
            }

            prepared.use { snapshot ->
                current = RoutedEngineScheduler.execute(
                    initial = current,
                    workspace = snapshot.workspace,
                    outputRoot = File(context.filesDir, "analysis-results"),
                    cancellation = cancellation,
                    skipController = EngineSkipController(),
                    cache = cache,
                    progress = progress,
                    onPartial = { },
                    allowedScheduleClasses = setOf(EngineScheduleClass.CONFIRMATION),
                )
            }

            verification = withContext(Dispatchers.IO) {
                TargetShaVerifier.verify(
                    context = context,
                    target = target,
                    expectedSha256 = current.index.artifactSha256,
                    cancellation = cancellation,
                    progress = progress,
                )
            }
        }

        return finish(
            result = current,
            verification = verification,
            requestedStaticConfirmations = requested,
        )
    }

    private fun finish(
        result: FastAnalysisResult,
        verification: TargetShaVerification,
        requestedStaticConfirmations: Int,
    ): AutoModPreparationResult {
        val plan = PatchPreparationPlanner.prepare(
            result = result,
            shaVerification = verification,
        )
        return AutoModPreparationResult(
            analysisResult = result,
            shaVerification = verification,
            plan = plan,
            requestedStaticConfirmations = requestedStaticConfirmations,
            remainingStaticConfirmations = result.confirmationQueue.count { it.availableNow },
        )
    }


}
