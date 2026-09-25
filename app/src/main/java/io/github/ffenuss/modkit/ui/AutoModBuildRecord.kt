package io.github.ffenuss.modkit.ui

import android.content.Context
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallApk
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallPlan
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItem
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItemMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class AutoModBuildRecord(
    val plan: RepackedRuntimeInstallPlan,
    val builtAt: Long,
    val changes: List<String>,
    val reportPath: String,
    val userObservation: String? = null,
    val runtimeMenuItems: List<RepackedRuntimeTestMenuItem> = emptyList(),
) {
    fun save(context: Context) {
        val file = location(context, plan.artifactSha256)
        file.parentFile!!.mkdirs()
        val obj = JSONObject().put("sha", plan.artifactSha256).put("package", plan.packageName)
            .put("builtAt", builtAt).put("signers", JSONArray(plan.signerCertificateSha256.toList()))
            .put("changes", JSONArray(changes)).put("report", reportPath)
            .put("userObservation", userObservation)
            .put("runtimeMenu", JSONArray(runtimeMenuItems.map { item -> JSONObject()
                .put("id", item.id).put("label", item.label)
                .put("detail", item.detail).put("mode", item.mode.name)
                .put("module", item.moduleName)
                .put("address", item.binaryVirtualAddress)
                .put("originalHex", item.originalHex)
                .put("replacementHex", item.replacementHex) }))
            .put("files", JSONArray(plan.apks.map { apk -> JSONObject()
                .put("name", apk.sourceDisplayName).put("path", apk.signedPath)
                .put("sha", apk.expectedSha256).put("size", apk.size) }))
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(obj.toString(2))
        check(temp.renameTo(file)) { "Не удалось сохранить запись о сборке." }
    }

    companion object {
        private fun location(context: Context, sha: String): File {
            require(sha.matches(Regex("[a-fA-F0-9]{64}")))
            return File(context.filesDir, "build-history/$sha.json")
        }
        fun load(context: Context, sha: String): AutoModBuildRecord? = runCatching {
            val file = location(context, sha)
            require(file.length() in 1..1_048_576)
            val obj = JSONObject(file.readText())
            require(obj.getString("sha") == sha)
            val list = obj.getJSONArray("files")
            require(list.length() in 1..1000)
            val root = context.filesDir.canonicalPath + File.separator
            val apks = (0 until list.length()).map { index ->
                val item = list.getJSONObject(index)
                val apk = File(item.getString("path"))
                require(apk.canonicalPath.startsWith(root) && apk.isFile)
                RepackedRuntimeInstallApk(item.getString("name"), apk.absolutePath,
                    item.getString("sha"), item.getLong("size"))
            }
            fun strings(key: String): List<String> = obj.getJSONArray(key).let { array ->
                (0 until array.length()).map { array.getString(it) }
            }
            val menu = obj.optJSONArray("runtimeMenu")?.let { entries ->
                require(entries.length() in 0..24)
                (0 until entries.length()).map { index ->
                    val item = entries.getJSONObject(index)
                    RepackedRuntimeTestMenuItem(
                        id = item.getString("id"),
                        label = item.getString("label"),
                        detail = item.getString("detail"),
                        mode = RepackedRuntimeTestMenuItemMode.valueOf(item.getString("mode")),
                        moduleName = item.getString("module"),
                        binaryVirtualAddress = item.getLong("address"),
                        originalHex = item.getString("originalHex"),
                        replacementHex = item.getString("replacementHex"),
                    )
                }
            }.orEmpty()
            require(menu.all { it.mode == RepackedRuntimeTestMenuItemMode.PATCH })
            require(menu.map { it.id }.distinct().size == menu.size)
            AutoModBuildRecord(RepackedRuntimeInstallPlan(sha, obj.getString("package"),
                strings("signers").toSet(), apks, apks.sumOf { it.size }, emptyList()),
                obj.getLong("builtAt"), strings("changes"), obj.getString("report"),
                if (obj.isNull("userObservation")) null else obj.getString("userObservation"),
                menu)
        }.getOrNull()
    }
}
