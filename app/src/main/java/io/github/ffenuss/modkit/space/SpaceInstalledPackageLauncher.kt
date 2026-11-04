package io.github.ffenuss.modkit.space

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SpaceInstalledIdentity(
    val packageName: String,
    val currentSignerSha256: Set<String>,
    val hasLauncher: Boolean,
    val installedApkSha256: List<String> = emptyList(),
)

enum class SpaceLaunchStatus {
    NOT_INSTALLED,
    DIFFERENT_SIGNATURE,
    NO_LAUNCHER,
    APK_CONTENT_MISMATCH,
    READY,
}

/** Pure policy: never launch a different package, signer, or non-launchable APK. */
object SpaceLaunchIdentityPolicy {
    fun evaluate(record: AutoModBuildRecord, installed: SpaceInstalledIdentity?): SpaceLaunchStatus {
        if (installed == null || installed.packageName != record.plan.packageName) {
            return SpaceLaunchStatus.NOT_INSTALLED
        }
        val expected = record.plan.signerCertificateSha256.map(String::lowercase).toSet()
        val actual = installed.currentSignerSha256.map(String::lowercase).toSet()
        if (expected.isEmpty() || actual != expected) {
            return SpaceLaunchStatus.DIFFERENT_SIGNATURE
        }
        if (!installed.hasLauncher) return SpaceLaunchStatus.NO_LAUNCHER
        val expectedApks = record.plan.apks.map { it.expectedSha256.lowercase() }.sorted()
        val actualApks = installed.installedApkSha256.map(String::lowercase).sorted()
        return if (expectedApks == actualApks) SpaceLaunchStatus.READY
        else SpaceLaunchStatus.APK_CONTENT_MISMATCH
    }
}

/**
 * Starts only an already installed, signer-matching package.
 *
 * This is NOT Android app virtualization. Static patches must have been
 * installed through the ordinary user-approved PackageInstaller path first.
 */
object SpaceInstalledPackageLauncher {
    @Suppress("DEPRECATION")
    private fun identity(
        context: Context,
        packageName: String,
        expectedSigners: Set<String>,
    ): SpaceInstalledIdentity? {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        val hashes = signatures.map { signature ->
            val bytes = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.toSet()
        val launcher = pm.getLaunchIntentForPackage(packageName)
        // Hash APK bytes only after the signer check. This is potentially
        // expensive and is called from a background dispatcher.
        val matchingSigner = hashes.map(String::lowercase).toSet() ==
            expectedSigners.map(String::lowercase).toSet()
        val installedHashes = if (matchingSigner) {
            val appInfo = info.applicationInfo
            val paths = listOfNotNull(appInfo?.sourceDir) + appInfo?.splitSourceDirs.orEmpty()
            runCatching {
                paths.map { path ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    File(path).inputStream().buffered(128 * 1024).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) digest.update(buffer, 0, count)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                }
            }.getOrDefault(emptyList())
        } else emptyList()
        return SpaceInstalledIdentity(
            packageName = packageName,
            currentSignerSha256 = hashes,
            hasLauncher = launcher?.component?.packageName == packageName,
            installedApkSha256 = installedHashes,
        )
    }

    /** Call on a background dispatcher: installed APKs can be several gigabytes. */
    fun status(context: Context, record: AutoModBuildRecord): SpaceLaunchStatus =
        SpaceLaunchIdentityPolicy.evaluate(
            record,
            identity(context, record.plan.packageName, record.plan.signerCertificateSha256),
        )

    suspend fun launch(context: Context, record: AutoModBuildRecord) {
        check(withContext(Dispatchers.IO) { status(context, record) } == SpaceLaunchStatus.READY) {
            "Установленная игра не совпадает с проверенной сборкой или не запускается."
        }
        val name = record.plan.packageName
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(name)) {
            "Для установленной игры не найдена стартовая Activity."
        }
        check(intent.component?.packageName == name) {
            "Android вернул стартовую Activity другого пакета."
        }
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
