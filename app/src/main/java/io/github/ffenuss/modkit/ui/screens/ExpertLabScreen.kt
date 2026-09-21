package io.github.ffenuss.modkit.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
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
import io.github.ffenuss.modkit.runtime.RootRuntimePointerScanResult
import io.github.ffenuss.modkit.runtime.RootRuntimeUnknownBaseline
import io.github.ffenuss.modkit.runtime.RootRuntimeUnknownValueCoordinator
import io.github.ffenuss.modkit.runtime.RootRuntimeValueScanCoordinator
import io.github.ffenuss.modkit.runtime.RootRuntimeValueScanResult
import io.github.ffenuss.modkit.runtime.RootRuntimeValueWriteCoordinator
import io.github.ffenuss.modkit.runtime.RuntimeScanAlignment
import io.github.ffenuss.modkit.runtime.RuntimeValueRefinement
import io.github.ffenuss.modkit.runtime.RuntimeValueType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExpertLabScreen(
    onBack: () -> Unit,
    initialTarget: AnalysisTargetDescriptor? = null,
    initialResult: FastAnalysisResult? = null,
) {
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
    var runtimeValueType by remember {
        mutableStateOf(RuntimeValueType.INT32)
    }
    var runtimeScanAlignment by remember {
        mutableStateOf(
            RuntimeScanAlignment.NATURAL,
        )
    }
    var runtimeValueQuery by remember {
        mutableStateOf("")
    }
    var runtimeRefineQuery by remember {
        mutableStateOf("")
    }
    var runtimeValueScan by remember {
        mutableStateOf<RootRuntimeValueScanResult?>(null)
    }
    var runtimeUnknownBaseline by remember {
        mutableStateOf<RootRuntimeUnknownBaseline?>(null)
    }
    var runtimePointerScan by remember {
        mutableStateOf<RootRuntimePointerScanResult?>(null)
    }
    var runtimeWritesEnabled by remember {
        mutableStateOf(false)
    }
    var runtimeWriteValue by remember {
        mutableStateOf("")
    }
    var runtimeWriteMessage by remember {
        mutableStateOf<String?>(null)
    }
    var runtimeFreezeJob by remember {
        mutableStateOf<Job?>(null)
    }
    var runtimeFreezeAddress by remember {
        mutableStateOf<Long?>(null)
    }
    var installReadiness by remember {
        mutableStateOf<RepackedRuntimeInstallReadiness?>(null)
    }
    val installStatus by
        RepackedRuntimeInstallStatusStore.status.collectAsState()

    var showInstalled by remember { mutableStateOf(false) }
    var installedLoading by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<InstalledAppTarget>>(emptyList()) }

    val latestSession by rememberUpdatedState(session)
    val latestFreezeJob by
        rememberUpdatedState(runtimeFreezeJob)
    val latestUnknownBaseline by
        rememberUpdatedState(runtimeUnknownBaseline)
    DisposableEffect(Unit) {
        onDispose {
            cancellation?.cancel()
            latestFreezeJob?.cancel()
            RootRuntimeUnknownValueCoordinator
                .deleteBaseline(
                    latestUnknownBaseline,
                )
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
                runtimeFreezeJob?.cancel()
                runtimeFreezeJob = null
                runtimeFreezeAddress = null
                RootRuntimeUnknownValueCoordinator
                    .deleteBaseline(
                        runtimeUnknownBaseline,
                    )
                runtimeUnknownBaseline = null
                session?.close()
                session = opened
                runtimeValueScan = null
                runtimePointerScan = null
                runtimeValueQuery = ""
                runtimeRefineQuery = ""
                runtimeWriteValue = ""
                runtimeWriteMessage = null
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
                runtimeFreezeJob?.cancel()
                runtimeFreezeJob = null
                runtimeFreezeAddress = null
                RootRuntimeUnknownValueCoordinator
                    .deleteBaseline(
                        runtimeUnknownBaseline,
                    )
                runtimeUnknownBaseline = null
                session?.close()
                session = opened
                runtimeValueScan = null
                runtimePointerScan = null
                runtimeValueQuery = ""
                runtimeRefineQuery = ""
                runtimeWriteValue = ""
                runtimeWriteMessage = null
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

    LaunchedEffect(
        initialTarget,
        initialResult?.index?.artifactSha256,
    ) {
        if (
            session != null ||
            initialTarget == null ||
            initialResult == null
        ) {
            return@LaunchedEffect
        }
        val installedTarget =
            initialTarget as?
                AnalysisTargetDescriptor
                    .InstalledPackage
                ?: return@LaunchedEffect
        val signal =
            beginOperation(
                "expert.current-target",
            ) ?: return@LaunchedEffect
        try {
            session =
                ExpertLabSessionController
                    .openExistingInstalled(
                        context = appContext,
                        target =
                            installedTarget,
                        result =
                            initialResult,
                        cancellation =
                            signal,
                        progress =
                            ProgressSink {
                                update ->
                                scope.launch {
                                    progress =
                                        update
                                }
                            },
                    )
        } catch (_: AnalysisCancelledException) {
            error =
                "Открытие текущей цели в Expert Lab отменено."
        } catch (failure: Throwable) {
            error =
                failure.message
                    ?: failure.javaClass
                        .simpleName
        } finally {
            finishOperation()
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

    fun captureUnknownRuntimeBaseline() {
        val current = session ?: return
        val packageName =
            current.packageName
                ?: run {
                    error =
                        "Unknown-value scan доступен только для установленного приложения."
                    return
                }
        val signal =
            beginOperation(
                "runtime.root-unknown-baseline",
            ) ?: return

        scope.launch {
            try {
                RootRuntimeUnknownValueCoordinator
                    .deleteBaseline(
                        runtimeUnknownBaseline,
                    )
                val snapshotFile =
                    File(
                        appContext.cacheDir,
                        "runtime-unknown/" +
                            packageName
                                .replace(
                                    Regex(
                                        "[^A-Za-z0-9._-]",
                                    ),
                                    "_",
                                ) +
                            "-" +
                            System.currentTimeMillis() +
                            ".bin",
                    )
                runtimeUnknownBaseline =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeUnknownValueCoordinator
                            .captureBaseline(
                                packageName =
                                    packageName,
                                valueType =
                                    runtimeValueType,
                                alignment =
                                    runtimeScanAlignment,
                                snapshotFile =
                                    snapshotFile,
                                cancellation =
                                    signal,
                            )
                    }
                runtimeValueScan = null
                runtimePointerScan = null
            } catch (_: AnalysisCancelledException) {
                error =
                    "Создание unknown-value baseline отменено."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun compareUnknownRuntimeBaseline(
        refinement:
            RuntimeValueRefinement,
    ) {
        val baseline =
            runtimeUnknownBaseline
                ?: return
        val signal =
            beginOperation(
                "runtime.root-unknown-compare",
            ) ?: return

        scope.launch {
            try {
                runtimePointerScan = null
                runtimeValueScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeUnknownValueCoordinator
                            .compareBaseline(
                                baseline =
                                    baseline,
                                refinement =
                                    refinement,
                                cancellation =
                                    signal,
                            )
                    }
                RootRuntimeUnknownValueCoordinator
                    .deleteBaseline(
                        baseline,
                    )
                runtimeUnknownBaseline =
                    null
            } catch (_: AnalysisCancelledException) {
                error =
                    "Unknown-value сравнение отменено."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun scanRootRuntimeValue() {
        val current = session ?: return
        val packageName =
            current.packageName
                ?: run {
                    error =
                        "Поиск памяти доступен только для установленного приложения."
                    return
                }
        val signal =
            beginOperation(
                "runtime.root-value-scan",
            ) ?: return

        scope.launch {
            try {
                runtimePointerScan = null
                runtimeValueScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeValueScanCoordinator
                            .scanExact(
                                packageName =
                                    packageName,
                                valueType =
                                    runtimeValueType,
                                query =
                                    runtimeValueQuery,
                                cancellation =
                                    signal,
                                alignment =
                                    runtimeScanAlignment,
                            )
                    }
            } catch (_: AnalysisCancelledException) {
                error =
                    "Root-поиск значений отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun refineRootRuntimeValueExact() {
        val previous =
            runtimeValueScan ?: return
        val signal =
            beginOperation(
                "runtime.root-value-refine-exact",
            ) ?: return

        scope.launch {
            try {
                runtimeValueScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeValueScanCoordinator
                            .refineExact(
                                previous =
                                    previous,
                                query =
                                    runtimeRefineQuery,
                                cancellation =
                                    signal,
                            )
                    }
            } catch (_: AnalysisCancelledException) {
                error =
                    "Фильтрация по новому значению отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun refreshRootRuntimeValues() {
        val previous =
            runtimeValueScan ?: return
        val signal =
            beginOperation(
                "runtime.root-value-refresh",
            ) ?: return

        scope.launch {
            try {
                runtimeValueScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeValueScanCoordinator
                            .refresh(
                                previous =
                                    previous,
                                cancellation =
                                    signal,
                            )
                    }
            } catch (_: AnalysisCancelledException) {
                error =
                    "Обновление runtime-значений отменено."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun refineRootRuntimeValue(
        refinement: RuntimeValueRefinement,
    ) {
        val previous =
            runtimeValueScan ?: return
        val signal =
            beginOperation(
                "runtime.root-value-refine",
            ) ?: return

        scope.launch {
            try {
                runtimeValueScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeValueScanCoordinator
                            .refine(
                                previous =
                                    previous,
                                refinement =
                                    refinement,
                                cancellation =
                                    signal,
                            )
                    }
            } catch (_: AnalysisCancelledException) {
                error =
                    "Фильтрация runtime-значений отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun writeRootRuntimeHit(
        address: Long,
    ) {
        val previous =
            runtimeValueScan ?: return
        if (!runtimeWritesEnabled) {
            error =
                "Сначала явно включите запись runtime-значений."
            return
        }
        val signal =
            beginOperation(
                "runtime.root-value-write",
            ) ?: return

        scope.launch {
            try {
                val result =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeValueWriteCoordinator
                            .writeHit(
                                previous =
                                    previous,
                                address = address,
                                valueText =
                                    runtimeWriteValue,
                                cancellation =
                                    signal,
                            )
                    }
                runtimeValueScan =
                    result.updatedScan
                runtimeWriteMessage =
                    "0x" +
                        address.toString(16) +
                        ": " +
                        result.oldValue +
                        " → " +
                        result.newValue +
                        " · read-back verified"
            } catch (_: AnalysisCancelledException) {
                error =
                    "Root-запись значения отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                finishOperation()
            }
        }
    }

    fun toggleFreezeRootRuntimeHit(
        address: Long,
    ) {
        if (
            runtimeFreezeAddress ==
            address
        ) {
            runtimeFreezeJob?.cancel()
            runtimeFreezeJob = null
            runtimeFreezeAddress = null
            runtimeWriteMessage =
                "Freeze остановлен для 0x" +
                    address.toString(16)
            return
        }

        val initialScan =
            runtimeValueScan ?: return
        if (
            !runtimeWritesEnabled ||
            runtimeWriteValue.isBlank()
        ) {
            error =
                "Для freeze сначала включите запись и задайте значение."
            return
        }

        runtimeFreezeJob?.cancel()
        val frozenValue =
            runtimeWriteValue
        runtimeFreezeAddress =
            address
        runtimeFreezeJob =
            scope.launch {
                var currentScan =
                    initialScan
                while (isActive) {
                    try {
                        val signal =
                            AtomicCancellationSignal()
                        val result =
                            withContext(
                                Dispatchers.IO,
                            ) {
                                RootRuntimeValueWriteCoordinator
                                    .writeHit(
                                        previous =
                                            currentScan,
                                        address =
                                            address,
                                        valueText =
                                            frozenValue,
                                        cancellation =
                                            signal,
                                    )
                            }
                        currentScan =
                            result.updatedScan
                        runtimeValueScan =
                            result.updatedScan
                        runtimeWriteMessage =
                            "Freeze 0x" +
                                address.toString(16) +
                                " = " +
                                result.newValue +
                                " · verified"
                    } catch (
                        failure:
                            AnalysisCancelledException,
                    ) {
                        break
                    } catch (failure: Throwable) {
                        error =
                            "Freeze остановлен: " +
                                (
                                    failure.message
                                        ?: failure
                                            .javaClass
                                            .simpleName
                                    )
                        break
                    }
                    delay(750L)
                }
                if (
                    runtimeFreezeAddress ==
                    address
                ) {
                    runtimeFreezeAddress =
                        null
                    runtimeFreezeJob =
                        null
                }
            }
    }

    fun findPointersToRuntimeAddress(
        address: Long,
        depth: Int = 1,
    ) {
        val valueScan =
            runtimeValueScan ?: return
        val signal =
            beginOperation(
                "runtime.root-pointer-scan",
            ) ?: return

        scope.launch {
            try {
                runtimePointerScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeValueScanCoordinator
                            .findPointersTo(
                                packageName =
                                    valueScan.packageName,
                                expectedPid =
                                    valueScan.pid,
                                targetAddress =
                                    address,
                                depth = depth,
                                cancellation =
                                    signal,
                            )
                    }
            } catch (_: AnalysisCancelledException) {
                error =
                    "Pointer scan отменён."
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

                                Text(
                                    "Live Memory Scanner",
                                    fontWeight =
                                        FontWeight.SemiBold,
                                )
                                Text(
                                    "Поиск значений в private readable+writable памяти процесса. " +
                                        "По умолчанию это анализ; запись включается отдельно и только вручную. " +
                                        "Первый проход может искать точное или неизвестное значение, после чего " +
                                        "результаты уточняются без повторного полного сканирования.",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(
                                    onClick = {
                                        RootRuntimeUnknownValueCoordinator
                                            .deleteBaseline(
                                                runtimeUnknownBaseline,
                                            )
                                        runtimeUnknownBaseline =
                                            null
                                        runtimeValueType =
                                            runtimeValueType.next()
                                        runtimeValueScan = null
                                        runtimePointerScan = null
                                    },
                                    enabled = !busy,
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Тип значения: " +
                                            runtimeValueType.title +
                                            " · нажмите для смены",
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        RootRuntimeUnknownValueCoordinator
                                            .deleteBaseline(
                                                runtimeUnknownBaseline,
                                            )
                                        runtimeUnknownBaseline =
                                            null
                                        runtimeScanAlignment =
                                            runtimeScanAlignment
                                                .next()
                                        runtimeValueScan =
                                            null
                                        runtimePointerScan =
                                            null
                                    },
                                    enabled = !busy,
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Выравнивание: " +
                                            runtimeScanAlignment
                                                .title +
                                            " · нажмите для смены",
                                    )
                                }
                                Text(
                                    "Неизвестное начальное значение",
                                    fontWeight =
                                        FontWeight.SemiBold,
                                )
                                Text(
                                    "Если число заранее неизвестно, ModKit сохраняет bounded baseline " +
                                        "в приватный cache-файл. После изменения значения в игре " +
                                        "выберите, как оно изменилось; в память UI попадут только совпавшие адреса.",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                )
                                if (
                                    runtimeUnknownBaseline ==
                                    null
                                ) {
                                    OutlinedButton(
                                        onClick =
                                            ::captureUnknownRuntimeBaseline,
                                        enabled = !busy,
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Создать baseline неизвестного значения",
                                        )
                                    }
                                } else {
                                    val unknown =
                                        requireNotNull(
                                            runtimeUnknownBaseline,
                                        )
                                    Text(
                                        "Baseline: PID " +
                                            unknown.pid +
                                            " · " +
                                            (
                                                unknown
                                                    .capturedBytes /
                                                    (1024L * 1024L)
                                                ) +
                                            " MiB · сегментов " +
                                            unknown.segments
                                                .size +
                                            (
                                                if (
                                                    unknown
                                                        .truncatedByByteLimit
                                                ) {
                                                    " · достигнут лимит"
                                                } else {
                                                    ""
                                                }
                                                ),
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                    RuntimeValueRefinement
                                        .entries
                                        .forEach {
                                            refinement ->
                                            OutlinedButton(
                                                onClick = {
                                                    compareUnknownRuntimeBaseline(
                                                        refinement,
                                                    )
                                                },
                                                enabled = !busy,
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth(),
                                            ) {
                                                Text(
                                                    "После изменения: " +
                                                        refinement
                                                            .title,
                                                )
                                            }
                                        }
                                    OutlinedButton(
                                        onClick = {
                                            RootRuntimeUnknownValueCoordinator
                                                .deleteBaseline(
                                                    runtimeUnknownBaseline,
                                                )
                                            runtimeUnknownBaseline =
                                                null
                                        },
                                        enabled = !busy,
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Удалить baseline",
                                        )
                                    }
                                }

                                Text(
                                    "Известное точное значение",
                                    fontWeight =
                                        FontWeight.SemiBold,
                                )
                                OutlinedTextField(
                                    value =
                                        runtimeValueQuery,
                                    onValueChange = {
                                        runtimeValueQuery = it
                                        runtimeValueScan = null
                                        runtimePointerScan = null
                                    },
                                    label = {
                                        Text(
                                            "Точное значение",
                                        )
                                    },
                                    supportingText = {
                                        Text(
                                            "Например: 100, 9999, 2.5",
                                        )
                                    },
                                    singleLine = true,
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick =
                                        ::scanRootRuntimeValue,
                                    enabled =
                                        !busy &&
                                            runtimeValueQuery
                                                .isNotBlank(),
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Найти значение в процессе",
                                    )
                                }

                                runtimeValueScan?.let {
                                        scan ->
                                    Text(
                                        "PID " +
                                            scan.pid +
                                            " · найдено " +
                                            scan.snapshot
                                                .hits.size +
                                            " · просканировано " +
                                            (
                                                scan.snapshot
                                                    .scannedBytes /
                                                    (1024L * 1024L)
                                                ) +
                                            " MiB · регионов " +
                                            scan.snapshot
                                                .scannedRegions,
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                    if (
                                        scan.snapshot
                                            .truncatedByHitLimit
                                    ) {
                                        Text(
                                            "Результаты ограничены лимитом найденных адресов; уточните значение или используйте фильтрацию.",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                    }
                                    if (
                                        scan.snapshot
                                            .truncatedByByteLimit
                                    ) {
                                        Text(
                                            "Первый проход остановлен на лимите объёма памяти для одного сканирования.",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                    }

                                    OutlinedButton(
                                        onClick =
                                            ::refreshRootRuntimeValues,
                                        enabled =
                                            !busy &&
                                                scan.snapshot
                                                    .hits
                                                    .isNotEmpty(),
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Обновить текущие значения",
                                        )
                                    }

                                    OutlinedTextField(
                                        value =
                                            runtimeRefineQuery,
                                        onValueChange = {
                                            runtimeRefineQuery = it
                                        },
                                        label = {
                                            Text(
                                                "Новое точное значение",
                                            )
                                        },
                                        supportingText = {
                                            Text(
                                                "После изменения значения в игре можно оставить только адреса, равные этому числу.",
                                            )
                                        },
                                        singleLine = true,
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                    )
                                    OutlinedButton(
                                        onClick =
                                            ::refineRootRuntimeValueExact,
                                        enabled =
                                            !busy &&
                                                runtimeRefineQuery
                                                    .isNotBlank() &&
                                                scan.snapshot
                                                    .hits
                                                    .isNotEmpty(),
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Фильтр: равно новому значению",
                                        )
                                    }

                                    RuntimeValueRefinement
                                        .entries
                                        .forEach {
                                            refinement ->
                                            OutlinedButton(
                                                onClick = {
                                                    refineRootRuntimeValue(
                                                        refinement,
                                                    )
                                                },
                                                enabled =
                                                    !busy &&
                                                        scan.snapshot
                                                            .hits
                                                            .isNotEmpty(),
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth(),
                                            ) {
                                                Text(
                                                    "Фильтр: " +
                                                        refinement
                                                            .title,
                                                )
                                            }
                                        }

                                    Row(
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                    ) {
                                        Checkbox(
                                            checked =
                                                runtimeWritesEnabled,
                                            onCheckedChange = {
                                                enabled ->
                                                runtimeWritesEnabled =
                                                    enabled
                                                runtimeWriteMessage =
                                                    null
                                                if (!enabled) {
                                                    runtimeFreezeJob
                                                        ?.cancel()
                                                    runtimeFreezeJob =
                                                        null
                                                    runtimeFreezeAddress =
                                                        null
                                                    runtimeWriteValue =
                                                        ""
                                                }
                                            },
                                        )
                                        Column {
                                            Text(
                                                "Разрешить ручную запись найденного runtime-значения",
                                            )
                                            Text(
                                                "Запись выполняется только по явному нажатию, " +
                                                    "после повторной проверки PID, writable mapping и read-back.",
                                                style =
                                                    MaterialTheme.typography
                                                        .bodySmall,
                                            )
                                        }
                                    }
                                    if (
                                        runtimeWritesEnabled
                                    ) {
                                        OutlinedTextField(
                                            value =
                                                runtimeWriteValue,
                                            onValueChange = {
                                                runtimeWriteValue =
                                                    it
                                                runtimeWriteMessage =
                                                    null
                                            },
                                            label = {
                                                Text(
                                                    "Новое значение",
                                                )
                                            },
                                            singleLine = true,
                                            modifier =
                                                Modifier.fillMaxWidth(),
                                        )
                                    }
                                    runtimeWriteMessage
                                        ?.let {
                                            message ->
                                            Text(
                                                message,
                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall,
                                            )
                                        }

                                    scan.snapshot.hits
                                        .take(24)
                                        .forEachIndexed {
                                                index,
                                                hit,
                                            ->
                                            Column(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth(),
                                                verticalArrangement =
                                                    Arrangement
                                                        .spacedBy(
                                                            2.dp,
                                                        ),
                                            ) {
                                                Text(
                                                    (
                                                        index + 1
                                                        ).toString() +
                                                        ". 0x" +
                                                        hit.address
                                                            .toString(16) +
                                                        " = " +
                                                        hit.displayValue(
                                                            scan.snapshot
                                                                .valueType,
                                                        ) +
                                                        " · map+0x" +
                                                        hit.offsetInRegion
                                                            .toString(16) +
                                                        " · file+0x" +
                                                        hit.mappedFileOffset
                                                            .toString(16) +
                                                        (
                                                            hit.regionPath
                                                                ?.let {
                                                                    " · " +
                                                                        it
                                                                }
                                                                .orEmpty()
                                                            ),
                                                    style =
                                                        MaterialTheme
                                                            .typography
                                                            .bodySmall,
                                                )
                                                if (
                                                    runtimeWritesEnabled &&
                                                    runtimeWriteValue
                                                        .isNotBlank() &&
                                                    index < 8
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            writeRootRuntimeHit(
                                                                hit.address,
                                                            )
                                                        },
                                                        enabled =
                                                            !busy,
                                                    ) {
                                                        Text(
                                                            "Записать новое значение",
                                                        )
                                                    }
                                                }
                                                if (
                                                    runtimeWritesEnabled &&
                                                    runtimeWriteValue
                                                        .isNotBlank() &&
                                                    index < 8
                                                ) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            toggleFreezeRootRuntimeHit(
                                                                hit.address,
                                                            )
                                                        },
                                                        enabled =
                                                            !busy,
                                                    ) {
                                                        Text(
                                                            if (
                                                                runtimeFreezeAddress ==
                                                                hit.address
                                                            ) {
                                                                "Остановить freeze"
                                                            } else {
                                                                "Freeze этого значения"
                                                            },
                                                        )
                                                    }
                                                }
                                                if (index < 8) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            findPointersToRuntimeAddress(
                                                                hit.address,
                                                                1,
                                                            )
                                                        },
                                                        enabled =
                                                            !busy,
                                                    ) {
                                                        Text(
                                                            "Найти указатели на этот адрес",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    if (
                                        scan.snapshot.hits
                                            .size > 24
                                    ) {
                                        Text(
                                            "Показаны первые 24 адреса из " +
                                                scan.snapshot
                                                    .hits.size +
                                                ".",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                    }

                                    runtimePointerScan
                                        ?.let {
                                            pointer ->
                                            Text(
                                                "Pointer scan · уровень " +
                                                    pointer.depth +
                                                    " · target 0x" +
                                                    pointer
                                                        .targetAddress
                                                        .toString(16) +
                                                    " · найдено " +
                                                    pointer.snapshot
                                                        .hits.size,
                                                fontWeight =
                                                    FontWeight.SemiBold,
                                            )
                                            pointer.snapshot.hits
                                                .take(12)
                                                .forEachIndexed {
                                                        pointerIndex,
                                                        pointerHit,
                                                    ->
                                                    Column(
                                                        modifier =
                                                            Modifier
                                                                .fillMaxWidth(),
                                                        verticalArrangement =
                                                            Arrangement
                                                                .spacedBy(
                                                                    2.dp,
                                                                ),
                                                    ) {
                                                        Text(
                                                            (
                                                                pointerIndex +
                                                                    1
                                                                ).toString() +
                                                                ". ptr 0x" +
                                                                pointerHit
                                                                    .address
                                                                    .toString(
                                                                        16,
                                                                    ) +
                                                                " → 0x" +
                                                                pointer
                                                                    .targetAddress
                                                                    .toString(
                                                                        16,
                                                                    ) +
                                                                (
                                                                    pointerHit
                                                                        .regionPath
                                                                        ?.let {
                                                                            " · " +
                                                                                it
                                                                        }
                                                                        .orEmpty()
                                                                    ),
                                                            style =
                                                                MaterialTheme
                                                                    .typography
                                                                    .bodySmall,
                                                        )
                                                        if (
                                                            pointer.depth <
                                                                4 &&
                                                            pointerIndex <
                                                                6
                                                        ) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    findPointersToRuntimeAddress(
                                                                        pointerHit
                                                                            .address,
                                                                        pointer
                                                                            .depth +
                                                                            1,
                                                                    )
                                                                },
                                                                enabled =
                                                                    !busy,
                                                            ) {
                                                                Text(
                                                                    "Искать уровень " +
                                                                        (
                                                                            pointer
                                                                                .depth +
                                                                                1
                                                                            ),
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                        }
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
