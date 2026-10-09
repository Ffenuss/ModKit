package io.github.ffenuss.modkit.space

import android.content.Context
import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.patch.*
import io.github.ffenuss.modkit.runtime.BinaryAndroidManifestInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Display profiles contain evidence, never an implied runtime patch capability. */
data class SpaceMenuSummary(val packageName: String, val label: String, val profilePath: String,
    val plan: GameAnalysisPlan, val candidates: Int, val staticRecipes: Int,
    val truncated: Boolean, val warnings: List<String>, val runtimeRecipes: Int = 0)

object SpaceMenuCoordinator {
    suspend fun prepare(context: Context, target: AnalysisTargetDescriptor, initial: FastAnalysisResult,
        cancellation: CancellationSignal, progress: ProgressSink, workspace: AnalysisWorkspace): SpaceMenuSummary =
        withContext(Dispatchers.IO) {
                val result = initial
                val files = workspace.sources.map { it.file }
                val fresh = PortableArtifactIndexer.index(files, cancellation, progress).index
                require(fresh.artifactSha256 == result.index.artifactSha256) { "Состав APK изменился" }
                val preparation = PatchPreparationPlanner.prepare(result, TargetShaVerification(
                    result.index.artifactSha256, fresh.artifactSha256, true, fresh.sources, null, null))
                val manifests = files.map { BinaryAndroidManifestInspector.inspectApk(it, cancellation) }
                val pkg = manifests.first().packageName
                require(pkg.length <= 255 && pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "Некорректное имя пакета" }
                require(manifests.all { it.packageName == pkg } && manifests.count { it.splitName == null } == 1) {
                    "Комплект должен содержать один base APK и splits одного приложения"
                }
                if (target is AnalysisTargetDescriptor.InstalledPackage) require(pkg == target.packageName)
                val dex = DexLocalPatchEngine.scanApks(files, developerTestMode = true, cancellation = cancellation,
                    progress = { count, entry -> progress.publish(io.github.ffenuss.modkit.domain.EngineProgress(
                        "space.menu-dex", io.github.ffenuss.modkit.domain.EngineScheduleClass.TARGETED,
                        io.github.ffenuss.modkit.domain.RunState.RUNNING, currentTask = "Читаем код DEX для меню",
                        currentArtifact = entry, processed = count.toLong(), lastHeartbeatEpochMs = System.currentTimeMillis())) })
                val native = if (result.il2cppFastDump != null || result.il2cppBinaryBinding != null)
                    NativeRecipeCatalog.create(result, preparation, File(context.filesDir, "analysis-results"), cancellation)
                    else emptyList()
                val jni = JniSpaceRecipeScanner.scan(files, File(context.cacheDir, "space-jni"), cancellation)
                val recipes = DexRecipeCatalog.create(dex) + native
                val symbols = (result.il2cppFastDump?.metadata?.methods.orEmpty().asSequence().map { it.name } +
                    result.il2cppFastDump?.metadata?.fields.orEmpty().asSequence().map { it.name } +
                    dex.opportunities.asSequence().map { it.methodName }).asIterable()
                val chosenGenre = SavedSpaceMenus.selectedGenre(context, pkg)
                val plan = GameAnalysisPlanner.plan(result.index, symbols, chosenGenre)
                val ordered = recipes.sortedWith(compareBy<AutoModRecipe> { recipe ->
                    plan.searchPriorities.indexOfFirst { priority -> recipe.category.contains(priority.substringBefore(" /"), true) }
                        .let { if (it < 0) Int.MAX_VALUE else it }
                }.thenBy { it.id })
                val jniIncluded = jni.recipes.take(128)
                val warnings = (dex.warnings + jni.warnings + result.engineWarnings + result.index.warnings + preparation.globalBlockers).distinct().take(32).map { it.take(1000) }
                val imageHashes = mutableMapOf<String, String>()
                val patchRanges = jniIncluded.filter { it.module == "libil2cpp.so" }.map { it.address to (it.address + it.expected.length / 2) }.toMutableList()
                val nativePatches = ordered.asSequence().mapNotNull { recipe ->
                    val candidate = recipe.native ?: return@mapNotNull null
                    if (!recipe.selectable || !recipe.verification.recipePrepared) return@mapNotNull null
                    runCatching {
                        val window = Il2CppNativeMutationDraftBuilder.readCodeWindow(result, candidate.targetId,
                            File(context.filesDir, "analysis-results"))
                        require(window.abi == "arm64-v8a")
                        val address = requireNotNull(window.binaryVirtualAddress)
                        val replacement = Il2CppNativeMutationDraftBuilder.parseHex(requireNotNull(candidate.replacementHex))
                        val expected = Il2CppNativeMutationDraftBuilder.parseHex(window.originalHex).take(replacement.size).toByteArray()
                        require(address > 0 && address % 4L == 0L && replacement.size in 4..64 && replacement.size % 4 == 0 && expected.size == replacement.size)
                        require(address <= Long.MAX_VALUE - replacement.size)
                        val end = address + replacement.size
                        require(patchRanges.none { (start, stop) -> address < stop && start < end })
                        val image = File(window.extractedLibraryPath)
                        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
                        val imageHash = imageHashes.getOrPut(image.canonicalPath) {
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            image.inputStream().use { input ->
                            val buffer = ByteArray(32768)
                            while (true) { if (cancellation.isCancelled()) throw AnalysisCancelledException()
                                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                            }
                            hex(digest.digest())
                        }
                        patchRanges.add(address to end)
                        recipe.id to JSONObject().put("module", "libil2cpp.so").put("abi", window.abi)
                            .put("address", address).put("expected", hex(expected)).put("replacement", hex(replacement))
                            .put("imageSha256", imageHash)
                    }.getOrElse { error -> if (error is AnalysisCancelledException) throw error; null }
                }.take(128 - jniIncluded.size).toMap()
                val included = SpaceMenuSelection.select(ordered, 128 - jniIncluded.size) { it.id in nativePatches }
                val profile = JSONObject().put("schema", 2).put("packageName", pkg).put("label", target.label.take(180))
                    .put("artifactSha256", result.index.artifactSha256).put("backend", "native_v1")
                    .put("genre", plan.genre.genre.title).put("genreKey", plan.genre.genre.name)
                    .put("genreSelectedByUser", chosenGenre != null).put("genreEvidence", JSONArray(plan.genre.evidence.map { it.take(256) }))
                    .put("engines", JSONArray(plan.engines.map { "${it.title} · ${it.status}" }))
                    .put("priorities", JSONArray(plan.searchPriorities))
                    .put("coverage", JSONObject().put("apkCount", files.size).put("indexedEntries", fresh.entries.size)
                        .put("dexFilesExamined", dex.dexFilesExamined).put("dexMethodsExamined", dex.methodsExamined)
                        .put("dexMethodsWithCode", dex.methodsWithCode).put("exportedItems", included.size + jniIncluded.size)
                        .put("executableItems", nativePatches.size + jniIncluded.size).put("universalDeepAnalysis", false))
                    .put("truncated", result.index.truncated || recipes.size > included.size || jni.truncated || result.il2cppFastDump?.metadata?.truncated == true)
                    .put("warnings", JSONArray(warnings))
                    .put("sources", JSONArray(fresh.sources.map { JSONObject().put("sha256", it.sha256).put("size", it.size) }))
                    .put("items", JSONArray(jniIncluded.map { recipe -> JSONObject().put("id", recipe.id.take(512))
                        .put("title", recipe.title).put("category", recipe.category).put("evidence", recipe.evidence.take(512))
                        .put("state", "static_recipe").put("detail", "Проверены Java-сигнатура и JNI-экспорт без вызовов и записи состояния. Игровой эффект требует проверки.")
                        .put("patch", JSONObject().put("module", recipe.module).put("abi", "arm64-v8a").put("address", recipe.address)
                            .put("expected", recipe.expected).put("replacement", recipe.replacement).put("imageSha256", recipe.imageSha256))
                    } + included.map { recipe -> JSONObject().put("id", recipe.id.take(512))
                        .put("title", recipe.title.take(180)).put("category", recipe.category.take(180))
                        .put("evidence", recipe.targetLabel.take(256))
                        .put("state", if (recipe.selectable && recipe.verification.recipePrepared) "static_recipe" else "candidate")
                        .put("patch", nativePatches[recipe.id] ?: JSONObject.NULL)
                        .put("detail", (recipe.blocker ?: recipe.description).take(400)) }))
                if (cancellation.isCancelled()) throw AnalysisCancelledException()
                // Rehash after scanners; no profile may bind stale or changing source bytes.
                val after = PortableArtifactIndexer.index(files, cancellation, progress).index
                require(after.artifactSha256 == fresh.artifactSha256) { "APK изменился во время подготовки меню" }
                val root = File(context.filesDir, "space-menu-profiles").apply { mkdirs() }
                val file = File(root, "$pkg-${fresh.artifactSha256}.json")
                val bytes = profile.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 256 * 1024) { "Профиль превышает лимит передачи" }
                val atomic = android.util.AtomicFile(file)
                val stream = atomic.startWrite()
                try { stream.write(bytes); atomic.finishWrite(stream) }
                catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
                SavedSpaceMenus.notifyMenus(context)
                SpaceMenuSummary(pkg, target.label, file.absolutePath, plan, recipes.size + jni.recipes.size,
                    recipes.count { it.selectable && it.verification.recipePrepared } + jni.recipes.size, profile.getBoolean("truncated"), warnings, nativePatches.size + jniIncluded.size)
        }
}
