package io.github.ffenuss.modkit.analysis

import android.content.Context
import android.net.Uri
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceIntegrator
import io.github.ffenuss.modkit.runtime.RuntimeModuleEvidenceCollector
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ExpertLabSourceKind {
    FILES,
    INSTALLED_APP,
}

data class ExpertLabSession(
    val label: String,
    val sourceKind: ExpertLabSourceKind,
    val result: FastAnalysisResult,
    val workspace: AnalysisWorkspace,
    val temporaryFiles: List<File>,
) : AutoCloseable {
    override fun close() {
        temporaryFiles.forEach(File::delete)
    }

    fun withResult(updated: FastAnalysisResult): ExpertLabSession =
        copy(result = updated)
}

/**
 * Dedicated Expert Lab orchestration.
 *
 * Unlike Simple Mode, this exposes the routed backend plan and lets the user
 * execute one compatible backend at a time. It still uses the same immutable
 * ArtifactIndex, cache, cancellation and fail-closed confirmation gates.
 */
object ExpertLabSessionController {
    suspend fun openUris(
        context: Context,
        uris: List<Uri>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): ExpertLabSession {
        require(uris.isNotEmpty()) { "Не выбрано ни одного файла." }

        val materialized = mutableListOf<MaterializedTarget>()
        try {
            for (uri in uris) {
                if (cancellation.isCancelled()) throw AnalysisCancelledException()
                materialized += withContext(Dispatchers.IO) {
                    TargetMaterializer.fromUri(
                        context = context,
                        uri = uri,
                        cancellation = cancellation,
                        progress = progress,
                    )
                }
            }

            val files = materialized.map { it.file }
            val knownSha = materialized.associate {
                it.file.absolutePath to it.sha256
            }
            val cache = EngineResultCache(
                File(context.filesDir, "analysis-cache"),
            )
            val result = withContext(Dispatchers.IO) {
                FastArtifactIndexer.index(
                    files = files,
                    cancellation = cancellation,
                    progress = progress,
                    knownSha256 = knownSha,
                    cache = cache,
                )
            }
            val workspace = AnalysisWorkspace(
                index = result.index,
                sources = result.index.sources.zip(files).map { (descriptor, file) ->
                    WorkspaceSource(descriptor, file)
                },
            )

            return ExpertLabSession(
                label = if (uris.size == 1) {
                    uris.single().lastPathSegment ?: files.single().name
                } else {
                    uris.size.toString() + " выбранных файлов"
                },
                sourceKind = ExpertLabSourceKind.FILES,
                result = result,
                workspace = workspace,
                temporaryFiles = files,
            )
        } catch (failure: Throwable) {
            materialized.forEach { it.file.delete() }
            throw failure
        }
    }

    suspend fun openInstalled(
        context: Context,
        app: InstalledAppTarget,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): ExpertLabSession {
        val cache = EngineResultCache(
            File(context.filesDir, "analysis-cache"),
        )
        val result = withContext(Dispatchers.IO) {
            FastArtifactIndexer.index(
                files = app.apkFiles,
                cancellation = cancellation,
                progress = progress,
                cache = cache,
            )
        }
        val workspace = AnalysisWorkspace(
            index = result.index,
            sources = result.index.sources.zip(app.apkFiles).map { (descriptor, file) ->
                WorkspaceSource(descriptor, file)
            },
        )
        return ExpertLabSession(
            label = app.label + " · " + app.packageName,
            sourceKind = ExpertLabSourceKind.INSTALLED_APP,
            result = result,
            workspace = workspace,
            temporaryFiles = emptyList(),
        )
    }

    suspend fun integrateRuntimeMaps(
        context: Context,
        session: ExpertLabSession,
        procMapsText: String,
        cancellation: CancellationSignal,
    ): ExpertLabSession {
        require(procMapsText.isNotBlank()) {
            "Снимок /proc/<pid>/maps пуст."
        }
        val libraryEntry = session.result.il2cppBinaryBinding
            ?.evidence
            ?.firstOrNull()
            ?.libraryEntry
            ?: session.result.il2cppFastDump
                ?.libraryEntries
                ?.firstOrNull { it.endsWith("/libil2cpp.so") || it.endsWith(":libil2cpp.so") }
            ?: error("В текущем результате нет IL2CPP library evidence.")

        val abi = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            .firstOrNull { candidate ->
                "/$candidate/" in libraryEntry.lowercase()
            }
            ?: error("Не удалось определить ABI для libil2cpp.so.")
        val moduleName = libraryEntry.substringAfterLast('/')
        val relative = session.result.index.artifactSha256 +
            "/il2cpp/native/" + abi + "-libil2cpp.so"
        val moduleFile = listOf(
            File(context.filesDir, "expert-lab-results/" + relative),
            File(context.filesDir, "analysis-results/" + relative),
        ).firstOrNull { it.isFile && it.canRead() }
            ?: error(
                "Извлечённый libil2cpp.so не найден. Сначала запустите IL2CPP binary confirmation.",
            )

        val evidence = withContext(Dispatchers.IO) {
            RuntimeModuleEvidenceCollector.collect(
                artifactSha256 = session.result.index.artifactSha256,
                moduleFile = moduleFile,
                moduleName = moduleName,
                procMapsText = procMapsText,
                cancellation = cancellation,
            )
        }
        val integrated = RuntimeEvidenceIntegrator.integrate(
            result = session.result,
            evidence = evidence,
            procMapsText = procMapsText,
        )
        return session.withResult(integrated)
    }

    suspend fun runEngine(
        context: Context,
        session: ExpertLabSession,
        engineId: String,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): ExpertLabSession {
        val planned = session.result.routingPlan.engines
            .singleOrNull { it.id == engineId }
            ?: error("Движок отсутствует в routing plan текущей цели.")

        require(planned.availableNow) {
            "Этот backend ещё не подключён в чистом ModKit."
        }
        require(engineId != "artifact.fast-index") {
            "FAST index уже выполнен при открытии цели."
        }
        if (
            planned.scheduleClass == io.github.ffenuss.modkit.domain.EngineScheduleClass.CONFIRMATION &&
            session.result.confirmationQueue.none {
                it.engineId == engineId && it.availableNow
            }
        ) {
            error(
                "Для этого confirmation backend ещё не выполнены его доказательные предпосылки. " +
                    "Сначала запустите предыдущий рекомендованный backend.",
            )
        }

        val cache = EngineResultCache(
            File(context.filesDir, "analysis-cache"),
        )
        val updated = RoutedEngineScheduler.execute(
            initial = session.result,
            workspace = session.workspace,
            outputRoot = File(context.filesDir, "expert-lab-results"),
            cancellation = cancellation,
            skipController = EngineSkipController(),
            cache = cache,
            progress = progress,
            onPartial = { },
            allowedEngineIds = setOf(engineId),
        )
        return session.withResult(updated)
    }
}
