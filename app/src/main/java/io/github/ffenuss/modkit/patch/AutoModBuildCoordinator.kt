package io.github.ffenuss.modkit.patch

import android.content.Context
import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.build.VerifiedBuildPipeline
import io.github.ffenuss.modkit.build.VerifiedBuildResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One transaction for selected DEX AND native recipes, using the existing verified pipeline. */
object AutoModBuildCoordinator {
    suspend fun build(
        context: Context,
        target: AnalysisTargetDescriptor,
        analysis: FastAnalysisResult,
        nativePreparation: PatchPreparationPlan,
        selected: List<AutoModRecipe>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        onStaticVerified: () -> Unit = {},
    ): VerifiedBuildResult {
        require(selected.isNotEmpty() && selected.all { it.selectable }) { "Выберите доступные изменения." }
        require(selected.map { it.id }.distinct().size == selected.size)
        val dexMethods = selected.flatMap { it.dex }.distinctBy { it.id }
        val dex = if (dexMethods.isEmpty()) null else DexAutoModCoordinator.prepare(
            context, target, analysis, dexMethods, false, cancellation, progress)
        try {
            val nativeRequests = withContext(Dispatchers.IO) {
                selected.mapNotNull { it.native }.map { opportunity ->
                    if (cancellation.isCancelled()) throw AnalysisCancelledException()
                    Il2CppNativeMutationDraftBuilder.build(analysis, opportunity.targetId,
                        requireNotNull(opportunity.replacementHex),
                        File(context.filesDir, "analysis-results"), File(context.filesDir, "patch-staging"))
                        .request
                }
            }
            val preparation = if (nativeRequests.isEmpty()) requireNotNull(dex).preparation else
                nativePreparation.copy(targets = nativePreparation.targets + dex?.preparation?.targets.orEmpty())
            val requests = nativeRequests + dex?.requests.orEmpty()
            val outcome = MutationApplyCoordinator.apply(context, target, analysis, preparation,
                requests, cancellation, progress)
            require(outcome.applied) { outcome.blockers.joinToString("; ") }
            onStaticVerified()
            return VerifiedBuildPipeline.build(context, outcome, cancellation, progress)
        } finally { dex?.close() }
    }
}
