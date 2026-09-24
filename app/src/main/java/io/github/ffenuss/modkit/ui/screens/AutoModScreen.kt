package io.github.ffenuss.modkit.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.BinaryFormat
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.build.BuildArtifactExporter
import io.github.ffenuss.modkit.build.VerifiedBuildPipeline
import io.github.ffenuss.modkit.build.VerifiedBuildResult
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.patch.AutoModPreparationCoordinator
import io.github.ffenuss.modkit.patch.AutoModRuntimeTestMenuBuild
import io.github.ffenuss.modkit.patch.AutoModRuntimeTestMenuCoordinator
import io.github.ffenuss.modkit.patch.DexAutoModCoordinator
import io.github.ffenuss.modkit.patch.DexLocalCategory
import io.github.ffenuss.modkit.patch.DexLocalOpportunity
import io.github.ffenuss.modkit.patch.DexLocalScan
import io.github.ffenuss.modkit.patch.DexScanDiagnosticReport
import io.github.ffenuss.modkit.patch.GameplayModificationFinder
import io.github.ffenuss.modkit.patch.GameplayModificationOpportunity
import io.github.ffenuss.modkit.patch.Il2CppPatchTargetBrowser
import io.github.ffenuss.modkit.patch.MutationApplyOutcome
import io.github.ffenuss.modkit.patch.PatchLabDiagnosticReportExporter
import io.github.ffenuss.modkit.patch.PatchLabDiagnosticReportWriter
import io.github.ffenuss.modkit.patch.PatchPreparationPlan
import io.github.ffenuss.modkit.patch.PreparationTargetStatus
import io.github.ffenuss.modkit.runtime.AndroidRepackedRuntimeInstaller
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallPlanner
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallReadiness
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallReadinessState
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallStatusStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AutoModScreen(
    target: AnalysisTargetDescriptor,
    result: FastAnalysisResult,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var analysisResult by remember(result.index.artifactSha256) { mutableStateOf(result) }
    var preparing by remember(result.index.artifactSha256) { mutableStateOf(false) }
    var progress by remember(result.index.artifactSha256) { mutableStateOf<EngineProgress?>(null) }
    var plan by remember(result.index.artifactSha256) { mutableStateOf<PatchPreparationPlan?>(null) }
    var confirmationNote by remember(result.index.artifactSha256) { mutableStateOf<String?>(null) }
    var showManualPatch by remember(result.index.artifactSha256) { mutableStateOf(false) }
    var stagingOutcome by remember(result.index.artifactSha256) {
        mutableStateOf<MutationApplyOutcome?>(null)
    }
    var building by remember(result.index.artifactSha256) { mutableStateOf(false) }
    var buildResult by remember(result.index.artifactSha256) {
        mutableStateOf<VerifiedBuildResult?>(null)
    }
    var error by remember(result.index.artifactSha256) { mutableStateOf<String?>(null) }
    var showErrorDetails by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var exportingReport by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var runtimeMenuBuild by remember(result.index.artifactSha256) {
        mutableStateOf<AutoModRuntimeTestMenuBuild?>(null)
    }
    var runtimeMenuBusy by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var runtimeMenuInstallReadiness by remember(result.index.artifactSha256) {
        mutableStateOf<RepackedRuntimeInstallReadiness?>(null)
    }
    var runtimeMenuNote by remember(result.index.artifactSha256) {
        mutableStateOf<String?>(null)
    }
    var previewFindings by remember(result.index.artifactSha256) {
        mutableStateOf<List<GameplayModificationOpportunity>?>(null)
    }
    var previewFindingBusy by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var previewFindingError by remember(result.index.artifactSha256) {
        mutableStateOf<String?>(null)
    }
    var cancellation by remember(result.index.artifactSha256) {
        mutableStateOf<AtomicCancellationSignal?>(null)
    }
    var dexScan by remember(result.index.artifactSha256) {
        mutableStateOf<DexLocalScan?>(null)
    }
    var dexLoading by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var dexScanError by remember(result.index.artifactSha256) {
        mutableStateOf<String?>(null)
    }
    var dexApplying by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var dexDeveloperTestMode by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var dexSelectedIds by remember(result.index.artifactSha256) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var dexShowAll by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var dexRetry by remember(result.index.artifactSha256) {
        mutableStateOf(0)
    }
    var dexReportBusy by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var builtInstallBusy by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var builtInstallReadiness by remember(result.index.artifactSha256) {
        mutableStateOf<RepackedRuntimeInstallReadiness?>(null)
    }
    var builtInstallNote by remember(result.index.artifactSha256) {
        mutableStateOf<String?>(null)
    }
    var builtInstallSessionId by remember(result.index.artifactSha256) {
        mutableStateOf<Int?>(null)
    }
    val installStatus by RepackedRuntimeInstallStatusStore.status.collectAsState()

    var buildSaveBusy by remember(result.index.artifactSha256) {
        mutableStateOf(false)
    }
    var buildSaveMessage by remember(result.index.artifactSha256) {
        mutableStateOf<String?>(null)
    }
    var buildPendingSafSave by remember(result.index.artifactSha256) {
        mutableStateOf<VerifiedBuildResult?>(null)
    }
    val saveBuildFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { activityResult ->
        val pending = buildPendingSafSave
        buildPendingSafSave = null
        val destination = activityResult.data?.data
        if (
            activityResult.resultCode == Activity.RESULT_OK &&
            destination != null &&
            pending != null
        ) {
            buildSaveBusy = true
            buildSaveMessage = null
            scope.launch {
                try {
                    val saved = withContext(Dispatchers.IO) {
                        BuildArtifactExporter.writeToUri(
                            context = context,
                            result = pending,
                            destination = destination,
                        )
                    }
                    buildSaveMessage =
                        "Готовый файл сохранён в выбранную папку · " +
                            saved.bytesWritten + " байт."
                } catch (failure: Throwable) {
                    buildSaveMessage =
                        "Не удалось сохранить: " +
                            (failure.message ?: failure.javaClass.simpleName)
                } finally {
                    buildSaveBusy = false
                }
            }
        } else if (pending != null) {
            buildSaveMessage = "Сохранение файла отменено."
        }
    }

    fun chooseBuildDestination(built: VerifiedBuildResult) {
        if (buildSaveBusy || buildPendingSafSave != null) return
        buildPendingSafSave = built
        buildSaveMessage = null
        val mimeType =
            if (built.files.size == 1) {
                "application/vnd.android.package-archive"
            } else {
                "application/zip"
            }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(
                Intent.EXTRA_TITLE,
                BuildArtifactExporter.proposedFileName(built),
            )
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            saveBuildFilePicker.launch(intent)
        } catch (failure: Throwable) {
            buildPendingSafSave = null
            buildSaveMessage =
                failure.message ?: "Системный выбор папки недоступен."
        }
    }

    fun saveBuildToDownloads(built: VerifiedBuildResult) {
        if (buildSaveBusy || buildPendingSafSave != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            chooseBuildDestination(built)
            return
        }
        buildSaveBusy = true
        buildSaveMessage = null
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    BuildArtifactExporter.saveApkFilesToDownloads(
                        context = context,
                        result = built,
                    )
                }
                buildSaveMessage =
                    "Готово: " + saved.destinationDirectory +
                        " · сохранено APK: " + saved.files.size +
                        " (" + saved.totalBytes / 1_048_576L + " МиБ). " +
                        if (saved.files.size > 1) {
                            "Чтобы установить игру, нажмите «Установить игру» в ModKit."
                        } else {
                            "Можно установить этот APK через ModKit."
                        }
            } catch (failure: Throwable) {
                buildSaveMessage =
                    "Не удалось сохранить: " +
                        (failure.message ?: failure.javaClass.simpleName) +
                        ". Можно выбрать другую папку."
            } finally {
                buildSaveBusy = false
            }
        }
    }



    fun prepareChanges() {
        if (preparing || building || runtimeMenuBusy) return
        val signal = AtomicCancellationSignal()
        cancellation = signal
        preparing = true
        error = null
        progress = null

        scope.launch {
            try {
                val prepared = AutoModPreparationCoordinator.prepare(
                    context = context,
                    target = target,
                    initial = analysisResult,
                    cancellation = signal,
                    progress = ProgressSink { update ->
                        scope.launch { progress = update }
                    },
                )
                analysisResult = prepared.analysisResult
                plan = prepared.plan
                stagingOutcome = null
                buildResult = null
                runtimeMenuBuild = null
                runtimeMenuInstallReadiness = null
                runtimeMenuNote = null
                confirmationNote = when {
                    prepared.requestedStaticConfirmations <= 0 -> null
                    prepared.remainingStaticConfirmations == 0 ->
                        "Недостающее статическое подтверждение выполнено автоматически."
                    else ->
                        "Часть статических подтверждений остаётся нерешённой; автоматическое применение не разрешено."
                }
            } catch (_: AnalysisCancelledException) {
                error = "Подготовка отменена. Исходные результаты анализа не изменены."
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                preparing = false
                cancellation = null
            }
        }
    }

    fun exportDiagnosticReport() {
        if (
            exportingReport ||
            preparing ||
            building ||
            runtimeMenuBusy
        ) return
        exportingReport = true
        error = null
        scope.launch {
            try {
                val report =
                    withContext(Dispatchers.IO) {
                        PatchLabDiagnosticReportWriter.write(
                            outputDir =
                                File(
                                    context.filesDir,
                                    "expert-lab-export",
                                ),
                            label = target.label,
                            result = analysisResult,
                            preparation = plan,
                        )
                    }
                PatchLabDiagnosticReportExporter.share(
                    context = context,
                    report = report,
                )
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                exportingReport = false
            }
        }
    }

    fun startBuild(staged: MutationApplyOutcome) {
        if (
            !staged.applied ||
            preparing ||
            building ||
            runtimeMenuBusy
        ) return

        val signal = AtomicCancellationSignal()
        cancellation = signal
        building = true
        error = null
        progress = null
        buildResult = null
        buildSaveMessage = null
        builtInstallReadiness = null
        builtInstallNote = null
        builtInstallSessionId = null

        scope.launch {
            try {
                val built = VerifiedBuildPipeline.build(
                    context = context,
                    stagingOutcome = staged,
                    cancellation = signal,
                    progress = ProgressSink { update ->
                        scope.launch { progress = update }
                    },
                )
                // A completed signed build remains available in ModKit even
                // if exporting a large APK-set exhausts Downloads storage.
                buildResult = built
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    buildSaveBusy = true
                    buildSaveMessage =
                        "Все " + built.files.size +
                            " APK подписаны и проверены. Сохраняем в " +
                            "Downloads/ModKit; не закрывайте ModKit…"
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            BuildArtifactExporter.saveApkFilesToDownloads(
                                context = context,
                                result = built,
                            )
                        }
                        buildSaveMessage =
                            "Готово: " + saved.destinationDirectory +
                                " · " + saved.files.size +
                                " APK (" +
                                (saved.totalBytes / 1_048_576L) +
                                " МиБ). " +
                                if (saved.files.size > 1) {
                                    "Нажмите «Установить игру» — Android " +
                                        "получит весь комплект одновременно."
                                } else {
                                    "APK можно установить прямо из ModKit."
                                }
                    } catch (exportFailure: Throwable) {
                        buildSaveMessage =
                            "APK собран, подписан и проверен, но сохранение " +
                                "в Downloads не удалось: " +
                                (exportFailure.message
                                    ?: exportFailure.javaClass.simpleName) +
                                ". Освободите место или выберите " +
                                "другую папку ниже."
                    } finally {
                        buildSaveBusy = false
                    }
                } else {
                    buildSaveMessage =
                        "APK собран и проверен. На Android 8/9 " +
                            "выберите папку для сохранения ниже."
                }
            } catch (_: AnalysisCancelledException) {
                error = "Сборка отменена. Staging APK сохранён."
            } catch (failure: Throwable) {
                error = "Сборка APK не завершена: " +
                    (failure.message ?: failure.javaClass.simpleName)
            } finally {
                building = false
                cancellation = null
            }
        }
    }

    fun buildApk() {
        val staged = stagingOutcome ?: return
        startBuild(staged)
    }

    fun installBuiltPackage() {
        val built = buildResult ?: return
        if (builtInstallBusy || preparing || building || dexApplying) return

        val signal = AtomicCancellationSignal()
        cancellation = signal
        builtInstallBusy = true
        builtInstallNote = null
        builtInstallReadiness = null
        builtInstallSessionId = null

        scope.launch {
            try {
                val installPlan = withContext(Dispatchers.IO) {
                    RepackedRuntimeInstallPlanner.plan(
                        build = built,
                        cancellation = signal,
                    )
                }
                require(installPlan.ready) {
                    installPlan.blockers.firstOrNull()
                        ?: "Готовый APK не прошёл install preflight."
                }
                val readiness =
                    AndroidRepackedRuntimeInstaller.inspectReadiness(
                        context = context,
                        plan = installPlan,
                    )
                builtInstallReadiness = readiness
                when (readiness.state) {
                    RepackedRuntimeInstallReadinessState
                        .READY_NEW_INSTALL,
                    RepackedRuntimeInstallReadinessState
                        .READY_TEST_SIGNER_UPDATE -> {
                        val submission = withContext(Dispatchers.IO) {
                            AndroidRepackedRuntimeInstaller.submit(
                                context = context,
                                plan = installPlan,
                                cancellation = signal,
                            )
                        }
                        builtInstallSessionId = submission.sessionId
                        builtInstallNote =
                            "Android получил APK-set (" +
                                submission.apkCount +
                                " файлов). Подтверди системный запрос установки."
                    }
                    RepackedRuntimeInstallReadinessState
                        .UNKNOWN_SOURCES_PERMISSION_REQUIRED -> {
                        builtInstallNote =
                            "Разреши установку из ModKit в настройках Android " +
                                "и нажми «Установить» ещё раз."
                        context.startActivity(
                            AndroidRepackedRuntimeInstaller
                                .unknownSourcesSettingsIntent(context),
                        )
                    }
                    RepackedRuntimeInstallReadinessState
                        .INSTALLED_SIGNATURE_CONFLICT -> {
                        builtInstallNote =
                            "Установленная версия подписана другим ключом. " +
                                "Перед удалением сохрани игровые данные: " +
                                "Android не разрешает обновление поверх неё."
                    }
                }
            } catch (_: AnalysisCancelledException) {
                builtInstallNote = "Установка отменена."
            } catch (failure: Throwable) {
                builtInstallNote =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                builtInstallBusy = false
                if (cancellation === signal) cancellation = null
            }
        }
    }

    fun exportDexDiagnostics(scan: DexLocalScan) {
        if (dexReportBusy) return
        dexReportBusy = true
        scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    DexScanDiagnosticReport.write(
                        context = context,
                        targetName = target.label,
                        analysis = analysisResult,
                        scan = scan,
                    )
                }
                DexScanDiagnosticReport.share(context, report)
            } catch (failure: Throwable) {
                error = "Не удалось экспортировать диагностику: " +
                    (failure.message ?: failure.javaClass.simpleName)
            } finally {
                dexReportBusy = false
            }
        }
    }

    fun applySelectedDexChanges() {
        val scan = dexScan ?: return
        if (dexApplying || preparing || building || runtimeMenuBusy) return
        val selected = scan.opportunities.filter { it.id in dexSelectedIds }
        if (selected.isEmpty()) {
            error = "Отметьте хотя бы одно найденное изменение DEX."
            return
        }
        if (!dexDeveloperTestMode &&
            selected.any {
                it.category in setOf(
                    DexLocalCategory.FULL_VERSION,
                    DexLocalCategory.DEBUG_UI,
                )
            }
        ) {
            error = "Full/Premium и отладочные флаги требуют режима " +
                "тестирования собственной игры или приложения."
            return
        }
        val signal = AtomicCancellationSignal()
        cancellation = signal
        dexApplying = true
        error = null
        progress = null
        buildResult = null
        stagingOutcome = null

        scope.launch {
            try {
                val outcome = DexAutoModCoordinator.prepareAndApply(
                    context = context,
                    target = target,
                    analysis = analysisResult,
                    selected = selected,
                    developerTestMode = dexDeveloperTestMode,
                    cancellation = signal,
                    progress = ProgressSink { update ->
                        scope.launch { progress = update }
                    },
                )
                require(outcome.applied) {
                    outcome.blockers.joinToString("; ")
                        .ifBlank { "DEX staging не прошёл проверку." }
                }
                stagingOutcome = outcome
                dexApplying = false
                cancellation = null
                startBuild(outcome)
            } catch (_: AnalysisCancelledException) {
                error = "Изменение DEX отменено; исходное приложение не затронуто."
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                dexApplying = false
                if (cancellation === signal) cancellation = null
            }
        }
    }

    fun buildRuntimeTestMenu() {
        val prepared = plan ?: return
        if (runtimeMenuBusy || preparing || building) return

        val signal = AtomicCancellationSignal()
        cancellation = signal
        runtimeMenuBusy = true
        runtimeMenuBuild = null
        runtimeMenuInstallReadiness = null
        runtimeMenuNote = null
        error = null
        progress = null

        scope.launch {
            try {
                runtimeMenuBuild =
                    AutoModRuntimeTestMenuCoordinator.build(
                        context = context,
                        target = target,
                        result = analysisResult,
                        preparation = prepared,
                        cancellation = signal,
                        progress = ProgressSink { update ->
                            scope.launch { progress = update }
                        },
                    )
                runtimeMenuNote =
                    "Тестовая APK собрана. Установите её, затем запустите через ModKit."
            } catch (_: AnalysisCancelledException) {
                error = "Сборка runtime test menu отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                runtimeMenuBusy = false
                cancellation = null
            }
        }
    }

    fun installRuntimeTestMenu() {
        val prepared = runtimeMenuBuild ?: return
        if (runtimeMenuBusy || preparing || building) return

        val signal = AtomicCancellationSignal()
        cancellation = signal
        runtimeMenuBusy = true
        error = null
        runtimeMenuNote = null

        scope.launch {
            try {
                val installPlan =
                    withContext(Dispatchers.IO) {
                        RepackedRuntimeInstallPlanner.plan(
                            build = prepared.build,
                            cancellation = signal,
                        )
                    }
                require(installPlan.ready) {
                    installPlan.blockers.firstOrNull()
                        ?: "Тестовая APK не готова к установке."
                }
                val readiness =
                    AndroidRepackedRuntimeInstaller.inspectReadiness(
                        context = context,
                        plan = installPlan,
                    )
                runtimeMenuInstallReadiness = readiness
                when (readiness.state) {
                    RepackedRuntimeInstallReadinessState
                        .READY_NEW_INSTALL,
                    RepackedRuntimeInstallReadinessState
                        .READY_TEST_SIGNER_UPDATE -> {
                        withContext(Dispatchers.IO) {
                            AndroidRepackedRuntimeInstaller.submit(
                                context = context,
                                plan = installPlan,
                                cancellation = signal,
                            )
                        }
                        runtimeMenuNote =
                            "Запрос установки отправлен Android. Подтвердите установку системы."
                    }
                    RepackedRuntimeInstallReadinessState
                        .UNKNOWN_SOURCES_PERMISSION_REQUIRED -> {
                        runtimeMenuNote =
                            "Разрешите ModKit установку неизвестных приложений, затем нажмите установку ещё раз."
                        context.startActivity(
                            AndroidRepackedRuntimeInstaller
                                .unknownSourcesSettingsIntent(
                                    context,
                                ),
                        )
                    }
                    RepackedRuntimeInstallReadinessState
                        .INSTALLED_SIGNATURE_CONFLICT -> {
                        runtimeMenuNote =
                            "Оригинальное приложение с тем же packageName подписано другим ключом. " +
                                "Android не установит тестовую копию поверх него. ModKit не удаляет оригинал автоматически."
                    }
                }
            } catch (_: AnalysisCancelledException) {
                error = "Установка runtime test APK отменена."
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                runtimeMenuBusy = false
                cancellation = null
            }
        }
    }

    fun launchRuntimeTestMenu() {
        val prepared = runtimeMenuBuild ?: return
        if (runtimeMenuBusy || preparing || building) return

        runtimeMenuBusy = true
        error = null
        runtimeMenuNote = null
        scope.launch {
            try {
                val launched =
                    AutoModRuntimeTestMenuCoordinator
                        .configureAndLaunch(
                            context = context,
                            prepared = prepared,
                        )
                runtimeMenuNote =
                    "Test Menu загружено: " +
                        launched.menuStatus.patchItemCount +
                        " переключателей · " +
                        launched.menuStatus.infoItemCount +
                        " диагностических целей. " +
                        "Запущено: " +
                        launched.activityClassName
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                runtimeMenuBusy = false
            }
        }
    }

    val hasDex =
        analysisResult.index.entries.any { it.format == BinaryFormat.DEX }

    LaunchedEffect(analysisResult.index.artifactSha256, hasDex, dexRetry) {
        if (hasDex && dexScan == null && !dexLoading) {
            val signal = AtomicCancellationSignal()
            dexLoading = true
            dexScanError = null
            try {
                dexScan = DexAutoModCoordinator.scan(
                    context = context,
                    target = target,
                    analysis = analysisResult,
                    cancellation = signal,
                    progress = ProgressSink { },
                )
            } catch (_: AnalysisCancelledException) {
                dexScanError = "Поиск DEX отменён."
            } catch (failure: Throwable) {
                dexScanError =
                    failure.message ?: failure.javaClass.simpleName
            } finally {
                dexLoading = false
            }
        }
    }

    LaunchedEffect(
        analysisResult.index.artifactSha256,
        plan?.preparedAtEpochMs,
    ) {
        val prepared = plan
        if (prepared == null) {
            previewFindings = null
            previewFindingBusy = false
            previewFindingError = null
        } else {
            previewFindingBusy = true
            previewFindingError = null
            try {
                previewFindings =
                    withContext(Dispatchers.Default) {
                        GameplayModificationFinder.find(
                            result = analysisResult,
                            preparation = prepared,
                            projectCodeOnly = true,
                            limit = 256,
                            perCategoryLimit = 32,
                        )
                    }
            } catch (failure: Throwable) {
                previewFindings = emptyList()
                previewFindingError =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                previewFindingBusy = false
            }
        }
    }

    // Long game reports can span thousands of lines. Keep operation feedback
    // attached to the visible viewport instead of hiding it near the top.
    LaunchedEffect(error) {
        if (error?.startsWith("Сборка APK") == true) {
            showErrorDetails = true
        }
    }
    if (showErrorDetails && error != null) {
        AlertDialog(
            onDismissRequest = { showErrorDetails = false },
            title = { Text("Ошибка ModKit") },
            text = {
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showErrorDetails = false },
                ) { Text("Закрыть") }
            },
        )
    }

    Scaffold(
        bottomBar = {
            if (building || buildSaveBusy || error != null ||
                buildSaveMessage != null || buildResult != null ||
                builtInstallBusy || builtInstallNote != null
            ) {
                Surface(
                    tonalElevation = 5.dp,
                    shadowElevation = 5.dp,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        when {
                            error != null -> {
                                Text(
                                    "Ошибка операции — подробности здесь",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    error.orEmpty().take(230),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(12.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            showErrorDetails = true
                                        },
                                    ) { Text("Полная причина") }
                                    TextButton(
                                        onClick = {
                                            error = null
                                            showErrorDetails = false
                                        },
                                    ) { Text("Закрыть") }
                                }
                            }
                            building -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    progress?.currentTask
                                        ?: "APK выравнивается и подписывается…",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                progress?.currentArtifact?.let {
                                    Text(
                                        it,
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    "Не закрывайте приложение: большие split APK " +
                                        "могут обрабатываться несколько минут.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            buildSaveBusy -> {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    "Все APK подписаны. Сохраняем в Загрузки/ModKit…",
                                )
                            }
                            buildResult != null -> {
                                val built = requireNotNull(buildResult)
                                Text(
                                    if (builtInstallBusy) {
                                        "Передаём комплект APK в Android…"
                                    } else if (built.files.size > 1) {
                                        "Готова игра: " + built.files.size +
                                            " подписанных APK"
                                    } else {
                                        "Готовый подписанный APK"
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (builtInstallBusy) {
                                    LinearProgressIndicator(
                                        Modifier.fillMaxWidth(),
                                    )
                                }
                                Button(
                                    onClick = ::installBuiltPackage,
                                    enabled =
                                        !builtInstallBusy &&
                                            !preparing &&
                                            !dexApplying,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (builtInstallBusy) {
                                            "Проверка и установка…"
                                        } else if (built.files.size > 1) {
                                            "Установить игру (" +
                                                built.files.size + " APK)"
                                        } else {
                                            "Установить APK"
                                        },
                                    )
                                }
                                buildSaveMessage?.let { message ->
                                    Text(
                                        message,
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                }
                                builtInstallNote?.let { message ->
                                    Text(
                                        message,
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                        color =
                                            if (
                                                builtInstallReadiness?.state ==
                                                    RepackedRuntimeInstallReadinessState
                                                        .INSTALLED_SIGNATURE_CONFLICT
                                            ) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme
                                                    .onSurfaceVariant
                                            },
                                    )
                                }
                                if (
                                    builtInstallReadiness?.state ==
                                        RepackedRuntimeInstallReadinessState
                                            .INSTALLED_SIGNATURE_CONFLICT
                                ) {
                                    Text(
                                        "Не удаляйте оригинал без резервной " +
                                            "копии сохранений.",
                                        color = MaterialTheme.colorScheme.error,
                                        style =
                                            MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            buildSaveMessage != null -> {
                                Text(
                                    buildSaveMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color =
                                        if (
                                            buildSaveMessage
                                                ?.contains("не удалось") ==
                                                true
                                        ) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                )
                                buildResult?.let { built ->
                                    if (!buildSaveBusy) {
                                        OutlinedButton(
                                            onClick = {
                                                chooseBuildDestination(built)
                                            },
                                        ) { Text("Выбрать папку для APK") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier =
                Modifier.fillMaxSize()
                    .padding(scaffoldPadding)
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            OutlinedButton(onClick = onBack) { Text("← Назад к результатам") }
        }
        item {
            Text(
                "AutoMod / Patch Lab",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Здесь остаётся только пользовательский поток. Проверка SHA, точной привязки и готовности выполняется внутри.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (building || buildSaveBusy || buildResult != null ||
            buildSaveMessage != null
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Результат сборки",
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (buildSaveBusy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                "Подпись и проверка завершены. Сохраняем в " +
                                    "Загрузки → ModKit. Для больших игр " +
                                    "это может занять несколько минут.",
                            )
                        } else if (building) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                progress?.currentTask
                                    ?: "Подготовка, подпись и проверка APK…",
                            )
                            progress?.currentArtifact?.let {
                                Text(it)
                            }
                            Text(
                                "Пока выполняется сборка, итогового файла " +
                                    "в Downloads ещё нет.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        buildSaveMessage?.let { message ->
                            Text(
                                message,
                                color =
                                    if (message.contains("не удалось")) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                            )
                        }
                        buildResult?.let { built ->
                            val qualifier =
                                if (built.files.size > 1) {
                                    " · для установки требуется весь набор."
                                } else {
                                    ""
                                }
                            Text(
                                "Проверенных APK: " + built.files.size +
                                    qualifier,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!building && !buildSaveBusy) {
                                Button(
                                    onClick = ::installBuiltPackage,
                                    enabled = !builtInstallBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (builtInstallBusy) {
                                            "Передача Android…"
                                        } else if (built.files.size > 1) {
                                            "Установить игру (" +
                                                built.files.size + " APK)"
                                        } else {
                                            "Установить APK"
                                        },
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        saveBuildToDownloads(built)
                                    },
                                    enabled = buildPendingSafSave == null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (built.files.size > 1) {
                                            "Сохранить все APK в папку"
                                        } else {
                                            "Сохранить APK в Загрузки"
                                        },
                                    )
                                }
                                builtInstallNote?.let { message ->
                                    Text(
                                        message,
                                        color =
                                            MaterialTheme.colorScheme
                                                .onSurfaceVariant,
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

        item {
            val graph = analysisResult.evidenceGraph
            val summary = graph?.summary
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Текущие результаты", fontWeight = FontWeight.SemiBold)
                    Text("Цель: " + target.label)
                    Text(
                        "SHA: " + analysisResult.index.artifactSha256.take(16) + "…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (summary != null) {
                        Text(
                            "Точных методов: " + summary.confirmed +
                                " · готовых автопатчей: " + summary.ready +
                                " · на проверке: " + summary.confirming,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (
                            summary.confirmed > 0 &&
                            summary.ready == 0
                        ) {
                            Text(
                                "Точные методы уже найдены. Ноль готовых автопатчей " +
                                    "означает только то, что конкретные байты изменения " +
                                    "ещё не выбраны.",
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (summary.runtimeRequired > 0) {
                            Text(
                                "Требуется runtime: " + summary.runtimeRequired,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else {
                        Text(
                            "Система подтверждений для этой цели ещё не сформирована.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    analysisResult.il2cppFastDump
                        ?.metadata
                        ?.let {
                            metadata ->
                            Text(
                                "IL2CPP metadata найдено: " +
                                    metadata.methods.size +
                                    " методов · " +
                                    metadata.fields.size +
                                    " полей · " +
                                    metadata.types.size +
                                    " типов.",
                                style =
                                    MaterialTheme.typography
                                        .bodySmall,
                            )
                            val binding =
                                analysisResult
                                    .il2cppBinaryBinding
                            if (
                                binding != null &&
                                !binding
                                    .exactBindingAvailable
                            ) {
                                Text(
                                    "Важно: код и metadata найдены. Ноль exact-методов означает, " +
                                        "что не завершилось только сопоставление MethodDef → адрес в libil2cpp.so, " +
                                        "а не что в игре нет методов.",
                                    style =
                                        MaterialTheme.typography
                                            .bodySmall,
                                )
                                val memoryWarning =
                                    binding.warnings
                                        .firstOrNull {
                                            warning ->
                                            warning.contains(
                                                "allocate",
                                                ignoreCase =
                                                    true,
                                            ) ||
                                                warning.contains(
                                                    "OOM",
                                                    ignoreCase =
                                                        true,
                                                ) ||
                                                warning.contains(
                                                    "heap",
                                                    ignoreCase =
                                                        true,
                                                )
                                        }
                                if (
                                    memoryWarning != null
                                ) {
                                    Text(
                                        "Предыдущая точная привязка упёрлась в лимит памяти Android. " +
                                            "В этой версии используется bounded/compact binding и low-memory retry.",
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
                            }
                        }

                    val runtimes =
                        analysisResult.index.runtimeProfiles
                    if (runtimes.isNotEmpty()) {
                        Text(
                            "Найденные технологии: " +
                                runtimes.joinToString {
                                    it.title
                                },
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                    }
                    val hasIl2Cpp =
                        runtimes.any {
                            it.runtimeId ==
                                "unity_il2cpp"
                        }
                    if (!hasIl2Cpp) {
                        Text(
                            "Для этой цели Unity/IL2CPP не подтверждён. " +
                                "Текущий native Patch Lab не должен использоваться " +
                                "как универсальный редактор Android-приложений: " +
                                "DEX/обычный NDK требуют отдельного mutation executor.",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                    }
                    if (hasIl2Cpp) {
                        OutlinedButton(
                            onClick = ::exportDiagnosticReport,
                            enabled =
                                !exportingReport &&
                                    !preparing &&
                                    !building,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (exportingReport) {
                                    "Формирование отчёта…"
                                } else {
                                    "Экспортировать полный отчёт с dump"
                                },
                            )
                        }
                        Text(
                            "ZIP содержит dump.cs, metadata methods/types/fields, " +
                                "exact binary bindings, return types, file offsets, " +
                                "shared bodies и снимок AutoMod. Его можно прислать сюда для разбора.",
                            style =
                                MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (preparing || building || runtimeMenuBusy) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            progress?.currentTask
                                ?: when {
                                    runtimeMenuBusy -> "Runtime Test Menu…"
                                    building -> "Сборка APK…"
                                    else -> "Подготовка изменений…"
                                },
                            fontWeight = FontWeight.SemiBold,
                        )
                        progress?.currentArtifact?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        progress?.processed?.let { processed ->
                            Text(
                                progress?.total?.let { total -> "$processed / $total" }
                                    ?: "Обработано: $processed",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(
                            onClick = { cancellation?.cancel() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    runtimeMenuBusy -> "Отменить"
                                    building -> "Отменить сборку"
                                    else -> "Отменить подготовку"
                                },
                            )
                        }
                    }
                }
            }
        }

        error?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error)
            }
        }

        confirmationNote?.let { message ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        message,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (!hasDex) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "В APK не обнаружено DEX-кода",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Нельзя предложить DEX-патчи, если нет classes.dex. " +
                                "Для Unity/IL2CPP используйте подготовку и Native Patch Lab. " +
                                "Нативные библиотеки нельзя модифицировать DEX-шаблонами.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = {
                                exportDexDiagnostics(
                                    DexLocalScan(
                                        opportunities = emptyList(),
                                        warnings = emptyList(),
                                        dexFilesExamined = 0,
                                        methodsExamined = 0,
                                    ),
                                )
                            },
                            enabled = !dexReportBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Экспортировать отчёт о причинах")
                        }
                    }
                }
            }
        }

        if (hasDex) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Автомодификации Android DEX",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "ModKit ищет методы твоего приложения, проверяет сигнатуру, " +
                                "предлагает конкретные изменения и пересобирает DEX. " +
                                "Исходные APK не изменяются.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = dexDeveloperTestMode,
                                onCheckedChange = { enabled ->
                                    dexDeveloperTestMode = enabled
                                    if (!enabled) {
                                        val blockedIds =
                                            dexScan?.opportunities.orEmpty()
                                                .filter {
                                                    it.category in setOf(
                                                        DexLocalCategory.FULL_VERSION,
                                                        DexLocalCategory.DEBUG_UI,
                                                    )
                                                }
                                                .map { it.id }.toSet()
                                        dexSelectedIds -= blockedIds
                                    }
                                    stagingOutcome = null
                                    buildResult = null
                                },
                            )
                            Text(
                                "Тестирую свою игру/приложение: Full/Premium и debug-флаги",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (dexLoading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Проверяем DEX-классы и методы…")
                        }
                        dexScanError?.let { message ->
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = {
                                    dexScan = null
                                    dexLoading = false
                                    dexRetry++
                                },
                            ) {
                                Text("Повторить поиск DEX")
                            }
                        }
                        val current = dexScan
                        if (current != null) {
                            Text(
                                "Проверено: " + current.dexFilesExamined +
                                    " DEX · " + current.methodsExamined +
                                    " методов · найдено: " +
                                    current.opportunities.size,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (current.excludedAmbiguousProgressionNames > 0) {
                                Text(
                                    "Не показано " +
                                        current.excludedAmbiguousProgressionNames +
                                        " неоднозначных совпадений Level/Experience. " +
                                        "Это может быть уровень логирования, " +
                                        "профиль пользователя или иная логика " +
                                        "обычного приложения, а не игровой опыт.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val visible =
                                if (dexShowAll) current.opportunities
                                else current.opportunities.take(48)
                            if (current.opportunities.isEmpty()) {
                                Text(
                                    current.explanation,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    "Найдено в DEX: классов " +
                                        current.classesInspected +
                                        " · исключено библиотечных " +
                                        current.classesExcluded +
                                        " · методов с кодом " +
                                        current.methodsWithCode +
                                        " · сигналы игровых имён " +
                                        current.semanticNamesMatched +
                                        " · несовместимые сигнатуры " +
                                        current.rejectedReturnTypes +
                                        ".",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (current.nativeLibrariesObserved > 0) {
                                    Text(
                                        "Обнаружено нативных библиотек: " +
                                            current.nativeLibrariesObserved +
                                            ". Игровая логика может находиться в .so.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (
                                    analysisResult.index.runtimeProfiles.any {
                                        it.runtimeId == "unity_il2cpp"
                                    }
                                ) {
                                    Text(
                                        "Обнаружен Unity/IL2CPP: нажмите " +
                                            "«Подготовить изменения» и откройте Patch Lab.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            visible.forEach { opportunity ->
                                val allowed = opportunity.selectable &&
                                    (dexDeveloperTestMode ||
                                        opportunity.category !in setOf(
                                            DexLocalCategory.FULL_VERSION,
                                            DexLocalCategory.DEBUG_UI,
                                        ))
                                Row(Modifier.fillMaxWidth()) {
                                    Checkbox(
                                        checked =
                                            opportunity.id in dexSelectedIds,
                                        enabled =
                                            allowed &&
                                                !dexApplying &&
                                                !building,
                                        onCheckedChange = { checked ->
                                            dexSelectedIds =
                                                if (checked) dexSelectedIds + opportunity.id
                                                else dexSelectedIds - opportunity.id
                                            stagingOutcome = null
                                            buildResult = null
                                        },
                                    )
                                    Column {
                                        Text(
                                            opportunity.category.label +
                                                ": " + opportunity.action.label,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            opportunity.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            if (allowed) opportunity.reason
                                            else "Для тестовых флагов отметьте режим собственной игры/приложения.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (current.opportunities.size > 48) {
                                OutlinedButton(
                                    onClick = { dexShowAll = !dexShowAll },
                                ) {
                                    Text(
                                        if (dexShowAll) "Свернуть список"
                                        else "Показать все (" +
                                            current.opportunities.size + ")",
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { exportDexDiagnostics(current) },
                                enabled = !dexReportBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (dexReportBusy) {
                                        "Создаём диагностический отчёт…"
                                    } else {
                                        "Экспортировать подробный отчёт сканирования"
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    dexScan = null
                                    dexSelectedIds = emptySet()
                                    stagingOutcome = null
                                    buildResult = null
                                    dexRetry++
                                },
                                enabled = !dexLoading && !dexApplying && !building,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Сканировать ещё раз")
                            }
                            current.warnings.take(4).forEach { warning ->
                                Text(
                                    "Внимание: " + warning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                "Выбрано: " + dexSelectedIds.size,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = ::applySelectedDexChanges,
                                enabled =
                                    dexSelectedIds.isNotEmpty() &&
                                        !dexApplying &&
                                        !preparing &&
                                        !building &&
                                        !runtimeMenuBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (dexApplying) "Применяем DEX…"
                                    else "Применить выбранное и собрать APK",
                                )
                            }
                            if (dexApplying) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(progress?.currentTask ?: "Подготовка DEX…")
                                OutlinedButton(
                                    onClick = { cancellation?.cancel() },
                                ) {
                                    Text("Отменить")
                                }
                            }
                        }
                    }
                }
            }
        }

        plan?.let { prepared ->
            val manualEligibleCount = prepared.targets.count { candidate ->
                (
                    candidate.status ==
                        PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE ||
                        candidate.status ==
                        PreparationTargetStatus.READY
                    ) &&
                    candidate.target.runtimeId == "unity_il2cpp" &&
                    candidate.target.fileOffset != null &&
                    candidate.target.abi != null
            }
            val projectCodeCount =
                prepared.targets.count { candidate ->
                    (
                        candidate.status ==
                            PreparationTargetStatus
                                .CONFIRMED_NEEDS_CHANGE ||
                            candidate.status ==
                            PreparationTargetStatus.READY
                        ) &&
                        candidate.target.runtimeId ==
                        "unity_il2cpp" &&
                        candidate.target.fileOffset != null &&
                        candidate.target.abi != null &&
                        Il2CppPatchTargetBrowser
                            .isProjectCode(
                                candidate.target,
                            )
                }
            item {
                Card(Modifier.fillMaxWidth()) {
                    val summary = prepared.summary
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("План подготовки", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (prepared.sourceShaVerified) {
                                "Исходная версия подтверждена по SHA-256."
                            } else {
                                "Исходная версия не подтверждена — применение заблокировано."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Целей: " + summary.found +
                                " · точных binary-привязок: " + summary.confirmed +
                                " · готовых автопатчей: " + summary.ready +
                                " · нужен ещё proof: " + summary.requireConfirmation,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (summary.runtimeRequired > 0 || summary.blocked > 0) {
                            Text(
                                "Runtime: " + summary.runtimeRequired +
                                    " · заблокировано: " + summary.blocked,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        prepared.globalBlockers.forEach {
                            Text("• " + it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            "Что можно менять",
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        if (projectCodeCount > 0) {
                            Text(
                                "Код самой игры/приложения " +
                                    "(игровые сборки): " +
                                    projectCodeCount +
                                    " подтверждённых методов.",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                            )
                        }
                        Text(
                            "Всего точных IL2CPP-методов: " +
                                manualEligibleCount +
                                ". Библиотеки Unity/.NET и плагины " +
                                "по умолчанию скрываются внутри Patch Lab, " +
                                "чтобы не смешивать их с кодом проекта.",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                        Text(
                            "Локальные Full/Premium проверки своего проекта доступны без " +
                                "ввода ключей; выбирайте найденные модификации ниже.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Patch Lab сначала показывает найденные категории модификаций " +
                                "с галочками: здоровье/урон, движение, stamina, cooldown, " +
                                "коллизии, опыт/уровень, inventory и другие. " +
                                "Название метода остаётся только evidence для поиска кандидата; " +
                                "конкретный patch всё равно проходит preflight.",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                    }
                }
            }

            item {
                if (manualEligibleCount > 0) {
                    Button(
                        onClick = { showManualPatch = !showManualPatch },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (showManualPatch) {
                                "Скрыть ручной Patch Lab"
                            } else {
                                if (projectCodeCount > 0) {
                                    "Найти и выбрать модификации (" +
                                        projectCodeCount +
                                        " методов проекта)"
                                } else {
                                    "Найти модификации в IL2CPP (" +
                                        manualEligibleCount +
                                        " методов)"
                                }
                            },
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("IL2CPP Patch Lab недоступен")
                    }
                    Text(
                        "Нет подтверждённых IL2CPP-методов с точным file offset. " +
                            "Для обычного Android DEX/NDK это другой тип изменений; " +
                            "он не подменяется IL2CPP-шаблонами.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (showManualPatch) {
                item {
                    ManualNativePatchSection(
                        target = target,
                        analysis = analysisResult,
                        preparation = prepared,
                        onStagingReady = { outcome ->
                            stagingOutcome = outcome
                            buildResult = null
                        },
                        onStagingInvalidated = {
                            stagingOutcome = null
                            buildResult = null
                        },
                        onBuildRequested = { outcome ->
                            stagingOutcome = outcome
                            buildResult = null
                            startBuild(outcome)
                        },
                        externalBusy =
                            building || preparing || runtimeMenuBusy || dexApplying,
                    )
                }
            }
        }

        plan?.let { prepared ->
            item {
                val currentPreview =
                    previewFindings.orEmpty()
                val previewPatchCount =
                    currentPreview.count {
                        it.selectable
                    }
                val previewInfoCount =
                    currentPreview.size -
                        previewPatchCount
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            "ModKit Test Menu",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (previewFindingBusy) {
                                "Поиск модификаций выполняется в фоне…"
                            } else {
                                "Проверяемые runtime-переключатели: " +
                                    previewPatchCount +
                                    " · диагностические цели: " +
                                    previewInfoCount +
                                    "."
                            },
                            style =
                                MaterialTheme.typography.bodySmall,
                        )
                        previewFindingError?.let { message ->
                            Text(
                                "Поиск модификаций: " + message,
                                color = MaterialTheme.colorScheme.error,
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Переключатели создаются только для exact локальных целей. " +
                                "Billing/auth/anti-cheat и server-backed RNG показываются как INFO без bypass-патча.",
                            style =
                                MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = ::buildRuntimeTestMenu,
                            enabled =
                                !runtimeMenuBusy &&
                                    !preparing &&
                                    !building &&
                                    !previewFindingBusy &&
                                    currentPreview.isNotEmpty(),
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (runtimeMenuBusy) {
                                    "Подготовка Test Menu…"
                                } else {
                                    "Собрать тестовую игру с оверлеем"
                                },
                            )
                        }
                        runtimeMenuBuild?.let { built ->
                            Text(
                                "Собрано: " +
                                    built.menu.patchItemCount +
                                    " переключателей · " +
                                    built.menu.infoItemCount +
                                    " INFO · package " +
                                    built.build.packageName,
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(
                                onClick = ::installRuntimeTestMenu,
                                enabled =
                                    !runtimeMenuBusy &&
                                        !preparing &&
                                        !building,
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text("Установить тестовую игру")
                            }
                            Button(
                                onClick = ::launchRuntimeTestMenu,
                                enabled =
                                    !runtimeMenuBusy &&
                                        !preparing &&
                                        !building,
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text("Запустить с ModKit Test Menu")
                            }
                        }
                        runtimeMenuInstallReadiness?.let {
                            Text(
                                "Install: " +
                                    it.state.name,
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                        }
                        runtimeMenuNote?.let {
                            Text(
                                it,
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = ::prepareChanges,
                enabled = !preparing && !building && !runtimeMenuBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (plan == null) "Подготовить изменения" else "Обновить подготовку")
            }
        }

        item {
            Button(
                onClick = ::buildApk,
                enabled =
                    stagingOutcome?.applied == true &&
                        !preparing &&
                        !building &&
                        !runtimeMenuBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (building) "Сборка…" else "Собрать APK")
            }
            Text(
                when {
                    buildResult != null ->
                        "APK собран, выровнен, подписан и проверен."
                    plan == null ->
                        "Сначала выполните подготовку изменений."
                    stagingOutcome?.applied == true ->
                        "Staging проверен. Сборка выполнит align → sign → verify → mutation diff check."
                    plan?.automaticApplyAllowed != true ->
                        "Сборка заблокирована, пока нет полностью подготовленного изменения."
                    else ->
                        "Отметьте готовые моды в Patch Lab и нажмите «Применить и собрать APK». " +
                            "Эта кнопка сама выполнит staging, подпись и проверку; " +
                            "нижняя кнопка сборки нужна только для уже подготовленного APK."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        buildResult?.let { built ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            if (built.files.size > 1) {
                                "Готовая игра · " + built.files.size +
                                    " APK (split-комплект)"
                            } else {
                                "Готовый APK"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Подпись: автоматический тестовый ключ ModKit",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { saveBuildToDownloads(built) },
                            enabled =
                                !buildSaveBusy &&
                                    !building &&
                                    !preparing &&
                                    !dexApplying,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (buildSaveBusy) {
                                    "Сохраняем на устройство…"
                                } else if (Build.VERSION.SDK_INT >=
                                    Build.VERSION_CODES.Q
                                ) {
                                    if (built.files.size == 1) {
                                        "Сохранить APK в Downloads/ModKit"
                                    } else {
                                        "Сохранить все APK в отдельную папку"
                                    }
                                } else {
                                    "Сохранить через системный выбор папки"
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = { chooseBuildDestination(built) },
                            enabled =
                                !buildSaveBusy &&
                                    !building &&
                                    !preparing &&
                                    !dexApplying &&
                                    buildPendingSafSave == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (built.files.size > 1) {
                                    "Сохранить резервный ZIP в выбранную папку"
                                } else {
                                    "Выбрать другую папку"
                                },
                            )
                        }
                        buildSaveMessage?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (message.startsWith("Не удалось")) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                            )
                        }
                        Button(
                            onClick = ::installBuiltPackage,
                            enabled =
                                !builtInstallBusy &&
                                    !building &&
                                    !preparing &&
                                    !dexApplying,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (builtInstallBusy) "Проверяем и устанавливаем…"
                                else if (built.files.size == 1) "Установить APK"
                                else "Установить игру (" +
                                    built.files.size + " APK)",
                            )
                        }
                        builtInstallNote?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val readiness = builtInstallReadiness
                        if (
                            readiness?.state ==
                                RepackedRuntimeInstallReadinessState
                                    .INSTALLED_SIGNATURE_CONFLICT
                        ) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        AndroidRepackedRuntimeInstaller
                                            .uninstallConflictIntent(
                                                readiness.packageName,
                                            ),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Открыть системное удаление оригинала")
                            }
                            Text(
                                "Удаление может стереть сохранения. " +
                                    "ModKit не удаляет оригинал автоматически.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (
                            builtInstallSessionId != null &&
                            installStatus.sessionId ==
                                builtInstallSessionId
                        ) {
                            Text(
                                "Установка Android: " +
                                    installStatus.kind.name +
                                    " · " +
                                    installStatus.message.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        Text(
                            "Android не установит этот APK поверх версии с другой подписью. " +
                                "Перед заменой оригинала сохраните свои данные.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        built.signerCertificateSha256.firstOrNull()?.let { fingerprint ->
                            Text(
                                "Сертификат SHA-256: " + fingerprint.take(20) + "…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        built.files.forEach { file ->
                            Text(
                                file.file.name + " · " + file.file.length() +
                                    " байт · SHA-256 " + file.sha256.take(20) + "…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Mutation diff: подтверждён · файлов: " + built.files.size,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Устанавливаемость: подтверждена · package " +
                                (built.installability.packageName ?: "не определён"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val post = built.postBuildAnalysis
                        Text(
                            "Повторный анализ: runtime " +
                                post.index.runtimeProfiles.size +
                                " · подтверждений " +
                                (post.evidenceGraph?.targets?.size ?: 0),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    BuildArtifactExporter.share(context, built)
                                }.onFailure { failure ->
                                    error = failure.message ?: failure.javaClass.simpleName
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (built.files.size == 1) {
                                    "Экспортировать APK"
                                } else {
                                    "Экспортировать APK-set"
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    BuildArtifactExporter.shareReport(context, built)
                                }.onFailure { failure ->
                                    error = failure.message ?: failure.javaClass.simpleName
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Экспортировать отчёт сборки")
                        }
                    }
                }
            }
        }
        }
    }
}

private fun preparationStatusLabel(status: PreparationTargetStatus): String = when (status) {
    PreparationTargetStatus.READY -> "Готово к применению"
    PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE ->
        "Цель подтверждена · нужно выбрать конкретное изменение"
    PreparationTargetStatus.NEEDS_CONFIRMATION -> "Нужно дополнительное подтверждение"
    PreparationTargetStatus.RUNTIME_REQUIRED -> "Требуется runtime-подтверждение"
    PreparationTargetStatus.BLOCKED -> "Изменение заблокировано"
}
