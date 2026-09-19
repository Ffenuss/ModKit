package io.github.ffenuss.modkit.build

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

data class ParsedApkPackage(
    val fileName: String,
    val packageName: String,
    val splitName: String?,
    val versionCode: Long,
)

data class InstallabilityVerification(
    val verified: Boolean,
    val packageName: String?,
    val files: List<ParsedApkPackage>,
    val blockers: List<String>,
)

/**
 * Local installability sanity check. This is not a device install attempt: it
 * proves that Android PackageManager can parse every final APK and that all
 * members of an APK-set belong to one package with exactly one base APK.
 */
object BuiltPackageVerifier {
    fun verify(
        context: Context,
        files: List<File>,
    ): InstallabilityVerification {
        val blockers = mutableListOf<String>()
        val parsed = files.mapNotNull { file ->
            val info = packageInfo(context.packageManager, file)
            if (info == null) {
                blockers += file.name + ": PackageManager не смог разобрать APK."
                null
            } else {
                ParsedApkPackage(
                    fileName = file.name,
                    packageName = info.packageName,
                    splitName = info.applicationInfo?.splitName,
                    versionCode = if (Build.VERSION.SDK_INT >= 28) {
                        info.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        info.versionCode.toLong()
                    },
                )
            }
        }

        val packages = parsed.map { it.packageName }.distinct()
        if (packages.size > 1) {
            blockers += "APK-set содержит разные packageName."
        }
        val bases = parsed.count { it.splitName.isNullOrBlank() }
        if (parsed.isNotEmpty() && bases != 1) {
            blockers += "APK-set должен содержать ровно один base APK."
        }
        val duplicateSplits = parsed
            .mapNotNull { it.splitName }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateSplits.isNotEmpty()) {
            blockers += "APK-set содержит повторяющиеся splitName."
        }

        return InstallabilityVerification(
            verified = blockers.isEmpty() &&
                parsed.size == files.size &&
                parsed.isNotEmpty(),
            packageName = packages.singleOrNull(),
            files = parsed,
            blockers = blockers.distinct(),
        )
    }

    private fun packageInfo(
        packageManager: PackageManager,
        file: File,
    ): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }
}
