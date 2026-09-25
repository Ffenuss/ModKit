package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class Il2CppBinaryBindingResult(
    val evidence: List<Il2CppBinaryEvidence>,
    val exactBindingCount: Int,
    val warnings: List<String>,
) : java.io.Serializable {
    val exactBindingAvailable: Boolean get() = exactBindingCount > 0
}

object Il2CppBinaryBindingEngine {
    private const val MAX_LIBRARY_BYTES = 2L * 1024L * 1024L * 1024L
    private const val EXTRACTION_READ_BYTES = 128 * 1024
    private const val EXTRACTION_WRITE_BUFFER_BYTES = 256 * 1024
    private const val HEARTBEAT_MS = 1_000L
    private const val LOW_MEMORY_BINDING_LIMIT = 5_000

    fun analyze(
        workspace: AnalysisWorkspace,
        metadata: Il2CppMetadataModel,
        outputRoot: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): Il2CppBinaryBindingResult {
        if (!metadata.structuredSupported || metadata.images.isEmpty()) {
            return Il2CppBinaryBindingResult(
                evidence = emptyList(),
                exactBindingCount = 0,
                warnings = listOf("IL2CPP binary binding requires reconstructed metadata image definitions."),
            )
        }

        val candidates = workspace.index.entries
            .filter { "il2cpp_binary" in it.tags && "elf_valid" in it.tags }
            .sortedWith(compareBy<ArtifactEntry> { abiPriority(it.abi) }.thenBy { it.path })

        if (candidates.isEmpty()) {
            return Il2CppBinaryBindingResult(
                evidence = emptyList(),
                exactBindingCount = 0,
                warnings = listOf("No validated libil2cpp.so candidate is available."),
            )
        }

        val resultDir = File(
            outputRoot,
            workspace.index.artifactSha256 + "/il2cpp/native",
        ).apply { mkdirs() }

        val evidence = mutableListOf<Il2CppBinaryEvidence>()
        val warnings = mutableListOf<String>()

        for ((index, candidate) in candidates.withIndex()) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()

            progress.publish(
                EngineProgress(
                    engineId = "il2cpp.codegen-bind",
                    scheduleClass = EngineScheduleClass.CONFIRMATION,
                    state = RunState.RUNNING,
                    currentTask = "IL2CPP: binary confirmation",
                    currentArtifact = candidate.path,
                    processed = index.toLong(),
                    total = candidates.size.toLong(),
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )

            val source = workspace.sources.firstOrNull {
                it.descriptor.displayName == candidate.container
            }
            if (source == null) {
                warnings += "Source container unavailable for " + candidate.path
                continue
            }

            val safeAbi = candidate.abi ?: "unknown"
            val output = File(
                resultDir,
                safeAbi.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-libil2cpp.so",
            )

            val extracted = runCatching {
                extract(
                    archive = source.file,
                    entryName = candidate.path,
                    output = output,
                    expectedSize = candidate.size,
                    cancellation = cancellation,
                    progress = progress,
                )
            }
            val extractionFailure = extracted.exceptionOrNull()
            if (extractionFailure != null) {
                if (extractionFailure is AnalysisCancelledException) throw extractionFailure
                warnings += candidate.path + ": " +
                    (extractionFailure.message ?: extractionFailure.javaClass.simpleName)
                continue
            }

            // The extraction can finish inside the heartbeat window. Mark the
            // phase transition explicitly before the potentially long ELF and
            // method-pointer analysis, rather than leaving the UI at 16 KiB.
            progress.publish(
                EngineProgress(
                    engineId = "il2cpp.codegen-bind",
                    scheduleClass = EngineScheduleClass.CONFIRMATION,
                    state = RunState.RUNNING,
                    currentTask = "IL2CPP: анализ libil2cpp.so",
                    currentArtifact = candidate.path,
                    processed = 0,
                    total = null,
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )

            var lowMemoryRetry = false
            val scan =
                runCatching {
                    Il2CppCodeGenScanner.scan(
                        file = output,
                        libraryEntry =
                            candidate.container +
                                ":" +
                                candidate.path,
                        metadata = metadata,
                        cancellation =
                            cancellation,
                        progress = progress,
                    )
                }.recoverCatching {
                    failure ->
                    if (
                        failure !is
                        OutOfMemoryError
                    ) {
                        throw failure
                    }
                    lowMemoryRetry = true
                    // The first attempt has unwound. Ask ART to reclaim scan
                    // windows and partial binding objects before the compact
                    // retry; source metadata stays SHA-bound and unchanged.
                    System.gc()
                    Il2CppCodeGenScanner.scan(
                        file = output,
                        libraryEntry =
                            candidate.container +
                                ":" +
                                candidate.path,
                        metadata = metadata,
                        cancellation =
                            cancellation,
                        progress = progress,
                        maxMaterializedBindings =
                            LOW_MEMORY_BINDING_LIMIT,
                    )
                }
            val scanFailure = scan.exceptionOrNull()
            if (scanFailure != null) {
                if (scanFailure is AnalysisCancelledException) throw scanFailure
                warnings += candidate.path + ": " +
                    (scanFailure.message ?: scanFailure.javaClass.simpleName)
                continue
            }

            val item = scan.getOrThrow()
            if (lowMemoryRetry) {
                warnings +=
                    candidate.path +
                        ": low-memory IL2CPP retry succeeded; " +
                        "exact bindings are capped at " +
                        LOW_MEMORY_BINDING_LIMIT +
                        " prioritized methods for this pass."
            }
            evidence += item
            if (item.exactBindingAvailable) {
                break
            }
        }

        val exactCount = evidence.sumOf { it.bindingIndex?.boundCount ?: it.bindings.size }
        if (exactCount == 0) {
            warnings += "No exact metadata-token → executable method-pointer binding was proven."
        }

        return Il2CppBinaryBindingResult(
            evidence = evidence,
            exactBindingCount = exactCount,
            warnings = warnings.distinct(),
        )
    }

    // Internal for regression tests: the final extraction event must be
    // emitted even when the whole library copies faster than HEARTBEAT_MS.
    internal fun extract(
        archive: File,
        entryName: String,
        output: File,
        expectedSize: Long,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ) {
        require(archive.isFile) { "Archive is unavailable: " + archive.absolutePath }
        var written = 0L
        var lastHeartbeat = System.currentTimeMillis()
        progress.publish(
            EngineProgress(
                engineId = "il2cpp.codegen-bind",
                scheduleClass = EngineScheduleClass.CONFIRMATION,
                state = RunState.RUNNING,
                currentTask = "IL2CPP: извлечение libil2cpp.so",
                currentArtifact = entryName,
                processed = 0,
                total = expectedSize.takeIf { it >= 0L },
                lastHeartbeatEpochMs = lastHeartbeat,
            ),
        )

        try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(entryName)
                    ?: error("Archive entry disappeared after indexing: " + entryName)
                require(entry.size < 0L || entry.size <= MAX_LIBRARY_BYTES) {
                    "libil2cpp.so exceeds bounded extraction limit"
                }

                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output)
                        .buffered(EXTRACTION_WRITE_BUFFER_BYTES)
                        .use { sink ->
                        val buffer =
                            ByteArray(EXTRACTION_READ_BYTES)
                        while (true) {
                            if (cancellation.isCancelled()) throw AnalysisCancelledException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            require(written <= MAX_LIBRARY_BYTES) {
                                "libil2cpp.so exceeds bounded extraction limit"
                            }
                            sink.write(buffer, 0, read)

                            val now = System.currentTimeMillis()
                            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                                lastHeartbeat = now
                                progress.publish(
                                    EngineProgress(
                                        engineId = "il2cpp.codegen-bind",
                                        scheduleClass = EngineScheduleClass.CONFIRMATION,
                                        state = RunState.RUNNING,
                                        currentTask = "IL2CPP: извлечение libil2cpp.so",
                                        currentArtifact = entryName,
                                        processed = written,
                                        total = expectedSize.takeIf { it >= 0L },
                                        lastHeartbeatEpochMs = now,
                                    ),
                                )
                            }
                        }
                        sink.flush()
                    }
                }
            }

            require(expectedSize < 0L || written == expectedSize) {
                "libil2cpp.so extraction size differs from indexed entry: " +
                    "$written / $expectedSize"
            }
            require(output.length() == written) {
                "libil2cpp.so extraction was not fully written to disk."
            }
            progress.publish(
                EngineProgress(
                    engineId = "il2cpp.codegen-bind",
                    scheduleClass = EngineScheduleClass.CONFIRMATION,
                    state = RunState.RUNNING,
                    currentTask = "IL2CPP: libil2cpp.so извлечена",
                    currentArtifact = entryName,
                    processed = written,
                    total = written,
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    private fun abiPriority(abi: String?): Int = when (abi) {
        "arm64-v8a" -> 0
        "armeabi-v7a" -> 1
        "x86_64" -> 2
        "x86" -> 3
        else -> 4
    }
}
