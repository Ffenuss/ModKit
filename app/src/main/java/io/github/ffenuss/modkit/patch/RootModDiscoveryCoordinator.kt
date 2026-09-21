package io.github.ffenuss.modkit.patch

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.EngineSkipController
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.FastArtifactIndexer
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.RoutedEngineScheduler
import io.github.ffenuss.modkit.analysis.WorkspaceSource
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RootModDiscoveryResult(
    val analysisResult: FastAnalysisResult,
    val preparation: PatchPreparationPlan,
    val opportunities: List<GameplayModificationOpportunity>,
    val elapsedMs: Long,
)

/**
 * Process-oriented gameplay modification discovery.
 *
 * Root is used for process identity/runtime work, but broad modification
 * discovery deliberately reuses the installed APK and the normal analysis
 * cache instead of dumping gigabytes of live memory. This keeps discovery
 * fast and avoids stressing the target process.
 *
 * Sensitive billing/auth/anti-cheat surfaces are retained as discovery-only
 * entries. They are visible to the user for analysis but never become
 * auto-selectable runtime actions.
 */
object RootModDiscoveryCoordinator {
    suspend fun discover(
        context: Context,
        app: InstalledAppTarget,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): RootModDiscoveryResult {
        val startedAt =
            System.currentTimeMillis()
        val cache =
            EngineResultCache(
                File(
                    context.filesDir,
                    "analysis-cache",
                ),
            )

        var result =
            withContext(
                Dispatchers.IO,
            ) {
                FastArtifactIndexer.index(
                    files =
                        app.apkFiles,
                    cancellation =
                        cancellation,
                    progress =
                        progress,
                    cache =
                        cache,
                )
            }

        val workspace =
            AnalysisWorkspace(
                index =
                    result.index,
                sources =
                    result.index.sources
                        .zip(
                            app.apkFiles,
                        )
                        .map {
                            pair ->
                            WorkspaceSource(
                                descriptor =
                                    pair.first,
                                file =
                                    pair.second,
                            )
                        },
            )

        result =
            RoutedEngineScheduler.execute(
                initial =
                    result,
                workspace =
                    workspace,
                outputRoot =
                    File(
                        context.filesDir,
                        "analysis-results",
                    ),
                cancellation =
                    cancellation,
                skipController =
                    EngineSkipController(),
                cache =
                    cache,
                progress =
                    progress,
                onPartial = { },
                allowedScheduleClasses =
                    setOf(
                        EngineScheduleClass.FAST,
                        EngineScheduleClass.TARGETED,
                    ),
            )

        val target =
            AnalysisTargetDescriptor
                .InstalledPackage(
                    packageName =
                        app.packageName,
                    label =
                        app.label,
                )
        val prepared =
            AutoModPreparationCoordinator
                .prepare(
                    context =
                        context,
                    target =
                        target,
                    initial =
                        result,
                    cancellation =
                        cancellation,
                    progress =
                        progress,
                )

        val opportunities =
            GameplayModificationFinder
                .find(
                    result =
                        prepared.analysisResult,
                    preparation =
                        prepared.plan,
                    projectCodeOnly =
                        true,
                    limit =
                        256,
                    perCategoryLimit =
                        24,
)

        return RootModDiscoveryResult(
            analysisResult =
                prepared.analysisResult,
            preparation =
                prepared.plan,
            opportunities =
                opportunities,
            elapsedMs =
                System.currentTimeMillis() -
                    startedAt,
        )
    }
}
