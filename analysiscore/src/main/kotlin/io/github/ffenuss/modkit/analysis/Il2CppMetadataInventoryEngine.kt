package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile

data class Il2CppMetadataInventoryRecord(
    val container: String, val path: String, val version: Int?,
    val magicValid: Boolean, val structuredSupported: Boolean, val truncated: Boolean,
    val declaredTypes: Int?, val declaredMethods: Int?, val declaredFields: Int?, val declaredImages: Int?,
    val parsedTypes: Int, val parsedMethods: Int, val parsedFields: Int, val parsedImages: Int,
    val typeSamples: List<Il2CppTypeDefinition>, val methodSamples: List<Il2CppMethodDefinition>,
    val fieldSamples: List<Il2CppFieldDefinition>, val imageSamples: List<Il2CppImageDefinition>,
    val warnings: List<String>,
)
data class Il2CppMetadataInventoryResult(val records: List<Il2CppMetadataInventoryRecord>, val warnings: List<String>)

/** Reuses the original metadata reader; keeps bounded summaries rather than all models in memory. */
object Il2CppMetadataInventoryEngine {
    data class Limits(val maxFiles: Int = 4, val maxFileBytes: Long = 64L * 1024 * 1024,
        val maxTotalBytes: Long = 128L * 1024 * 1024,
        val reader: Il2CppMetadataReader.Limits = Il2CppMetadataReader.Limits(
            maxTypes = 20_000, maxMethods = 75_000, maxFields = 75_000,
            maxImages = 2_048, maxCachedStrings = 20_000))
    fun analyze(workspace: AnalysisWorkspace, outputRoot: File, cancellation: CancellationSignal,
        progress: ProgressSink, limits: Limits = Limits()): Il2CppMetadataInventoryResult {
        require(limits.maxFiles in 1..32 && limits.maxFileBytes >= 8 && limits.maxTotalBytes >= 8)
        checkCancelled(cancellation)
        val candidates = workspace.index.entries.filter { it.format == BinaryFormat.IL2CPP_METADATA || "il2cpp_metadata" in it.tags }
        val warnings = mutableListOf<String>()
        val records = mutableListOf<Il2CppMetadataInventoryRecord>()
        if (candidates.isEmpty()) return Il2CppMetadataInventoryResult(records, warnings)
        if (candidates.size > limits.maxFiles) warnings += "Metadata file limit: ${limits.maxFiles}/${candidates.size}"
        require(outputRoot.isDirectory || outputRoot.mkdirs()) { "Metadata workspace unavailable" }
        val temp = Files.createTempDirectory(outputRoot.toPath(), "il2cpp-").toFile()
        var consumed = 0L
        try {
            candidates.take(limits.maxFiles).forEachIndexed { ordinal, entry ->
                checkCancelled(cancellation)
                val identity = "${entry.container}:${entry.path}"
                if (entry.size !in 8..limits.maxFileBytes || entry.size > limits.maxTotalBytes - consumed) {
                    warnings += "$identity: metadata byte limit; not parsed"
                    return@forEachIndexed
                }
                val source = workspace.sources.singleOrNull { it.descriptor.displayName == entry.container }
                if (source == null) { warnings += "$identity: source unavailable"; return@forEachIndexed }
                consumed += entry.size // Failed candidates still consume the attempted-work budget.
                val extracted = File(temp, "metadata-$ordinal.dat")
                try {
                    fun copy(input: InputStream, expectedCrc: Long?) {
                        val crc = CRC32(); var count = 0L; var reported = -1L
                        extracted.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                checkCancelled(cancellation)
                                val read = input.read(buffer)
                                if (read < 0) break
                                require(read > 0) { "Metadata input made no progress" }
                                count += read
                                require(count <= entry.size && count <= limits.maxFileBytes) { "Metadata exceeded declared size" }
                                output.write(buffer, 0, read); crc.update(buffer, 0, read)
                                if (reported < 0 || count - reported >= 1024 * 1024 || count == entry.size) {
                                    reported = count
                                    progress.publish(EngineProgress("il2cpp.metadata-inventory", EngineScheduleClass.TARGETED,
                                        RunState.RUNNING, "IL2CPP: извлечение metadata", identity, count, entry.size, System.currentTimeMillis()))
                                }
                            }
                        }
                        require(count == entry.size) { "Metadata size changed" }
                        require(expectedCrc == null || crc.value == expectedCrc) { "Metadata CRC mismatch" }
                    }
                    if (entry.path == source.file.name) source.file.inputStream().use { copy(it, null) }
                    else ZipFile(source.file).use { zip ->
                        val candidate = zip.getEntry(entry.path) ?: error("Metadata entry disappeared")
                        require(!candidate.isDirectory && candidate.size == entry.size && candidate.crc == entry.crc32) { "Metadata ZIP identity changed" }
                        zip.getInputStream(candidate).use { copy(it, candidate.crc) }
                    }
                    val model = Il2CppMetadataReader.read(extracted, cancellation, ProgressSink { progress.publish(it.copy(currentArtifact = identity)) },
                        limits.reader.copy(maxFileBytes = minOf(limits.maxFileBytes, limits.reader.maxFileBytes)))
                    records += Il2CppMetadataInventoryRecord(entry.container, entry.path, model.metadataVersion,
                        model.magicValid, model.structuredSupported, model.truncated,
                        model.declaredTypeCount, model.declaredMethodCount, model.declaredFieldCount, model.declaredImageCount,
                        model.types.size, model.methods.size, model.fields.size, model.images.size,
                        model.types.take(16), model.methods.take(24), model.fields.take(24), model.images.take(8), model.warnings)
                } catch (failure: Exception) {
                    if (failure is AnalysisCancelledException) throw failure
                    warnings += "$identity: ${failure.message ?: failure.javaClass.simpleName}"
                } finally { extracted.delete() }
            }
        } finally {
            temp.listFiles()?.forEach(File::delete); temp.delete()
        }
        checkCancelled(cancellation)
        return Il2CppMetadataInventoryResult(records, warnings)
    }
    private fun checkCancelled(signal: CancellationSignal) { if (signal.isCancelled()) throw AnalysisCancelledException() }
}
