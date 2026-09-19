package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass
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
                        val dump = withContext(Dispatchers.IO) {
                            Il2CppFastDumpEngine.analyze(
                                workspace = workspace,
                                outputRoot = outputRoot,
                                cancellation = engineCancellation,
                                progress = progress,
                            )
                        }
                        result.copy(
                            il2cppFastDump = dump,
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
                        )
                    }

                    "il2cpp.codegen-bind" -> {
                        val dump = result.il2cppFastDump
                        if (dump == null) {
                            result.withEngineWarning(
                                engine.id,
                                "Skipped because IL2CPP metadata reconstruction did not complete.",
                            )
                        } else {
                            val binding = withContext(Dispatchers.IO) {
                                Il2CppBinaryBindingEngine.analyze(
                                    workspace = workspace,
                                    metadata = dump.metadata,
                                    outputRoot = outputRoot,
                                    cancellation = engineCancellation,
                                    progress = progress,
                                )
                            }
                            result.copy(
                                il2cppBinaryBinding = binding,
                                il2cppEvidence = EvidenceGate.evaluate(
                                    artifactSha256 = result.index.artifactSha256,
                                    metadataIdentityExact = dump.metadata.magicValid &&
                                        dump.metadata.structuredSupported &&
                                        !dump.metadata.truncated,
                                    binaryIdentityExact = binding.exactBindingAvailable,
                                    runtimeConfirmed = false,
                                    mutationValidated = false,
                                    requestedChangeReady = false,
                                ),
                            )
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

    private fun FastAnalysisResult.withEngineWarning(
        engineId: String,
        message: String,
    ): FastAnalysisResult = copy(
        engineWarnings = (engineWarnings + "$engineId: $message").distinct(),
    )
}
