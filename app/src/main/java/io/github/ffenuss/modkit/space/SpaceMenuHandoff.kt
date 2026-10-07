package io.github.ffenuss.modkit.space

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

object SpaceMenuHandoff {
    fun open(context: Context, file: File) {
        val host = "com.dualspace.multispace.androidx"
        check(context.packageManager.getLaunchIntentForPackage(host) != null) { "Сначала установите пространство" }
        check(TrustedSpace.installed(context)) { "Подпись установленного пространства не соответствует ModKit" }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        context.startActivity(Intent("io.github.ffenuss.modkit.OPEN_SPACE_MENU")
            .setClassName(host, "com.dualspace.multispace.MainActivity")
            .setDataAndType(uri, "application/json").apply {
                clipData = ClipData.newRawUri("ModKit menu", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
    }
}

data class SavedSpaceMenu(val packageName: String, val label: String, val genre: String,
    val items: Int, val staticRecipes: Int, val truncated: Boolean, val file: File)

object SavedSpaceMenus {
    fun load(context: Context): List<SavedSpaceMenu> {
        val root = File(context.filesDir, "space-menu-profiles")
        return root.listFiles().orEmpty().filter { it.name.endsWith(".json") }
            .sortedByDescending { it.lastModified() }.take(512).mapNotNull { file ->
            runCatching {
                require(file.isFile && file.length() in 1..(256 * 1024) && file.canonicalFile.parentFile == root.canonicalFile)
                val json = JSONObject(file.readText())
                require(json.getInt("schema") == 1 && json.getString("backend") == "none")
                val pkg = json.getString("packageName")
                require(pkg.length <= 255 && pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
                val items = json.getJSONArray("items")
                SavedSpaceMenu(pkg, json.getString("label"), json.getString("genre"), items.length(),
                    (0 until items.length()).count { items.getJSONObject(it).getString("state") == "static_recipe" },
                    json.getBoolean("truncated"), file)
            }.getOrNull()
        }.distinctBy { it.packageName }.sortedBy { it.label }
    }
}
