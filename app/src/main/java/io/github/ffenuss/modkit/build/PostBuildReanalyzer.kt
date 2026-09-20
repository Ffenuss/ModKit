package io.github.ffenuss.modkit.build

import android.content.Context
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.FastArtifactIndexer
import io.github.ffenuss.modkit.analysis.ProgressSink
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Re-checks the final signed APK/APK-set after align/sign/diff verification.
 *
 * The build-critical path deliberately uses the bounded FAST index only.
 * Re-running deep IL2CPP binding here duplicated the heaviest analysis after
 * the exact mutation diff had already been verified and could cause Android
 * to kill the app on large games. Deep analysis remains available as a normal
 * analysis operation after export/install.
 */
object PostBuildReanalyzer {
    suspend fun analyze(
        context: Context,
        files: List<BuiltApkFile>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): FastAnalysisResult {
        require(files.isNotEmpty()) {
            "No built APK files are available for re-analysis."
        }

        val localFiles =
            files.map { it.file }
        val knownSha =
            files.associate { built ->
                built.file.absolutePath to
                    built.sha256
            }
        val cache =
            EngineResultCache(
                File(
                    context.filesDir,
                    "analysis-cache",
                ),
            )

        return withContext(Dispatchers.IO) {
            FastArtifactIndexer.index(
                files = localFiles,
                cancellation = cancellation,
                progress = progress,
                knownSha256 = knownSha,
                cache = cache,
            )
        }
    }
}
