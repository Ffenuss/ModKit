package io.github.ffenuss.modkit.patch

import android.content.Context
import android.net.Uri
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.FastArtifactIndexer
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.TargetPackageSet
import io.github.ffenuss.modkit.analysis.OpenedTarget
import io.github.ffenuss.modkit.analysis.TargetMaterializer
import io.github.ffenuss.modkit.analysis.WorkspaceSource
import io.github.ffenuss.modkit.data.InstalledAppRepository
import java.io.File

data class PatchWorkspaceSnapshot(
    val workspace: AnalysisWorkspace,
    val temporaryFiles: List<File>,
    private val cleanup: (() -> Unit)? = null,
) : AutoCloseable {
    override fun close() {
        temporaryFiles.forEach(File::delete)
        cleanup?.invoke()
    }
}

/**
 * Opens a fresh source workspace for preparation/apply and proves that its
 * current bytes still identify the analyzed artifact.
 */
object PatchWorkspaceProvider {
    fun open(
        context: Context,
        target: AnalysisTargetDescriptor,
        expected: FastAnalysisResult,
        cache: EngineResultCache,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): PatchWorkspaceSnapshot {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()

        val files: List<File>
        val knownSha: Map<String, String>
        val temporary: List<File>

        var opened: OpenedTarget? = null
        when (target) {
            is AnalysisTargetDescriptor.FileUri -> {
                val source = TargetPackageSet.open(context, target, cancellation, progress)
                opened = source
                files = source.files
                knownSha = source.knownSha256
                temporary = emptyList()

            }

            is AnalysisTargetDescriptor.InstalledPackage -> {
                val installed = InstalledAppRepository(context)
                    .find(target.packageName)
                    ?: error(
                        "Установленное приложение больше недоступно: " +
                            target.packageName,
                    )
                files = installed.apkFiles
                knownSha = emptyMap()
                temporary = emptyList()
            }
        }

        try {
            val fresh = FastArtifactIndexer.index(
                files = files,
                cancellation = cancellation,
                progress = progress,
                knownSha256 = knownSha,
                cache = cache,
            )
            require(
                fresh.index.artifactSha256 == expected.index.artifactSha256,
            ) {
                "Содержимое цели изменилось после анализа. " +
                    "Старые подтверждения и адреса не переиспользуются."
            }

            val aligned = alignFiles(
                descriptors = expected.index.sources,
                files = files,
            )
            return PatchWorkspaceSnapshot(
                workspace = AnalysisWorkspace(
                    index = expected.index,
                    sources = expected.index.sources.zip(aligned).map {
                            (descriptor, file) ->
                        WorkspaceSource(descriptor, file)
                    },
                ),
                temporaryFiles = temporary,
                cleanup = { opened?.close() },
            )
        } catch (failure: Throwable) {
            temporary.forEach(File::delete)
            opened?.close()
            throw failure
        }
    }

    private fun alignFiles(
        descriptors: List<ArtifactSource>,
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
                "Не удалось однозначно сопоставить split APK " +
                    descriptor.displayName + "."
            }
            matches.single()
        }
    }
}
