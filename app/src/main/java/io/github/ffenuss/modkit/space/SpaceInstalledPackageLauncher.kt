package io.github.ffenuss.modkit.space

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import java.security.MessageDigest

data class SpaceInstalledIdentity(
    val packageName: String,
    val currentSignerSha256: Set<String>,
    val hasLauncher: Boolean,
)

enum class SpaceLaunchStatus {
    NOT_INSTALLED,
    DIFFERENT_SIGNATURE,
    NO_LAUNCHER,
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
        return if (installed.hasLauncher) SpaceLaunchStatus.READY
        else SpaceLaunchStatus.NO_LAUNCHER
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
    private fun identity(context: Context, packageName: String): SpaceInstalledIdentity? {
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
        return SpaceInstalledIdentity(
            packageName = packageName,
            currentSignerSha256 = hashes,
            hasLauncher = launcher?.component?.packageName == packageName,
        )
    }

    fun status(context: Context, record: AutoModBuildRecord): SpaceLaunchStatus =
        SpaceLaunchIdentityPolicy.evaluate(record, identity(context, record.plan.packageName))

    fun launch(context: Context, record: AutoModBuildRecord) {
        check(status(context, record) == SpaceLaunchStatus.READY) {
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
