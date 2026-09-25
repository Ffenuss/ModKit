package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.Serializable
import java.util.zip.ZipFile

data class FlutterAssetManifestRecord(
    val container: String,
    val path: String,
    val status: String,
    val assetCount: Int,
    val sampledAssets: List<String>,
    val note: String? = null,
) : Serializable

data class FlutterRuntimeLibraryRecord(
    val container: String,
    val path: String,
    val abi: String?,
    val component: String,
    val validatedElf: Boolean,
) : Serializable

data class FlutterAssetInventoryResult(
    val manifests: List<FlutterAssetManifestRecord>,
    val runtimeLibraries: List<FlutterRuntimeLibraryRecord>,
    val packagedAssetCount: Int,
    val warnings: List<String>,
) : Serializable {
    val validatedManifestCount: Int get() =
        manifests.count { it.status == "MANIFEST_BIN_PARSED" }
    val listedAssetCount: Int get() =
        manifests.sumOf { it.assetCount }
}

/**
 * Flutter/Dart structural analyzer. Decodes actual AssetManifest.bin codec maps
 * in supplied base/split APKs and identifies AOT and engine ELF components.
 * No inference of game-stat functionality from asset file names.
 */
object FlutterAssetInventoryEngine {
    const val ID = "flutter.asset-inventory"
    const val VERSION = "1"
    private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
    private const val MAX_ASSET_SAMPLE = 24

    fun analyze(
        workspace: AnalysisWorkspace,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): FlutterAssetInventoryResult {
        val entries = workspace.index.entries
        val manifests = entries.filter {
            it.path.endsWith("/AssetManifest.bin", ignoreCase = true) ||
                it.path.endsWith("/AssetManifest.json", ignoreCase = true)
        }
        val libraries = entries.filter {
            "flutter_engine" in it.tags || "flutter_app" in it.tags
        }.map { entry ->
            FlutterRuntimeLibraryRecord(
                container = entry.container,
                path = entry.path,
                abi = entry.abi,
                component = if ("flutter_engine" in entry.tags) "Flutter Engine" else "Dart AOT",
                validatedElf = "elf_valid" in entry.tags,
            )
        }
        val assetFiles = entries.count {
            it.path.contains("flutter_assets/", ignoreCase = true) &&
                !it.path.endsWith("/", ignoreCase = true)
        }
        val sources = workspace.sources.associateBy { it.descriptor.displayName }
        val results = ArrayList<FlutterAssetManifestRecord>(manifests.size)
        val warnings = mutableListOf<String>()
        if (manifests.isEmpty()) {
            warnings += "Flutter AssetManifest.bin/json отсутствует в доступных APK/splits; " +
                "ресурсы могли быть упакованы, удалены или загружены отдельно."
        }
        val grouped = manifests.groupBy { it.container }
        var processed = 0
        for ((container, items) in grouped) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            val source = sources[container]
            if (source == null) {
                items.forEach { entry ->
                    results += FlutterAssetManifestRecord(
                        entry.container, entry.path, "SOURCE_MISSING", 0, emptyList(),
                        "Исходный APK/split недоступен.",
                    )
                }
                warnings += "$container: исходный APK/split недоступен."
                continue
            }
            val standalone = items.all {
                it.path == source.file.name && it.container == source.descriptor.displayName
            }
            val zip = if (standalone) null else runCatching {
                ZipFile(source.file)
            }.getOrElse { error ->
                warnings += "$container: " + (error.message ?: error.javaClass.simpleName)
                null
            }
            try {
                for (entry in items) {
                    if (cancellation.isCancelled()) throw AnalysisCancelledException()
                    val item = when {
                        entry.path.endsWith(".json", ignoreCase = true) ->
                            FlutterAssetManifestRecord(
                                entry.container, entry.path, "LEGACY_JSON_FOUND",
                                0, emptyList(),
                                "Устаревший AssetManifest.json обнаружен. " +
                                    "Его содержимое не считается проверенным бинарным манифестом.",
                            )
                        entry.size !in 1..MAX_MANIFEST_BYTES.toLong() ->
                            FlutterAssetManifestRecord(
                                entry.container, entry.path, "MANIFEST_SIZE_UNSUPPORTED",
                                0, emptyList(),
                                "Размер отсутствует или превышает 8 МиБ.",
                            )
                        zip == null && !standalone ->
                            FlutterAssetManifestRecord(
                                entry.container, entry.path, "SOURCE_UNREADABLE",
                                0, emptyList(), "Не удалось открыть архив APK.",
                            )
                        else -> runCatching {
                            val manifestBytes = if (zip != null) {
                                val item = zip.getEntry(entry.path)
                                    ?: error("Missing ZIP entry: ${entry.path}")
                                zip.getInputStream(item).use {
                                    readBounded(it, entry.size, cancellation)
                                }
                            } else {
                                FileInputStream(source.file).use {
                                    readBounded(it, entry.size, cancellation)
                                }
                            }
                            val names = FlutterStandardMessageCodec
                                .decodeAssetManifest(manifestBytes)
                            FlutterAssetManifestRecord(
                                entry.container, entry.path, "MANIFEST_BIN_PARSED",
                                names.size, names.take(MAX_ASSET_SAMPLE),
                            )
                        }.getOrElse { error ->
                            if (error is AnalysisCancelledException) throw error
                            FlutterAssetManifestRecord(
                                entry.container, entry.path, "MANIFEST_INVALID",
                                0, emptyList(), error.message ?: error.javaClass.simpleName,
                            )
                        }
                    }
                    results += item
                    processed++
                    progress.publish(EngineProgress(
                        engineId = ID,
                        scheduleClass = EngineScheduleClass.TARGETED,
                        state = RunState.RUNNING,
                        currentTask = "Flutter: анализ манифеста ресурсов",
                        currentArtifact = entry.container + ":" + entry.path,
                        processed = processed.toLong(),
                        total = manifests.size.toLong(),
                        lastHeartbeatEpochMs = System.currentTimeMillis(),
                    ))
                }
            } finally {
                zip?.close()
            }
        }
        if (libraries.none { it.component == "Dart AOT" && it.validatedElf }) {
            warnings += "Flutter: проверенная libapp.so отсутствует в выбранном наборе APK/splits."
        }
        if (libraries.none { it.component == "Flutter Engine" && it.validatedElf }) {
            warnings += "Flutter: проверенная libflutter.so отсутствует в выбранном наборе APK/splits."
        }
        progress.publish(EngineProgress(
            engineId = ID,
            scheduleClass = EngineScheduleClass.TARGETED,
            state = RunState.COMPLETED,
            currentTask = "Flutter: ресурсы проиндексированы; Dart AOT модификации недоступны",
            processed = manifests.size.toLong(),
            total = manifests.size.toLong(),
            lastHeartbeatEpochMs = System.currentTimeMillis(),
        ))
        return FlutterAssetInventoryResult(
            manifests = results,
            runtimeLibraries = libraries,
            packagedAssetCount = assetFiles,
            warnings = warnings,
        )
    }

    private fun readBounded(
        input: InputStream,
        expectedSize: Long,
        cancellation: CancellationSignal,
    ): ByteArray {
        require(expectedSize in 1..MAX_MANIFEST_BYTES.toLong())
        val output = ByteArrayOutputStream(minOf(expectedSize.toInt(), 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var readTotal = 0L
        while (true) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            val n = input.read(buffer)
            if (n == -1) break
            require(readTotal + n <= MAX_MANIFEST_BYTES &&
                readTotal + n <= expectedSize
            ) { "Flutter manifest exceeds declared/bounded size." }
            output.write(buffer, 0, n)
            readTotal += n
        }
        require(readTotal == expectedSize) { "Flutter manifest is truncated." }
        return output.toByteArray()
    }
}
