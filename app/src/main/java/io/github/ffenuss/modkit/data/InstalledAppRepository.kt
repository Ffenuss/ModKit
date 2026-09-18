package io.github.ffenuss.modkit.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
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
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        return packages.mapNotNull { info ->
            val applicationInfo = info.applicationInfo ?: return@mapNotNull null
            val apkFiles = ArrayList<File>()
            applicationInfo.sourceDir
                ?.let(::File)
                ?.takeIf { it.isFile }
                ?.let(apkFiles::add)
            applicationInfo.splitSourceDirs
                .orEmpty()
                .asSequence()
                .map(::File)
                .filter { it.isFile }
                .forEach(apkFiles::add)

            val distinctFiles = apkFiles.distinctBy { it.absolutePath }
            if (distinctFiles.isEmpty()) return@mapNotNull null

            InstalledAppTarget(
                label = pm.getApplicationLabel(applicationInfo).toString().ifBlank { info.packageName },
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                },
                apkFiles = distinctFiles,
                isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }
}
