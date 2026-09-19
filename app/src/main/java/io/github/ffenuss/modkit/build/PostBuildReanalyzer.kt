package io.github.ffenuss.modkit.build

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.EngineSkipController
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.FastArtifactIndexer
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.RoutedEngineScheduler
import io.github.ffenuss.modkit.analysis.WorkspaceSource
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-analyzes the final signed APK/APK-set rather than assuming that a
 * successful mutation/signature pipeline preserved the analyzed structure.
 *
 * BACKGROUND engines are deliberately excluded from the build-critical path;
 * FAST inventory plus available TARGETED/CONFIRMATION engines are sufficient
 * for structural regression and exact binding checks here.
 */
object PostBuildReanalyzer {
    suspend fun analyze(
        context: Context,
        files: List<BuiltApkFile>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): FastAnalysisResult {
        require(files.isNotEmpty()) { "No built APK files are available for re-analysis." }

        val localFiles = files.map { it.file }
        val knownSha = files.associate { built ->
            built.file.absolutePath to built.sha256
        }
        val cache = EngineResultCache(
            File(context.filesDir, "analysis-cache"),
        )

        var result = withContext(Dispatchers.IO) {
            FastArtifactIndexer.index(
                files = localFiles,
                cancellation = cancellation,
                progress = progress,
                knownSha256 = knownSha,
                cache = cache,
            )
        }

        val byName = localFiles.groupBy(File::getName)
        val aligned = result.index.sources.map { descriptor ->
            val matches = byName[descriptor.displayName].orEmpty()
            require(matches.size == 1) {
                "Could not map built APK-set member " + descriptor.displayName +
                    " during post-build analysis."
            }
            matches.single()
        }
        val workspace = AnalysisWorkspace(
            index = result.index,
            sources = result.index.sources.zip(aligned).map { (descriptor, file) ->
                WorkspaceSource(descriptor, file)
            },
        )

        result = RoutedEngineScheduler.execute(
            initial = result,
            workspace = workspace,
            outputRoot = File(context.filesDir, "post-build-analysis"),
            cancellation = cancellation,
            skipController = EngineSkipController(),
            cache = cache,
            progress = progress,
            onPartial = { },
            allowedScheduleClasses = setOf(
                EngineScheduleClass.TARGETED,
                EngineScheduleClass.CONFIRMATION,
            ),
        )
        return result
    }
}
