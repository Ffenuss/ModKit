package io.github.ffenuss.modkit.patch

import android.content.Context
import android.net.Uri
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.EngineSkipController
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.FastArtifactIndexer
import io.github.ffenuss.modkit.analysis.MaterializedTarget
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.RoutedEngineScheduler
import io.github.ffenuss.modkit.analysis.TargetMaterializer
import io.github.ffenuss.modkit.analysis.TargetShaVerification
import io.github.ffenuss.modkit.analysis.TargetShaVerifier
import io.github.ffenuss.modkit.analysis.WorkspaceSource
import io.github.ffenuss.modkit.data.InstalledAppRepository
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
                prepareWorkspace(
                    context = context,
                    target = target,
                    expected = current,
                    cache = cache,
                    cancellation = cancellation,
                    progress = progress,
                )
            }

            try {
                current = RoutedEngineScheduler.execute(
                    initial = current,
                    workspace = prepared.workspace,
                    outputRoot = File(context.filesDir, "analysis-results"),
                    cancellation = cancellation,
                    skipController = EngineSkipController(),
                    cache = cache,
                    progress = progress,
                    onPartial = { },
                    allowedScheduleClasses = setOf(EngineScheduleClass.CONFIRMATION),
                )
            } finally {
                prepared.temporaryFiles.forEach(File::delete)
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

    private data class PreparedWorkspace(
        val workspace: AnalysisWorkspace,
        val temporaryFiles: List<File>,
    )

    private fun prepareWorkspace(
        context: Context,
        target: AnalysisTargetDescriptor,
        expected: FastAnalysisResult,
        cache: EngineResultCache,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): PreparedWorkspace {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()

        val localFiles: List<File>
        val knownSha: Map<String, String>
        val temporary: List<File>

        when (target) {
            is AnalysisTargetDescriptor.FileUri -> {
                val materialized: MaterializedTarget = TargetMaterializer.fromUri(
                    context = context,
                    uri = Uri.parse(target.uri),
                    cancellation = cancellation,
                    progress = progress,
                )
                if (materialized.sha256.lowercase() != expected.index.artifactSha256.lowercase()) {
                    materialized.file.delete()
                    error(
                        "Исходный файл изменился после анализа. Статическое подтверждение " +
                            "для старого SHA остановлено.",
                    )
                }
                localFiles = listOf(materialized.file)
                knownSha = mapOf(materialized.file.absolutePath to materialized.sha256)
                temporary = listOf(materialized.file)
            }

            is AnalysisTargetDescriptor.InstalledPackage -> {
                val installed = InstalledAppRepository(context)
                    .find(target.packageName)
                    ?: error("Установленное приложение больше недоступно: ${target.packageName}")
                localFiles = installed.apkFiles
                knownSha = emptyMap()
                temporary = emptyList()
            }
        }

        try {
            val fresh = FastArtifactIndexer.index(
                files = localFiles,
                cancellation = cancellation,
                progress = progress,
                knownSha256 = knownSha,
                cache = cache,
            )
            if (fresh.index.artifactSha256 != expected.index.artifactSha256) {
                error(
                    "Содержимое цели изменилось после анализа. " +
                        "Старые подтверждения и адреса не переиспользуются.",
                )
            }

            val aligned = alignFiles(
                descriptors = expected.index.sources,
                files = localFiles,
            )
            return PreparedWorkspace(
                workspace = AnalysisWorkspace(
                    index = expected.index,
                    sources = expected.index.sources.zip(aligned).map { (descriptor, file) ->
                        WorkspaceSource(descriptor, file)
                    },
                ),
                temporaryFiles = temporary,
            )
        } catch (failure: Throwable) {
            temporary.forEach(File::delete)
            throw failure
        }
    }

    private fun alignFiles(
        descriptors: List<io.github.ffenuss.modkit.analysis.ArtifactSource>,
        files: List<File>,
    ): List<File> {
        require(descriptors.size == files.size) {
            "Состав APK-set изменился после анализа."
        }
        if (descriptors.size == 1) return files

        val byName = files.groupBy(File::getName)
        return descriptors.map { descriptor ->
            val matches = byName[descriptor.displayName].orEmpty()
            require(matches.size == 1) {
                "Не удалось однозначно сопоставить split APK ${descriptor.displayName}."
            }
            matches.single()
        }
    }
}
