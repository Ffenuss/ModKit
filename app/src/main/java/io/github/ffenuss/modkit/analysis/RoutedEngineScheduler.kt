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
    private val registeredEngineIds = setOf(
        "dex.inventory",
        "elf.universal-inventory",
        "unreal.package-inventory",
        "flutter.asset-inventory",
        "il2cpp.fast-dump",
        "il2cpp.codegen-bind",
    )

    fun supportsEngine(engineId: String): Boolean =
        engineId in registeredEngineIds

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
        allowedScheduleClasses: Set<EngineScheduleClass> = EngineScheduleClass.entries.toSet(),
        allowedEngineIds: Set<String>? = null,
    ): FastAnalysisResult {
        var result = initial
        val engines = initial.routingPlan.engines
            .asSequence()
            .filter { it.availableNow }
            .filter { it.scheduleClass in allowedScheduleClasses }
            .filter { allowedEngineIds == null || it.id in allowedEngineIds }
            .filter { it.id != "artifact.fast-index" }
            .sortedWith(
                compareBy<PlannedEngine> { executionOrder[it.scheduleClass] ?: Int.MAX_VALUE }
                    .thenBy { it.id },
            )
            .toList()

        for (engine in engines) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            if (
                engine.scheduleClass == EngineScheduleClass.CONFIRMATION &&
                result.confirmationQueue.none { request ->
                    request.engineId == engine.id && request.availableNow
                }
            ) {
                continue
            }
            val engineCancellation = object : CancellationSignal {
                override fun isCancelled(): Boolean =
                    cancellation.isCancelled() || skipController.isRequested(engine.id)
            }

            result = try {
                when (engine.id) {
                    "dex.inventory" -> {
                        val cached = withContext(Dispatchers.IO) {
                            cache?.loadDexInventory(
                                result.index.artifactSha256,
                            )
                        }
                        val inventory = cached ?: withContext(Dispatchers.IO) {
                            DexInventoryEngine.analyze(
                                workspace = workspace,
                                cancellation = engineCancellation,
                                progress = progress,
                            )
                        }.also { produced ->
                            withContext(Dispatchers.IO) {
                                cache?.saveDexInventory(
                                    result.index.artifactSha256,
                                    produced,
                                )
                            }
                        }
                        if (cached != null) {
                            publishCacheHit(
                                progress,
                                engine,
                                result.index.artifactSha256,
                            )
                        }
                        result.copy(
                            dexInventory = inventory,
                            engineCacheHits = if (cached != null) {
                                result.engineCacheHits + engine.id
                            } else {
                                result.engineCacheHits
                            },
                            engineWarnings = (
                                result.engineWarnings +
                                    inventory.warnings.map {
                                        engine.id + ": " + it
                                    }
                                ).distinct(),
                        )
                    }

                    "flutter.asset-inventory" -> {
                        val inventory = withContext(Dispatchers.IO) {
                            FlutterAssetInventoryEngine.analyze(
                                workspace, engineCancellation, progress,
                            )
                        }
                        result.copy(
                            flutterAssetInventory = inventory,
                            engineWarnings = (
                                result.engineWarnings + inventory.warnings.map {
                                    engine.id + ": " + it
                                }
                            ).distinct(),
                        )
                    }

                    "unreal.package-inventory" -> {
                        val inventory = withContext(Dispatchers.IO) {
                            UnrealAssetInventoryEngine.analyze(
                                workspace, engineCancellation, progress,
                            )
                        }
                        result.copy(
                            unrealAssetInventory = inventory,
                            engineWarnings = (
                                result.engineWarnings + inventory.warnings.map {
                                    engine.id + ": " + it
                                }
                            ).distinct(),
                        )
                    }

                    "elf.universal-inventory" -> {
                        val cached = withContext(Dispatchers.IO) {
                            cache?.loadUniversalElfInventory(
                                result.index.artifactSha256,
                            )
                        }
                        val inventory = cached ?: withContext(Dispatchers.IO) {
                            UniversalElfInventoryEngine.analyze(
                                workspace = workspace,
                                outputRoot = outputRoot,
                                cancellation = engineCancellation,
                                progress = progress,
                            )
                        }.also { produced ->
                            withContext(Dispatchers.IO) {
                                cache?.saveUniversalElfInventory(
                                    result.index.artifactSha256,
                                    produced,
                                )
                            }
                        }
                        if (cached != null) {
                            publishCacheHit(
                                progress,
                                engine,
                                result.index.artifactSha256,
                            )
                        }
                        result.copy(
                            elfInventory = inventory,
                            engineCacheHits = if (cached != null) {
                                result.engineCacheHits + engine.id
                            } else {
                                result.engineCacheHits
                            },
                            engineWarnings = (
                                result.engineWarnings +
                                    inventory.warnings.map {
                                        engine.id + ": " + it
                                    }
                                ).distinct(),
                        )
                    }

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

                    else -> {
                        check(!engine.availableNow || !supportsEngine(engine.id)) {
                            "Router/scheduler capability mismatch for " + engine.id
                        }
                        result.withEngineWarning(
                            engine.id,
                            "Engine is marked available but no scheduler executor is registered.",
                        )
                    }
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
