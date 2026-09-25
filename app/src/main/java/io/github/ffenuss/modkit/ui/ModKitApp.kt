package io.github.ffenuss.modkit.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.ffenuss.modkit.analysis.AnalysisManager
import io.github.ffenuss.modkit.analysis.AnalysisRunState
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.dump.InstalledPackageDumper
import io.github.ffenuss.modkit.ui.screens.SimpleAnalysisScreen
import io.github.ffenuss.modkit.ui.screens.SimpleAutoModScreen
import io.github.ffenuss.modkit.ui.screens.InstalledAppsScreen
import io.github.ffenuss.modkit.ui.screens.InstalledTargetKind
import io.github.ffenuss.modkit.ui.screens.RecoveryScreen
import io.github.ffenuss.modkit.ui.screens.RestoringPartialScreen
import io.github.ffenuss.modkit.ui.screens.TargetSelectionScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen {
    TARGET,
    INSTALLED_APPS,
    AUTOMOD,
}

@Composable
fun ModKitApp() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    val installedRepository =
        remember {
            InstalledAppRepository(appContext)
        }
    val autoModSessionStore =
        remember {
            AutoModSessionStore(appContext)
        }
    val analysisCache =
        remember {
            EngineResultCache(
                File(
                    context.filesDir,
                    "analysis-cache",
                ),
            )
        }

    remember(appContext) {
        AnalysisManager.initialize(appContext)
        true
    }

    val analysisState by
        AnalysisManager.state.collectAsState()

    var screen by remember {
        mutableStateOf(Screen.TARGET)
    }
    var installedApps by remember {
        mutableStateOf<List<InstalledAppTarget>>(
            emptyList(),
        )
    }
    var installedLoading by remember {
        mutableStateOf(false)
    }
    var installedError by remember {
        mutableStateOf<String?>(null)
    }
    var installedKind by remember {
        mutableStateOf(InstalledTargetKind.GAMES)
    }
    var dumpingPackageName by remember {
        mutableStateOf<String?>(null)
    }
    var dumpNotice by remember {
        mutableStateOf<String?>(null)
    }

    var autoModTarget by remember {
        mutableStateOf<AnalysisTargetDescriptor?>(null)
    }
    var autoModResult by remember {
        mutableStateOf<FastAnalysisResult?>(null)
    }

    fun openInstalled(
        kind: InstalledTargetKind,
    ) {
        installedKind = kind
        screen = Screen.INSTALLED_APPS
        installedLoading = true
        installedError = null
        dumpNotice = null
        scope.launch {
            val loaded =
                runCatching {
                    withContext(Dispatchers.IO) {
                        installedRepository.load()
                    }
                }
            installedApps =
                loaded.getOrDefault(emptyList())
            installedError =
                loaded.exceptionOrNull()?.message
            installedLoading = false
        }
    }

    LaunchedEffect(Unit) {
        if (
            AnalysisManager.state.value is
                AnalysisRunState.Idle
        ) {
            val saved =
                withContext(Dispatchers.IO) {
                    autoModSessionStore.load()
                }
            if (saved != null) {
                val restored =
                    withContext(Dispatchers.IO) {
                        analysisCache
                            .restorePartialResult(
                                saved.artifactSha256,
                            )
                    }
                if (restored != null) {
                    autoModTarget = saved.target
                    autoModResult = restored
                    screen = Screen.AUTOMOD
                } else {
                    withContext(Dispatchers.IO) {
                        autoModSessionStore.clear()
                    }
                }
            }
        }
    }

    val filePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver
                        .takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                }
                autoModSessionStore.clear()
                AnalysisManager.startFile(
                    uri,
                    uri.lastPathSegment
                        ?: "Выбранный файл",
                )
            }
        }

    LaunchedEffect(analysisState) {
        val completed = analysisState as? AnalysisRunState.Completed ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            autoModSessionStore.save(completed.target, completed.result.index.artifactSha256)
        }
        autoModTarget = completed.target
        autoModResult = completed.result
        screen = Screen.AUTOMOD
        AnalysisManager.clearTerminalState()
    }

    when (val state = analysisState) {
        is AnalysisRunState.Interrupted ->
            RecoveryScreen(
                state = state,
                onResume = {
                    AnalysisManager.resumeInterrupted()
                },
                onOpenPartial = {
                    AnalysisManager
                        .openInterruptedPartial()
                },
                onDelete = {
                    AnalysisManager
                        .dismissInterrupted()
                },
            )

        is AnalysisRunState.RestoringPartial ->
            RestoringPartialScreen(
                state = state,
            )

        is AnalysisRunState.RecoveredPartial ->
            SimpleAnalysisScreen(
                title = state.target.label,
                progress = null,
                result = state.result,
                active = false,
                error = null,
                cancelled = false,
                cancelling = false,
                stalledAgeMs = null,
                canSkipStalled = false,
                partialNotice = state.message,
                onCancel = AnalysisManager::cancel,
                onRetry =
                    AnalysisManager::retryStalled,
                onSkip =
                    AnalysisManager::skipStalled,
                onBack = {
                    AnalysisManager
                        .closeRecoveredPartial()
                },
            )

        is AnalysisRunState.Running ->
            SimpleAnalysisScreen(
                title = state.target.label,
                progress = state.progress,
                result = state.partialResult,
                active = true,
                error = null,
                cancelled = false,
                cancelling = false,
                stalledAgeMs = null,
                canSkipStalled = false,
                onCancel = AnalysisManager::cancel,
                onRetry =
                    AnalysisManager::retryStalled,
                onSkip =
                    AnalysisManager::skipStalled,
                onBack = {
                    AnalysisManager.cancel()
                },
            )

        is AnalysisRunState.Cancelling ->
            SimpleAnalysisScreen(
                title = state.target.label,
                progress = state.progress,
                result = state.partialResult,
                active = true,
                error = null,
                cancelled = false,
                cancelling = true,
                stalledAgeMs = null,
                canSkipStalled = false,
                onCancel = AnalysisManager::cancel,
                onRetry =
                    AnalysisManager::retryStalled,
                onSkip =
                    AnalysisManager::skipStalled,
                onBack = { },
            )

        is AnalysisRunState.Stalled ->
            SimpleAnalysisScreen(
                title = state.target.label,
                progress = state.progress,
                result = state.partialResult,
                active = true,
                error = null,
                cancelled = false,
                cancelling = false,
                stalledAgeMs =
                    state.heartbeatAgeMs,
                canSkipStalled =
                    state.progress?.scheduleClass !=
                        io.github.ffenuss.modkit.domain
                            .EngineScheduleClass.FAST,
                onCancel = AnalysisManager::cancel,
                onRetry =
                    AnalysisManager::retryStalled,
                onSkip =
                    AnalysisManager::skipStalled,
                onBack = {
                    AnalysisManager.cancel()
                },
            )

        is AnalysisRunState.Completed ->
            SimpleAnalysisScreen(
                title = state.target.label,
                progress = null,
                result = state.result,
                active = false,
                error = null,
                cancelled = false,
                cancelling = false,
                stalledAgeMs = null,
                canSkipStalled = false,
                onOpenAutoMod = {
                    runCatching {
                        autoModSessionStore.save(
                            target = state.target,
                            artifactSha256 =
                                state.result.index
                                    .artifactSha256,
                        )
                    }
                    autoModTarget =
                        state.target
                    autoModResult =
                        state.result
                    AnalysisManager
                        .clearTerminalState()
                    screen = Screen.AUTOMOD
                },
                onCancel = AnalysisManager::cancel,
                onRetry =
                    AnalysisManager::retryStalled,
                onSkip =
                    AnalysisManager::skipStalled,
                onBack = {
                    AnalysisManager
                        .clearTerminalState()
                    screen = Screen.TARGET
                },
            )

        is AnalysisRunState.Cancelled ->
            SimpleAnalysisScreen(
                title = state.target.label,
                progress = null,
                result = state.partialResult,
                active = false,
                error = null,
                cancelled = true,
                cancelling = false,
                stalledAgeMs = null,
                canSkipStalled = false,
                onCancel = AnalysisManager::cancel,
                onRetry =
                    AnalysisManager::retryStalled,
                onSkip =
                    AnalysisManager::skipStalled,
                onBack = {
                    AnalysisManager
                        .clearTerminalState()
                    screen = Screen.TARGET
                },
            )

        is AnalysisRunState.Failed ->
            SimpleAnalysisScreen(
                title = state.target.label,
                progress = null,
                result = null,
                active = false,
                error = state.message,
                cancelled = false,
                cancelling = false,
                stalledAgeMs = null,
                canSkipStalled = false,
                onCancel = AnalysisManager::cancel,
                onRetry =
                    AnalysisManager::retryStalled,
                onSkip =
                    AnalysisManager::skipStalled,
                onBack = {
                    AnalysisManager
                        .clearTerminalState()
                    screen = Screen.TARGET
                },
            )

        AnalysisRunState.Idle ->
            when (screen) {
                Screen.TARGET ->
                    TargetSelectionScreen(
                        onSelectGames = {
                            openInstalled(
                                InstalledTargetKind.GAMES,
                            )
                        },
                        onSelectApps = {
                            openInstalled(
                                InstalledTargetKind
                                    .APPLICATIONS,
                            )
                        },
                        onSelectFile = {
                            filePicker.launch(
                                arrayOf(
                                    "application/vnd.android.package-archive",
                                    "application/zip",
                                    "application/octet-stream",
                                ),
                            )
                        },
                    )

                Screen.INSTALLED_APPS ->
                    InstalledAppsScreen(
                        apps = installedApps,
                        loading = installedLoading,
                        error = installedError,
                        kind = installedKind,
                        dumpingPackageName =
                            dumpingPackageName,
                        dumpNotice = dumpNotice,
                        onBack = {
                            dumpNotice = null
                            screen = Screen.TARGET
                        },
                        onKindChanged = {
                            installedKind = it
                            dumpNotice = null
                        },
                        onSelect = { app ->
                            autoModSessionStore.clear()
                            AnalysisManager
                                .startInstalled(app)
                        },
                        onDump = { app ->
                            if (
                                dumpingPackageName == null
                            ) {
                                dumpingPackageName =
                                    app.packageName
                                dumpNotice =
                                    "Создаётся дамп «" +
                                        app.label +
                                        "»…"
                                scope.launch {
                                    val dumped =
                                        runCatching {
                                            InstalledPackageDumper
                                                .dump(
                                                    appContext,
                                                    app,
                                                )
                                        }
                                    dumpNotice =
                                        dumped.fold(
                                            onSuccess = {
                                                result ->
                                                "Дамп готов: " +
                                                    result.location +
                                                    " · APK: " +
                                                    result.apkCount +
                                                    " · исходный размер: " +
                                                    formatBytes(
                                                        result.totalBytes,
                                                    )
                                            },
                                            onFailure = {
                                                failure ->
                                                "Ошибка дампа: " +
                                                    (
                                                        failure.message
                                                            ?: failure
                                                                .javaClass
                                                                .simpleName
                                                    )
                                            },
                                        )
                                    dumpingPackageName =
                                        null
                                }
                            }
                        },
                    )

                Screen.AUTOMOD -> {
                    val target = autoModTarget
                    val result = autoModResult
                    if (
                        target != null &&
                        result != null
                    ) {
                        SimpleAutoModScreen(
                            target = target,
                            result = result,
                            onBack = {
                                autoModSessionStore.clear()
                                autoModTarget = null
                                autoModResult = null
                                screen = Screen.TARGET
                            },
                        )
                    } else {
                        screen = Screen.TARGET
                    }
                }
            }
    }
}

private fun formatBytes(
    bytes: Long,
): String {
    if (bytes < 1024L) {
        return bytes.toString() + " B"
    }
    val kb = bytes / 1024.0
    if (kb < 1024.0) {
        return String.format(
            java.util.Locale.US,
            "%.1f KB",
            kb,
        )
    }
    val mb = kb / 1024.0
    if (mb < 1024.0) {
        return String.format(
            java.util.Locale.US,
            "%.1f MB",
            mb,
        )
    }
    return String.format(
        java.util.Locale.US,
        "%.2f GB",
        mb / 1024.0,
    )
}
