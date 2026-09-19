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
import io.github.ffenuss.modkit.analysis.ExpertLabSession
import io.github.ffenuss.modkit.analysis.ExpertLabSessionController
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.domain.EngineProgress
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

            item {
                Text("Backend routing", fontWeight = FontWeight.SemiBold)
            }

            items(
                current.result.routingPlan.engines,
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
                                "Вставьте снимок /proc/<pid>/maps соответствующего тестового процесса. " +
                                    "Один filename match не считается подтверждением: ModKit сверяет PT_LOAD, file offsets и executable mapping.",
                                style = MaterialTheme.typography.bodySmall,
                            )
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
                                Text("Проверить module mapping и runtime VA")
                            }

                            current.result.runtimeEvidence?.let { runtimeEvidence ->
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
                                    Text(
                                        "loadBias: " +
                                            mapping.loadBias?.let { "0x" + it.toString(16) }
                                                .orEmpty().ifBlank { "null" } +
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
                                runtimeEvidence.addressConfirmations.take(12).forEach { address ->
                                    Text(
                                        "• " + address.targetId +
                                            " · RVA=0x" + address.rva.toString(16) +
                                            " · runtimeVA=0x" +
                                            address.runtimeVirtualAddress.toString(16),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
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
