package io.github.ffenuss.modkit.analysis

import android.content.Context
import android.net.Uri
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.runtime.AndroidNonRootProcessProbe
import io.github.ffenuss.modkit.runtime.AndroidRepackedRuntimeProbeTransport
import io.github.ffenuss.modkit.runtime.NonRootRuntimeCaptureCoordinator
import io.github.ffenuss.modkit.runtime.RepackedRuntimeBuildCoordinator
import io.github.ffenuss.modkit.runtime.RepackedRuntimeBuildResult
import io.github.ffenuss.modkit.runtime.RepackedRuntimeEvidenceCapture
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstrumentationCoordinator
import io.github.ffenuss.modkit.runtime.RepackedRuntimeNativeLookupCoordinator
import io.github.ffenuss.modkit.runtime.RepackedRuntimePassiveTraceCoordinator
import io.github.ffenuss.modkit.runtime.RepackedRuntimePassiveTraceSession
import io.github.ffenuss.modkit.runtime.RepackedRuntimePassiveTraceSessionCapture
import io.github.ffenuss.modkit.runtime.RuntimeNativeLookupGraphAnnotator
import io.github.ffenuss.modkit.runtime.ProcMemRuntimeMemoryReader
import io.github.ffenuss.modkit.runtime.RuntimeMemoryElfValidator
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceBundle
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceIntegrator
import io.github.ffenuss.modkit.runtime.RuntimeModuleEvidenceCollector
import io.github.ffenuss.modkit.runtime.RuntimeEscalationPlanner
import io.github.ffenuss.modkit.runtime.RuntimeEscalationStage
import io.github.ffenuss.modkit.runtime.RuntimeStageAttempt
import io.github.ffenuss.modkit.runtime.RuntimeStageAttemptRecorder
import io.github.ffenuss.modkit.runtime.RuntimeStageAttemptState
import io.github.ffenuss.modkit.runtime.RuntimeStageBlocker
import io.github.ffenuss.modkit.runtime.RuntimeStageBlockerCategory
import io.github.ffenuss.modkit.runtime.toEvidenceBundle
import io.github.ffenuss.modkit.domain.ProofLevel
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
    val packageName: String? = null,
    val repackedRuntimeBuild: RepackedRuntimeBuildResult? = null,
    val repackedNativeRuntimeBuild: RepackedRuntimeBuildResult? = null,
    val repackedPassiveTraceSession:
        RepackedRuntimePassiveTraceSession? = null,
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
    private data class ResolvedRuntimeModule(
        val file: File,
        val moduleName: String,
    )

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
                val indexed = FastArtifactIndexer.index(
                    files = files,
                    cancellation = cancellation,
                    progress = progress,
                    knownSha256 = knownSha,
                    cache = cache,
                )
                val ledger = cache.loadRuntimeStageAttempts(
                    indexed.index.artifactSha256,
                )
                if (ledger == null) {
                    indexed
                } else {
                    indexed.copy(
                        runtimeStageAttempts = ledger.attempts,
                        engineCacheHits = indexed.engineCacheHits +
                            EngineResultCache.RUNTIME_STAGE_ATTEMPTS_ENGINE_ID,
                    )
                }
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
            val indexed = FastArtifactIndexer.index(
                files = app.apkFiles,
                cancellation = cancellation,
                progress = progress,
                cache = cache,
            )
            val ledger = cache.loadRuntimeStageAttempts(
                indexed.index.artifactSha256,
            )
            if (ledger == null) {
                indexed
            } else {
                indexed.copy(
                    runtimeStageAttempts = ledger.attempts,
                    engineCacheHits = indexed.engineCacheHits +
                        EngineResultCache.RUNTIME_STAGE_ATTEMPTS_ENGINE_ID,
                )
            }
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
            packageName = app.packageName,
        )
    }

    /**
     * Imported maps are useful for mapping diagnostics, but they no longer
     * promote a target to RUNTIME_CONFIRMED because process identity cannot be
     * independently established from pasted text.
     */
    suspend fun integrateRuntimeMaps(
        context: Context,
        session: ExpertLabSession,
        procMapsText: String,
        cancellation: CancellationSignal,
    ): ExpertLabSession {
        require(procMapsText.isNotBlank()) {
            "Снимок /proc/<pid>/maps пуст."
        }
        val module = resolveIl2CppRuntimeModule(context, session.result)

        val evidence = withContext(Dispatchers.IO) {
            RuntimeModuleEvidenceCollector.collect(
                artifactSha256 = session.result.index.artifactSha256,
                moduleFile = module.file,
                moduleName = module.moduleName,
                procMapsText = procMapsText,
                cancellation = cancellation,
                artifactEntries = session.result.index.entries,
            )
        }
        val integrated = RuntimeEvidenceIntegrator.integrate(
            result = session.result,
            evidence = evidence,
            procMapsText = procMapsText,
        )
        return persistRuntimeEvidence(
            context = context,
            session = session,
            integrated = integrated,
            fallbackEvidence = evidence,
        )
    }

    /**
     * Attempts the strongest runtime confirmation available without root.
     *
     * The target PID is selected only after exact /proc/<pid>/cmdline proof.
     * The same cmdline is verified before and after bounded maps capture so PID
     * reuse cannot silently produce evidence for another process.
     */
    suspend fun integrateNonRootRuntime(
        context: Context,
        session: ExpertLabSession,
        cancellation: CancellationSignal,
    ): ExpertLabSession {
        val plan = RuntimeEscalationPlanner.plan(session.result)
        val requestedTargets = plan.needs
            .filter {
                it.firstStage.ordinal <=
                    RuntimeEscalationStage.NON_ROOT_RUNTIME.ordinal
            }
            .mapTo(linkedSetOf()) { it.targetId }

        try {
            require(requestedTargets.isNotEmpty()) {
                "No unresolved runtime confirmation target requires non-root runtime."
            }
            val packageName = requireNotNull(session.packageName) {
                "Non-root runtime auto-discovery is available only for an installed-app target."
            }
            val probe = AndroidNonRootProcessProbe(context)
            val verifiedCapture = withContext(Dispatchers.IO) {
                NonRootRuntimeCaptureCoordinator.captureMaps(
                    packageName = packageName,
                    probe = probe,
                    cancellation = cancellation,
                )
            }
            val module = resolveIl2CppRuntimeModule(context, session.result)
            val evidence = withContext(Dispatchers.IO) {
                val collected = RuntimeModuleEvidenceCollector.collect(
                    artifactSha256 = session.result.index.artifactSha256,
                    moduleFile = module.file,
                    moduleName = module.moduleName,
                    capture = verifiedCapture.capture,
                    cancellation = cancellation,
                    artifactEntries = session.result.index.entries,
                )
                val memoryElf = if (collected.memoryMappingCandidates.isEmpty()) {
                    emptyList()
                } else {
                    RuntimeMemoryElfValidator.validateCandidates(
                        candidates = collected.memoryMappingCandidates,
                        reader = ProcMemRuntimeMemoryReader(verifiedCapture.pid),
                        cancellation = cancellation,
                    )
                }
                collected.copy(
                    memoryElfEvidence = memoryElf,
                    processIdentity = packageName,
                    processIdentityConfirmed = true,
                )
            }
            val integrated = RuntimeEvidenceIntegrator.integrate(
                result = session.result,
                evidence = evidence,
                procMapsText = verifiedCapture.capture.text,
            )

            val resolvedTargets = integrated.evidenceGraph
                ?.targets
                .orEmpty()
                .asSequence()
                .filter { it.id in requestedTargets }
                .filter {
                    it.proofLevel.ordinal >=
                        ProofLevel.RUNTIME_CONFIRMED.ordinal
                }
                .mapTo(linkedSetOf()) { it.id }
            val unresolvedTargets = requestedTargets - resolvedTargets
            val attempt = RuntimeStageAttempt(
                stage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
                state = if (unresolvedTargets.isEmpty()) {
                    RuntimeStageAttemptState.COMPLETED
                } else {
                    RuntimeStageAttemptState.BLOCKED
                },
                attemptedAtEpochMs = System.currentTimeMillis(),
                requestedTargetIds = requestedTargets,
                resolvedTargetIds = resolvedTargets,
                blockers = if (unresolvedTargets.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        RuntimeStageBlocker(
                            code = "NON_ROOT_EVIDENCE_UNRESOLVED",
                            message =
                                "Non-root capture completed, but runtime proof remains unresolved for: " +
                                    unresolvedTargets.sorted().joinToString(),
                            category = RuntimeStageBlockerCategory.EVIDENCE_GAP,
                        ),
                    )
                },
            )
            val withAttempt = appendAttempt(
                result = integrated,
                attempt = attempt,
            )
            val withEvidence = persistRuntimeEvidence(
                context = context,
                session = session,
                integrated = withAttempt,
                fallbackEvidence = evidence,
            )
            return persistRuntimeAttempts(
                context = context,
                session = withEvidence,
            )
        } catch (failure: Throwable) {
            if (failure is AnalysisCancelledException) throw failure

            val attempt = RuntimeStageAttemptRecorder.blockedAttempt(
                stage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
                requestedTargetIds = requestedTargets,
                failure = failure,
            )
            val updated = appendAttempt(
                result = session.result,
                attempt = attempt,
            ).copy(
                engineWarnings = (
                    session.result.engineWarnings +
                        "runtime.non-root: " +
                        (failure.message ?: failure.javaClass.simpleName)
                    ).distinct(),
            )
            return persistRuntimeAttempts(
                context = context,
                session = session.withResult(updated),
            )
        }
    }

    suspend fun buildRepackedTestRuntime(
        context: Context,
        session: ExpertLabSession,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): ExpertLabSession {
        val plan = RuntimeEscalationPlanner.plan(session.result)
        val requestedTargets = plan.needs
            .filter {
                it.firstStage ==
                    RuntimeEscalationStage.REPACKED_TEST_RUNTIME
            }
            .mapTo(linkedSetOf()) { it.targetId }
        require(requestedTargets.isNotEmpty()) {
            "No unresolved target currently requires repacked test runtime."
        }

        val outputRoot = File(
            context.filesDir,
            "expert-lab-results",
        )
        val build = withContext(Dispatchers.IO) {
            val instrumentation =
                RepackedRuntimeInstrumentationCoordinator.instrument(
                    context = context,
                    workspace = session.workspace,
                    outputRoot = outputRoot,
                    cancellation = cancellation,
                )
            RepackedRuntimeBuildCoordinator.buildProbeInjected(
                context = context,
                manifestInventory =
                    instrumentation.manifestInventory,
                injection = instrumentation.probeInjection,
                outputRoot = outputRoot,
                cancellation = cancellation,
                progress = progress,
            )
        }
        require(
            build.artifactSha256.equals(
                session.result.index.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "Repacked test build artifact SHA does not match the active Expert Lab target."
        }

        return session.copy(
            repackedRuntimeBuild = build,
        )
    }

    suspend fun buildRepackedNativeLookupRuntime(
        context: Context,
        session: ExpertLabSession,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): ExpertLabSession {
        require(
            session.result.index.entries.any {
                it.format == BinaryFormat.ELF ||
                    it.path.lowercase().endsWith(".so")
            },
        ) {
            "Current target contains no indexed native ELF module for runtime lookup."
        }

        val outputRoot = File(
            context.filesDir,
            "expert-lab-results",
        )
        val build = withContext(Dispatchers.IO) {
            val instrumentation =
                RepackedRuntimeInstrumentationCoordinator
                    .instrumentNativeLookup(
                        context = context,
                        workspace = session.workspace,
                        outputRoot = outputRoot,
                        cancellation = cancellation,
                    )
            RepackedRuntimeBuildCoordinator
                .buildNativeProbeInjected(
                    context = context,
                    manifestInventory =
                        instrumentation.base.manifestInventory,
                    injection =
                        instrumentation.nativeProbeInjection,
                    outputRoot = outputRoot,
                    cancellation = cancellation,
                    progress = progress,
                )
        }
        require(
            build.artifactSha256.equals(
                session.result.index.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "Native lookup test build artifact SHA does not match the active Expert Lab target."
        }
        return session.copy(
            repackedNativeRuntimeBuild = build,
        )
    }

    suspend fun startRepackedPassiveDlsymTrace(
        context: Context,
        session: ExpertLabSession,
        cancellation: CancellationSignal,
    ): ExpertLabSession {
        val build =
            requireNotNull(session.repackedNativeRuntimeBuild) {
                "Build the native lookup test APK before passive dlsym tracing."
            }
        require(session.repackedPassiveTraceSession == null) {
            "A passive dlsym trace session is already active."
        }
        val traceSession = withContext(Dispatchers.IO) {
            RepackedRuntimePassiveTraceSessionCapture.start(
                build = build,
                transport =
                    AndroidRepackedRuntimeProbeTransport(context),
                cancellation = cancellation,
            )
        }
        return session.copy(
            repackedPassiveTraceSession = traceSession,
        )
    }

    suspend fun stopRepackedPassiveDlsymTrace(
        context: Context,
        session: ExpertLabSession,
        cancellation: CancellationSignal,
    ): ExpertLabSession {
        val build =
            requireNotNull(session.repackedNativeRuntimeBuild) {
                "Native passive trace build is unavailable."
            }
        val traceSession =
            requireNotNull(session.repackedPassiveTraceSession) {
                "No passive dlsym trace session is active."
            }

        val execution = withContext(Dispatchers.IO) {
            RepackedRuntimePassiveTraceCoordinator.stopAndValidate(
                build = build,
                session = traceSession,
                workspace = session.workspace,
                transport =
                    AndroidRepackedRuntimeProbeTransport(context),
                tempRoot = File(
                    context.cacheDir,
                    "expert-lab-passive-trace",
                ),
                cancellation = cancellation,
            )
        }
        val integrated = RuntimeEvidenceIntegrator.integrate(
            result = session.result,
            evidence = execution.attachedEvidence,
            procMapsText = execution.mapsCapture.maps.text,
        )
        val annotated =
            RuntimeNativeLookupGraphAnnotator.annotateAll(
                result = integrated,
                validation = execution.validation,
            )
        val persisted = persistRuntimeEvidence(
            context = context,
            session = session,
            integrated = annotated,
            fallbackEvidence =
                execution.attachedEvidence,
        )
        return persisted.copy(
            repackedNativeRuntimeBuild = build,
            repackedPassiveTraceSession = null,
        )
    }

    suspend fun integrateRepackedNativeLookup(
        context: Context,
        session: ExpertLabSession,
        moduleName: String,
        symbolName: String,
        cancellation: CancellationSignal,
    ): ExpertLabSession {
        val build =
            requireNotNull(session.repackedNativeRuntimeBuild) {
                "Build the native lookup test APK before targeted runtime lookup."
            }
        require(
            build.artifactSha256.equals(
                session.result.index.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "Native lookup test build is stale for the active target SHA."
        }

        val execution = withContext(Dispatchers.IO) {
            RepackedRuntimeNativeLookupCoordinator.execute(
                build = build,
                workspace = session.workspace,
                moduleName = moduleName,
                symbolName = symbolName,
                transport =
                    AndroidRepackedRuntimeProbeTransport(context),
                tempRoot = File(
                    context.cacheDir,
                    "expert-lab-native-lookup",
                ),
                cancellation = cancellation,
            )
        }
        val integrated = RuntimeEvidenceIntegrator.integrate(
            result = session.result,
            evidence = execution.attachedEvidence,
            procMapsText = execution.mapsCapture.maps.text,
        )
        val annotated = RuntimeNativeLookupGraphAnnotator.annotate(
            result = integrated,
            moduleName = moduleName,
            symbolName = symbolName,
            validation = execution.lookup.validation,
        )
        val persisted = persistRuntimeEvidence(
            context = context,
            session = session,
            integrated = annotated,
            fallbackEvidence = execution.attachedEvidence,
        )
        return persisted.copy(
            repackedNativeRuntimeBuild = build,
        )
    }

    suspend fun integrateRepackedTestRuntime(
        context: Context,
        session: ExpertLabSession,
        cancellation: CancellationSignal,
    ): ExpertLabSession {
        val plan = RuntimeEscalationPlanner.plan(session.result)
        val requestedTargets = plan.needs
            .filter {
                it.firstStage ==
                    RuntimeEscalationStage.REPACKED_TEST_RUNTIME
            }
            .mapTo(linkedSetOf()) { it.targetId }
        val build = requireNotNull(session.repackedRuntimeBuild) {
            "Build the repacked test runtime APK before runtime capture."
        }
        require(
            build.artifactSha256.equals(
                session.result.index.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "Repacked runtime build is stale for the active target SHA."
        }

        try {
            require(requestedTargets.isNotEmpty()) {
                "No unresolved target currently requires repacked test runtime."
            }
            val captured = withContext(Dispatchers.IO) {
                RepackedRuntimeEvidenceCapture.capture(
                    build = build,
                    transport =
                        AndroidRepackedRuntimeProbeTransport(context),
                    cancellation = cancellation,
                )
            }

            var evidence = captured.toEvidenceBundle(
                artifactSha256 =
                    session.result.index.artifactSha256,
                artifactEntries =
                    session.result.index.entries,
            )

            val module = runCatching {
                resolveIl2CppRuntimeModule(
                    context = context,
                    result = session.result,
                )
            }.getOrNull()
            if (module != null) {
                evidence = withContext(Dispatchers.IO) {
                    RuntimeModuleEvidenceCollector.collect(
                        artifactSha256 =
                            session.result.index.artifactSha256,
                        moduleFile = module.file,
                        moduleName = module.moduleName,
                        capture = captured.maps,
                        cancellation = cancellation,
                        artifactEntries =
                            session.result.index.entries,
                    )
                }.copy(
                    processIdentity =
                        captured.processIdentity,
                    processIdentityConfirmed = true,
                )
            }

            val integrated = RuntimeEvidenceIntegrator.integrate(
                result = session.result,
                evidence = evidence,
                procMapsText = captured.maps.text,
            )
            val resolvedTargets = integrated.evidenceGraph
                ?.targets
                .orEmpty()
                .asSequence()
                .filter { it.id in requestedTargets }
                .filter {
                    it.proofLevel.ordinal >=
                        ProofLevel.RUNTIME_CONFIRMED.ordinal
                }
                .mapTo(linkedSetOf()) { it.id }
            val unresolvedTargets =
                requestedTargets - resolvedTargets
            val attempt = RuntimeStageAttempt(
                stage =
                    RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
                state = if (unresolvedTargets.isEmpty()) {
                    RuntimeStageAttemptState.COMPLETED
                } else {
                    RuntimeStageAttemptState.BLOCKED
                },
                attemptedAtEpochMs =
                    System.currentTimeMillis(),
                requestedTargetIds = requestedTargets,
                resolvedTargetIds = resolvedTargets,
                blockers = if (unresolvedTargets.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        RuntimeStageBlocker(
                            code =
                                "REPACKED_RUNTIME_EVIDENCE_UNRESOLVED",
                            message =
                                "Repacked runtime capture completed, but proof remains unresolved for: " +
                                    unresolvedTargets
                                        .sorted()
                                        .joinToString(),
                            category =
                                RuntimeStageBlockerCategory.EVIDENCE_GAP,
                        ),
                    )
                },
            )
            val withAttempt = appendAttempt(
                result = integrated,
                attempt = attempt,
            )
            val withEvidence = persistRuntimeEvidence(
                context = context,
                session = session,
                integrated = withAttempt,
                fallbackEvidence = evidence,
            )
            return persistRuntimeAttempts(
                context = context,
                session = withEvidence.copy(
                    repackedRuntimeBuild = build,
                ),
            )
        } catch (failure: Throwable) {
            if (failure is AnalysisCancelledException) {
                throw failure
            }
            val attempt =
                RuntimeStageAttemptRecorder.blockedAttempt(
                    stage =
                        RuntimeEscalationStage.REPACKED_TEST_RUNTIME,
                    requestedTargetIds = requestedTargets,
                    failure = failure,
                )
            val updated = appendAttempt(
                result = session.result,
                attempt = attempt,
            ).copy(
                engineWarnings = (
                    session.result.engineWarnings +
                        "runtime.repacked: " +
                        (
                            failure.message
                                ?: failure.javaClass.simpleName
                            )
                    ).distinct(),
            )
            return persistRuntimeAttempts(
                context = context,
                session = session.withResult(updated),
            )
        }
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

    private fun resolveIl2CppRuntimeModule(
        context: Context,
        result: FastAnalysisResult,
    ): ResolvedRuntimeModule {
        val libraryEntry = result.il2cppBinaryBinding
            ?.evidence
            ?.firstOrNull()
            ?.libraryEntry
            ?: result.il2cppFastDump
                ?.libraryEntries
                ?.firstOrNull {
                    it.endsWith("/libil2cpp.so") ||
                        it.endsWith(":libil2cpp.so")
                }
            ?: error("В текущем результате нет IL2CPP library evidence.")

        val abi = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            .firstOrNull { candidate ->
                "/$candidate/" in libraryEntry.lowercase()
            }
            ?: error("Не удалось определить ABI для libil2cpp.so.")
        val moduleName = libraryEntry.substringAfterLast('/')
        val relative = result.index.artifactSha256 +
            "/il2cpp/native/" + abi + "-libil2cpp.so"
        val moduleFile = listOf(
            File(context.filesDir, "expert-lab-results/" + relative),
            File(context.filesDir, "analysis-results/" + relative),
        ).firstOrNull { it.isFile && it.canRead() }
            ?: error(
                "Извлечённый libil2cpp.so не найден. Сначала запустите IL2CPP binary confirmation.",
            )

        return ResolvedRuntimeModule(
            file = moduleFile,
            moduleName = moduleName,
        )
    }

    private fun appendAttempt(
        result: FastAnalysisResult,
        attempt: RuntimeStageAttempt,
    ): FastAnalysisResult {
        val ledger = RuntimeStageAttemptRecorder.append(
            artifactSha256 = result.index.artifactSha256,
            existing = result.runtimeStageAttempts,
            attempt = attempt,
        )
        return result.copy(
            runtimeStageAttempts = ledger.attempts,
        )
    }

    private suspend fun persistRuntimeAttempts(
        context: Context,
        session: ExpertLabSession,
    ): ExpertLabSession {
        val ledger = RuntimeStageAttemptRecorder.append(
            artifactSha256 = session.result.index.artifactSha256,
            existing = session.result.runtimeStageAttempts.dropLast(1),
            attempt = session.result.runtimeStageAttempts.lastOrNull()
                ?: return session,
        )
        val persisted = withContext(Dispatchers.IO) {
            EngineResultCache(
                File(context.filesDir, "analysis-cache"),
            ).saveRuntimeStageAttempts(
                artifactSha256 = session.result.index.artifactSha256,
                ledger = ledger,
            )
        }
        return if (persisted) {
            session
        } else {
            session.withResult(
                session.result.copy(
                    engineWarnings = (
                        session.result.engineWarnings +
                            "runtime.stage-attempts: persistence failed"
                        ).distinct(),
                ),
            )
        }
    }

    private suspend fun persistRuntimeEvidence(
        context: Context,
        session: ExpertLabSession,
        integrated: FastAnalysisResult,
        fallbackEvidence: RuntimeEvidenceBundle,
    ): ExpertLabSession {
        val runtimeSnapshot = integrated.runtimeEvidence ?: fallbackEvidence
        val persisted = withContext(Dispatchers.IO) {
            EngineResultCache(
                File(context.filesDir, "analysis-cache"),
            ).saveRuntimeEvidence(
                artifactSha256 = session.result.index.artifactSha256,
                result = runtimeSnapshot,
            )
        }
        val finalResult = if (persisted) {
            integrated
        } else {
            integrated.copy(
                engineWarnings = integrated.engineWarnings +
                    "runtime.evidence: persistence failed",
            )
        }
        return session.withResult(finalResult)
    }
}
