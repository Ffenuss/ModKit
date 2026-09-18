package io.github.ffenuss.modkit.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.FastArtifactIndexer
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.TargetMaterializer
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.ui.screens.AnalysisScreen
import io.github.ffenuss.modkit.ui.screens.ExpertLabScreen
import io.github.ffenuss.modkit.ui.screens.InstalledAppsScreen
import io.github.ffenuss.modkit.ui.screens.TargetSelectionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Screen { TARGET, INSTALLED_APPS, ANALYSIS, EXPERT_LAB }

@Composable
fun ModKitApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedRepository = remember { InstalledAppRepository(context.applicationContext) }

    var screen by remember { mutableStateOf(Screen.TARGET) }
    var installedApps by remember { mutableStateOf<List<InstalledAppTarget>>(emptyList()) }
    var installedLoading by remember { mutableStateOf(false) }
    var installedError by remember { mutableStateOf<String?>(null) }

    var analysisTitle by remember { mutableStateOf("Быстрый анализ") }
    var analysisProgress by remember { mutableStateOf<EngineProgress?>(null) }
    var analysisResult by remember { mutableStateOf<FastAnalysisResult?>(null) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var analysisCancelled by remember { mutableStateOf(false) }
    var cancellation by remember { mutableStateOf<AtomicCancellationSignal?>(null) }

    fun startAnalysis(title: String, fileProvider: suspend (AtomicCancellationSignal) -> List<File>) {
        val signal = AtomicCancellationSignal()
        cancellation = signal
        analysisTitle = title
        analysisProgress = null
        analysisResult = null
        analysisError = null
        analysisCancelled = false
        screen = Screen.ANALYSIS

        scope.launch {
            val result = runCatching {
                val files = fileProvider(signal)
                withContext(Dispatchers.IO) {
                    FastArtifactIndexer.index(
                        files = files,
                        cancellation = signal,
                        progress = ProgressSink { progress -> scope.launch { analysisProgress = progress } },
                    )
                }
            }
            result.onSuccess { analysisResult = it }
            result.onFailure { failure ->
                if (failure is AnalysisCancelledException) {
                    analysisCancelled = true
                } else {
                    analysisError = failure.message ?: failure.javaClass.simpleName
                }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            startAnalysis(uri.lastPathSegment ?: "Выбранный файл") { signal ->
                listOf(withContext(Dispatchers.IO) { TargetMaterializer.fromUri(context, uri, signal) })
            }
        }
    }

    when (screen) {
        Screen.TARGET -> TargetSelectionScreen(
            onSelectInstalled = {
                screen = Screen.INSTALLED_APPS
                installedLoading = true
                installedError = null
                scope.launch {
                    val loaded = runCatching { withContext(Dispatchers.IO) { installedRepository.load() } }
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
            onSelect = { app ->
                startAnalysis("${app.label} · ${app.packageName}") { app.apkFiles }
            },
        )

        Screen.ANALYSIS -> AnalysisScreen(
            title = analysisTitle,
            progress = analysisProgress,
            result = analysisResult,
            error = analysisError,
            cancelled = analysisCancelled,
            onCancel = { cancellation?.cancel() },
            onBack = {
                cancellation?.cancel()
                screen = Screen.TARGET
            },
        )

        Screen.EXPERT_LAB -> ExpertLabScreen(onBack = { screen = Screen.TARGET })
    }
}
