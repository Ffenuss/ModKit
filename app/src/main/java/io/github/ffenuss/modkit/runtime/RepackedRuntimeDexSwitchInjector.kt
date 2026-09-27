package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.patch.DexRuntimeSelection
import io.github.ffenuss.modkit.patch.DexRuntimeSwitchRewriter
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class RuntimeDexApkSource(
    val sourceDisplayName: String,
    val inputPath: String,
    val inputSha256: String,
    val outputPath: String,
    val outputSha256: String,
    val rewrittenDexSha256: Map<String, String>,
)

/** Separate provenance stage; a changed DEX must not masquerade as an unchanged native injection. */
data class RepackedRuntimeDexSwitchInjection(
    val nativeInjection: RepackedRuntimeNativeProbeInjectionResult,
    val sources: List<RuntimeDexApkSource>,
    val switchIds: Set<String>,
    val instrumentedMethods: Int,
)

object RepackedRuntimeDexSwitchInjector {
    const val CATALOG = "assets/modkit-dex-switches.txt"
    private const val MAX_DEX = 96 * 1024 * 1024

    fun inject(
        nativeInjection: RepackedRuntimeNativeProbeInjectionResult,
        sourceNames: List<String>,
        selections: List<DexRuntimeSelection>,
        outputRoot: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimeDexSwitchInjection {
        require(selections.isNotEmpty() && selections.map { it.method.id }.distinct().size == selections.size)
        require(nativeInjection.sources.map { it.sourceDisplayName } == sourceNames) { "APK-set order changed before DEX instrumentation." }
        require(selections.all { it.method.apkIndex in sourceNames.indices }) { "DEX source APK is missing." }
        val switchIds = selections.map { it.switchId }.toSet()
        require(switchIds.size in 1..24 && switchIds.all { it.matches(Regex("dex:[0-9a-f]{32}")) })
        val catalog = (switchIds.sorted().joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        val root = File(outputRoot, nativeInjection.artifactSha256 + "/repacked-test/dex-switch-injection").apply { mkdirs() }
        try {
            val sources = nativeInjection.sources.mapIndexed { index, source ->
                checkCancelled(cancellation)
                val input = File(source.outputPath)
                require(hash(input.inputStream(), cancellation) == source.outputSha256) { "Native injection changed before DEX instrumentation." }
                val byEntry = selections.filter { it.method.apkIndex == index }.groupBy { it.method.dexEntry }
                val isBase = source.sourceDisplayName == nativeInjection.baseSourceDisplayName
                val replacement = linkedMapOf<String, ByteArray>()
                ZipFile(input).use { zip ->
                    require(zip.getEntry(CATALOG) == null) { "APK already contains a DEX switch catalog. Use the original APK." }
                    byEntry.forEach { (name, chosen) ->
                        require(name.matches(Regex("classes(?:[0-9]+)?[.]dex")))
                        val entry = requireNotNull(zip.getEntry(name)) { "Selected DEX entry is missing: $name" }
                        require(entry.size in 1..MAX_DEX.toLong())
                        val bytes = zip.getInputStream(entry).use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                checkCancelled(cancellation)
                                val count = stream.read(buffer)
                                if (count < 0) break
                                require(out.size().toLong() + count <= MAX_DEX) { "DEX expansion limit exceeded." }
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                        require(bytes.size.toLong() == entry.size && bytes.size <= MAX_DEX)
                        val rewritten = DexRuntimeSwitchRewriter.rewrite(bytes, index, name, chosen,
                            File(root, "$index-$name"), cancellation)
                        require(rewritten.appliedIds == chosen.map { it.method.id }.toSet())
                        replacement[name] = rewritten.file.readBytes()
                        rewritten.file.delete()
                    }
                }
                val dexHashes = replacement.mapValues { hash(it.value) }
                if (isBase) replacement[CATALOG] = catalog
                val output = if (replacement.isEmpty()) input else File(root, "$index.apk").also {
                    rewriteApk(input, it, replacement, if (isBase) CATALOG else null, cancellation)
                }
                RuntimeDexApkSource(source.sourceDisplayName, input.absolutePath, source.outputSha256,
                    output.absolutePath, hash(output.inputStream(), cancellation), dexHashes)
            }
            return RepackedRuntimeDexSwitchInjection(nativeInjection, sources, switchIds, selections.size)
                .also { verify(it, cancellation) }
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    fun verify(result: RepackedRuntimeDexSwitchInjection, cancellation: CancellationSignal) {
        require(result.sources.map { it.sourceDisplayName } == result.nativeInjection.sources.map { it.sourceDisplayName })
        for ((index, source) in result.sources.withIndex()) {
            val prior = result.nativeInjection.sources[index]
            require(source.inputPath == prior.outputPath && source.inputSha256 == prior.outputSha256)
            require(hash(File(source.inputPath).inputStream(), cancellation) == source.inputSha256) { "Previous runtime stage changed." }
            require(hash(File(source.outputPath).inputStream(), cancellation) == source.outputSha256) { "DEX-switch APK changed before signing." }
        }
    }

    private fun rewriteApk(input: File, output: File, replacements: Map<String, ByteArray>,
                           addedEntry: String?, signal: CancellationSignal) {
        val expected = linkedMapOf<String, String>()
        ZipFile(input).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    checkCancelled(signal)
                    require(entry.name !in expected) { "Duplicate APK ZIP entry: ${entry.name}" }
                    val bytes = replacements[entry.name]
                    val target = ZipEntry(entry.name).apply {
                        method = entry.method
                        if (entry.time >= 0) time = entry.time
                        if (method == ZipEntry.STORED) {
                            size = bytes?.size?.toLong() ?: entry.size
                            compressedSize = size
                            crc = bytes?.let { CRC32().apply { update(it) }.value } ?: entry.crc
                        }
                    }
                    out.putNextEntry(target)
                    expected[entry.name] = if (bytes != null) {
                        out.write(bytes); hash(bytes)
                    } else hash(zip.getInputStream(entry), signal, out)
                    out.closeEntry()
                }
                require(replacements.keys.all { it in expected || it == addedEntry }) { "Missing selected APK entry." }
                if (addedEntry != null) {
                    require(addedEntry !in expected)
                    val bytes = replacements.getValue(addedEntry)
                    out.putNextEntry(ZipEntry(addedEntry)); out.write(bytes); out.closeEntry()
                    expected[addedEntry] = hash(bytes)
                }
            }
        }
        ZipFile(output).use { zip ->
            val entries = zip.entries().toList()
            require(entries.map { it.name }.toSet() == expected.keys && entries.size == expected.size)
            for (entry in entries) require(hash(zip.getInputStream(entry), signal) == expected.getValue(entry.name)) {
                "DEX instrumentation changed an unexpected APK entry: ${entry.name}"
            }
        }
    }

    private fun hash(input: InputStream, signal: CancellationSignal, output: OutputStream? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use {
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(signal)
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                output?.write(buffer, 0, n)
            }
        }
        return digest.digest().hex()
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun checkCancelled(signal: CancellationSignal) { if (signal.isCancelled()) throw AnalysisCancelledException() }
}
