package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.Serializable
import java.util.zip.ZipFile

data class ElfInventoryRecord(
    val container: String,
    val entryPath: String,
    val size: Long,
    val bits: Int,
    val machine: Int,
    val architecture: String,
    val indexedAbi: String?,
    val abiPathMismatch: Boolean,
    val loadSegmentCount: Int,
    val executableLoadSegmentCount: Int,
    val dynamicSymbolCount: Int,
    val definedDynamicSymbolCount: Int,
    val sampledDefinedSymbols: List<String>,
    val warnings: List<String>,
) : Serializable

data class UniversalElfInventoryResult(
    val records: List<ElfInventoryRecord>,
    val warnings: List<String>,
) : Serializable

/**
 * Portable ELF inventory for every validated ELF in the ArtifactIndex.
 *
 * Large libraries are streamed to a bounded temporary file when they originate
 * inside APK/ZIP containers. This backend inventories ELF32/ELF64 and supported
 * Android machine IDs without claiming deep disassembly coverage.
 */
object UniversalElfInventoryEngine {
    const val ID = "elf.universal-inventory"
    const val VERSION = "2"

    private const val MAX_ELF_BYTES = 512L * 1024L * 1024L
    private const val BUFFER_BYTES = 128 * 1024
    private const val MAX_SYMBOL_SAMPLE = 256

    fun analyze(
        workspace: AnalysisWorkspace,
        outputRoot: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): UniversalElfInventoryResult {
        val candidates = workspace.index.entries.filter {
            it.format == BinaryFormat.ELF
        }
        val records = mutableListOf<ElfInventoryRecord>()
        val warnings = mutableListOf<String>()
        val tempRoot = File(
            outputRoot,
            workspace.index.artifactSha256 + "/elf/inventory-tmp",
        ).apply { mkdirs() }

        try {
            candidates.forEachIndexed { index, entry ->
                checkCancelled(cancellation)
                val artifact = entry.container + ":" + entry.path
                // Distinguish a genuinely slow file from a stale 7/16 counter:
                // begin each file with an explicit byte progress event.
                progress.publish(EngineProgress(
                    engineId = ID,
                    scheduleClass = EngineScheduleClass.TARGETED,
                    state = RunState.RUNNING,
                    currentTask = "ELF: извлечение или открытие файла " +
                        (index + 1) + "/" + candidates.size,
                    currentArtifact = artifact,
                    processed = 0,
                    total = entry.size.takeIf { it > 0L },
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ))
                if (entry.size !in 1..MAX_ELF_BYTES) {
                    warnings += entry.container + ":" + entry.path +
                        ": ELF size is outside inventory limit"
                    return@forEachIndexed
                }

                val source = workspace.sources.singleOrNull {
                    it.descriptor.displayName == entry.container
                }
                if (source == null) {
                    warnings += entry.container + ":" + entry.path +
                        ": source container unavailable"
                    return@forEachIndexed
                }

                val materialized = runCatching {
                    materializeEntry(
                        source = source,
                        entry = entry,
                        tempRoot = tempRoot,
                        cancellation = cancellation,
                        onCopiedBytes = { copied ->
                            progress.publish(EngineProgress(
                                engineId = ID,
                                scheduleClass = EngineScheduleClass.TARGETED,
                                state = RunState.RUNNING,
                                currentTask = "ELF: извлечение файла " +
                                    (index + 1) + "/" + candidates.size,
                                currentArtifact = artifact,
                                processed = copied,
                                total = entry.size,
                                lastHeartbeatEpochMs = System.currentTimeMillis(),
                            ))
                        },
                    )
                }.getOrElse { failure ->
                    if (failure is AnalysisCancelledException) throw failure
                    warnings += entry.container + ":" + entry.path + ": " +
                        (failure.message ?: failure.javaClass.simpleName)
                    return@forEachIndexed
                }

                try {
                    // After extraction finishes the byte counter is no longer
                    // applicable. Label the actual ELF parser separately.
                    progress.publish(EngineProgress(
                        engineId = ID,
                        scheduleClass = EngineScheduleClass.TARGETED,
                        state = RunState.RUNNING,
                        currentTask = "ELF: разбор заголовков и символов",
                        currentArtifact = artifact,
                        processed = index.toLong(),
                        total = candidates.size.toLong(),
                        lastHeartbeatEpochMs = System.currentTimeMillis(),
                    ))
                    val record = runCatching {
                        ElfImage.open(materialized.file, cancellation).use { elf ->
                            val architecture = architectureForMachine(
                                elf.machine,
                                elf.is64Bit,
                            )
                            val mismatch = entry.abi?.let { abi ->
                                !abiMatchesMachine(abi, elf.machine, elf.is64Bit)
                            } ?: false
                            val defined = elf.dynamicSymbols.filter { it.defined }
                            ElfInventoryRecord(
                                container = entry.container,
                                entryPath = entry.path,
                                size = entry.size,
                                bits = if (elf.is64Bit) 64 else 32,
                                machine = elf.machine,
                                architecture = architecture,
                                indexedAbi = entry.abi,
                                abiPathMismatch = mismatch,
                                loadSegmentCount = elf.loadSegments.size,
                                executableLoadSegmentCount = elf.loadSegments.count {
                                    it.executable
                                },
                                dynamicSymbolCount = elf.dynamicSymbols.size,
                                definedDynamicSymbolCount = defined.size,
                                sampledDefinedSymbols = defined.asSequence()
                                    .map { it.name }
                                    .filter { it.isNotBlank() }
                                    .distinct()
                                    .sorted()
                                    .take(MAX_SYMBOL_SAMPLE)
                                    .toList(),
                                warnings = buildList {
                                    if (architecture == "unknown") {
                                        add("ELF machine is not mapped to a supported Android architecture.")
                                    }
                                    if (mismatch) {
                                        add("ABI/path classification disagrees with ELF machine/class.")
                                    }
                                    if (elf.loadSegments.isEmpty()) {
                                        add("ELF contains no PT_LOAD segments.")
                                    }
                                },
                            )
                        }
                    }.getOrElse { failure ->
                        if (failure is AnalysisCancelledException) throw failure
                        warnings += entry.container + ":" + entry.path + ": " +
                            (failure.message ?: failure.javaClass.simpleName)
                        null
                    }
                    if (record != null) records += record
                } finally {
                    if (materialized.temporary) {
                        materialized.file.delete()
                    }
                }

                progress.publish(
                    EngineProgress(
                        engineId = ID,
                        scheduleClass = EngineScheduleClass.TARGETED,
                        state = RunState.RUNNING,
                        currentTask = "ELF inventory",
                        currentArtifact = entry.container + ":" + entry.path,
                        processed = (index + 1).toLong(),
                        total = candidates.size.toLong(),
                        lastHeartbeatEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
        } finally {
            tempRoot.listFiles()?.forEach(File::delete)
            tempRoot.delete()
        }

        progress.publish(
            EngineProgress(
                engineId = ID,
                scheduleClass = EngineScheduleClass.TARGETED,
                state = RunState.COMPLETED,
                currentTask = "ELF inventory ready",
                processed = records.size.toLong(),
                total = candidates.size.toLong(),
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )

        return UniversalElfInventoryResult(
            records = records,
            warnings = warnings.distinct(),
        )
    }

    private data class MaterializedElf(
        val file: File,
        val temporary: Boolean,
    )

    private fun materializeEntry(
        source: WorkspaceSource,
        entry: ArtifactEntry,
        tempRoot: File,
        cancellation: CancellationSignal,
        onCopiedBytes: (Long) -> Unit,
    ): MaterializedElf {
        if (
            entry.path == source.file.name &&
            entry.container == source.descriptor.displayName
        ) {
            return MaterializedElf(source.file, false)
        }

        val safeName = Integer.toHexString(
            (entry.container + ":" + entry.path).hashCode(),
        ) + ".elf"
        val target = File(tempRoot, safeName)
        target.delete()

        ZipFile(source.file).use { zip ->
            val zipEntry = zip.getEntry(entry.path)
                ?: error("ELF entry disappeared from source archive")
            require(!zipEntry.isDirectory) { "ELF entry is a directory" }
            require(zipEntry.size in 1..MAX_ELF_BYTES) {
                "ELF entry size is outside inventory limit"
            }

            zip.getInputStream(zipEntry).use { input ->
                copyBounded(
                    input = input, output = target, expectedSize = zipEntry.size,
                    cancellation = cancellation, onProgress = onCopiedBytes,
                )
            }
        }
        return MaterializedElf(target, true)
    }

    /**
     * Testable bounded copy with authentic byte progress and a mandatory final
     * update even when a large ELF finishes within one heartbeat interval.
     * Partial files are removed on I/O failure, cancellation or size mismatch.
     */
    internal fun copyBounded(
        input: InputStream,
        output: File,
        expectedSize: Long,
        cancellation: CancellationSignal,
        onProgress: (Long) -> Unit,
    ) {
        require(expectedSize in 1..MAX_ELF_BYTES)
        var total = 0L
        var lastTime = System.currentTimeMillis()
        var lastReported = 0L
        try {
            onProgress(0)
            FileOutputStream(output).use { sink ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    checkCancelled(cancellation)
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    require(total <= MAX_ELF_BYTES && total <= expectedSize) {
                        "ELF extraction exceeded indexed/bounded size"
                    }
                    sink.write(buffer, 0, n)
                    val now = System.currentTimeMillis()
                    if (now - lastTime >= 1_000L ||
                        total - lastReported >= 1L * 1024L * 1024L
                    ) {
                        lastTime = now
                        lastReported = total
                        onProgress(total)
                    }
                }
            }
            require(total == expectedSize && output.length() == expectedSize) {
                "ELF extraction size mismatch"
            }
            onProgress(total)
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    private fun architectureForMachine(
        machine: Int,
        is64Bit: Boolean,
    ): String =
        when (machine) {
            40 -> if (is64Bit) "unknown" else "armeabi-v7a"
            183 -> if (is64Bit) "arm64-v8a" else "unknown"
            3 -> if (is64Bit) "unknown" else "x86"
            62 -> if (is64Bit) "x86_64" else "unknown"
            else -> "unknown"
        }

    private fun abiMatchesMachine(
        abi: String,
        machine: Int,
        is64Bit: Boolean,
    ): Boolean =
        when (abi.lowercase()) {
            "armeabi-v7a" -> machine == 40 && !is64Bit
            "arm64-v8a" -> machine == 183 && is64Bit
            "x86" -> machine == 3 && !is64Bit
            "x86_64" -> machine == 62 && is64Bit
            else -> true
        }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }
}
