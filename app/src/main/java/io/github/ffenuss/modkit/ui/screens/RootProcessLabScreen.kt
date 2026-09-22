package io.github.ffenuss.modkit.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.patch.RootModDiscoveryCoordinator
import io.github.ffenuss.modkit.patch.RootModDiscoveryResult
import io.github.ffenuss.modkit.patch.RootModProfileWriter
import io.github.ffenuss.modkit.runtime.RootAccessProbeResult
import io.github.ffenuss.modkit.runtime.RootInstalledGameOverlayCoordinator
import io.github.ffenuss.modkit.runtime.RootProcessDiscovery
import io.github.ffenuss.modkit.runtime.RootProcessMemoryDumpCoordinator
import io.github.ffenuss.modkit.runtime.RootProcessMemoryDumpProgress
import io.github.ffenuss.modkit.runtime.RootProcessMemoryDumpResult
import io.github.ffenuss.modkit.runtime.RootProcessOverlayLauncher
import io.github.ffenuss.modkit.runtime.RootRunningAppProcess
import io.github.ffenuss.modkit.runtime.RootRuntimeCaptureCoordinator
import io.github.ffenuss.modkit.runtime.RootRuntimePointerScanResult
import io.github.ffenuss.modkit.runtime.RootRuntimeUnknownBaseline
import io.github.ffenuss.modkit.runtime.RootRuntimeUnknownValueCoordinator
import io.github.ffenuss.modkit.runtime.RootRuntimeValueScanCoordinator
import io.github.ffenuss.modkit.runtime.RootRuntimeValueScanResult
import io.github.ffenuss.modkit.runtime.RootRuntimeValueWriteCoordinator
import io.github.ffenuss.modkit.runtime.RuntimeScanAlignment
import io.github.ffenuss.modkit.runtime.RuntimeValueRefinement
import io.github.ffenuss.modkit.runtime.RuntimeValueScanner
import io.github.ffenuss.modkit.runtime.RuntimeValueType
import io.github.ffenuss.modkit.sandbox.RootSandboxLaunchCoordinator
import io.github.ffenuss.modkit.sandbox.RootSandboxRunningSession
import io.github.ffenuss.modkit.sandbox.SandboxProfileStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RootProcessUiItem(
    val process: RootRunningAppProcess,
    val app: InstalledAppTarget?,
) {
    val label: String
        get() =
            app?.label
                ?: process.packageName
    val isGame: Boolean
        get() =
            app?.isGame == true
}

@Composable
fun RootProcessLabScreen(
    onBack: () -> Unit,
    initialRootProbe: RootAccessProbeResult? = null,
    initialPackageName: String? = null,
) {
    val context =
        LocalContext.current
    val appContext =
        context.applicationContext
    val scope =
        rememberCoroutineScope()
    val repository =
        remember {
            InstalledAppRepository(
                appContext,
            )
        }
    val sandboxProfileStore =
        remember {
            SandboxProfileStore(
                appContext,
            )
        }

    var rootProbe by remember {
        mutableStateOf(
            initialRootProbe,
        )
    }
    var rootChecking by remember {
        mutableStateOf(false)
    }
    var processesLoading by remember {
        mutableStateOf(false)
    }
    var processItems by remember {
        mutableStateOf<
            List<RootProcessUiItem>
        >(emptyList())
    }
    var launchableApps by remember {
        mutableStateOf<
            List<InstalledAppTarget>
        >(emptyList())
    }
    var processQuery by remember {
        mutableStateOf(
            initialPackageName
                .orEmpty(),
        )
    }
    var showAppsOnly by remember {
        mutableStateOf(false)
    }
    var selected by remember {
        mutableStateOf<
            RootProcessUiItem?
        >(null)
    }
    var attachedPid by remember {
        mutableStateOf<Int?>(null)
    }
    var attachedMapsCount by remember {
        mutableStateOf(0)
    }
    var busy by remember {
        mutableStateOf(false)
    }
    var busyLabel by remember {
        mutableStateOf<String?>(null)
    }
    var error by remember {
        mutableStateOf<String?>(null)
    }
    var cancellation by remember {
        mutableStateOf<
            AtomicCancellationSignal?
        >(null)
    }

    var dumpResult by remember {
        mutableStateOf<
            RootProcessMemoryDumpResult?
        >(null)
    }
    var dumpProgress by remember {
        mutableStateOf<
            RootProcessMemoryDumpProgress?
        >(null)
    }
    var fullDumpMode by remember {
        mutableStateOf(false)
    }

    var modDiscovery by remember {
        mutableStateOf<
            RootModDiscoveryResult?
        >(null)
    }
    var modScanBusy by remember {
        mutableStateOf(false)
    }
    var modScanProgress by remember {
        mutableStateOf<
            EngineProgress?
        >(null)
    }
    var modScanError by remember {
        mutableStateOf<String?>(null)
    }
    var modScanSignal by remember {
        mutableStateOf<
            AtomicCancellationSignal?
        >(null)
    }
    var selectedModIds by remember {
        mutableStateOf<Set<String>>(
            emptySet(),
        )
    }
    var pendingDumpSave by remember {
        mutableStateOf<File?>(null)
    }
    var pendingProfileText by remember {
        mutableStateOf<String?>(null)
    }
    var saveMessage by remember {
        mutableStateOf<String?>(null)
    }
    var sandboxLaunchBusy by remember {
        mutableStateOf(false)
    }
    var sandboxSession by remember {
        mutableStateOf<RootSandboxRunningSession?>(null)
    }

    var valueType by remember {
        mutableStateOf(
            RuntimeValueType.INT32,
        )
    }
    var alignment by remember {
        mutableStateOf(
            RuntimeScanAlignment.NATURAL,
        )
    }
    var exactQuery by remember {
        mutableStateOf("")
    }
    var refineExactQuery by remember {
        mutableStateOf("")
    }
    var valueScan by remember {
        mutableStateOf<
            RootRuntimeValueScanResult?
        >(null)
    }
    var unknownBaseline by remember {
        mutableStateOf<
            RootRuntimeUnknownBaseline?
        >(null)
    }
    var pointerScan by remember {
        mutableStateOf<
            RootRuntimePointerScanResult?
        >(null)
    }
    var writesEnabled by remember {
        mutableStateOf(false)
    }
    var writeValue by remember {
        mutableStateOf("")
    }
    var writeMessage by remember {
        mutableStateOf<String?>(null)
    }
    var freezeJob by remember {
        mutableStateOf<Job?>(null)
    }
    var freezeAddress by remember {
        mutableStateOf<Long?>(null)
    }

    val dumpSaveLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts
                .CreateDocument(
                    "application/zip",
                ),
        ) {
            uri ->
            val source =
                pendingDumpSave
            pendingDumpSave = null
            if (
                uri != null &&
                source != null
            ) {
                scope.launch {
                    val saved =
                        runCatching {
                            withContext(
                                Dispatchers.IO,
                            ) {
                                require(
                                    source.isFile,
                                ) {
                                    "Runtime dump больше недоступен."
                                }
                                appContext
                                    .contentResolver
                                    .openOutputStream(
                                        uri,
                                        "w",
                                    )
                                    ?.use {
                                        output ->
                                        source
                                            .inputStream()
                                            .use {
                                                input ->
                                                input.copyTo(
                                                    output,
                                                    1024 *
                                                        1024,
                                                )
                                            }
                                    }
                                    ?: error(
                                        "Не удалось открыть выбранный файл для записи.",
                                    )
                            }
                        }
                    saveMessage =
                        if (
                            saved.isSuccess
                        ) {
                            "Runtime dump сохранён на устройство."
                        } else {
                            "Не удалось сохранить runtime dump: " +
                                (
                                    saved.exceptionOrNull()
                                        ?.message
                                        ?: "ошибка записи"
                                    )
                        }
                }
            }
        }

    val profileSaveLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts
                .CreateDocument(
                    RootModProfileWriter
                        .MIME_TYPE,
                ),
        ) {
            uri ->
            val text =
                pendingProfileText
            pendingProfileText = null
            if (
                uri != null &&
                text != null
            ) {
                scope.launch {
                    val saved =
                        runCatching {
                            withContext(
                                Dispatchers.IO,
                            ) {
                                appContext
                                    .contentResolver
                                    .openOutputStream(
                                        uri,
                                        "w",
                                    )
                                    ?.bufferedWriter(
                                        Charsets.UTF_8,
                                    )
                                    ?.use {
                                        writer ->
                                        writer.write(
                                            text,
                                        )
                                    }
                                    ?: error(
                                        "Не удалось открыть выбранный файл для записи.",
                                    )
                            }
                        }
                    saveMessage =
                        if (
                            saved.isSuccess
                        ) {
                            "Профиль модификаций сохранён на устройство."
                        } else {
                            "Не удалось сохранить профиль: " +
                                (
                                    saved.exceptionOrNull()
                                        ?.message
                                        ?: "ошибка записи"
                                    )
                        }
                }
            }
        }

    fun begin(
        label: String,
    ): AtomicCancellationSignal? {
        if (busy) {
            return null
        }
        val signal =
            AtomicCancellationSignal()
        cancellation = signal
        busy = true
        busyLabel = label
        error = null
        return signal
    }

    fun finish() {
        busy = false
        busyLabel = null
        cancellation = null
    }

    fun clearRuntimeState() {
        freezeJob?.cancel()
        freezeJob = null
        freezeAddress = null
        RootRuntimeUnknownValueCoordinator
            .deleteBaseline(
                unknownBaseline,
            )
        unknownBaseline = null
        valueScan = null
        pointerScan = null
        dumpResult = null
        dumpProgress = null
        modScanSignal?.cancel()
        modScanSignal = null
        modScanBusy = false
        modScanProgress = null
        modScanError = null
        modDiscovery = null
        selectedModIds = emptySet()
        writesEnabled = false
        writeValue = ""
        writeMessage = null
    }

    DisposableEffect(Unit) {
        onDispose {
            cancellation?.cancel()
            modScanSignal?.cancel()
            freezeJob?.cancel()
            RootRuntimeUnknownValueCoordinator
                .deleteBaseline(
                    unknownBaseline,
                )
        }
    }

    fun checkRoot() {
        val signal =
            begin(
                "Проверка root",
            ) ?: return
        rootChecking = true
        scope.launch {
            try {
                rootProbe =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootProcessDiscovery
                            .probe(
                                cancellation =
                                    signal,
                            )
                    }
            } catch (_: AnalysisCancelledException) {
                error =
                    "Проверка root отменена."
            } catch (failure: Throwable) {
                rootProbe =
                    RootAccessProbeResult(
                        available = false,
                        uid = null,
                        message =
                            failure.message
                                ?: failure
                                    .javaClass
                                    .simpleName,
                    )
            } finally {
                rootChecking = false
                finish()
            }
        }
    }

    fun loadProcesses() {
        val signal =
            begin(
                "Список процессов",
            ) ?: return
        processesLoading = true
        scope.launch {
            try {
                val snapshot =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        val running =
                            RootProcessDiscovery
                                .listMainAppProcesses(
                                    cancellation =
                                        signal,
                                )
                        val installedList =
                            repository.load()
                        Triple(
                            running,
                            installedList
                                .associateBy {
                                    it.packageName
                                },
                            installedList,
                        )
                    }
                rootProbe =
                    RootAccessProbeResult(
                        available = true,
                        uid = 0,
                        message =
                            "Root подтверждён: uid=0.",
                    )
                processItems =
                    snapshot.first
                        .map {
                            process ->
                            RootProcessUiItem(
                                process =
                                    process,
                                app =
                                    snapshot.second[
                                        process
                                            .packageName
                                    ],
                            )
                        }
                        .filter {
                            it.app != null
                        }
                        .sortedWith(
                            compareByDescending<
                                RootProcessUiItem
                            > {
                                it.isGame
                            }.thenBy(
                                String.CASE_INSENSITIVE_ORDER,
                            ) {
                                it.label
                            },
                        )
                val runningPackages =
                    snapshot.first
                        .map {
                            it.packageName
                        }
                        .toSet()
                launchableApps =
                    snapshot.third
                        .asSequence()
                        .filter {
                            !it.isSystemApp &&
                                it.hasLauncherActivity &&
                                it.packageName !in
                                runningPackages
                        }
                        .sortedWith(
                            compareByDescending<
                                InstalledAppTarget
                            > {
                                it.isGame
                            }.thenBy(
                                String.CASE_INSENSITIVE_ORDER,
                            ) {
                                it.label
                            },
                        )
                        .toList()
            } catch (_: AnalysisCancelledException) {
                error =
                    "Получение процессов отменено."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                processesLoading =
                    false
                finish()
            }
        }
    }

    fun discoverMods(
        item: RootProcessUiItem,
    ) {
        val app =
            item.app
                ?: run {
                    modScanError =
                        "Для процесса не найден установленный APK."
                    return
                }
        if (modScanBusy) {
            return
        }

        val signal =
            AtomicCancellationSignal()
        modScanSignal = signal
        modScanBusy = true
        modScanProgress = null
        modScanError = null
        modDiscovery = null
        selectedModIds = emptySet()

        scope.launch {
            try {
                val discovered =
                    RootModDiscoveryCoordinator
                        .discover(
                            context =
                                appContext,
                            app =
                                app,
                            cancellation =
                                signal,
                            progress =
                                ProgressSink {
                                    update ->
                                    scope.launch {
                                        modScanProgress =
                                            update
                                    }
                                },
                        )
                val currentCapture =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeCaptureCoordinator
                            .captureMaps(
                                packageName =
                                    item.process
                                        .packageName,
                                cancellation =
                                    signal,
                                expectedPid =
                                    item.process.pid,
                            )
                    }
                require(
                    currentCapture.pid ==
                        attachedPid
                ) {
                    "Процесс перезапустился во время поиска модификаций."
                }
                modDiscovery =
                    discovered
                saveMessage =
                    if (
                        discovered.opportunities
                            .any { it.selectable }
                    ) {
                        "Моды найдены. Выберите нужные и нажмите «Запустить игру с выбранными модами»."
                    } else {
                        saveMessage
                    }
            } catch (_: AnalysisCancelledException) {
                modScanError =
                    "Поиск модификаций отменён."
            } catch (failure: Throwable) {
                modScanError =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                modScanBusy = false
                modScanSignal = null
            }
        }
    }

    fun attach(
        item: RootProcessUiItem,
    ) {
        val signal =
            begin(
                "Подключение к процессу",
            ) ?: return
        scope.launch {
            try {
                val capture =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeCaptureCoordinator
                            .captureMaps(
                                packageName =
                                    item.process
                                        .packageName,
                                cancellation =
                                    signal,
                                expectedPid =
                                    item.process.pid,
                            )
                    }
                require(
                    capture.pid ==
                        item.process.pid,
                ) {
                    "PID процесса изменился. Обновите список процессов."
                }
                val overlay =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootProcessOverlayLauncher
                            .startAndBringToFront(
                                packageName =
                                    item.process
                                        .packageName,
                                pid =
                                    capture.pid,
                                label =
                                    item.label,
                                cancellation =
                                    signal,
                            )
                    }
                clearRuntimeState()
                selected = item
                attachedPid =
                    capture.pid
                attachedMapsCount =
                    capture.capture.text
                        .lineSequence()
                        .count {
                            it.isNotBlank()
                        }
                saveMessage =
                    "Подключено к PID " +
                        overlay.pid +
                        ". Игра выведена на передний план; MK overlay готов для автоскана, обучения действий и ручного поиска."
            } catch (_: AnalysisCancelledException) {
                error =
                    "Подключение к процессу отменено."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun launchAndAttach(
        app: InstalledAppTarget,
    ) {
        val signal =
            begin(
                "Запуск игры и подключение",
            ) ?: return
        scope.launch {
            try {
                val result =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootInstalledGameOverlayCoordinator
                            .launchAndAttach(
                                packageName =
                                    app.packageName,
                                label =
                                    app.label,
                                cancellation =
                                    signal,
                            )
                    }
                val capture =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeCaptureCoordinator
                            .captureMaps(
                                packageName =
                                    app.packageName,
                                cancellation =
                                    signal,
                                expectedPid =
                                    result.process
                                        .pid,
                            )
                    }
                clearRuntimeState()
                selected =
                    RootProcessUiItem(
                        process =
                            result.process,
                        app = app,
                    )
                attachedPid =
                    result.process.pid
                attachedMapsCount =
                    capture.capture.text
                        .lineSequence()
                        .count {
                            it.isNotBlank()
                        }
                saveMessage =
                    "Игра запущена и подключена к PID " +
                        result.process.pid +
                        ". MK overlay уже поверх игры."
            } catch (
                _: AnalysisCancelledException,
            ) {
                error =
                    "Запуск и подключение отменены."
            } catch (
                failure: Throwable,
            ) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun dumpProcess() {
        val item =
            selected ?: return
        val signal =
            begin(
                "Runtime dump",
            ) ?: return
        dumpProgress = null
        scope.launch {
            try {
                val output =
                    File(
                        appContext.filesDir,
                        "root-process-dump/" +
                            item.process.packageName
                                .replace(
                                    Regex(
                                        "[^A-Za-z0-9._-]",
                                    ),
                                    "_",
                                ) +
                            "-" +
                            System.currentTimeMillis() +
                            ".zip",
                    )
                dumpResult =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootProcessMemoryDumpCoordinator
                            .dump(
                                packageName =
                                    item.process
                                        .packageName,
                                outputFile =
                                    output,
                                cancellation =
                                    signal,
                                maxDumpBytes =
                                    if (
                                        fullDumpMode
                                    ) {
                                        null
                                    } else {
                                        RootProcessMemoryDumpCoordinator
                                            .QUICK_MAX_DUMP_BYTES
                                    },
                                includeSystemMappings =
                                    fullDumpMode,
                                progress = {
                                    update ->
                                    scope.launch {
                                        dumpProgress =
                                            update
                                    }
                                },
                            )
                    }
            } catch (_: AnalysisCancelledException) {
                error =
                    "Runtime dump отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun scanExact() {
        val item =
            selected ?: return
        val signal =
            begin(
                "Поиск значения",
            ) ?: return
        scope.launch {
            try {
                pointerScan = null
                valueScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeValueScanCoordinator
                            .scanExact(
                                packageName =
                                    item.process
                                        .packageName,
                                valueType =
                                    valueType,
                                query =
                                    exactQuery,
                                cancellation =
                                    signal,
                                alignment =
                                    alignment,
                                maxScanBytes =
                                    if (
                                        fullDumpMode
                                    ) {
                                        null
                                    } else {
                                        RuntimeValueScanner
                                            .DEFAULT_MAX_SCAN_BYTES
                                    },
                            )
                    }
            } catch (_: AnalysisCancelledException) {
                error =
                    "Поиск значения отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun captureUnknown() {
        val item =
            selected ?: return
        val signal =
            begin(
                "Unknown baseline",
            ) ?: return
        scope.launch {
            try {
                RootRuntimeUnknownValueCoordinator
                    .deleteBaseline(
                        unknownBaseline,
                    )
                val file =
                    File(
                        appContext.cacheDir,
                        "root-process-unknown/" +
                            item.process
                                .packageName
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
                unknownBaseline =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeUnknownValueCoordinator
                            .captureBaseline(
                                packageName =
                                    item.process
                                        .packageName,
                                valueType =
                                    valueType,
                                alignment =
                                    alignment,
                                snapshotFile =
                                    file,
                                cancellation =
                                    signal,
                                maxBytes =
                                    if (
                                        fullDumpMode
                                    ) {
                                        null
                                    } else {
                                        RootRuntimeUnknownValueCoordinator
                                            .QUICK_MAX_BASELINE_BYTES
                                    },
                            )
                    }
                valueScan = null
                pointerScan = null
            } catch (_: AnalysisCancelledException) {
                error =
                    "Unknown baseline отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun compareUnknown(
        refinement:
            RuntimeValueRefinement,
    ) {
        val baseline =
            unknownBaseline ?: return
        val signal =
            begin(
                "Unknown compare",
            ) ?: return
        scope.launch {
            try {
                valueScan =
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
                pointerScan = null
                RootRuntimeUnknownValueCoordinator
                    .deleteBaseline(
                        baseline,
                    )
                unknownBaseline = null
            } catch (_: AnalysisCancelledException) {
                error =
                    "Unknown compare отменён."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun refine(
        refinement:
            RuntimeValueRefinement,
    ) {
        val previous =
            valueScan ?: return
        val signal =
            begin(
                "Фильтрация",
            ) ?: return
        scope.launch {
            try {
                valueScan =
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
                pointerScan = null
            } catch (_: AnalysisCancelledException) {
                error =
                    "Фильтрация отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun refineExact() {
        val previous =
            valueScan ?: return
        val signal =
            begin(
                "Точное уточнение",
            ) ?: return
        scope.launch {
            try {
                valueScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeValueScanCoordinator
                            .refineExact(
                                previous =
                                    previous,
                                query =
                                    refineExactQuery,
                                cancellation =
                                    signal,
                            )
                    }
                pointerScan = null
            } catch (_: AnalysisCancelledException) {
                error =
                    "Точное уточнение отменено."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun refreshValues() {
        val previous =
            valueScan ?: return
        val signal =
            begin(
                "Обновление значений",
            ) ?: return
        scope.launch {
            try {
                valueScan =
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
                    "Обновление отменено."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun writeHit(
        address: Long,
    ) {
        val previous =
            valueScan ?: return
        if (!writesEnabled) {
            error =
                "Сначала включите ручную запись."
            return
        }
        val signal =
            begin(
                "Запись значения",
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
                                address =
                                    address,
                                valueText =
                                    writeValue,
                                cancellation =
                                    signal,
                            )
                    }
                valueScan =
                    result.updatedScan
                writeMessage =
                    "0x" +
                        address.toString(16) +
                        ": " +
                        result.oldValue +
                        " → " +
                        result.newValue +
                        " · read-back verified"
            } catch (_: AnalysisCancelledException) {
                error =
                    "Запись отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    fun toggleFreeze(
        address: Long,
    ) {
        if (
            freezeAddress ==
            address
        ) {
            freezeJob?.cancel()
            freezeJob = null
            freezeAddress = null
            writeMessage =
                "Freeze остановлен."
            return
        }
        val startScan =
            valueScan ?: return
        if (
            !writesEnabled ||
            writeValue.isBlank()
        ) {
            error =
                "Для freeze включите запись и задайте значение."
            return
        }

        freezeJob?.cancel()
        freezeAddress =
            address
        val frozenValue =
            writeValue
        freezeJob =
            scope.launch {
                var current =
                    startScan
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
                                            current,
                                        address =
                                            address,
                                        valueText =
                                            frozenValue,
                                        cancellation =
                                            signal,
                                    )
                            }
                        current =
                            result.updatedScan
                        valueScan =
                            result.updatedScan
                        writeMessage =
                            "Freeze 0x" +
                                address.toString(16) +
                                " = " +
                                result.newValue
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
                    freezeAddress ==
                    address
                ) {
                    freezeAddress = null
                    freezeJob = null
                }
            }
    }

    fun findPointers(
        address: Long,
        depth: Int,
    ) {
        val scan =
            valueScan ?: return
        val signal =
            begin(
                "Pointer scan",
            ) ?: return
        scope.launch {
            try {
                pointerScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        RootRuntimeValueScanCoordinator
                            .findPointersTo(
                                packageName =
                                    scan.packageName,
                                expectedPid =
                                    scan.pid,
                                targetAddress =
                                    address,
                                depth =
                                    depth,
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
                        ?: failure.javaClass
                            .simpleName
            } finally {
                finish()
            }
        }
    }

    val normalizedQuery =
        processQuery.trim()
            .lowercase()
    val filteredProcesses =
        remember(
            processItems,
            normalizedQuery,
            showAppsOnly,
        ) {
            processItems.filter {
                item ->
                (
                    normalizedQuery.isBlank() ||
                        item.label
                            .lowercase()
                            .contains(
                                normalizedQuery,
                            ) ||
                        item.process
                            .packageName
                            .lowercase()
                            .contains(
                                normalizedQuery,
                            )
                    ) &&
                    (
                        !showAppsOnly ||
                            !item.isGame
                        )
            }
        }
    val filteredLaunchableApps =
        remember(
            launchableApps,
            normalizedQuery,
            showAppsOnly,
        ) {
            launchableApps.filter {
                app ->
                (
                    normalizedQuery.isBlank() ||
                        app.label
                            .lowercase()
                            .contains(
                                normalizedQuery,
                            ) ||
                        app.packageName
                            .lowercase()
                            .contains(
                                normalizedQuery,
                            )
                    ) &&
                    (
                        !showAppsOnly ||
                            !app.isGame
                        )
            }
        }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(20.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(
                onClick = {
                    cancellation?.cancel()
                    clearRuntimeState()
                    onBack()
                },
            ) {
                Text("← Назад")
            }
        }

        item {
            Text(
                "Root Process Lab",
                style =
                    MaterialTheme.typography
                        .headlineMedium,
                fontWeight =
                    FontWeight.Bold,
            )
            Text(
                "Выбери запущенный процесс или установленную игру. Если процесс ещё не запущен, ModKit запустит его сам и сразу откроет MK live overlay.",
            )
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Root",
                        fontWeight =
                            FontWeight.SemiBold,
                    )
                    Text(
                        rootProbe?.message
                            ?: "Root ещё не проверен.",
                    )
                    Button(
                        onClick = ::checkRoot,
                        enabled = !busy,
                        modifier =
                            Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (rootChecking) {
                                "Проверяется…"
                            } else {
                                "Проверить root"
                            },
                        )
                    }
                    Button(
                        onClick =
                            ::loadProcesses,
                        enabled =
                            rootProbe?.available ==
                                true &&
                                !busy,
                        modifier =
                            Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Подключиться к процессу",
                        )
                    }
                }
            }
        }

        if (
            busy &&
            busyLabel != null
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp,
                            ),
                    ) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth(),
                        )
                        Text(
                            requireNotNull(
                                busyLabel,
                            ),
                        )
                        if (
                            busyLabel ==
                                "Runtime dump"
                        ) {
                            dumpProgress
                                ?.let {
                                    progress ->
                                    Text(
                                        progress.phase +
                                            " · " +
                                            (
                                                progress.fraction *
                                                    100f
                                                ).toInt() +
                                            "% · " +
                                            (
                                                progress.processedBytes /
                                                    (1024L * 1024L)
                                                ) +
                                            "/" +
                                            (
                                                progress.plannedBytes /
                                                    (1024L * 1024L)
                                                ) +
                                            " MiB · slices " +
                                            progress.processedSlices +
                                            "/" +
                                            progress.totalSlices +
                                            " · regions " +
                                            progress.processedRegions +
                                            "/" +
                                            progress.totalRegions +
                                            " · " +
                                            (
                                                progress.elapsedMs /
                                                    1000L
                                                ) +
                                            " с",
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                }
                        }
                        OutlinedButton(
                            onClick = {
                                cancellation?.cancel()
                            },
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text("Отменить")
                        }
                    }
                }
            }
        }

        error?.let {
            message ->
            item {
                Text(
                    "Ошибка: " +
                        message,
                    color =
                        MaterialTheme.colorScheme
                            .error,
                )
            }
        }

        saveMessage
            ?.let {
                message ->
                item {
                    Text(
                        message,
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                }
            }

        if (
            processesLoading
        ) {
            item {
                CircularProgressIndicator()
            }
        }

        if (
            (
                processItems.isNotEmpty() ||
                    launchableApps.isNotEmpty()
                ) &&
                selected == null
        ) {
            item {
                OutlinedTextField(
                    value =
                        processQuery,
                    onValueChange = {
                        processQuery = it
                    },
                    label = {
                        Text(
                            "Поиск игры или приложения",
                        )
                    },
                    singleLine = true,
                    modifier =
                        Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            8.dp,
                        ),
                ) {
                    FilterChip(
                        selected =
                            !showAppsOnly,
                        onClick = {
                            showAppsOnly =
                                false
                        },
                        label = {
                            Text(
                                "Игры и приложения",
                            )
                        },
                    )
                    FilterChip(
                        selected =
                            showAppsOnly,
                        onClick = {
                            showAppsOnly =
                                true
                        },
                        label = {
                            Text(
                                "Только приложения",
                            )
                        },
                    )
                }
            }
            items(
                filteredProcesses,
                key = {
                    it.process.pid
                },
            ) {
                item ->
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                attach(item)
                            },
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                4.dp,
                            ),
                    ) {
                        Text(
                            item.label,
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        Text(
                            if (item.isGame) {
                                "Игра"
                            } else {
                                "Приложение"
                            },
                            style =
                                MaterialTheme.typography
                                    .labelMedium,
                        )
                        Text(
                            item.process
                                .packageName,
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                        Text(
                            "PID " +
                                item.process.pid +
                                " · " +
                                item.process.user,
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                    }
                }
            }

            if (
                filteredLaunchableApps
                    .isNotEmpty()
            ) {
                item {
                    Text(
                        "Не запущены — нажми, и ModKit сам запустит приложение, дождётся процесса и подключит MK overlay.",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                }
                items(
                    filteredLaunchableApps,
                    key = {
                        "launch:" +
                            it.packageName
                    },
                ) {
                    app ->
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    launchAndAttach(
                                        app,
                                    )
                                },
                    ) {
                        Column(
                            Modifier.padding(
                                14.dp,
                            ),
                            verticalArrangement =
                                Arrangement
                                    .spacedBy(
                                        4.dp,
                                    ),
                        ) {
                            Text(
                                app.label,
                                fontWeight =
                                    FontWeight
                                        .SemiBold,
                            )
                            Text(
                                (
                                    if (
                                        app.isGame
                                    ) {
                                        "Игра"
                                    } else {
                                        "Приложение"
                                    }
                                    ) +
                                    " · не запущено",
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelMedium,
                            )
                            Text(
                                app.packageName,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                            )
                        }
                    }
                }
            }
        }

        selected?.let {
            selectedItem ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp,
                            ),
                    ) {
                        Text(
                            "Подключено",
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        Text(
                            selectedItem.label,
                            style =
                                MaterialTheme.typography
                                    .titleLarge,
                        )
                        Text(
                            selectedItem.process
                                .packageName +
                                " · PID " +
                                (
                                    attachedPid
                                        ?: selectedItem.process
                                            .pid
                                    ),
                        )
                        Text(
                            "Maps: " +
                                attachedMapsCount +
                                ". Все runtime-операции повторно проверяют PID перед чтением или записью.",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                        Text(
                            "Основная работа теперь идёт в MK overlay поверх игры: Автоскан, Обучить действие и ручной поиск значений. Runtime dump для этого не нужен.",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                        OutlinedButton(
                            onClick = {
                                clearRuntimeState()
                                selected = null
                                attachedPid = null
                                attachedMapsCount = 0
                            },
                            enabled = !busy,
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Выбрать другой процесс",
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp,
                            ),
                    ) {
                        Text(
                            "Расширенный статический анализ",
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        Text(
                            "Необязательный экспертный инструмент. Основной режим находится в MK overlay и анализирует живой процесс; этот раздел оставлен для статического сопоставления методов и подтверждённых binary-targets.",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )

                        if (modScanBusy) {
                            LinearProgressIndicator(
                                Modifier.fillMaxWidth(),
                            )
                            Text(
                                modScanProgress
                                    ?.currentTask
                                    ?: "Анализируем приложение и ищем модификации…",
                                style =
                                    MaterialTheme.typography
                                        .bodySmall,
                            )
                            modScanProgress
                                ?.currentArtifact
                                ?.let {
                                    artifact ->
                                    Text(
                                        artifact,
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                }
                            OutlinedButton(
                                onClick = {
                                    modScanSignal
                                        ?.cancel()
                                },
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "Отменить поиск модов",
                                )
                            }
                        }

                        modScanError
                            ?.let {
                                message ->
                                Text(
                                    "Ошибка поиска: " +
                                        message,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .error,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                )
                            }

                        modDiscovery
                            ?.let {
                                discovery ->
                                val ready =
                                    discovery
                                        .opportunities
                                        .count {
                                            it.selectable
                                        }
                                Text(
                                    "Найдено вариантов: " +
                                        discovery
                                            .opportunities
                                            .size +
                                        " · готовых к профилю: " +
                                        ready +
                                        " · " +
                                        (
                                            discovery.elapsedMs /
                                                1000L
                                            ) +
                                        " с",
                                    fontWeight =
                                        FontWeight
                                            .SemiBold,
                                )

                                if (
                                    discovery
                                        .opportunities
                                        .isEmpty()
                                ) {
                                    Text(
                                        "Для этой версии пока нет достаточно подтверждённых gameplay-кандидатов. Это не означает, что модификаций нет: backend для её runtime может быть ещё недостаточно глубоким.",
                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodySmall,
                                    )
                                } else {
                                    discovery
                                        .opportunities
                                        .take(
                                            48,
                                        )
                                        .forEach {
                                            opportunity ->
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth(),
                                                verticalAlignment =
                                                    Alignment
                                                        .Top,
                                            ) {
                                                Checkbox(
                                                    checked =
                                                        opportunity
                                                            .id in
                                                            selectedModIds,
                                                    onCheckedChange = {
                                                        checked ->
                                                        if (
                                                            opportunity
                                                                .selectable
                                                        ) {
                                                            selectedModIds =
                                                                if (
                                                                    checked
                                                                ) {
                                                                    selectedModIds +
                                                                        opportunity
                                                                            .id
                                                                } else {
                                                                    selectedModIds -
                                                                        opportunity
                                                                            .id
                                                                }
                                                        }
                                                    },
                                                    enabled =
                                                        opportunity
                                                            .selectable,
                                                )
                                                Column(
                                                    modifier =
                                                        Modifier
                                                            .weight(
                                                                1f,
                                                            ),
                                                ) {
                                                    Text(
                                                        opportunity
                                                            .title,
                                                        fontWeight =
                                                            FontWeight
                                                                .SemiBold,
                                                    )
                                                    Text(
                                                        opportunity
                                                            .targetDisplayName,
                                                        style =
                                                            MaterialTheme
                                                                .typography
                                                                .bodySmall,
                                                    )
                                                    Text(
                                                        if (
                                                            opportunity
                                                                .selectable
                                                        ) {
                                                            "Подтверждённый локальный кандидат"
                                                        } else {
                                                            opportunity
                                                                .blocker
                                                                ?: "Нужно дополнительное подтверждение"
                                                        },
                                                        style =
                                                            MaterialTheme
                                                                .typography
                                                                .bodySmall,
                                                    )
                                                }
                                            }
                                        }
                                    if (
                                        discovery
                                            .opportunities
                                            .size >
                                        48
                                    ) {
                                        Text(
                                            "Показаны первые 48 из " +
                                                discovery
                                                    .opportunities
                                                    .size +
                                                ".",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        val app =
                                            selectedItem
                                                .app
                                        if (
                                            app != null
                                        ) {
                                            val profile =
                                                runCatching {
                                                    RootModProfileWriter
                                                        .build(
                                                            app =
                                                                app,
                                                            discovery =
                                                                discovery,
                                                            selectedIds =
                                                                selectedModIds,
                                                        )
                                                }
                                            if (
                                                profile.isSuccess
                                            ) {
                                                pendingProfileText =
                                                    profile
                                                        .getOrThrow()
                                                profileSaveLauncher
                                                    .launch(
                                                        app.packageName +
                                                            "-mods.modkit.json",
                                                    )
                                            } else {
                                                saveMessage =
                                                    profile
                                                        .exceptionOrNull()
                                                        ?.message
                                                        ?: "Не удалось сформировать профиль."
                                            }
                                        }
                                    },
                                    enabled =
                                        selectedModIds
                                            .isNotEmpty(),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth(),
                                ) {
                                    Text(
                                        "Сохранить профиль выбранных модов",
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        val app =
                                            selectedItem.app
                                        if (app != null) {
                                            val result =
                                                runCatching {
                                                    val text =
                                                        RootModProfileWriter.build(
                                                            app = app,
                                                            discovery = discovery,
                                                            selectedIds = selectedModIds,
                                                        )
                                                    sandboxProfileStore.save(text)
                                                }
                                            saveMessage =
                                                if (result.isSuccess) {
                                                    "Выбранные моды добавлены в ModKit Sandbox."
                                                } else {
                                                    "Не удалось добавить в Sandbox: " +
                                                        (result.exceptionOrNull()?.message
                                                            ?: "ошибка профиля")
                                                }
                                        }
                                    },
                                    enabled = selectedModIds.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "Добавить в ModKit Sandbox",
                                    )
                                }

                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(
                                            8.dp,
                                        ),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            selectedModIds =
                                                discovery
                                                    .opportunities
                                                    .filter {
                                                        it.selectable
                                                    }
                                                    .map {
                                                        it.id
                                                    }
                                                    .toSet()
                                        },
                                        enabled =
                                            !sandboxLaunchBusy &&
                                                discovery
                                                    .opportunities
                                                    .any {
                                                        it.selectable
                                                    },
                                        modifier =
                                            Modifier.weight(1f),
                                    ) {
                                        Text("Выбрать все")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            selectedModIds =
                                                emptySet()
                                        },
                                        enabled =
                                            !sandboxLaunchBusy &&
                                                selectedModIds
                                                    .isNotEmpty(),
                                        modifier =
                                            Modifier.weight(1f),
                                    ) {
                                        Text("Снять все")
                                    }
                                }

                                Button(
                                    onClick = {
                                        val app =
                                            selectedItem.app
                                        if (
                                            app != null &&
                                            !sandboxLaunchBusy
                                        ) {
                                            sandboxLaunchBusy =
                                                true
                                            saveMessage =
                                                "Запускаем ModKit Sandbox, применяем моды и поднимаем MK overlay…"
                                            scope.launch {
                                                val result =
                                                    runCatching {
                                                        withContext(
                                                            Dispatchers.IO,
                                                        ) {
                                                            val text =
                                                                RootModProfileWriter.build(
                                                                    app = app,
                                                                    discovery = discovery,
                                                                    selectedIds = selectedModIds,
                                                                )
                                                            val stored =
                                                                sandboxProfileStore.save(
                                                                    text,
                                                                )
                                                            RootSandboxLaunchCoordinator.launch(
                                                                context = appContext,
                                                                app = app,
                                                                profile = stored.profile,
                                                                cancellation =
                                                                    AtomicCancellationSignal(),
                                                            )
                                                        }
                                                    }
                                                result.onSuccess {
                                                    session ->
                                                    sandboxSession =
                                                        session
                                                    saveMessage =
                                                        "Готово: игра запущена в Sandbox · PID " +
                                                            session.pid +
                                                            " · модов в MK overlay: " +
                                                            session.overlay.itemCount +
                                                            ". Переключатели работают в реальном времени."
                                                }.onFailure {
                                                    failure ->
                                                    saveMessage =
                                                        "Не удалось запустить игру с mod menu: " +
                                                            (
                                                                failure.message
                                                                    ?: failure
                                                                        .javaClass
                                                                        .simpleName
                                                                )
                                                }
                                                sandboxLaunchBusy =
                                                    false
                                            }
                                        }
                                    },
                                    enabled =
                                        selectedModIds
                                            .isNotEmpty() &&
                                            !sandboxLaunchBusy,
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (
                                            sandboxLaunchBusy
                                        ) {
                                            "Запускаем Sandbox + MK overlay…"
                                        } else {
                                            "Запустить игру с выбранными модами"
                                        },
                                    )
                                }

                                sandboxSession
                                    ?.let {
                                        session ->
                                        OutlinedButton(
                                            onClick = {
                                                if (
                                                    !sandboxLaunchBusy
                                                ) {
                                                    sandboxLaunchBusy =
                                                        true
                                                    scope.launch {
                                                        val result =
                                                            runCatching {
                                                                withContext(
                                                                    Dispatchers.IO,
                                                                ) {
                                                                    RootSandboxLaunchCoordinator
                                                                        .stopOverlayAndRollback(
                                                                            session =
                                                                                session,
                                                                            cancellation =
                                                                                AtomicCancellationSignal(),
                                                                        )
                                                                }
                                                            }
                                                        if (
                                                            result.isSuccess
                                                        ) {
                                                            sandboxSession =
                                                                null
                                                            saveMessage =
                                                                "MK overlay остановлено, моды текущей sandbox-сессии откатаны."
                                                        } else {
                                                            saveMessage =
                                                                "Rollback sandbox-сессии не подтверждён: " +
                                                                    (
                                                                        result
                                                                            .exceptionOrNull()
                                                                            ?.message
                                                                            ?: "ошибка"
                                                                        )
                                                        }
                                                        sandboxLaunchBusy =
                                                            false
                                                    }
                                                }
                                            },
                                            enabled =
                                                !sandboxLaunchBusy,
                                            modifier =
                                                Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                "Остановить overlay и откатить эту сессию",
                                            )
                                        }
                                    }
                            }

                        if (
                            !modScanBusy
                        ) {
                            OutlinedButton(
                                onClick = {
                                    discoverMods(
                                        selectedItem,
                                    )
                                },
                                modifier =
                                    Modifier
                                        .fillMaxWidth(),
                            ) {
                                Text(
                                    if (
                                        modDiscovery ==
                                        null
                                    ) {
                                        "Найти модификации"
                                    } else {
                                        "Повторить поиск модификаций"
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp,
                            ),
                    ) {
                        Text(
                            "Технический runtime dump",
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        Text(
                            "Для автопоиска модов этот dump не нужен. Он оставлен как технический инструмент диагностики. " +
                                "Дамп читается из живой памяти процесса через root. " +
                                "В ZIP входят maps.txt, индекс сегментов, runtime mapping bytes и runtime-artifacts/index.tsv с найденными ELF/DEX/CompactDEX/IL2CPP metadata/WASM/SQLite/ZIP/PE кандидатами. " +
                                "Чтение выполняется потоково и пакетами; полный режим не имеет искусственного лимита 256 MiB. " +
                                "Это runtime-снимок и структурный индекс, а не обещание восстановить исходный C#/Java-код один-в-один.",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                        OutlinedButton(
                            onClick = {
                                fullDumpMode =
                                    !fullDumpMode
                                dumpResult = null
                                dumpProgress = null
                            },
                            enabled = !busy,
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (
                                    fullDumpMode
                                ) {
                                    "Режим: полный технический dump"
                                } else {
                                    "Режим: быстрый технический dump"
                                },
                            )
                        }
                        Text(
                            if (
                                fullDumpMode
                            ) {
                                "Полный режим проходит все readable mappings процесса, кроме специальных kernel mappings, и не останавливается на 256 MiB. Перед стартом проверяется свободное место."
                            } else {
                                "Быстрый режим ограничен 256 MiB и приоритизирует app/runtime mappings без системных библиотек."
                            },
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                        Button(
                            onClick =
                                ::dumpProcess,
                            enabled = !busy,
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (
                                    fullDumpMode
                                ) {
                                    "Сделать полный технический dump"
                                } else {
                                    "Сделать быстрый технический dump"
                                },
                            )
                        }
                        dumpResult?.let {
                            dump ->
                            Text(
                                "Готово: " +
                                    dump.dumpedRegions +
                                    "/" +
                                    dump.mappedRegions +
                                    " regions · " +
                                    (
                                        dump.dumpedBytes /
                                            (1024L * 1024L)
                                        ) +
                                    " MiB" +
                                    if (
                                        dump.truncatedByByteLimit
                                    ) {
                                        " · достигнут лимит"
                                    } else {
                                        " · полный проход"
                                    } +
                                    " · runtime artifacts " +
                                    dump.detectedArtifacts,
                                style =
                                    MaterialTheme.typography
                                        .bodySmall,
                            )
                            OutlinedButton(
                                onClick = {
                                    pendingDumpSave =
                                        dump.file
                                    dumpSaveLauncher
                                        .launch(
                                            selectedItem
                                                .process
                                                .packageName +
                                                "-runtime-dump.zip",
                                        )
                                },
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "Сохранить runtime dump на устройство",
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp,
                            ),
                    ) {
                        Text(
                            "Дополнительный ручной сканер памяти",
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        Text(
                            if (
                                fullDumpMode
                            ) {
                                "Expert fallback. Полный режим exact scan и unknown baseline проходит все подходящие writable private ranges. Основной сценарий ModKit — автопоиск модификаций выше."
                            } else {
                                "Expert fallback. Быстрый exact scan ограничен 128 MiB, unknown baseline — 32 MiB. Для обычного поиска модов используйте автоматический список выше."
                            },
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                        OutlinedButton(
                            onClick = {
                                valueType =
                                    valueType.next()
                                valueScan = null
                                pointerScan = null
                            },
                            enabled = !busy,
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Тип: " +
                                    valueType.title +
                                    " · сменить",
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                alignment =
                                    alignment.next()
                                valueScan = null
                                pointerScan = null
                                RootRuntimeUnknownValueCoordinator
                                    .deleteBaseline(
                                        unknownBaseline,
                                    )
                                unknownBaseline = null
                            },
                            enabled = !busy,
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Выравнивание: " +
                                    alignment.title +
                                    " · сменить",
                            )
                        }

                        Text(
                            "Известное значение",
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value =
                                exactQuery,
                            onValueChange = {
                                exactQuery = it
                            },
                            label = {
                                Text(
                                    "Значение",
                                )
                            },
                            singleLine = true,
                            modifier =
                                Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick =
                                ::scanExact,
                            enabled =
                                !busy &&
                                    exactQuery
                                        .isNotBlank(),
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Найти в памяти",
                            )
                        }

                        Text(
                            "Неизвестное начальное значение",
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        if (
                            unknownBaseline ==
                            null
                        ) {
                            OutlinedButton(
                                onClick =
                                    ::captureUnknown,
                                enabled = !busy,
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "Сохранить baseline",
                                )
                            }
                        } else {
                            Text(
                                "Baseline сохранён. Измените значение в игре и выберите результат.",
                                style =
                                    MaterialTheme.typography
                                        .bodySmall,
                            )
                            RuntimeValueRefinement
                                .entries
                                .forEach {
                                    mode ->
                                    OutlinedButton(
                                        onClick = {
                                            compareUnknown(
                                                mode,
                                            )
                                        },
                                        enabled =
                                            !busy,
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            mode.title,
                                        )
                                    }
                                }
                        }
                    }
                }
            }

            valueScan?.let {
                scan ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    8.dp,
                                ),
                        ) {
                            Text(
                                "Найденные значения",
                                fontWeight =
                                    FontWeight.SemiBold,
                            )
                            Text(
                                "PID " +
                                    scan.pid +
                                    " · адресов " +
                                    scan.snapshot
                                        .hits.size +
                                    " · " +
                                    (
                                        scan.snapshot
                                            .scannedBytes /
                                            (1024L * 1024L)
                                        ) +
                                    " MiB",
                            )
                            OutlinedButton(
                                onClick =
                                    ::refreshValues,
                                enabled =
                                    !busy &&
                                        scan.snapshot
                                            .hits
                                            .isNotEmpty(),
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "Обновить значения",
                                )
                            }
                            RuntimeValueRefinement
                                .entries
                                .forEach {
                                    mode ->
                                    OutlinedButton(
                                        onClick = {
                                            refine(mode)
                                        },
                                        enabled =
                                            !busy &&
                                                scan.snapshot
                                                    .hits
                                                    .isNotEmpty(),
                                        modifier =
                                            Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            "Фильтр: " +
                                                mode.title,
                                        )
                                    }
                                }
                            OutlinedTextField(
                                value =
                                    refineExactQuery,
                                onValueChange = {
                                    refineExactQuery =
                                        it
                                },
                                label = {
                                    Text(
                                        "Новое точное значение",
                                    )
                                },
                                singleLine = true,
                                modifier =
                                    Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick =
                                    ::refineExact,
                                enabled =
                                    !busy &&
                                        refineExactQuery
                                            .isNotBlank() &&
                                        scan.snapshot
                                            .hits
                                            .isNotEmpty(),
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "Оставить равные новому значению",
                                )
                            }

                            Row(
                                modifier =
                                    Modifier.fillMaxWidth(),
                                verticalAlignment =
                                    Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked =
                                        writesEnabled,
                                    onCheckedChange = {
                                        enabled ->
                                        writesEnabled =
                                            enabled
                                        if (
                                            !enabled
                                        ) {
                                            freezeJob
                                                ?.cancel()
                                            freezeJob =
                                                null
                                            freezeAddress =
                                                null
                                            writeValue =
                                                ""
                                        }
                                    },
                                )
                                Text(
                                    "Разрешить ручную запись",
                                )
                            }
                            if (
                                writesEnabled
                            ) {
                                OutlinedTextField(
                                    value =
                                        writeValue,
                                    onValueChange = {
                                        writeValue = it
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
                            writeMessage?.let {
                                message ->
                                Text(
                                    message,
                                    style =
                                        MaterialTheme.typography
                                            .bodySmall,
                                )
                            }

                            scan.snapshot.hits
                                .take(50)
                                .forEachIndexed {
                                        index,
                                        hit,
                                    ->
                                    Card(
                                        Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            Modifier.padding(
                                                10.dp,
                                            ),
                                            verticalArrangement =
                                                Arrangement.spacedBy(
                                                    4.dp,
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
                                                    ),
                                                fontWeight =
                                                    FontWeight
                                                        .SemiBold,
                                            )
                                            Text(
                                                "map+0x" +
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
                                                writesEnabled &&
                                                writeValue
                                                    .isNotBlank()
                                            ) {
                                                Button(
                                                    onClick = {
                                                        writeHit(
                                                            hit.address,
                                                        )
                                                    },
                                                    enabled =
                                                        !busy,
                                                    modifier =
                                                        Modifier.fillMaxWidth(),
                                                ) {
                                                    Text(
                                                        "Записать",
                                                    )
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        toggleFreeze(
                                                            hit.address,
                                                        )
                                                    },
                                                    enabled =
                                                        !busy,
                                                    modifier =
                                                        Modifier.fillMaxWidth(),
                                                ) {
                                                    Text(
                                                        if (
                                                            freezeAddress ==
                                                            hit.address
                                                        ) {
                                                            "Остановить freeze"
                                                        } else {
                                                            "Freeze"
                                                        },
                                                    )
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    findPointers(
                                                        hit.address,
                                                        1,
                                                    )
                                                },
                                                enabled =
                                                    !busy,
                                                modifier =
                                                    Modifier.fillMaxWidth(),
                                            ) {
                                                Text(
                                                    "Найти указатели",
                                                )
                                            }
                                        }
                                    }
                                }
                            if (
                                scan.snapshot
                                    .hits.size > 50
                            ) {
                                Text(
                                    "Показаны первые 50 из " +
                                        scan.snapshot
                                            .hits.size +
                                        ". Сужайте результаты фильтрами.",
                                    style =
                                        MaterialTheme.typography
                                            .bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            pointerScan?.let {
                pointers ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    6.dp,
                                ),
                        ) {
                            Text(
                                "Pointer scan",
                                fontWeight =
                                    FontWeight.SemiBold,
                            )
                            Text(
                                "Уровень " +
                                    pointers.depth +
                                    " · target 0x" +
                                    pointers
                                        .targetAddress
                                        .toString(16) +
                                    " · найдено " +
                                    pointers.snapshot
                                        .hits.size,
                            )
                            pointers.snapshot.hits
                                .take(30)
                                .forEach {
                                    hit ->
                                    Text(
                                        "0x" +
                                            hit.address
                                                .toString(16) +
                                            " → 0x" +
                                            pointers
                                                .targetAddress
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
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                    if (
                                        pointers.depth <
                                        4
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                findPointers(
                                                    hit.address,
                                                    pointers.depth +
                                                        1,
                                                )
                                            },
                                            enabled =
                                                !busy,
                                            modifier =
                                                Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                "Искать уровень " +
                                                    (
                                                        pointers.depth +
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
        }
    }
}
