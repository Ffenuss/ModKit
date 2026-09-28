package io.github.ffenuss.modkit.patch

import android.content.Context
import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EngineResourceChange(
    val artifactSha256: String,
    val apkIndex: Int,
    val sourceName: String,
    val entry: String,
    val format: EngineResourceFormat,
    val sourceSha256: String,
    val sourceSize: Int,
    val key: String,
    val oldValue: String,
    val value: String,
)

data class EngineResourceScan(val recipes: List<AutoModRecipe>, val examinedFiles: Int, val warnings: List<String>)

/** Modifies packaged data only; a parseable field is never proof of its gameplay purpose. */
object EngineResourceMods {
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private const val MAX_FIELDS = 10_000

    fun format(path: String, runtimes: Set<String>): EngineResourceFormat? = when {
        "flutter" in runtimes && path.startsWith("assets/flutter_assets/") && path.endsWith(".json", true) &&
            path.substringAfterLast('/') !in setOf("AssetManifest.json", "FontManifest.json", "NativeAssetsManifest.json") -> EngineResourceFormat.FLUTTER_JSON
        "unreal" in runtimes && path.startsWith("assets/") && path.contains("/Config/", true) && path.endsWith(".ini", true) -> EngineResourceFormat.UNREAL_INI
        else -> null
    }

    fun scan(workspace: AnalysisWorkspace, signal: CancellationSignal, progress: ProgressSink): EngineResourceScan {
        val runtimes = workspace.index.runtimeProfiles.map { it.runtimeId }.toSet()
        val candidates = workspace.index.entries.filter { format(it.path, runtimes) != null }
        val duplicated = candidates.groupBy { it.path }.filterValues { it.size != 1 }.keys
        val warnings = mutableListOf<String>()
        val recipes = mutableListOf<AutoModRecipe>()
        var bytesRead = 0L
        var examined = 0
        var omitted = 0
        candidates.groupBy { it.container }.forEach { (container, entries) ->
            checkCancelled(signal)
            val indexed = workspace.sources.withIndex().singleOrNull { it.value.descriptor.displayName == container }
            requireNotNull(indexed) { "Resource source APK is missing: $container" }
            ZipFile(indexed.value.file).use { zip ->
                val actualCounts = zip.entries().asSequence().groupingBy { it.name }.eachCount()
                for (entry in entries) {
                    checkCancelled(signal)
                    progress.publish(EngineProgress(engineId = "engine.resource-mods", scheduleClass = EngineScheduleClass.TARGETED,
                        state = RunState.RUNNING, currentTask = "Проверка изменяемых ресурсов Flutter / Unreal",
                        currentArtifact = entry.path, processed = examined.toLong(), total = candidates.size.toLong(),
                        lastHeartbeatEpochMs = System.currentTimeMillis()))
                    if (entry.path in duplicated || actualCounts[entry.path] != 1) {
                        warnings += "Ресурс неоднозначен в APK-set: ${entry.path}"; continue
                    }
                    if (entry.size !in 1..EngineResourceDocument.MAX_BYTES.toLong() || bytesRead + entry.size > MAX_TOTAL_BYTES || recipes.size >= MAX_FIELDS) {
                        omitted++; continue
                    }
                    val actual = zip.getEntry(entry.path) ?: error("Resource entry disappeared: ${entry.path}")
                    try {
                        val bytes = zip.getInputStream(actual).use { read(it, signal) }
                        bytesRead += bytes.size; examined++
                        require(bytes.size.toLong() == entry.size)
                        val found = discover(workspace.index.artifactSha256, indexed.index, container, entry.path,
                            requireNotNull(format(entry.path, runtimes)), bytes, signal)
                        if (recipes.size + found.size > MAX_FIELDS) { omitted++; continue }
                        recipes += found
                    } catch (cancelled: AnalysisCancelledException) { throw cancelled }
                    catch (failure: Exception) { warnings += "${entry.path}: ${failure.message?.take(160)}" }
                }
            }
        }
        if (omitted > 0) warnings += "Не проверено файлов ресурсов: $omitted. Лимиты: 2 МиБ на файл, 32 МиБ суммарно, 10 000 полей."
        if ("flutter" in runtimes && recipes.none { it.resource?.format == EngineResourceFormat.FLUTTER_JSON })
            warnings += "Flutter: изменяемых JSON-значений нет; Dart AOT пока не поддерживается."
        if ("unreal" in runtimes && recipes.none { it.resource?.format == EngineResourceFormat.UNREAL_INI })
            warnings += "Unreal: открытых INI-значений нет; изменение PAK, IoStore и Blueprint пока не поддерживается."
        return EngineResourceScan(recipes, examined, warnings)
    }

    fun discover(artifactSha256: String, apkIndex: Int, sourceName: String, entry: String,
                 format: EngineResourceFormat, bytes: ByteArray, signal: CancellationSignal): List<AutoModRecipe> {
        require(safeEntry(entry)) { "Unsafe resource path." }
        val document = EngineResourceDocument.parse(bytes, format, signal)
        val sha = hash(bytes)
        return document.scalars.mapNotNull { scalar ->
            checkCancelled(signal)
            val values = scalar.choices()
            if (values.isEmpty()) return@mapNotNull null
            val label = scalar.key.replace('\u001f', '·').ifEmpty { "значение" }
            val change = EngineResourceChange(artifactSha256, apkIndex, sourceName, entry, format, sha, bytes.size,
                scalar.key, scalar.value, values.first())
            AutoModRecipe(id = "resource:" + hash("$artifactSha256\n$apkIndex\n$entry\n${scalar.key}".toByteArray()),
                category = "${format.label} · ресурсы", title = label,
                description = "Значение в файле: ${scalar.value}. Изменение применяется при сборке APK. Влияние на приложение не проверено.",
                targetLabel = entry.removePrefix("assets/flutter_assets/").removePrefix("assets/"),
                resource = change, verification = ModificationVerification(recipePrepared = true),
                scalarValues = values.map { ScalarRecipeValue(it, "") }, scalarValue = change.value)
        }
    }

    fun rewrite(bytes: ByteArray, changes: List<EngineResourceChange>, signal: CancellationSignal): ByteArray {
        require(changes.isNotEmpty() && changes.map { it.key }.distinct().size == changes.size)
        val first = changes.first()
        require(safeEntry(first.entry))
        require(changes.all { it.copy(key = first.key, oldValue = first.oldValue, value = first.value) == first }) {
            "Resource selections refer to different files or formats."
        }
        require(hash(bytes) == first.sourceSha256 && bytes.size == first.sourceSize) { "Resource changed since analysis." }
        val parsed = EngineResourceDocument.parse(bytes, first.format, signal)
        val byKey = parsed.scalars.associateBy { it.key }
        for (change in changes) require(byKey[change.key]?.value == change.oldValue) { "Original resource value changed." }
        val result = parsed.replace(changes.associate { it.key to it.value })
        checkCancelled(signal)
        val verified = EngineResourceDocument.parse(result, first.format, signal).scalars.associate { it.key to it.value }
        val expected = byKey.mapValues { it.value.value }.toMutableMap().apply { changes.forEach { put(it.key, it.value) } }
        require(verified == expected) { "Resource verification changed an unselected field." }
        return result
    }

    fun read(input: InputStream, signal: CancellationSignal): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            checkCancelled(signal)
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= EngineResourceDocument.MAX_BYTES) { "Resource expands beyond 2 MiB." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    private fun safeEntry(entry: String) = entry.startsWith("assets/") && '\\' !in entry && '\u0000' !in entry &&
        entry.split('/').none { it in setOf("", ".", "..") }
    internal fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun checkCancelled(signal: CancellationSignal) { if (signal.isCancelled()) throw AnalysisCancelledException() }
}

object EngineResourceModCoordinator {
    suspend fun scan(context: Context, target: AnalysisTargetDescriptor, analysis: FastAnalysisResult,
                     cancellation: CancellationSignal, progress: ProgressSink): EngineResourceScan = withContext(Dispatchers.IO) {
        val runtimes = analysis.index.runtimeProfiles.map { it.runtimeId }.toSet()
        if (runtimes.none { it == "flutter" || it == "unreal" }) {
            return@withContext EngineResourceScan(emptyList(), 0, emptyList())
        }
        if (analysis.index.entries.none { EngineResourceMods.format(it.path, runtimes) != null }) {
            return@withContext EngineResourceMods.scan(AnalysisWorkspace(analysis.index, emptyList()), cancellation, progress)
        }
        PatchWorkspaceProvider.open(context, target, analysis, EngineResultCache(File(context.filesDir, "analysis-cache")),
            cancellation, progress).use { EngineResourceMods.scan(it.workspace, cancellation, progress) }
    }
}
