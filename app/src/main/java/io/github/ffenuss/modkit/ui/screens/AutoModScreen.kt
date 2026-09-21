package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AutoModScreen(
    target: AnalysisTargetDescriptor,
    result: FastAnalysisResult,
    onBack: () -> Unit,
    onOpenRootRuntime: (FastAnalysisResult) -> Unit = { },
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

        scope.launch {
            try {
                buildResult = VerifiedBuildPipeline.build(
                    context = context,
                    stagingOutcome = staged,
                    cancellation = signal,
                    progress = ProgressSink { update ->
                        scope.launch { progress = update }
                    },
                )
            } catch (_: AnalysisCancelledException) {
                error = "Сборка отменена. Staging APK сохранён."
            } catch (failure: Throwable) {
                error = failure.message ?: failure.javaClass.simpleName
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
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

        if (
            target is
                AnalysisTargetDescriptor
                    .InstalledPackage
        ) {
            item {
                val runtimeRequired =
                    analysisResult.evidenceGraph
                        ?.summary
                        ?.runtimeRequired
                        ?: 0
                Card(
                    Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                7.dp,
                            ),
                    ) {
                        Text(
                            "Root / Live Memory",
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        Text(
                            if (
                                runtimeRequired > 0
                            ) {
                                "Статический анализ оставил " +
                                    runtimeRequired +
                                    " runtime-целей. Можно сразу открыть текущую игру в Root Process Lab без повторного выбора APK."
                            } else {
                                "Открыть текущую установленную игру в Root Process Lab: attach к процессу, live memory scan, unknown value, pointer scan, verified write и freeze."
                            },
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                        Button(
                            onClick = {
                                onOpenRootRuntime(
                                    analysisResult,
                                )
                            },
                            enabled =
                                !preparing &&
                                    !building &&
                                    !runtimeMenuBusy,
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Открыть Root Process Lab",
                            )
                        }
                        Text(
                            "Root не запускается автоматически: запрос superuser появится при первом privileged действии.",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
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
                            .isAssemblyCSharp(
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
                                    "(Assembly-CSharp): " +
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
                        "Нужно сначала применить проверенное изменение в staging APK."
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
                        Text("Готовый APK", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Подпись: локальный тестовый ключ ModKit · " + built.signerAlias,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Для обновления уже установленного оригинального приложения " +
                                "нужен совместимый ключ владельца; локальная подпись ModKit его не заменяет.",
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

private fun preparationStatusLabel(status: PreparationTargetStatus): String = when (status) {
    PreparationTargetStatus.READY -> "Готово к применению"
    PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE ->
        "Цель подтверждена · нужно выбрать конкретное изменение"
    PreparationTargetStatus.NEEDS_CONFIRMATION -> "Нужно дополнительное подтверждение"
    PreparationTargetStatus.RUNTIME_REQUIRED -> "Требуется runtime-подтверждение"
    PreparationTargetStatus.BLOCKED -> "Изменение заблокировано"
}
