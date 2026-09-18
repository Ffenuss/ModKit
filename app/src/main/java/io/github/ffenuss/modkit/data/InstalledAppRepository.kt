package io.github.ffenuss.modkit.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File

data class InstalledAppTarget(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val apkFiles: List<File>,
    val isSystemApp: Boolean,
)

class InstalledAppRepository(private val context: Context) {
    fun load(): List<InstalledAppTarget> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        return packages.mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val paths = buildList {
                app.sourceDir?.let(::File)?.takeIf(File::isFile)?.let(::add)
                app.splitSourceDirs.orEmpty().map(::File).filter(File::isFile).forEach(::add)
            }.distinctBy(File::getAbsolutePath)
            if (paths.isEmpty()) return@mapNotNull null
            InstalledAppTarget(
                label = pm.getApplicationLabel(app).toString().ifBlank { info.packageName },
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = info.longVersionCode,
                apkFiles = paths,
                isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
