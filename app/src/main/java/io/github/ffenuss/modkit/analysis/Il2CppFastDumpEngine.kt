package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class Il2CppFastDumpResult(
    val metadataEntry: String,
    val libraryEntries: List<String>,
    val metadata: Il2CppMetadataModel,
    val dumpFilePath: String,
    val preview: String,
    val warnings: List<String>,
) : java.io.Serializable

object Il2CppFastDumpEngine {
    private const val MAX_METADATA_BYTES = 1L * 1024L * 1024L * 1024L
    private const val HEARTBEAT_MS = 1_500L

    fun analyze(
        workspace: AnalysisWorkspace,
        outputRoot: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): Il2CppFastDumpResult {
        val metadataEntry = workspace.index.entries.firstOrNull {
            "il2cpp_metadata" in it.tags && "il2cpp_metadata_valid" in it.tags
        } ?: error("Validated global-metadata.dat was not found in ArtifactIndex")

        val libraryEntries = workspace.index.entries.filter {
            "il2cpp_binary" in it.tags && "elf_valid" in it.tags
        }
        require(libraryEntries.isNotEmpty()) { "Validated libil2cpp.so was not found in ArtifactIndex" }

        progress.publish(
            EngineProgress(
                engineId = "il2cpp.fast-dump",
                scheduleClass = EngineScheduleClass.TARGETED,
                state = RunState.RUNNING,
                currentTask = "IL2CPP: извлечение metadata",
                currentArtifact = metadataEntry.path,
                processed = 0,
                total = metadataEntry.size.takeIf { it >= 0L },
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )

        val source = workspace.sources.firstOrNull {
            it.descriptor.displayName == metadataEntry.container
        } ?: error("Source container for global-metadata.dat is unavailable: " + metadataEntry.container)

        val resultDir = File(outputRoot, workspace.index.artifactSha256 + "/il2cpp").apply { mkdirs() }
        val metadataFile = File(resultDir, "global-metadata.dat")
        extractEntry(
            archive = source.file,
            entryName = metadataEntry.path,
            output = metadataFile,
            expectedSize = metadataEntry.size,
            cancellation = cancellation,
            progress = progress,
        )

        val model = Il2CppMetadataReader.read(
            file = metadataFile,
            cancellation = cancellation,
            progress = progress,
        )

        val dumpFile = File(resultDir, "dump.cs")
        Il2CppDumpRenderer.write(
            model = model,
            output = dumpFile,
            cancellation = cancellation,
            progress = progress,
        )

        val warnings = buildList {
            addAll(model.warnings)
            if (!model.structuredSupported) {
                add("Metadata identity is validated, but this layout is not yet structurally reconstructed.")
            }
            if (model.truncated) {
                add("FAST dump is partial; absence of a symbol is not proof of absence.")
            }
            add("Exact native RVA/VA/file-offset binding is not claimed by the metadata-only fast dump.")
        }.distinct()

        progress.publish(
            EngineProgress(
                engineId = "il2cpp.fast-dump",
                scheduleClass = EngineScheduleClass.TARGETED,
                state = RunState.COMPLETED,
                currentTask = "IL2CPP fast dump готов",
                currentArtifact = dumpFile.name,
                processed = model.methods.size.toLong(),
                total = model.declaredMethodCount?.toLong(),
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )

        return Il2CppFastDumpResult(
            metadataEntry = metadataEntry.container + ":" + metadataEntry.path,
            libraryEntries = libraryEntries.map { it.container + ":" + it.path }.distinct().sorted(),
            metadata = model,
            dumpFilePath = dumpFile.absolutePath,
            preview = Il2CppDumpRenderer.preview(model),
            warnings = warnings,
        )
    }

    private fun extractEntry(
        archive: File,
        entryName: String,
        output: File,
        expectedSize: Long,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ) {
        require(archive.isFile) { "Archive is unavailable: " + archive.absolutePath }
        output.parentFile?.mkdirs()
        var written = 0L
        var lastHeartbeat = 0L

        try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(entryName)
                    ?: error("Archive entry disappeared after indexing: " + entryName)
                require(entry.size < 0L || entry.size <= MAX_METADATA_BYTES) {
                    "global-metadata.dat exceeds bounded extraction limit"
                }
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).buffered(128 * 1024).use { sink ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (cancellation.isCancelled()) throw AnalysisCancelledException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            require(written <= MAX_METADATA_BYTES) {
                                "global-metadata.dat exceeds bounded extraction limit"
                            }
                            sink.write(buffer, 0, read)

                            val now = System.currentTimeMillis()
                            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                                lastHeartbeat = now
                                progress.publish(
                                    EngineProgress(
                                        engineId = "il2cpp.fast-dump",
                                        scheduleClass = EngineScheduleClass.TARGETED,
                                        state = RunState.RUNNING,
                                        currentTask = "IL2CPP: извлечение metadata",
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
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }
}
