package io.github.ffenuss.modkit.runtime

import android.content.BroadcastReceiver
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RepackedRuntimeInstallReadinessState {
    READY_NEW_INSTALL,
    READY_TEST_SIGNER_UPDATE,
    UNKNOWN_SOURCES_PERMISSION_REQUIRED,
    INSTALLED_SIGNATURE_CONFLICT,
}

data class RepackedRuntimeInstallReadiness(
    val state: RepackedRuntimeInstallReadinessState,
    val packageName: String,
    val expectedSignerCertificateSha256: Set<String>,
    val installedSignerCertificateSha256: Set<String>,
    val blockers: List<String>,
) {
    val canCreateSession: Boolean
        get() =
            state == RepackedRuntimeInstallReadinessState.READY_NEW_INSTALL ||
                state == RepackedRuntimeInstallReadinessState.READY_TEST_SIGNER_UPDATE
}

enum class RepackedRuntimeInstallStatusKind {
    IDLE,
    SESSION_COMMITTED,
    USER_ACTION_REQUIRED,
    SUCCESS,
    FAILURE,
}

data class RepackedRuntimeInstallStatus(
    val kind: RepackedRuntimeInstallStatusKind,
    val sessionId: Int? = null,
    val packageName: String? = null,
    val statusCode: Int? = null,
    val message: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

data class RepackedRuntimeInstallSubmission(
    val sessionId: Int,
    val packageName: String,
    val apkCount: Int,
    val totalBytes: Long,
)

object RepackedRuntimeInstallStatusStore {
    private const val PREFS = "repacked-runtime-install-status"
    private const val KEY_KIND = "kind"
    private const val KEY_SESSION = "session"
    private const val KEY_PACKAGE = "package"
    private const val KEY_STATUS = "status"
    private const val KEY_MESSAGE = "message"
    private const val KEY_UPDATED = "updated"

    private val mutable = MutableStateFlow(
        RepackedRuntimeInstallStatus(
            kind = RepackedRuntimeInstallStatusKind.IDLE,
        ),
    )
    val status: StateFlow<RepackedRuntimeInstallStatus> =
        mutable.asStateFlow()

    fun restore(context: Context): RepackedRuntimeInstallStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = runCatching {
            RepackedRuntimeInstallStatusKind.valueOf(
                prefs.getString(KEY_KIND, null)
                    ?: RepackedRuntimeInstallStatusKind.IDLE.name,
            )
        }.getOrDefault(RepackedRuntimeInstallStatusKind.IDLE)
        val restored = RepackedRuntimeInstallStatus(
            kind = kind,
            sessionId = if (prefs.contains(KEY_SESSION)) {
                prefs.getInt(KEY_SESSION, -1).takeIf { it >= 0 }
            } else {
                null
            },
            packageName = prefs.getString(KEY_PACKAGE, null),
            statusCode = if (prefs.contains(KEY_STATUS)) {
                prefs.getInt(KEY_STATUS, 0)
            } else {
                null
            },
            message = prefs.getString(KEY_MESSAGE, null),
            updatedAtEpochMs = prefs.getLong(KEY_UPDATED, 0L),
        )
        mutable.value = restored
        return restored
    }

    fun publish(
        context: Context,
        status: RepackedRuntimeInstallStatus,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KIND, status.kind.name)
            .apply {
                if (status.sessionId != null) {
                    putInt(KEY_SESSION, status.sessionId)
                } else {
                    remove(KEY_SESSION)
                }
                if (status.packageName != null) {
                    putString(KEY_PACKAGE, status.packageName)
                } else {
                    remove(KEY_PACKAGE)
                }
                if (status.statusCode != null) {
                    putInt(KEY_STATUS, status.statusCode)
                } else {
                    remove(KEY_STATUS)
                }
                if (status.message != null) {
                    putString(KEY_MESSAGE, status.message)
                } else {
                    remove(KEY_MESSAGE)
                }
            }
            .putLong(KEY_UPDATED, status.updatedAtEpochMs)
            .apply()
        mutable.value = status
    }
}

object AndroidRepackedRuntimeInstaller {
    const val ACTION_INSTALL_STATUS =
        "io.github.ffenuss.modkit.action.REPACKED_INSTALL_STATUS"

    fun inspectReadiness(
        context: Context,
        plan: RepackedRuntimeInstallPlan,
    ): RepackedRuntimeInstallReadiness {
        require(plan.ready) {
            plan.blockers.firstOrNull()
                ?: "Repacked runtime install plan is not ready."
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return RepackedRuntimeInstallReadiness(
                state =
                    RepackedRuntimeInstallReadinessState
                        .UNKNOWN_SOURCES_PERMISSION_REQUIRED,
                packageName = plan.packageName,
                expectedSignerCertificateSha256 =
                    plan.signerCertificateSha256,
                installedSignerCertificateSha256 = emptySet(),
                blockers = listOf(
                    "Android has not allowed ModKit to request package installs.",
                ),
            )
        }

        val installedSigners = installedSignerHashes(
            context = context,
            packageName = plan.packageName,
        )
        if (installedSigners == null) {
            return RepackedRuntimeInstallReadiness(
                state =
                    RepackedRuntimeInstallReadinessState.READY_NEW_INSTALL,
                packageName = plan.packageName,
                expectedSignerCertificateSha256 =
                    plan.signerCertificateSha256,
                installedSignerCertificateSha256 = emptySet(),
                blockers = emptyList(),
            )
        }

        val normalizedExpected =
            plan.signerCertificateSha256.map(String::lowercase).toSet()
        val normalizedInstalled =
            installedSigners.map(String::lowercase).toSet()
        return if (normalizedInstalled == normalizedExpected) {
            RepackedRuntimeInstallReadiness(
                state =
                    RepackedRuntimeInstallReadinessState
                        .READY_TEST_SIGNER_UPDATE,
                packageName = plan.packageName,
                expectedSignerCertificateSha256 = normalizedExpected,
                installedSignerCertificateSha256 = normalizedInstalled,
                blockers = emptyList(),
            )
        } else {
            RepackedRuntimeInstallReadiness(
                state =
                    RepackedRuntimeInstallReadinessState
                        .INSTALLED_SIGNATURE_CONFLICT,
                packageName = plan.packageName,
                expectedSignerCertificateSha256 = normalizedExpected,
                installedSignerCertificateSha256 = normalizedInstalled,
                blockers = listOf(
                    "An installed package with the same packageName uses a different signing certificate.",
                    "Android will not install the ModKit-signed test build over that package.",
                ),
            )
        }
    }

    fun unknownSourcesSettingsIntent(
        context: Context,
    ): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun uninstallConflictIntent(
        packageName: String,
    ): Intent =
        Intent(
            Intent.ACTION_UNINSTALL_PACKAGE,
            Uri.parse("package:$packageName"),
        ).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun submit(
        context: Context,
        plan: RepackedRuntimeInstallPlan,
        cancellation: CancellationSignal,
        progress: ((copiedBytes: Long, totalBytes: Long, apkName: String) -> Unit)? = null,
    ): RepackedRuntimeInstallSubmission {
        val readiness = inspectReadiness(context, plan)
        require(readiness.canCreateSession) {
            readiness.blockers.firstOrNull()
                ?: "Repacked runtime install session is not ready."
        }
        // Android 8+ install authorization is governed by the special
        // per-source setting checked by canRequestPackageInstalls() above,
        // not a dangerous runtime permission dialog.

        progress?.invoke(0, plan.totalBytes, "Проверка APK перед установкой")
        plan.apks.forEach {
            verifyApkStillMatches(
                apk = it,
                cancellation = cancellation,
            )
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(plan.packageName)
            setSize(plan.totalBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                )
            }
        }

        val sessionId = installer.createSession(params)
        var copiedBytes = 0L
        var lastReported = 0L
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                plan.apks.forEachIndexed { index, apk ->
                    checkCancelled(cancellation)
                    val installName =
                        index.toString().padStart(3, '0') +
                            "-" +
                            apk.sourceDisplayName.replace(
                                Regex("[^A-Za-z0-9._-]"),
                                "_",
                            )
                    session.openWrite(
                        installName,
                        0,
                        apk.size,
                    ).use { output ->
                        BufferedInputStream(
                            FileInputStream(apk.signedPath),
                            128 * 1024,
                        ).use { input ->
                            val before = copiedBytes
                            copiedBytes += VerifiedApkTransfer.copy(
                                input, output, apk.size, apk.expectedSha256, cancellation,
                            ) { current ->
                                val total = before + current
                                if (current == 0L || total - lastReported >= 1024L * 1024L) {
                                    lastReported = total
                                    progress?.invoke(total, plan.totalBytes, apk.sourceDisplayName)
                                }
                            }
                        }
                        session.fsync(output)
                        progress?.invoke(
                            copiedBytes,
                            plan.totalBytes,
                            apk.sourceDisplayName,
                        )
                    }
                }

                // A non-exported explicit BroadcastReceiver can receive the
                // PackageInstaller result without starting a second activity
                // or losing the user's Patch Lab navigation state.
                val callbackIntent = Intent(
                    context,
                    RepackedInstallStatusReceiver::class.java,
                ).apply {
                    action = ACTION_INSTALL_STATUS
                    putExtra(
                        PackageInstaller.EXTRA_SESSION_ID,
                        sessionId,
                    )
                    putExtra(
                        PackageInstaller.EXTRA_PACKAGE_NAME,
                        plan.packageName,
                    )
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_MUTABLE,
                )
                // Publish BEFORE commit: the system may return an immediate
                // failure, which must not be overwritten by stale "waiting".
                RepackedRuntimeInstallStatusStore.publish(
                    context,
                    RepackedRuntimeInstallStatus(
                        kind =
                            RepackedRuntimeInstallStatusKind.SESSION_COMMITTED,
                        sessionId = sessionId,
                        packageName = plan.packageName,
                        message =
                            "Все APK переданы Android. Ожидаем системное подтверждение установки.",
                    ),
                )
                session.commit(callback.intentSender)
                committed = true
            }
            return RepackedRuntimeInstallSubmission(
                sessionId = sessionId,
                packageName = plan.packageName,
                apkCount = plan.apks.size,
                totalBytes = plan.totalBytes,
            )
        } catch (failure: Throwable) {
            if (!committed) {
                runCatching { installer.abandonSession(sessionId) }
            }
            throw failure
        }
    }

    private fun verifyApkStillMatches(
        apk: RepackedRuntimeInstallApk,
        cancellation: CancellationSignal,
    ) {
        val file = File(apk.signedPath)
        require(file.isFile && file.canRead()) {
            "Signed APK disappeared before PackageInstaller session creation."
        }
        require(file.length() == apk.size) {
            "Signed APK size changed before PackageInstaller session creation."
        }

        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(
            FileInputStream(file),
            128 * 1024,
        ).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest()
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        require(
            actual.equals(
                apk.expectedSha256,
                ignoreCase = true,
            ),
        ) {
            "Signed APK SHA changed before PackageInstaller session creation."
        }
    }

    private fun installedSignerHashes(
        context: Context,
        packageName: String,
    ): Set<String>? {
        val packageInfo = try {
            val flags = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                packageName,
                flags,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }

        val signatures = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            packageInfo.signingInfo
                ?.apkContentsSigners
                .orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        return signatures.map {
            MessageDigest.getInstance("SHA-256")
                .digest(it.toByteArray())
                .joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                }
        }.toSet()
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}

/**
 * A validated system confirmation Intent can be reopened by an explicit user
 * tap if the OEM blocked the immediate launch from a background receiver.
 * It deliberately does not persist a privileged Parcelable across restarts.
 */
object RepackedRuntimeInstallConfirmationStore {
    private val launchGate = InstallConfirmationLaunchGate()
    @Volatile private var pendingIntent: Intent? = null

    fun remember(sessionId: Int?, confirmation: Intent) {
        if (launchGate.remember(sessionId)) {
            pendingIntent = Intent(confirmation)
        }
    }

    fun open(context: Context, sessionId: Int): Boolean {
        // The receiver and the foreground button may race. Android 10 can
        // present an erroneous package-parse dialog when the same confirmation
        // activity is opened twice after a session has already been consumed.
        if (!launchGate.claim(sessionId)) return false
        val intent = pendingIntent ?: run {
            launchGate.releaseOnLaunchFailure(sessionId)
            return false
        }
        return try {
            context.startActivity(
                Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (failure: Exception) {
            // Background activity restrictions may reject the receiver's
            // launch. In that case, leave the explicit UI button usable.
            launchGate.releaseOnLaunchFailure(sessionId)
            throw failure
        }
    }

    fun clear(sessionId: Int?) {
        if (launchGate.clear(sessionId)) {
            pendingIntent = null
        }
    }

    fun availableFor(sessionId: Int?): Boolean =
        launchGate.availableFor(sessionId) && pendingIntent != null
}

object RepackedRuntimeInstallStatusHandler {
    fun handle(
        context: Context,
        intent: Intent?,
    ): Intent? {
        if (intent?.action != AndroidRepackedRuntimeInstaller.ACTION_INSTALL_STATUS) {
            return null
        }

        val sessionId = intent.getIntExtra(
            PackageInstaller.EXTRA_SESSION_ID,
            -1,
        ).takeIf { it >= 0 }
        val packageName = intent.getStringExtra(
            PackageInstaller.EXTRA_PACKAGE_NAME,
        )
        val statusCode = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intent.getStringExtra(
            PackageInstaller.EXTRA_STATUS_MESSAGE,
        )

        if (statusCode == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = trustedSystemConfirmationIntent(
                context = context,
                statusIntent = intent,
            )
            RepackedRuntimeInstallConfirmationStore.remember(
                sessionId = sessionId,
                confirmation = confirmation,
            )
            RepackedRuntimeInstallStatusStore.publish(
                context,
                RepackedRuntimeInstallStatus(
                    kind =
                        RepackedRuntimeInstallStatusKind
                            .USER_ACTION_REQUIRED,
                    sessionId = sessionId,
                    packageName = packageName,
                    statusCode = statusCode,
                    message =
                        message
                            ?: "Android requires user confirmation for test-build installation.",
                ),
            )
            return confirmation
        }

        RepackedRuntimeInstallConfirmationStore.clear(sessionId)
        val kind = if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            RepackedRuntimeInstallStatusKind.SUCCESS
        } else {
            RepackedRuntimeInstallStatusKind.FAILURE
        }
        RepackedRuntimeInstallStatusStore.publish(
            context,
            RepackedRuntimeInstallStatus(
                kind = kind,
                sessionId = sessionId,
                packageName = packageName,
                statusCode = statusCode,
                message = message,
            ),
        )
        return null
    }

    private fun trustedSystemConfirmationIntent(
        context: Context,
        statusIntent: Intent,
    ): Intent {
        val nested = if (Build.VERSION.SDK_INT >= 33) {
            statusIntent.getParcelableExtra(
                Intent.EXTRA_INTENT,
                Intent::class.java,
            )
        } else {
            @Suppress("DEPRECATION")
            statusIntent.getParcelableExtra(Intent.EXTRA_INTENT)
        } ?: error("PackageInstaller did not provide a confirmation intent.")

        val resolved = context.packageManager.resolveActivity(
            nested,
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo
            ?: error("PackageInstaller confirmation intent has no handler.")
        require(resolved.exported) {
            "PackageInstaller confirmation activity is not exported."
        }
        val flags = resolved.applicationInfo.flags
        val systemApp =
            flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        require(systemApp) {
            "PackageInstaller confirmation intent is not handled by a system app."
        }

        return Intent(nested).apply {
            component = ComponentName(
                resolved.packageName,
                resolved.name,
            )
            setPackage(resolved.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/**
 * Receives PackageInstaller callbacks even when MainActivity is in the
 * background. The manifest must keep this receiver non-exported; the mutable
 * explicit PendingIntent belongs only to our own PackageInstaller session.
 */
class RepackedInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val confirmation = RepackedRuntimeInstallStatusHandler.handle(
                context = context.applicationContext,
                intent = intent,
            )
            if (confirmation != null) {
                // Opening the same one-shot system confirmation both here and
                // from the UI must not create competing installer activities.
                // An OEM background-start rejection still permits a manual tap.
                val sessionId = RepackedRuntimeInstallStatusStore.status.value.sessionId
                if (sessionId != null) {
                    try {
                        RepackedRuntimeInstallConfirmationStore.open(context, sessionId)
                    } catch (failure: Exception) {
                        val pending = RepackedRuntimeInstallStatusStore.status.value
                        RepackedRuntimeInstallStatusStore.publish(context, pending.copy(
                            kind = RepackedRuntimeInstallStatusKind.USER_ACTION_REQUIRED,
                            message = "Android ждёт подтверждения. Вернитесь в ModKit и нажмите «Подтвердить установку».",
                        ))
                    }
                }
            }
        } catch (failure: Exception) {
            RepackedRuntimeInstallStatusStore.publish(
                context.applicationContext,
                RepackedRuntimeInstallStatus(
                    kind = RepackedRuntimeInstallStatusKind.FAILURE,
                    sessionId = intent.getIntExtra(
                        PackageInstaller.EXTRA_SESSION_ID,
                        -1,
                    ).takeIf { it >= 0 },
                    packageName = intent.getStringExtra(
                        PackageInstaller.EXTRA_PACKAGE_NAME,
                    ),
                    statusCode = PackageInstaller.STATUS_FAILURE,
                    message =
                        "Не удалось открыть системное подтверждение установки: " +
                            (failure.message ?: failure.javaClass.simpleName),
                ),
            )
        }
    }
}
