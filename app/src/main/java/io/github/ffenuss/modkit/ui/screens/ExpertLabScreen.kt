package io.github.ffenuss.modkit.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.ExpertCapabilityValidator
import io.github.ffenuss.modkit.analysis.ExpertLabReportExporter
import io.github.ffenuss.modkit.analysis.ExpertLabReportWriter
import io.github.ffenuss.modkit.analysis.ExpertLabInventoryFilter
import io.github.ffenuss.modkit.analysis.ExpertLabSession
import io.github.ffenuss.modkit.analysis.ExpertLabSessionController
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.runtime.AndroidRepackedRuntimeInstaller
import io.github.ffenuss.modkit.runtime.ProcMapsCaptureSource
import io.github.ffenuss.modkit.runtime.RepackedRuntimeBuildResult
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallPlanner
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallReadiness
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallReadinessState
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallStatusStore
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestAppLauncher
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceContract
import io.github.ffenuss.modkit.runtime.RuntimeEscalationPlanner
import io.github.ffenuss.modkit.runtime.RuntimeEscalationStage
import io.github.ffenuss.modkit.runtime.RootRuntimeDecisionEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExpertLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val installedRepository = remember { InstalledAppRepository(appContext) }

    var session by remember { mutableStateOf<ExpertLabSession?>(null) }
    var busy by remember { mutableStateOf(false) }
    var activeEngine by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<EngineProgress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cancellation by remember { mutableStateOf<AtomicCancellationSignal?>(null) }
    var procMapsText by remember { mutableStateOf("") }
    var nativeLookupModule by remember { mutableStateOf("") }
    var nativeLookupSymbol by remember { mutableStateOf("") }
    var backendFilter by remember { mutableStateOf("") }
    var targetFilter by remember { mutableStateOf("") }
    var installReadiness by remember {
        mutableStateOf<RepackedRuntimeInstallReadiness?>(null)
    }
    val installStatus by
        RepackedRuntimeInstallStatusStore.status.collectAsState()

    var showInstalled by remember { mutableStateOf(false) }
    var installedLoading by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledAppTarget>>(emptyList()) }

    val latestSession by rememberUpdatedState(session)
    DisposableEffect(Unit) {
        onDispose {
            cancellation?.cancel()
            latestSession?.close()
        }
    }

    fun beginOperation(engineId: String? = null): AtomicCancellationSignal? {
        if (busy) return null
        val signal = AtomicCancellationSignal()
        cancellation = signal
        busy = true
        activeEngine = engineId
        progress = null
        error = null
        return signal
    }

    fun finishOperation() {
        busy = false
        activeEngine = null
        cancellation = null
    }

    fun openFiles(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        val signal = beginOperation() ?: return

        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }

        scope.launch {
            try {
                val opened = ExpertLabSessionController.openUris(
                    context = appContext,
                    uris = uris,
                    cancellation = signal,
                    progress = ProgressSink { update ->
                        scope.launch { progress = update }
                    },
                )
                session?.close()
                session = opened
                showInstalled = false
            } catch (_: AnalysisCancelledException) {
                error = "Открытие цели отменено."
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun openInstalled(app: InstalledAppTarget) {
        val signal = beginOperation() ?: return
        scope.launch {
            try {
                val opened = ExpertLabSessionController.openInstalled(
                    context = appContext,
                    app = app,
                    cancellation = signal,
                    progress = ProgressSink { update ->
                        scope.launch { progress = update }
                    },
                )
                session?.close()
                session = opened
                showInstalled = false
            } catch (_: AnalysisCancelledException) {
                error = "Открытие установленного приложения отменено."
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun runEngine(engineId: String) {
        val current = session ?: return
        val signal = beginOperation(engineId) ?: return
        scope.launch {
            try {
                session = ExpertLabSessionController.runEngine(
                    context = appContext,
                    session = current,
                    engineId = engineId,
                    cancellation = signal,
                    progress = ProgressSink { update ->
                        scope.launch { progress = update }
                    },
                )
            } catch (_: AnalysisCancelledException) {
                error = "Запуск backend отменён."
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun integrateRuntimeMaps() {
        val current = session ?: return
        val signal = beginOperation("runtime.module-map") ?: return
        scope.launch {
            try {
                session = ExpertLabSessionController.integrateRuntimeMaps(
                    context = appContext,
                    session = current,
                    procMapsText = procMapsText,
                    cancellation = signal,
                )
            } catch (_: AnalysisCancelledException) {
                error = "Runtime-подтверждение отменено."
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun integrateNonRootRuntime() {
        val current = session ?: return
        val signal = beginOperation("runtime.non-root-map") ?: return
        scope.launch {
            try {
                session = ExpertLabSessionController.integrateNonRootRuntime(
                    context = appContext,
                    session = current,
                    cancellation = signal,
                )
            } catch (_: AnalysisCancelledException) {
                error = "Non-root runtime-проверка отменена."
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun attachRootProcess() {
        val current = session ?: return
        val signal =
            beginOperation(
                "runtime.root-process-attach",
            ) ?: return
        scope.launch {
            try {
                session =
                    ExpertLabSessionController
                        .attachRootProcess(
                            context = appContext,
                            session = current,
                            cancellation = signal,
                        )
            } catch (_: AnalysisCancelledException) {
                error =
                    "Root-подключение к процессу отменено."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun integrateRootRuntime() {
        val current = session ?: return
        val signal = beginOperation("runtime.root-map") ?: return
        scope.launch {
            try {
                session = ExpertLabSessionController.integrateRootRuntime(
                    context = appContext,
                    session = current,
                    cancellation = signal,
                )
            } catch (_: AnalysisCancelledException) {
                error = "Root runtime-проверка отменена."
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun buildRepackedTestRuntime() {
        val current = session ?: return
        val signal = beginOperation("runtime.repacked-build") ?: return
        scope.launch {
            try {
                session =
                    ExpertLabSessionController.buildRepackedTestRuntime(
                        context = appContext,
                        session = current,
                        cancellation = signal,
                        progress = ProgressSink { update ->
                            scope.launch { progress = update }
                        },
                    )
            } catch (_: AnalysisCancelledException) {
                error = "Сборка repacked test runtime отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun installRepackedBuild(
        build: RepackedRuntimeBuildResult,
        operationId: String,
        cancelledMessage: String,
    ) {
        val signal = beginOperation(operationId) ?: return
        scope.launch {
            try {
                val plan = withContext(Dispatchers.IO) {
                    RepackedRuntimeInstallPlanner.plan(
                        build = build,
                        cancellation = signal,
                    )
                }
                require(plan.ready) {
                    plan.blockers.firstOrNull()
                        ?: "Repacked runtime install plan is not ready."
                }
                val readiness = withContext(Dispatchers.IO) {
                    AndroidRepackedRuntimeInstaller.inspectReadiness(
                        context = appContext,
                        plan = plan,
                    )
                }
                installReadiness = readiness
                if (readiness.canCreateSession) {
                    withContext(Dispatchers.IO) {
                        AndroidRepackedRuntimeInstaller.submit(
                            context = appContext,
                            plan = plan,
                            cancellation = signal,
                        )
                    }
                } else {
                    error = readiness.blockers.joinToString("\n")
                }
            } catch (_: AnalysisCancelledException) {
                error = cancelledMessage
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun installRepackedTestRuntime() {
        val build = session?.repackedRuntimeBuild ?: return
        installRepackedBuild(
            build = build,
            operationId = "runtime.repacked-install",
            cancelledMessage = "Установка test runtime отменена.",
        )
    }

    fun buildRepackedNativeLookupRuntime() {
        val current = session ?: return
        val signal =
            beginOperation("runtime.native-lookup-build") ?: return
        scope.launch {
            try {
                val updated =
                    ExpertLabSessionController
                        .buildRepackedNativeLookupRuntime(
                            context = appContext,
                            session = current,
                            cancellation = signal,
                            progress = ProgressSink { update ->
                                scope.launch { progress = update }
                            },
                        )
                session = updated
                if (nativeLookupModule.isBlank()) {
                    nativeLookupModule =
                        updated.result.index.entries
                            .firstOrNull {
                                it.path.lowercase().endsWith(".so")
                            }
                            ?.path
                            ?.substringAfterLast('/')
                            .orEmpty()
                }
                if (nativeLookupSymbol.isBlank()) {
                    val suggested =
                        updated.result.elfInventory
                            ?.records
                            ?.firstOrNull {
                                it.entryPath.substringAfterLast('/') ==
                                    nativeLookupModule
                            }
                            ?.sampledDefinedSymbols
                            ?.firstOrNull()
                    if (suggested != null) {
                        nativeLookupSymbol = suggested
                    }
                }
            } catch (_: AnalysisCancelledException) {
                error =
                    "Сборка native lookup test runtime отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun installRepackedNativeLookupRuntime() {
        val build =
            session?.repackedNativeRuntimeBuild ?: return
        installRepackedBuild(
            build = build,
            operationId = "runtime.native-lookup-install",
            cancelledMessage =
                "Установка native lookup test runtime отменена.",
        )
    }

    fun launchRepackedNativeLookupRuntime() {
        val build =
            session?.repackedNativeRuntimeBuild ?: return
        val signal =
            beginOperation("runtime.native-lookup-launch") ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    RepackedRuntimeTestAppLauncher.launch(
                        context = appContext,
                        build = build,
                    )
                }
            } catch (_: AnalysisCancelledException) {
                error = "Запуск test-копии отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                if (signal.isCancelled()) {
                    error = "Запуск test-копии отменён."
                }
                finishOperation()
            }
        }
    }

    fun startRepackedPassiveDlsymTrace() {
        val current = session ?: return
        val signal =
            beginOperation("runtime.passive-dlsym-start") ?: return
        scope.launch {
            try {
                session =
                    ExpertLabSessionController
                        .startRepackedPassiveDlsymTrace(
                            context = appContext,
                            session = current,
                            cancellation = signal,
                        )
            } catch (_: AnalysisCancelledException) {
                error = "Запуск passive dlsym trace отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun stopRepackedPassiveDlsymTrace() {
        val current = session ?: return
        val signal =
            beginOperation("runtime.passive-dlsym-stop") ?: return
        scope.launch {
            try {
                session =
                    ExpertLabSessionController
                        .stopRepackedPassiveDlsymTrace(
                            context = appContext,
                            session = current,
                            cancellation = signal,
                        )
            } catch (_: AnalysisCancelledException) {
                error = "Остановка passive dlsym trace отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun startRepackedPassiveJniTrace() {
        val current = session ?: return
        val signal =
            beginOperation("runtime.passive-jni-start") ?: return
        scope.launch {
            try {
                session =
                    ExpertLabSessionController
                        .startRepackedPassiveJniTrace(
                            context = appContext,
                            session = current,
                            cancellation = signal,
                        )
            } catch (_: AnalysisCancelledException) {
                error = "Запуск passive JNI trace отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun stopRepackedPassiveJniTrace() {
        val current = session ?: return
        val signal =
            beginOperation("runtime.passive-jni-stop") ?: return
        scope.launch {
            try {
                session =
                    ExpertLabSessionController
                        .stopRepackedPassiveJniTrace(
                            context = appContext,
                            session = current,
                            cancellation = signal,
                        )
            } catch (_: AnalysisCancelledException) {
                error = "Остановка passive JNI trace отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun integrateRepackedNativeLookup() {
        val current = session ?: return
        val signal =
            beginOperation("runtime.native-lookup") ?: return
        scope.launch {
            try {
                session =
                    ExpertLabSessionController
                        .integrateRepackedNativeLookup(
                            context = appContext,
                            session = current,
                            moduleName =
                                nativeLookupModule.trim(),
                            symbolName =
                                nativeLookupSymbol.trim(),
                            cancellation = signal,
                        )
            } catch (_: AnalysisCancelledException) {
                error = "Native lookup отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun integrateRepackedTestRuntime() {
        val current = session ?: return
        val signal = beginOperation("runtime.repacked-capture") ?: return
        scope.launch {
            try {
                session =
                    ExpertLabSessionController.integrateRepackedTestRuntime(
                        context = appContext,
                        session = current,
                        cancellation = signal,
                    )
            } catch (_: AnalysisCancelledException) {
                error = "Repacked runtime capture отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        openFiles(uris)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(
                onClick = {
                    cancellation?.cancel()
                    session?.close()
                    session = null
                    onBack()
                },
            ) {
                Text("← Назад")
            }
        }

        item {
            Text(
                "Expert Lab",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Прямой запуск совместимого backend на APK, APK-set, установленном приложении или отдельном файле.",
            )
        }

        item {
            Button(
                onClick = {
                    filePicker.launch(
                        arrayOf(
                            "application/vnd.android.package-archive",
                            "application/zip",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Файл / APK / APK-set")
            }
            OutlinedButton(
                onClick = {
                    if (!showInstalled && installedApps.isEmpty() && !installedLoading) {
                        installedLoading = true
                        error = null
                        scope.launch {
                            val loaded = runCatching {
                                withContext(Dispatchers.IO) {
                                    installedRepository.load()
                                }
                            }
                            installedApps = loaded.getOrDefault(emptyList())
                            loaded.exceptionOrNull()?.let {
                                error = it.message ?: it.javaClass.simpleName
                            }
                            installedLoading = false
                        }
                    }
                    showInstalled = !showInstalled
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (showInstalled) {
                        "Скрыть установленные приложения"
                    } else {
                        "Установленное приложение"
                    },
                )
            }
        }

        if (installedLoading) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Читаем список установленных приложений…")
            }
        }

        if (showInstalled) {
            items(
                installedApps,
                key = { it.packageName },
            ) { app ->
                OutlinedButton(
                    onClick = { openInstalled(app) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(app.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            app.packageName +
                                " · APK: " + app.apkFiles.size,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (busy) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            progress?.currentTask
                                ?: activeEngine?.let { "Запуск $it…" }
                                ?: "Подготовка Expert Lab…",
                            fontWeight = FontWeight.SemiBold,
                        )
                        progress?.currentArtifact?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        progress?.processed?.let { processed ->
                            Text(
                                progress?.total?.let { total ->
                                    "$processed / $total"
                                } ?: "Обработано: $processed",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(
                            onClick = { cancellation?.cancel() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Отменить")
                        }
                    }
                }
            }
        }

        error?.let { message ->
            item {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        session?.let { current ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("Текущая цель", fontWeight = FontWeight.SemiBold)
                        Text(current.label)
                        Text(
                            "SHA-256: " + current.result.index.artifactSha256,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Файлов: " + current.workspace.sources.size +
                                " · entries: " + current.result.index.entries.size,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "ABI: " + current.result.index.detectedAbis
                                .sorted()
                                .joinToString()
                                .ifBlank { "не определены" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val report = withContext(Dispatchers.IO) {
                                    ExpertLabReportWriter.write(
                                        outputDir = File(
                                            appContext.filesDir,
                                            "expert-lab-export/" +
                                                current.result.index.artifactSha256,
                                        ),
                                        label = current.label,
                                        result = current.result,
                                    )
                                }
                                ExpertLabReportExporter.share(appContext, report)
                            }.onFailure { failure ->
                                error = failure.message ?: failure.javaClass.simpleName
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Экспортировать технический отчёт")
                }
            }

            if (current.result.index.runtimeProfiles.isNotEmpty()) {
                item {
                    Text(
                        "Runtime Profiler",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(
                    current.result.index.runtimeProfiles,
                    key = { it.runtimeId },
                ) { runtime ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(runtime.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                runtime.runtimeId + " · " +
                                    runtime.status.name + " · " +
                                    runtime.confidence.name,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            runtime.evidence.take(4).forEach {
                                Text(
                                    "• " + it,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            val capabilityReport = ExpertCapabilityValidator.validate(
                current.result.routingPlan,
            )
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Capability validation",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (capabilityReport.valid) {
                                "Router и реально зарегистрированные executors согласованы."
                            } else {
                                "Обнаружено несоответствие capability-модели; прямой запуск соответствующего backend заблокирован."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        capabilityReport.blockers.forEach {
                            Text(
                                "• " + it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            val filteredEngines =
                ExpertLabInventoryFilter.filterEngines(
                    engines = current.result.routingPlan.engines,
                    query = backendFilter,
                )
            item {
                Text("Backend routing", fontWeight = FontWeight.SemiBold)
            }
            item {
                OutlinedTextField(
                    value = backendFilter,
                    onValueChange = { backendFilter = it },
                    label = { Text("Фильтр backend") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Показано " + filteredEngines.size +
                        " из " + current.result.routingPlan.engines.size,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (filteredEngines.isEmpty()) {
                item {
                    Text(
                        "По фильтру backend ничего не найдено.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            items(
                filteredEngines,
                key = { it.id },
            ) { engine ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(engine.id, fontWeight = FontWeight.SemiBold)
                        Text(
                            engine.scheduleClass.name +
                                " · " +
                                if (engine.availableNow) {
                                    "backend подключён"
                                } else {
                                    "backend ещё не подключён"
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            engine.reason,
                            style = MaterialTheme.typography.bodySmall,
                        )

                        if (engine.id == "artifact.fast-index") {
                            Text(
                                "Уже выполнен при открытии цели.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            val capability = capabilityReport.capabilities
                                .singleOrNull { it.engineId == engine.id }
                            Button(
                                onClick = { runEngine(engine.id) },
                                enabled = engine.availableNow &&
                                    capability?.consistent != false &&
                                    !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Запустить только этот backend")
                            }
                        }
                    }
                }
            }

            if (current.result.routingPlan.missingCapabilities.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Непокрытые возможности",
                                fontWeight = FontWeight.SemiBold,
                            )
                            current.result.routingPlan.missingCapabilities.forEach {
                                Text(
                                    "• " + it,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            current.result.dexInventory?.let { inventory ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "DEX inventory · raw",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "entries=" + inventory.records.size +
                                    " · warnings=" +
                                    inventory.warnings.size,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            inventory.records
                                .take(MAX_RAW_DEX_RECORDS)
                                .forEach { record ->
                                    Text(
                                        "• " + record.entryPath +
                                            " · v" + record.version +
                                            " · strings=" +
                                            (record.stringIdsCount
                                                ?.toString() ?: "n/a") +
                                            " · types=" +
                                            (record.typeIdsCount
                                                ?.toString() ?: "n/a") +
                                            " · methods=" +
                                            (record.methodIdsCount
                                                ?.toString() ?: "n/a") +
                                            " · classes=" +
                                            (record.classDefsCount
                                                ?.toString() ?: "n/a"),
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                    record.warnings
                                        .take(2)
                                        .forEach { warning ->
                                            Text(
                                                "  ↳ " + warning,
                                                color =
                                                    MaterialTheme.colorScheme.error,
                                                style =
                                                    MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                }
                            if (
                                inventory.records.size >
                                MAX_RAW_DEX_RECORDS
                            ) {
                                Text(
                                    "… ещё " +
                                        (
                                            inventory.records.size -
                                                MAX_RAW_DEX_RECORDS
                                            ) +
                                        " DEX entries",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            current.result.evidenceGraph
                ?.takeIf { it.targets.isNotEmpty() }
                ?.let { graph ->
                    val filteredTargets =
                        ExpertLabInventoryFilter.filterTargets(
                            targets = graph.targets,
                            query = targetFilter,
                        )
                    item {
                        Text(
                            "Evidence targets",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = targetFilter,
                            onValueChange = { targetFilter = it },
                            label = {
                                Text(
                                    "Поиск по target / runtime / proof / blocker",
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Показано " + filteredTargets.size +
                                " из " + graph.targets.size,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (filteredTargets.isEmpty()) {
                        item {
                            Text(
                                "По фильтру targets ничего не найдено.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(
                        filteredTargets,
                        key = { "evidence-target:" + it.id },
                    ) { target ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    target.displayName,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    target.kind.name +
                                        " · " + target.runtimeId +
                                        " · " + target.proofLevel.name +
                                        " · " + target.userStatus.name,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "id: " + target.id,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                target.artifact?.let {
                                    Text(
                                        "artifact: " + it,
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                }
                                target.blockers
                                    .take(3)
                                    .forEach { blocker ->
                                        Text(
                                            "• " + blocker.code +
                                                " · " + blocker.message,
                                            color =
                                                MaterialTheme.colorScheme.error,
                                            style =
                                                MaterialTheme.typography.bodySmall,
                                        )
                                    }
                            }
                        }
                    }
                }

            current.result.il2cppFastDump?.let { dump ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                "IL2CPP fast dump · raw",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "metadata: " + dump.metadataEntry,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "types=" + dump.metadata.types.size +
                                    " · methods=" + dump.metadata.methods.size +
                                    " · fields=" + dump.metadata.fields.size,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                dump.preview.take(MAX_RAW_PREVIEW_CHARS),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (current.result.il2cppFastDump != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                "Runtime module mapping",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Runtime proof требует независимой привязки к процессу. " +
                                    "Для установленного приложения сначала используется non-root discovery; " +
                                    "вставленный вручную maps остаётся диагностическим snapshot и сам по себе не повышает proof.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val repackedPlan =
                                RuntimeEscalationPlanner.plan(current.result)
                            val needsRepacked =
                                repackedPlan.needs.any {
                                    it.firstStage ==
                                        RuntimeEscalationStage.REPACKED_TEST_RUNTIME
                                }
                            if (needsRepacked) {
                                Text(
                                    "Repacked test runtime",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Button(
                                    onClick = ::buildRepackedTestRuntime,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (current.repackedRuntimeBuild == null) {
                                            "Собрать test runtime APK"
                                        } else {
                                            "Пересобрать test runtime APK"
                                        },
                                    )
                                }
                                current.repackedRuntimeBuild?.let { build ->
                                    Text(
                                        "package: " + build.packageName +
                                            " · signer: " + build.signerAlias,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    build.signedApks.forEach { apk ->
                                        Text(
                                            "• " + apk.sourceDisplayName +
                                                " → " + apk.signedPath +
                                                " · SHA " +
                                                apk.signedSha256.take(16) + "…",
                                            style =
                                                MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Text(
                                        "Устанавливается только эта подписанная test-копия через Android PackageInstaller. " +
                                            "Если оригинал с тем же packageName подписан другим ключом, ModKit не удаляет его автоматически.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        onClick = ::installRepackedTestRuntime,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Установить test-копию")
                                    }

                                    installReadiness?.let { readiness ->
                                        if (readiness.packageName == build.packageName) {
                                            when (readiness.state) {
                                                RepackedRuntimeInstallReadinessState.UNKNOWN_SOURCES_PERMISSION_REQUIRED -> {
                                                    OutlinedButton(
                                                        onClick = {
                                                            appContext.startActivity(
                                                                AndroidRepackedRuntimeInstaller
                                                                    .unknownSourcesSettingsIntent(
                                                                        appContext,
                                                                    ),
                                                            )
                                                        },
                                                        enabled = !busy,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    ) {
                                                        Text(
                                                            "Разрешить установку из ModKit",
                                                        )
                                                    }
                                                }

                                                RepackedRuntimeInstallReadinessState.INSTALLED_SIGNATURE_CONFLICT -> {
                                                    Text(
                                                        "Установленный оригинал подписан другим сертификатом. " +
                                                            "Android не позволит поставить test-копию поверх него.",
                                                        color = MaterialTheme.colorScheme.error,
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                    OutlinedButton(
                                                        onClick = {
                                                            appContext.startActivity(
                                                                AndroidRepackedRuntimeInstaller
                                                                    .uninstallConflictIntent(
                                                                        build.packageName,
                                                                    ),
                                                            )
                                                        },
                                                        enabled = !busy,
                                                        modifier = Modifier.fillMaxWidth(),
                                                    ) {
                                                        Text(
                                                            "Открыть системное удаление оригинала",
                                                        )
                                                    }
                                                }

                                                else -> Unit
                                            }
                                        }
                                    }

                                    if (
                                        installStatus.packageName ==
                                        build.packageName
                                    ) {
                                        Text(
                                            "Install: " +
                                                installStatus.kind.name +
                                                installStatus.message?.let {
                                                    " · " + it
                                                }.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }

                                    Button(
                                        onClick = ::integrateRepackedTestRuntime,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Проверить установленную test-копию",
                                        )
                                    }
                                }
                            }
                            if (current.packageName != null) {
                                Button(
                                    onClick = ::integrateNonRootRuntime,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Проверить runtime без root")
                                }
                                Text(
                                    "Проверяется точный /proc/<pid>/cmdline до и после bounded maps capture. " +
                                        "Неоднозначный PID или недоступный maps блокирует подтверждение.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Root Process Lab",
                                    fontWeight =
                                        FontWeight.SemiBold,
                                )
                                Text(
                                    "Явное root-подключение к запущенному основному процессу: " +
                                        "ModKit подтверждает PID, читает /proc/<pid>/maps, " +
                                        "определяет load bias модулей и проверяет mapped ELF " +
                                        "через ограниченное чтение живой памяти процесса.",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                )
                                Button(
                                    onClick = ::attachRootProcess,
                                    enabled = !busy,
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Подключиться к процессу (root)",
                                    )
                                }
                            }
                            val rootDecision =
                                RootRuntimeDecisionEngine.decide(
                                    plan =
                                        RuntimeEscalationPlanner.plan(
                                            current.result,
                                        ),
                                    attempts =
                                        current.result.runtimeStageAttempts,
                                )
                            if (rootDecision.evidenceRequiresRoot) {
                                Text(
                                    "Root требуется только для оставшихся " +
                                        "неподтверждённых runtime-целей после " +
                                        "repacked/non-root попыток.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                rootDecision.reasons
                                    .take(3)
                                    .forEach { reason ->
                                        Text(
                                            "• " + reason,
                                            style =
                                                MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                Button(
                                    onClick = ::integrateRootRuntime,
                                    enabled =
                                        !busy &&
                                            rootDecision.readyToRunRoot,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Root-подтверждение оставшихся целей",
                                    )
                                }
                                if (!rootDecision.readyToRunRoot) {
                                    rootDecision.blockers
                                        .take(3)
                                        .forEach { blocker ->
                                            Text(
                                                "• " + blocker.message,
                                                color =
                                                    MaterialTheme.colorScheme.error,
                                                style =
                                                    MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                }
                            }
                            OutlinedTextField(
                                value = procMapsText,
                                onValueChange = { procMapsText = it },
                                label = { Text("/proc/<pid>/maps") },
                                minLines = 5,
                                maxLines = 12,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = ::integrateRuntimeMaps,
                                enabled = !busy && procMapsText.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Проверить импортированный module mapping")
                            }

                            current.result.runtimeEvidence?.let { runtimeEvidence ->
                                Text(
                                    "Capture: " + runtimeEvidence.captureSource.name +
                                        " · PID: " +
                                        (runtimeEvidence.capturePid?.toString()
                                            ?: if (
                                                runtimeEvidence.captureSource ==
                                                ProcMapsCaptureSource.IMPORTED_SNAPSHOT
                                            ) {
                                                "не подтверждён (импортированный snapshot)"
                                            } else {
                                                "не записан"
                                            }),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Snapshot SHA-256: " +
                                        runtimeEvidence.procMapsSha256,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Process identity: " +
                                        (runtimeEvidence.processIdentity
                                            ?: "не подтверждена") +
                                        " · confirmed=" +
                                        runtimeEvidence.processIdentityConfirmed,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (
                                    runtimeEvidence.captureSource ==
                                    ProcMapsCaptureSource.ROOT_PROCESS
                                ) {
                                    val validatedMemoryElf =
                                        runtimeEvidence
                                            .memoryElfEvidence
                                            .count {
                                                it.validated
                                            }
                                    Text(
                                        "Root live-memory ELF: " +
                                            validatedMemoryElf +
                                            "/" +
                                            runtimeEvidence
                                                .memoryElfEvidence
                                                .size +
                                            " подтверждено.",
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                }
                                runtimeEvidence.moduleMappings.forEach { mapping ->
                                    Text(
                                        mapping.moduleName +
                                            " · " +
                                            if (mapping.confirmed) {
                                                "mapping подтверждён"
                                            } else {
                                                "mapping не подтверждён"
                                            },
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "path: " + mapping.mappedPath,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    val unresolvedReason = mapping.blockers.firstOrNull()
                                        ?: "не удалось определить"
                                    Text(
                                        "loadBias: " +
                                            (mapping.loadBias?.let {
                                                "0x" + it.toString(16)
                                            } ?: unresolvedReason) +
                                            " · PT_LOAD: " + mapping.matchedLoadSegments +
                                            " · exec: " + mapping.matchedExecutableSegments,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    mapping.blockers.forEach {
                                        Text(
                                            "• " + it,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                                val observations =
                                    RuntimeEvidenceContract.observations(runtimeEvidence)
                                if (observations.isNotEmpty()) {
                                    Text(
                                        "Runtime evidence contract",
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    observations.take(16).forEach { observation ->
                                        Text(
                                            "• " + observation.kind.name +
                                                " · " + observation.strength.name +
                                                " · " + observation.summary,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        observation.blockers.forEach { blocker ->
                                            Text(
                                                "  ↳ " + blocker,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                                runtimeEvidence.addressConfirmations.take(12).forEach { address ->
                                    Text(
                                        "• " + address.targetId +
                                            " · RVA=0x" + address.rva.toString(16) +
                                            " · runtimeVA=0x" +
                                            address.runtimeVirtualAddress.toString(16),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                val runtimeOnly = runtimeEvidence.moduleInventory
                                    .filter { it.runtimeOnlyRelativeToArtifact }
                                if (runtimeEvidence.moduleInventory.isNotEmpty()) {
                                    Text(
                                        "Mapped modules: " +
                                            runtimeEvidence.moduleInventory.size +
                                            " · runtime-only относительно APK: " +
                                            runtimeOnly.size,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                runtimeOnly.take(8).forEach { module ->
                                    Text(
                                        "• runtime module: " + module.fileName +
                                            " · exec regions=" +
                                            module.executableRegionCount,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (runtimeEvidence.memoryMappingCandidates.isNotEmpty()) {
                                    Text(
                                        "Memory-backed executable candidates: " +
                                            runtimeEvidence.memoryMappingCandidates.size +
                                            " · пока только кандидаты до проверки ELF header в памяти",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                runtimeEvidence.memoryMappingCandidates.take(6).forEach { candidate ->
                                    Text(
                                        "• 0x" + candidate.start.toString(16) +
                                            "-0x" + candidate.endExclusive.toString(16) +
                                            " · " + (candidate.path ?: "anonymous"),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (
                current.result.index.entries.any {
                    it.path.lowercase().endsWith(".so")
                }
            ) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                "Targeted native lookup",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Targeted probe проверяет один symbol через RTLD_NOLOAD. " +
                                    "Passive trace отдельно наблюдает реальные успешные dlsym-вызовы app-owned .so; " +
                                    "ни один из режимов сам по себе не подтверждает исполнение функции.",
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick =
                                    ::buildRepackedNativeLookupRuntime,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (
                                        current.repackedNativeRuntimeBuild ==
                                        null
                                    ) {
                                        "Собрать native lookup test APK"
                                    } else {
                                        "Пересобрать native lookup test APK"
                                    },
                                )
                            }

                            current.repackedNativeRuntimeBuild?.let { build ->
                                Text(
                                    "package: " + build.packageName +
                                        " · signer: " +
                                        build.signerAlias,
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                )
                                build.signedApks.forEach { apk ->
                                    Text(
                                        "• " + apk.sourceDisplayName +
                                            " · SHA " +
                                            apk.signedSha256.take(16) +
                                            "…",
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Button(
                                    onClick =
                                        ::installRepackedNativeLookupRuntime,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Установить native lookup test-копию",
                                    )
                                }

                                installReadiness?.let { readiness ->
                                    if (
                                        readiness.packageName ==
                                        build.packageName
                                    ) {
                                        when (readiness.state) {
                                            RepackedRuntimeInstallReadinessState.UNKNOWN_SOURCES_PERMISSION_REQUIRED -> {
                                                OutlinedButton(
                                                    onClick = {
                                                        appContext.startActivity(
                                                            AndroidRepackedRuntimeInstaller
                                                                .unknownSourcesSettingsIntent(
                                                                    appContext,
                                                                ),
                                                        )
                                                    },
                                                    enabled = !busy,
                                                    modifier =
                                                        Modifier.fillMaxWidth(),
                                                ) {
                                                    Text(
                                                        "Разрешить установку из ModKit",
                                                    )
                                                }
                                            }

                                            RepackedRuntimeInstallReadinessState.INSTALLED_SIGNATURE_CONFLICT -> {
                                                Text(
                                                    "Оригинал с тем же packageName подписан другим сертификатом.",
                                                    color =
                                                        MaterialTheme.colorScheme.error,
                                                    style =
                                                        MaterialTheme.typography.bodySmall,
                                                )
                                                OutlinedButton(
                                                    onClick = {
                                                        appContext.startActivity(
                                                            AndroidRepackedRuntimeInstaller
                                                                .uninstallConflictIntent(
                                                                    build.packageName,
                                                                ),
                                                        )
                                                    },
                                                    enabled = !busy,
                                                    modifier =
                                                        Modifier.fillMaxWidth(),
                                                ) {
                                                    Text(
                                                        "Открыть системное удаление оригинала",
                                                    )
                                                }
                                            }

                                            else -> Unit
                                        }
                                    }
                                }

                                Button(
                                    onClick =
                                        ::launchRepackedNativeLookupRuntime,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Запустить test-копию",
                                    )
                                }

                                if (
                                    current.repackedPassiveTraceSession == null &&
                                    current.repackedPassiveJniTraceSession == null
                                ) {
                                    Button(
                                        onClick =
                                            ::startRepackedPassiveDlsymTrace,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Начать passive dlsym trace",
                                        )
                                    }
                                    Button(
                                        onClick =
                                            ::startRepackedPassiveJniTrace,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Начать passive JNI trace",
                                        )
                                    }
                                } else if (
                                    current.repackedPassiveTraceSession != null
                                ) {
                                    Text(
                                        "Passive dlsym trace активен · PID " +
                                            current.repackedPassiveTraceSession.pid +
                                            " · session " +
                                            current.repackedPassiveTraceSession
                                                .sessionId,
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        onClick =
                                            ::stopRepackedPassiveDlsymTrace,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Остановить и проверить dlsym trace",
                                        )
                                    }
                                } else {
                                    val jniSession =
                                        requireNotNull(
                                            current.repackedPassiveJniTraceSession,
                                        )
                                    Text(
                                        "Passive JNI trace активен · PID " +
                                            jniSession.pid +
                                            " · session " +
                                            jniSession.sessionId,
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "JNI_OnLoad фиксируется только после фактического вызова исходной функции; " +
                                            "RegisterNatives — только после успешной регистрации.",
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        onClick =
                                            ::stopRepackedPassiveJniTrace,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Остановить и проверить JNI trace",
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = nativeLookupModule,
                                onValueChange = {
                                    nativeLookupModule = it
                                },
                                label = {
                                    Text("Модуль, например libfoo.so")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = nativeLookupSymbol,
                                onValueChange = {
                                    nativeLookupSymbol = it
                                },
                                label = {
                                    Text("Точный dynamic symbol")
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            current.result.elfInventory
                                ?.records
                                ?.firstOrNull {
                                    it.entryPath.substringAfterLast('/') ==
                                        nativeLookupModule.trim()
                                }
                                ?.let { record ->
                                    Text(
                                        "ELF inventory: " +
                                            record.architecture +
                                            " · defined symbols=" +
                                            record.definedDynamicSymbolCount,
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                    record.sampledDefinedSymbols
                                        .take(6)
                                        .forEach { symbol ->
                                            Text(
                                                "• " + symbol,
                                                style =
                                                    MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                }
                            Button(
                                onClick =
                                    ::integrateRepackedNativeLookup,
                                enabled =
                                    !busy &&
                                        current.repackedNativeRuntimeBuild !=
                                        null &&
                                        nativeLookupModule.isNotBlank() &&
                                        nativeLookupSymbol.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Выполнить targeted dlsym probe")
                            }

                            current.result.runtimeEvidence?.let {
                                evidence ->
                                RuntimeEvidenceContract
                                    .observations(evidence)
                                    .filter {
                                        it.kind.name in
                                            setOf(
                                                "JNI_DLSYM_OBSERVED",
                                                "JNI_REGISTER_NATIVE_OBSERVED",
                                                "JNI_ON_LOAD_INVOCATION_OBSERVED",
                                            )
                                    }
                                    .takeLast(8)
                                    .forEach { observation ->
                                        Text(
                                            "• " +
                                                observation.strength.name +
                                                " · " +
                                                observation.summary,
                                            style =
                                                MaterialTheme.typography.bodySmall,
                                        )
                                        observation.blockers.forEach {
                                            blocker ->
                                            Text(
                                                "  ↳ " + blocker,
                                                color =
                                                    MaterialTheme.colorScheme.error,
                                                style =
                                                    MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                            }
                        }
                    }
                }
            }

            current.result.il2cppBinaryBinding?.let { binary ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                "IL2CPP executable binding · raw",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "exact bindings: " + binary.exactBindingCount,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            binary.evidence.forEach { evidence ->
                                Text(
                                    evidence.libraryEntry +
                                        " · machine=" + evidence.machine +
                                        " · ptr=" + evidence.pointerSize,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "CodeRegistration=" +
                                        hex(evidence.codeRegistrationVirtualAddress) +
                                        " · MetadataRegistration=" +
                                        hex(evidence.metadataRegistrationVirtualAddress),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "CodeGenRegister=" +
                                        hex(evidence.codegenRegisterVirtualAddress) +
                                        " · modules=" + evidence.modules.size +
                                        " · bindings=" + evidence.bindings.size,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                evidence.bindings.take(MAX_RAW_BINDINGS).forEach { binding ->
                                    Text(
                                        "• " + binding.managedIdentity +
                                            " · token=0x" +
                                            binding.metadataToken.toString(16) +
                                            " · VA=0x" +
                                            binding.functionVirtualAddress.toString(16) +
                                            (binding.functionFileOffset?.let {
                                                " · file+0x" + it.toString(16)
                                            } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (current.result.engineWarnings.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "Engine diagnostics",
                                fontWeight = FontWeight.SemiBold,
                            )
                            current.result.engineWarnings.forEach {
                                Text(
                                    "• " + it,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hex(value: Long?): String =
    value?.let { "0x" + it.toString(16) } ?: "null"

private const val MAX_RAW_PREVIEW_CHARS = 3_500
private const val MAX_RAW_BINDINGS = 24
private const val MAX_RAW_DEX_RECORDS = 32
