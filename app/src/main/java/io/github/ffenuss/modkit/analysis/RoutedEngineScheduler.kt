package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes only engines selected by [EngineRouter].
 *
 * The scheduler owns ordering and failure isolation. AnalysisManager owns run lifecycle,
 * persistence, watchdog and cancellation. This keeps engine-specific branching out of
 * the process-level coordinator.
 */
object RoutedEngineScheduler {
    private val executionOrder = mapOf(
        EngineScheduleClass.FAST to 0,
        EngineScheduleClass.TARGETED to 1,
        EngineScheduleClass.CONFIRMATION to 2,
        EngineScheduleClass.BACKGROUND to 3,
    )

    suspend fun execute(
        initial: FastAnalysisResult,
        workspace: AnalysisWorkspace,
        outputRoot: File,
        cancellation: CancellationSignal,
        skipController: EngineSkipController,
        cache: EngineResultCache? = null,
        progress: ProgressSink,
        onPartial: (FastAnalysisResult) -> Unit,
    ): FastAnalysisResult {
        var result = initial
        val engines = initial.routingPlan.engines
            .asSequence()
            .filter { it.availableNow }
            .filter { it.id != "artifact.fast-index" }
            .sortedWith(
                compareBy<PlannedEngine> { executionOrder[it.scheduleClass] ?: Int.MAX_VALUE }
                    .thenBy { it.id },
            )
            .toList()

        for (engine in engines) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            val engineCancellation = object : CancellationSignal {
                override fun isCancelled(): Boolean =
                    cancellation.isCancelled() || skipController.isRequested(engine.id)
            }

            result = try {
                when (engine.id) {
                    "il2cpp.fast-dump" -> {
                        val cached = withContext(Dispatchers.IO) {
                            cache?.loadIl2CppFastDump(result.index.artifactSha256)
                        }
                        val dump = cached ?: withContext(Dispatchers.IO) {
                            Il2CppFastDumpEngine.analyze(
                                workspace = workspace,
                                outputRoot = outputRoot,
                                cancellation = engineCancellation,
                                progress = progress,
                            )
                        }.also { produced ->
                            withContext(Dispatchers.IO) {
                                cache?.saveIl2CppFastDump(
                                    result.index.artifactSha256,
                                    produced,
                                )
                            }
                        }
                        if (cached != null) {
                            publishCacheHit(progress, engine, result.index.artifactSha256)
                        }
                        result.copy(
                            il2cppFastDump = dump,
                            engineCacheHits = if (cached != null) {
                                result.engineCacheHits + engine.id
                            } else {
                                result.engineCacheHits
                            },
                            il2cppEvidence = EvidenceGate.evaluate(
                                artifactSha256 = result.index.artifactSha256,
                                metadataIdentityExact = dump.metadata.magicValid &&
                                    dump.metadata.structuredSupported &&
                                    !dump.metadata.truncated,
                                binaryIdentityExact = false,
                                runtimeConfirmed = false,
                                mutationValidated = false,
                                requestedChangeReady = false,
                            ),
                        ).withEvidenceGraph()
                    }

                    "il2cpp.codegen-bind" -> {
                        val dump = result.il2cppFastDump
                        if (dump == null) {
                            result.withEngineWarning(
                                engine.id,
                                "Skipped because IL2CPP metadata reconstruction did not complete.",
                            )
                        } else {
                            val cached = withContext(Dispatchers.IO) {
                                cache?.loadIl2CppBinaryBinding(result.index.artifactSha256)
                            }
                            val binding = cached ?: withContext(Dispatchers.IO) {
                                Il2CppBinaryBindingEngine.analyze(
                                    workspace = workspace,
                                    metadata = dump.metadata,
                                    outputRoot = outputRoot,
                                    cancellation = engineCancellation,
                                    progress = progress,
                                )
                            }.also { produced ->
                                withContext(Dispatchers.IO) {
                                    cache?.saveIl2CppBinaryBinding(
                                        result.index.artifactSha256,
                                        produced,
                                    )
                                }
                            }
                            if (cached != null) {
                                publishCacheHit(progress, engine, result.index.artifactSha256)
                            }
                            result.copy(
                                il2cppBinaryBinding = binding,
                                engineCacheHits = if (cached != null) {
                                    result.engineCacheHits + engine.id
                                } else {
                                    result.engineCacheHits
                                },
                                il2cppEvidence = EvidenceGate.evaluate(
                                    artifactSha256 = result.index.artifactSha256,
                                    metadataIdentityExact = dump.metadata.magicValid &&
                                        dump.metadata.structuredSupported &&
                                        !dump.metadata.truncated,
                                    binaryIdentityExact = binding.exactBindingAvailable,
                                    runtimeConfirmed = false,
                                    mutationValidated = false,
                                    requestedChangeReady = false,
                                    suppliedBlockers = if (binding.exactBindingAvailable) {
                                        emptyList()
                                    } else {
                                        binding.toEvidenceBlockers()
                                    },
                                ),
                            ).withEvidenceGraph()
                        }
                    }

                    else -> result.withEngineWarning(
                        engine.id,
                        "Engine is marked available but no scheduler executor is registered.",
                    )
                }
            } catch (cancelled: AnalysisCancelledException) {
                if (!cancellation.isCancelled() && skipController.consume(engine.id)) {
                    result.withEngineWarning(engine.id, "Skipped after watchdog/user request.")
                } else {
                    throw cancelled
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                result.withEngineWarning(
                    engine.id,
                    failure.message ?: failure.javaClass.simpleName,
                )
            }

            onPartial(result)
        }

        return result
    }

    private fun Il2CppBinaryBindingResult.toEvidenceBlockers(): List<EvidenceBlocker> =
        evidence.asSequence()
            .flatMap { it.blockers.asSequence() }
            .distinct()
            .map { code ->
                EvidenceBlocker(
                    code = code,
                    message = when (code) {
                        "METADATA_IMAGE_MAP_UNAVAILABLE" ->
                            "Metadata image map is unavailable for exact module association."
                        "CODE_REGISTRATION_SYMBOL_UNRESOLVED" ->
                            "CodeRegistration symbol was not resolved and no replacement proof succeeded."
                        "CODEGEN_MODULE_ARRAY_UNRESOLVED" ->
                            "Il2CppCodeGenModule array could not be resolved."
                        "AMBIGUOUS_CODEGEN_MODULE_ARRAY" ->
                            "Multiple CodeGenModule arrays satisfy the available evidence."
                        "CODEGEN_FALLBACK_SCAN_LIMIT_REACHED" ->
                            "Bounded stripped-binary recovery reached its scan limit."
                        "STRIPPED_CODEGEN_MODULE_ARRAY_UNRESOLVED" ->
                            "Stripped binary recovery did not prove a unique CodeGenModule array."
                        "NO_METHOD_TOKEN_SLOT_BINDINGS" ->
                            "No MethodDef token could be bound to an executable method-pointer slot."
                        else ->
                            "Binary confirmation is blocked: $code."
                    },
                    requiredFor = io.github.ffenuss.modkit.domain.ProofLevel.EXACT_BINARY,
                )
            }
            .toList()

    private fun publishCacheHit(
        progress: ProgressSink,
        engine: PlannedEngine,
        artifactSha256: String,
    ) {
        progress.publish(
            EngineProgress(
                engineId = engine.id,
                scheduleClass = engine.scheduleClass,
                state = RunState.COMPLETED,
                currentTask = "Результат восстановлен из content-addressed cache",
                currentArtifact = artifactSha256.take(16),
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun FastAnalysisResult.withEngineWarning(
        engineId: String,
        message: String,
    ): FastAnalysisResult = copy(
        engineWarnings = (engineWarnings + "$engineId: $message").distinct(),
    )
}
