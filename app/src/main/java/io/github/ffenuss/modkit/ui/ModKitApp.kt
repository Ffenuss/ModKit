package io.github.ffenuss.modkit.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.ffenuss.modkit.analysis.AnalysisManager
import io.github.ffenuss.modkit.analysis.AnalysisRunState
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.ui.screens.AnalysisScreen
import io.github.ffenuss.modkit.ui.screens.ExpertLabScreen
import io.github.ffenuss.modkit.ui.screens.InstalledAppsScreen
import io.github.ffenuss.modkit.ui.screens.RecoveryScreen
import io.github.ffenuss.modkit.ui.screens.TargetSelectionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { TARGET, INSTALLED_APPS, EXPERT_LAB }

@Composable
fun ModKitApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedRepository = remember { InstalledAppRepository(context.applicationContext) }
    remember(context.applicationContext) {
        AnalysisManager.initialize(context.applicationContext)
        true
    }
    val analysisState by AnalysisManager.state.collectAsState()

    var screen by remember { mutableStateOf(Screen.TARGET) }
    var installedApps by remember { mutableStateOf<List<InstalledAppTarget>>(emptyList()) }
    var installedLoading by remember { mutableStateOf(false) }
    var installedError by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            AnalysisManager.startFile(uri, uri.lastPathSegment ?: "Выбранный файл")
        }
    }

    when (val state = analysisState) {
        is AnalysisRunState.Interrupted -> RecoveryScreen(
            state = state,
            onResume = { AnalysisManager.resumeInterrupted() },
            onDelete = { AnalysisManager.dismissInterrupted() },
        )

        is AnalysisRunState.Running -> AnalysisScreen(
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
            onRetry = AnalysisManager::retryStalled,
            onSkip = AnalysisManager::skipStalled,
            onBack = { AnalysisManager.cancel() },
        )

        is AnalysisRunState.Cancelling -> AnalysisScreen(
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
            onRetry = AnalysisManager::retryStalled,
            onSkip = AnalysisManager::skipStalled,
            onBack = { },
        )

        is AnalysisRunState.Stalled -> AnalysisScreen(
            title = state.target.label,
            progress = state.progress,
            result = state.partialResult,
            active = true,
            error = null,
            cancelled = false,
            cancelling = false,
            stalledAgeMs = state.heartbeatAgeMs,
            canSkipStalled = state.progress?.scheduleClass != io.github.ffenuss.modkit.domain.EngineScheduleClass.FAST,
            onCancel = AnalysisManager::cancel,
            onRetry = AnalysisManager::retryStalled,
            onSkip = AnalysisManager::skipStalled,
            onBack = { AnalysisManager.cancel() },
        )

        is AnalysisRunState.Completed -> AnalysisScreen(
            title = state.target.label,
            progress = null,
            result = state.result,
            active = false,
            error = null,
            cancelled = false,
            cancelling = false,
            stalledAgeMs = null,
            canSkipStalled = false,
            onCancel = AnalysisManager::cancel,
            onRetry = AnalysisManager::retryStalled,
            onSkip = AnalysisManager::skipStalled,
            onBack = {
                AnalysisManager.clearTerminalState()
                screen = Screen.TARGET
            },
        )

        is AnalysisRunState.Cancelled -> AnalysisScreen(
            title = state.target.label,
            progress = null,
            result = null,
            active = false,
            error = null,
            cancelled = true,
            cancelling = false,
            stalledAgeMs = null,
            canSkipStalled = false,
            onCancel = AnalysisManager::cancel,
            onRetry = AnalysisManager::retryStalled,
            onSkip = AnalysisManager::skipStalled,
            onBack = {
                AnalysisManager.clearTerminalState()
                screen = Screen.TARGET
            },
        )

        is AnalysisRunState.Failed -> AnalysisScreen(
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
            onRetry = AnalysisManager::retryStalled,
            onSkip = AnalysisManager::skipStalled,
            onBack = {
                AnalysisManager.clearTerminalState()
                screen = Screen.TARGET
            },
        )

        AnalysisRunState.Idle -> when (screen) {
            Screen.TARGET -> TargetSelectionScreen(
                onSelectInstalled = {
                    screen = Screen.INSTALLED_APPS
                    installedLoading = true
                    installedError = null
                    scope.launch {
                        val loaded = runCatching {
                            withContext(Dispatchers.IO) { installedRepository.load() }
                        }
                        installedApps = loaded.getOrDefault(emptyList())
                        installedError = loaded.exceptionOrNull()?.message
                        installedLoading = false
                    }
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
                onOpenExpertLab = { screen = Screen.EXPERT_LAB },
            )

            Screen.INSTALLED_APPS -> InstalledAppsScreen(
                apps = installedApps,
                loading = installedLoading,
                error = installedError,
                onBack = { screen = Screen.TARGET },
                onSelect = { app -> AnalysisManager.startInstalled(app) },
            )

            Screen.EXPERT_LAB -> ExpertLabScreen(onBack = { screen = Screen.TARGET })
        }
    }
}
