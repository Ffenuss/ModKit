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
    val truncated: Boolean, val warnings: List<String>)

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
                val recipes = DexRecipeCatalog.create(dex) + native
                val symbols = (result.il2cppFastDump?.metadata?.methods.orEmpty().asSequence().map { it.name } +
                    result.il2cppFastDump?.metadata?.fields.orEmpty().asSequence().map { it.name } +
                    dex.opportunities.asSequence().map { it.methodName }).asIterable()
                val plan = GameAnalysisPlanner.plan(result.index, symbols)
                val ordered = recipes.sortedWith(compareBy<AutoModRecipe> { recipe ->
                    plan.searchPriorities.indexOfFirst { priority -> recipe.category.contains(priority.substringBefore(" /"), true) }
                        .let { if (it < 0) Int.MAX_VALUE else it }
                }.thenBy { it.id })
                val included = ordered.take(128)
                val warnings = (dex.warnings + result.engineWarnings + result.index.warnings + preparation.globalBlockers).distinct().take(32).map { it.take(1000) }
                val profile = JSONObject().put("schema", 1).put("packageName", pkg).put("label", target.label.take(180))
                    .put("artifactSha256", result.index.artifactSha256).put("backend", "none")
                    .put("genre", plan.genre.genre.title).put("genreEvidence", JSONArray(plan.genre.evidence.map { it.take(256) }))
                    .put("engines", JSONArray(plan.engines.map { "${it.title} · ${it.status}" }))
                    .put("priorities", JSONArray(plan.searchPriorities))
                    .put("coverage", JSONObject().put("apkCount", files.size).put("indexedEntries", fresh.entries.size)
                        .put("dexFilesExamined", dex.dexFilesExamined).put("dexMethodsExamined", dex.methodsExamined)
                        .put("dexMethodsWithCode", dex.methodsWithCode).put("universalDeepAnalysis", false))
                    .put("truncated", result.index.truncated || recipes.size > included.size || result.il2cppFastDump?.metadata?.truncated == true)
                    .put("warnings", JSONArray(warnings))
                    .put("sources", JSONArray(fresh.sources.map { JSONObject().put("sha256", it.sha256).put("size", it.size) }))
                    .put("items", JSONArray(included.map { recipe -> JSONObject().put("id", recipe.id.take(512))
                        .put("title", recipe.title.take(180)).put("category", recipe.category.take(180))
                        .put("evidence", recipe.targetLabel.take(256))
                        .put("state", if (recipe.selectable && recipe.verification.recipePrepared) "static_recipe" else "candidate")
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
                SpaceMenuSummary(pkg, target.label, file.absolutePath, plan, recipes.size,
                    recipes.count { it.selectable && it.verification.recipePrepared }, profile.getBoolean("truncated"), warnings)
        }
}
