package io.github.ffenuss.modkit.runtime

import android.content.Context
import android.os.Build
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import java.io.File

/**
 * Installer metadata observed from the exact installed APK-set that was hashed for analysis.
 * This is never guessed from filenames, package names or store branding.
 */
data class OriginalInstallerRecord(
    val packageName: String,
    val installerPackageName: String,
    val artifactSha256: String,
) {
    init {
        require(validPackage(packageName) && validPackage(installerPackageName))
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
    }

    fun encode(): ByteArray =
        "modkit-installer/1\n$packageName\n$installerPackageName\n$artifactSha256\n"
            .toByteArray(Charsets.UTF_8)

    companion object {
        const val ENTRY = "assets/modkit-original-installer.txt"

        private fun validPackage(value: String): Boolean =
            value.length in 1..254 &&
                value.matches(
                    Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"),
                )

        @Suppress("DEPRECATION")
        fun capture(
            context: Context,
            target: AnalysisTargetDescriptor,
            packageName: String,
            artifactSha256: String,
            verifiedSourceFiles: List<File>,
        ): OriginalInstallerRecord? {
            if (target !is AnalysisTargetDescriptor.InstalledPackage) return null
            require(target.packageName == packageName) {
                "Installed source and manifest identity differ."
            }
            if (!validPackage(packageName)) return null

            val expectedPaths = verifiedSourceFiles.map { it.absolutePath }.toSet()
            fun installedPaths(): Set<String> {
                val app = context.packageManager.getApplicationInfo(packageName, 0)
                return (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty())
                    .map { File(it).absolutePath }
                    .toSet()
            }

            val installer = try {
                if (expectedPaths.isEmpty() || installedPaths() != expectedPaths) return null
                val observed =
                    if (Build.VERSION.SDK_INT >= 30) {
                        context.packageManager
                            .getInstallSourceInfo(packageName)
                            .installingPackageName
                    } else {
                        context.packageManager.getInstallerPackageName(packageName)
                    }
                if (installedPaths() != expectedPaths) return null
                observed
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SecurityException) {
                null
            }

            return installer
                ?.takeIf(::validPackage)
                ?.let { OriginalInstallerRecord(packageName, it, artifactSha256) }
        }
    }
}
