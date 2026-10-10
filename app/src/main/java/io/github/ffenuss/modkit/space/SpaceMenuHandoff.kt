package io.github.ffenuss.modkit.space

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

object SpaceMenuHandoff {
    fun open(context: Context, file: File) {
        val launcher = context.packageManager.getLaunchIntentForPackage(TrustedSpace.PACKAGE)
            ?: error("Сначала установите пространство")
        check(TrustedSpace.installed(context)) { "Подпись установленного пространства не соответствует ModKit" }
        check(SavedSpaceMenus.load(context).take(256).any { it.file.canonicalFile == file.canonicalFile }) {
            "Меню уже заменено. Повторите анализ приложения"
        }
        launchForMenuSync(context, launcher)
    }

    /** Space imports saved profiles through its authenticated provider on MainActivity resume.
     * Preserve the public launcher intent: its internal MainActivity is not exported.
     */
    internal fun launchForMenuSync(context: Context, launcher: Intent) {
        val component = requireNotNull(launcher.component) { "У пространства нет доступного входа" }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(component, 0)
        check(info.exported && info.enabled && info.applicationInfo.enabled &&
            (info.permission == null || context.checkSelfPermission(info.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            "Пространство не разрешает запуск. Проверьте установленную версию"
        }
        SavedSpaceMenus.notifyMenus(context)
        context.startActivity(Intent(launcher).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

data class SavedSpaceMenu(val packageName: String, val label: String, val genre: String,
    val items: Int, val staticRecipes: Int, val truncated: Boolean, val file: File, val runtimeRecipes: Int = 0)

object SavedSpaceMenus {
    fun selectedGenre(context: Context, pkg: String): io.github.ffenuss.modkit.analysis.GameGenre? {
        val file = load(context).singleOrNull { it.packageName == pkg }?.file ?: return null
        return runCatching {
            val json = JSONObject(file.readText())
            val legacyEvidence = json.optJSONArray("genreEvidence")
            val chosen = json.optBoolean("genreSelectedByUser") ||
                (legacyEvidence?.length() == 1 && legacyEvidence.optString(0) == "Жанр выбран пользователем")
            if (chosen) io.github.ffenuss.modkit.analysis.GameGenre.valueOf(json.getString("genreKey")) else null
        }.getOrNull()
    }

    /** Reorder only; the executable patch payload and source identities remain unchanged. */
    fun updateGenre(context: Context, file: File, genre: io.github.ffenuss.modkit.analysis.GameGenre) {
        val root = File(context.filesDir, "space-menu-profiles").canonicalFile
        require(file.canonicalFile.parentFile == root && file.name.endsWith(".json") && file.isFile && file.length() in 1..(256 * 1024))
        val json = JSONObject(file.readText())
        require((json.getInt("schema") == 1 && json.getString("backend") == "none") ||
            (json.getInt("schema") == 2 && json.getString("backend") == "native_v1"))
        val pkg = json.getString("packageName")
        require(load(context).any { it.packageName == pkg && it.file.canonicalFile == file.canonicalFile }) { "Меню уже заменено новым анализом" }
        val priorities = io.github.ffenuss.modkit.analysis.GameAnalysisPlanner.priorities(genre)
        json.put("genre", genre.title).put("genreKey", genre.name).put("genreSelectedByUser", true)
            .put("genreEvidence", org.json.JSONArray(listOf("Жанр выбран пользователем")))
            .put("priorities", org.json.JSONArray(priorities))
        val items = json.getJSONArray("items")
        val ordered = (0 until items.length()).map { items.getJSONObject(it) }.sortedWith(
            compareBy<JSONObject> { if (it.isNull("patch")) 1 else 0 }.thenBy { item ->
                priorities.indexOfFirst { item.getString("category").contains(it.substringBefore(" /"), true) }
                    .let { if (it < 0) Int.MAX_VALUE else it }
            })
        json.put("items", org.json.JSONArray(ordered))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024)
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        notifyMenus(context)
    }

    internal fun notifyMenus(context: Context) {
        context.contentResolver.notifyChange(android.net.Uri.parse("content://${context.packageName}.space-menu/index"), null)
    }

    fun load(context: Context): List<SavedSpaceMenu> {
        val root = File(context.filesDir, "space-menu-profiles")
        return root.listFiles().orEmpty().filter { it.name.endsWith(".json") }
            .sortedByDescending { it.lastModified() }.take(512).mapNotNull { file ->
            runCatching {
                require(file.isFile && file.length() in 1..(256 * 1024) && file.canonicalFile.parentFile == root.canonicalFile)
                val json = JSONObject(file.readText())
                require((json.getInt("schema") == 1 && json.getString("backend") == "none") ||
                    (json.getInt("schema") == 2 && json.getString("backend") == "native_v1"))
                val pkg = json.getString("packageName")
                require(pkg.length <= 255 && pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
                val items = json.getJSONArray("items")
                SavedSpaceMenu(pkg, json.getString("label"), json.getString("genre"), items.length(),
                    (0 until items.length()).count { items.getJSONObject(it).getString("state") == "static_recipe" },
                    json.getBoolean("truncated"), file, (0 until items.length()).count { !items.getJSONObject(it).isNull("patch") })
            }.getOrNull()
        }.distinctBy { it.packageName }.sortedBy { it.label }
    }
}
