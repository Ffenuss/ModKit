package io.github.ffenuss.modkit.ui

import android.app.Application
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.build.BuildArtifactExporter
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.patch.*
import io.github.ffenuss.modkit.runtime.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AutoModUiState(
    val busy: Boolean = true,
    val operation: String = "Подготовка анализа",
    val progress: EngineProgress? = null,
    val recipes: List<AutoModRecipe> = emptyList(),
    val selected: Set<String> = emptySet(),
    val error: String? = null,
    val notice: String? = null,
    val methodsExamined: Int = 0,
    val built: AutoModBuildRecord? = null,
    val showingResult: Boolean = false,
    val needsInstallPermission: Boolean = false,
    val installSession: Int? = null,
)

/** Operation lifetime belongs to the Activity ViewModel, not a scrolling/recomposed card. */
class AutoModViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AutoModUiState())
    val state = mutable.asStateFlow()
    private var signal = AtomicCancellationSignal()
    private var job: Job? = null
    private var key: String? = null
    private lateinit var target: AnalysisTargetDescriptor
    private lateinit var analysis: FastAnalysisResult
    private lateinit var preparation: PatchPreparationPlan
    private val sink = ProgressSink { update -> mutable.update { it.copy(progress = update) } }

    fun initialize(target: AnalysisTargetDescriptor, result: FastAnalysisResult) {
        if (key == result.index.artifactSha256) return
        signal.cancel()
        job?.cancel()
        this.target = target
        analysis = result
        key = result.index.artifactSha256
        mutable.value = AutoModUiState()
        discover()
    }

    fun discover() = operate("Проверяем доступные изменения") {
        val restored = withContext(Dispatchers.IO) { AutoModBuildRecord.load(context, analysis.index.artifactSha256) }
        val dex = DexAutoModCoordinator.scan(context, target, analysis, signal, sink)
        val native = if (analysis.il2cppFastDump != null || analysis.il2cppBinaryBinding != null) {
            val prepared = AutoModPreparationCoordinator.prepare(context, target, analysis, signal, sink)
            analysis = prepared.analysisResult
            preparation = prepared.plan
            withContext(Dispatchers.IO) {
                NativeRecipeCatalog.create(analysis, preparation, File(context.filesDir, "analysis-results"), signal)
            }
        } else {
            preparation = PatchPreparationPlan(analysis.index.artifactSha256, false,
                System.currentTimeMillis(), emptyList(), emptyList())
            emptyList()
        }
        val preferences = context.getSharedPreferences("automod-selection", 0)
        val recipes = (DexRecipeCatalog.create(dex) + native).map { recipe ->
            preferences.getString(analysis.index.artifactSha256 + ":value:" + recipe.id, null)
                ?.let(recipe::withScalarValue) ?: recipe
        }
        val saved = preferences.getStringSet(analysis.index.artifactSha256, emptySet()).orEmpty()
        mutable.update { it.copy(recipes = recipes, methodsExamined = dex.methodsExamined,
            selected = saved.intersect(recipes.filter { r -> r.selectable }.map { r -> r.id }.toSet()),
            built = it.built ?: restored, showingResult = restored != null,
            notice = (dex.warnings + analysis.engineWarnings).firstOrNull()) }
    }

    fun toggle(id: String) {
        if (state.value.busy || state.value.recipes.none { it.id == id && it.selectable }) return
        mutable.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
        context.getSharedPreferences("automod-selection", 0).edit()
            .putStringSet(analysis.index.artifactSha256, state.value.selected).apply()
    }

    fun setScalarValue(id: String, value: String) {
        if (state.value.busy) return
        mutable.update { it.copy(recipes = it.recipes.map { r -> if (r.id == id) r.withScalarValue(value) else r }) }
        context.getSharedPreferences("automod-selection", 0).edit()
            .putString(analysis.index.artifactSha256 + ":value:" + id, value).apply()
    }

    fun build() = operate("Создаём мод") {
        val selected = state.value.recipes.filter { it.id in state.value.selected }
        require(selected.isNotEmpty()) { "Сначала выберите изменения." }

        val record: AutoModBuildRecord
        val nativeSelected = selected.any { it.native != null }
        if (nativeSelected) {
            // No selected native change is written into the output APK.
            // Runtime patches are initially OFF and only toggled with consent.
            require(selected.all(RuntimeRecipeSelectionPolicy::supports)) {
                "Часть выбранных модов пока не поддерживает отключение в игре. Уберите их из выбора."
            }
            val result = AutoModRuntimeTestMenuCoordinator.build(
                context = context,
                target = target,
                result = analysis,
                preparation = preparation,
                cancellation = signal,
                progress = sink,
                selected = selected,
            )
            val plan = withContext(Dispatchers.IO) {
                RepackedRuntimeInstallPlanner.plan(result.build, signal)
            }
            require(plan.ready) { plan.blockers.joinToString("; ") }
            record = AutoModBuildRecord(
                plan = plan,
                builtAt = result.build.completedAtEpochMs,
                changes = selected.map { it.title + " · " + it.targetLabel },
                reportPath = result.build.reportPath,
                runtimeMenuItems = result.menu.items,
            )
        } else {
            // Preserve the pre-existing DEX workflow while its reversible
            // runtime backend is developed. The UI labels it as static.
            val built = AutoModBuildCoordinator.build(
                context, target, analysis, preparation, selected, signal, sink,
            ) {
                mutable.update { current -> current.copy(recipes = current.recipes.map {
                    if (it.id in current.selected) it.copy(
                        verification = it.verification.copy(staticVerified = true),
                    ) else it
                }) }
            }
            val plan = withContext(Dispatchers.IO) {
                RepackedRuntimeInstallPlanner.plan(built, signal)
            }
            require(plan.ready) { plan.blockers.joinToString("; ") }
            record = AutoModBuildRecord(
                plan, built.builtAtEpochMs,
                selected.map { it.title + " · " + it.targetLabel },
                built.reportFile.absolutePath,
            )
        }
        withContext(Dispatchers.IO) { record.save(context) }
        mutable.update { current -> current.copy(
            built = record,
            showingResult = true,
            installSession = null,
            notice = if (record.runtimeMenuItems.isNotEmpty()) {
                "Создан APK с ${record.runtimeMenuItems.size} переключателями. Все моды изначально выключены."
            } else {
                "Это статическая DEX-сборка: переключатели для неё пока не поддерживаются."
            },
            recipes = current.recipes.map { recipe ->
                if (recipe.id in current.selected) recipe.copy(
                    verification = recipe.verification.copy(apkBuilt = true),
                ) else recipe
            },
        ) }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val saved = withContext(Dispatchers.IO) {
                    BuildArtifactExporter.saveApkFilesToDownloads(context, record.plan, record.builtAt)
                }
                mutable.update { it.copy(
                    notice = (it.notice.orEmpty() + " APK: " + saved.destinationDirectory).take(360),
                ) }
            } catch (failure: Exception) {
                mutable.update { it.copy(
                    notice = "Сборка сохранена в ModKit. Экспорт в Загрузки не выполнен: ${failure.message}",
                ) }
            }
        }
    }

    fun launchWithOverlay() = operate("Запускаем с переключателями") {
        val record = requireNotNull(state.value.built)
        require(record.runtimeMenuItems.isNotEmpty()) {
            "В этой сборке нет переключателей. Создайте сборку с нативными модами."
        }
        require(Settings.canDrawOverlays(context)) {
            "Разрешите ModKit показывать окна поверх игр."
        }
        val install = RepackedRuntimeInstallStatusStore.status.value
        require(install.kind == RepackedRuntimeInstallStatusKind.SUCCESS &&
            install.packageName == record.plan.packageName &&
            install.updatedAtEpochMs >= record.builtAt
        ) {
            "Сначала установите и подтвердите эту сборку в Android."
        }
        val authority = record.plan.packageName +
            BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX
        val transport = AndroidRepackedRuntimeProbeTransport(context)
        val installed = withContext(Dispatchers.IO) {
            transport.inspectInstalled(record.plan.packageName, authority)
        } ?: error("Установленная сборка не содержит проверенный runtime probe.")
        require(installed.providerClassName == BinaryAndroidManifestProbeInjector.PROVIDER_CLASS &&
            installed.exported && installed.enabled &&
            installed.signerCertificateSha256.map(String::lowercase).toSet() ==
                record.plan.signerCertificateSha256.map(String::lowercase).toSet()
        ) { "Сертификат или runtime-компонент установленной сборки не соответствует собранному APK." }

        val configured = withContext(Dispatchers.IO) {
            transport.configureTestMenu(authority, record.runtimeMenuItems)
        }
        require(configured.packageName == record.plan.packageName &&
            configured.patchItemCount == record.runtimeMenuItems.size
        ) { "Установленная игра не подтвердила конфигурацию переключателей." }
        val launch = context.packageManager.getLaunchIntentForPackage(record.plan.packageName)
            ?: error("Установленная сборка не имеет доступного экрана запуска.")
        // Start while the ModKit activity is visible; Android 12+ disallows
        // arbitrary starts of foreground services from the background.
        ModKitRuntimeOverlayService.start(context, record.plan.artifactSha256)
        context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        mutable.update { it.copy(notice = "Игра запущена. Нажмите MK для переключения модов.") }
    }

    fun install() = operate("Готовим установку") {
        mutable.update { it.copy(needsInstallPermission = false) }
        val record = requireNotNull(state.value.built)
        val readiness = withContext(Dispatchers.IO) { AndroidRepackedRuntimeInstaller.inspectReadiness(context, record.plan) }
        when (readiness.state) {
            RepackedRuntimeInstallReadinessState.UNKNOWN_SOURCES_PERMISSION_REQUIRED ->
                mutable.update { it.copy(needsInstallPermission = true, notice = "Разрешите установку из ModKit. После возврата установка продолжится.") }
            RepackedRuntimeInstallReadinessState.INSTALLED_SIGNATURE_CONFLICT ->
                mutable.update { it.copy(error = "Установленная версия подписана другим ключом. Android не разрешает обновить её этим APK. " +
                    "Перед самостоятельным удалением оригинала сохраните игровые данные: локальные сохранения могут быть потеряны.") }
            else -> {
                val submitted = withContext(Dispatchers.IO) {
                    AndroidRepackedRuntimeInstaller.submit(context, record.plan, signal) { copied, total, name ->
                        mutable.update { it.copy(operation = "Передаём APK Android · ${copied / 1048576} / ${total / 1048576} МБ", notice = name) }
                    }
                }
                mutable.update { it.copy(installSession = submitted.sessionId, notice = "Комплект APK передан Android. Ожидаем подтверждение.") }
            }
        }
    }

    fun permissionReturned() {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else mutable.update { it.copy(needsInstallPermission = false, notice = "Разрешение не выдано. Можно повторить установку.") }
    }
    fun permissionLaunched() { mutable.update { it.copy(needsInstallPermission = false) } }

    fun save(tree: Uri? = null) = operate("Сохраняем APK") {
        val record = requireNotNull(state.value.built)
        val saved = withContext(Dispatchers.IO) {
            if (tree != null) BuildArtifactExporter.writeApkFilesToTree(context, record.plan, record.builtAt, tree)
            else if (Build.VERSION.SDK_INT >= 29) {
                BuildArtifactExporter.saveApkFilesToDownloads(context, record.plan, record.builtAt)
            } else error("Для Android 8/9 выберите папку сохранения.")
        }
        mutable.update { it.copy(notice = "Сохранено APK: ${saved.files.size} · ${saved.destinationDirectory}") }
    }

    fun exportDiagnostics() = operate("Подготавливаем диагностику") {
        val recipes = state.value.recipes
        val report = withContext(Dispatchers.IO) {
            PatchLabDiagnosticReportWriter.write(File(context.filesDir, "expert-lab-export"), target.label,
                analysis, if (::preparation.isInitialized) preparation else null, recipes,
                File(context.filesDir, "analysis-results"), signal)
        }
        PatchLabDiagnosticReportExporter.share(context, report)
        mutable.update { it.copy(notice = "Отчёт содержит доступные рецепты и точные причины блокировки.") }
    }

    fun recordObservation(observation: String) = operate("Сохраняем результат проверки") {
        val record = requireNotNull(state.value.built).copy(userObservation = observation)
        withContext(Dispatchers.IO) { record.save(context) }
        // A user report stays a user report; it never sets runtimeConfirmed=true.
        mutable.update { it.copy(built = record, notice = "Ваш результат проверки сохранён.") }
    }

    fun chooseAgain() { if (!state.value.busy) mutable.update { it.copy(showingResult = false) } }
    fun showPreviousResult() { if (state.value.built != null) mutable.update { it.copy(showingResult = true) } }
    fun cancel() { signal.cancel(); mutable.update { it.copy(operation = "Отмена…") } }
    fun showError(message: String) { mutable.update { it.copy(error = message) } }

    private fun operate(label: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        signal = AtomicCancellationSignal()
        job = scope.launch {
            mutable.update { it.copy(busy = true, operation = label, error = null, progress = null) }
            try { block() }
            catch (_: AnalysisCancelledException) { mutable.update { it.copy(notice = "Операция отменена. Предыдущая сборка сохранена.") } }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: failure.javaClass.simpleName) } }
            finally { mutable.update { it.copy(busy = false, progress = null) } }
        }
    }
    override fun onCleared() { signal.cancel(); scope.cancel() }
}
