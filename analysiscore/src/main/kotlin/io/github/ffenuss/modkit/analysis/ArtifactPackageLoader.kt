package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.*
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

class LoadedArtifactSet(val files: List<File>, private val temporaryRoot: File? = null) : AutoCloseable {
    override fun close() { temporaryRoot?.deleteRecursively() }
}

/** APK, or a bounded APKS/XAPK/ZIP containing original APKs. Never extracts arbitrary paths. */
object ArtifactPackageLoader {
    fun open(input: File, stagingRoot: File, cancellation: CancellationSignal, progress: ProgressSink,
             maxApks: Int = 64, maxApkBytes: Long = 2L * 1024 * 1024 * 1024,
             maxTotalBytes: Long = 8L * 1024 * 1024 * 1024): LoadedArtifactSet {
        require(maxApks > 0 && maxApkBytes > 0 && maxTotalBytes > 0)
        fun check() { if (cancellation.isCancelled()) throw AnalysisCancelledException() }
        check()
        val signature = ByteArray(4)
        val prefixSize = input.inputStream().use { it.read(signature) }
        if (prefixSize < 4 || signature[0] != 0x50.toByte() || signature[1] != 0x4b.toByte())
            return LoadedArtifactSet(listOf(input)) // Raw DEX/ELF/WASM still use their existing analyzers.
        ZipFile(input).use { zip ->
            if (zip.getEntry("AndroidManifest.xml") != null) return LoadedArtifactSet(listOf(input))
            val apkEntries = mutableListOf<java.util.zip.ZipEntry>()
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                check()
                require(++count <= 100_000) { "Too many archive entries" }
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.endsWith(".apk", true)) apkEntries += entry
                require(apkEntries.size <= maxApks) { "Too many APKs in container" }
            }
            if (apkEntries.isEmpty()) return LoadedArtifactSet(listOf(input)) // Generic game-data archives remain indexable.
            val names = apkEntries.map { it.name.substringAfterLast('/') }
            require(names.distinct().size == names.size && names.all { it.matches(Regex("[A-Za-z0-9._-]+")) && it != "." && it != ".." }) {
                "Ambiguous or invalid APK names in container"
            }
            val root = File(stagingRoot, UUID.randomUUID().toString())
            require(root.mkdirs()) { "Cannot create private APK staging" }
            try {
                var total = 0L
                var heartbeat = 0L
                val files = apkEntries.mapIndexed { index, entry ->
                    require(entry.size in 1..maxApkBytes && entry.crc >= 0) { "Invalid APK size/CRC" }
                    val file = File(root, names[index])
                    var copied = 0L
                    val crc = CRC32()
                    zip.getInputStream(entry).use { source -> file.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            check()
                            val size = source.read(buffer)
                            if (size < 0) break
                            copied += size; total += size
                            require(copied <= maxApkBytes && total <= maxTotalBytes) { "APK set exceeds extraction limits" }
                            sink.write(buffer, 0, size); crc.update(buffer, 0, size)
                            val now = System.currentTimeMillis()
                            if (now - heartbeat >= 1500) {
                                heartbeat = now
                                progress.publish(EngineProgress("artifact.apk-set", EngineScheduleClass.FAST, RunState.RUNNING,
                                    currentTask = "Читаем APK внутри комплекта", currentArtifact = entry.name,
                                    processed = total, lastHeartbeatEpochMs = now))
                            }
                        }
                    } }
                    check()
                    require(copied == entry.size && crc.value == entry.crc) { "APK container integrity mismatch" }
                    ZipFile(file).use { nested -> require(nested.getEntry("AndroidManifest.xml") != null) { "Nested APK lacks manifest" } }
                    file
                }
                return LoadedArtifactSet(files, root)
            } catch (failure: Throwable) { root.deleteRecursively(); throw failure }
        }
    }
}
