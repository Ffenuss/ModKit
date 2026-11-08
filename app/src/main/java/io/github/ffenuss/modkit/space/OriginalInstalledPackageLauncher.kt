package io.github.ffenuss.modkit.space

import android.content.Context
import android.content.Intent
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.TargetShaVerifier
import io.github.ffenuss.modkit.data.InstalledAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An explicit original-package launch. Never creates, signs or installs APKs. */
data class OriginalLaunchReadiness(
    val packageName: String,
    val expectedSourceSha256: String,
    val sourceMatches: Boolean,
    val hasLauncher: Boolean,
    val reason: String?,
) {
    val ready: Boolean get() = sourceMatches && hasLauncher && reason == null
}

/**
 * Android still runs the ORIGINAL app as a separately installed package.
 * This is intentionally not a virtual Android guest or a working runtime
 * mod execution environment.
 */
object OriginalInstalledPackageLauncher {
    suspend fun inspect(
        context: Context,
        packageName: String,
        analyzedApkSetSha256: String,
        cancellation: CancellationSignal,
    ): OriginalLaunchReadiness = withContext(Dispatchers.IO) {
        require(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
            .matches(packageName)) { "Некорректный пакет установленного приложения." }
        require(Regex("[0-9a-f]{64}").matches(analyzedApkSetSha256)) {
            "Некорректный SHA-256 проанализированного APK."
        }
        val installed = InstalledAppRepository(context).find(packageName)
            ?: return@withContext OriginalLaunchReadiness(
                packageName, analyzedApkSetSha256, false, false,
                "Оригинальное приложение больше не установлено.",
            )
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val hasLauncher = installed.hasLauncherActivity &&
            launchIntent?.component?.packageName == packageName
        if (!hasLauncher) return@withContext OriginalLaunchReadiness(
            packageName, analyzedApkSetSha256, false, false,
            "Android не нашёл экран запуска оригинального приложения.",
        )
        val verification = TargetShaVerifier.verify(
            context,
            AnalysisTargetDescriptor.InstalledPackage(
                packageName = packageName,
                label = installed.label,
            ),
            analyzedApkSetSha256,
            cancellation,
            ProgressSink { },
        )
        return@withContext OriginalLaunchReadiness(
            packageName = packageName,
            expectedSourceSha256 = analyzedApkSetSha256,
            sourceMatches = verification.matches,
            hasLauncher = hasLauncher,
            reason = if (verification.matches) null else
                verification.blockerMessage ?:
                    "Оригинальная игра обновилась после анализа. Выполните анализ заново.",
        )
    }

    suspend fun launch(
        context: Context,
        packageName: String,
        analyzedApkSetSha256: String,
        cancellation: CancellationSignal,
    ) {
        val readiness = inspect(context.applicationContext, packageName,
            analyzedApkSetSha256, cancellation)
        check(readiness.ready) { readiness.reason ?: "Запуск оригинала заблокирован." }
        val intent = requireNotNull(
            context.packageManager.getLaunchIntentForPackage(packageName)
        ) { "Игра не имеет доступного экрана запуска." }
        check(intent.component?.packageName == packageName) {
            "Android вернул запуск другого пакета."
        }
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
