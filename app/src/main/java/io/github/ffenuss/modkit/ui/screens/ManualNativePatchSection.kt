package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.nativecode.AArch64ControlFlowGraphBuilder
import io.github.ffenuss.modkit.analysis.nativecode.AArch64Disassembler
import io.github.ffenuss.modkit.analysis.nativecode.AArch64MethodAnalyzer
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.patch.AArch64ScalarReturnEncoder
import io.github.ffenuss.modkit.patch.GameplayModificationCategory
import io.github.ffenuss.modkit.patch.GameplayModificationFinder
import io.github.ffenuss.modkit.patch.GameplayModificationOpportunity
import io.github.ffenuss.modkit.patch.Il2CppArm64CallResolver
import io.github.ffenuss.modkit.patch.Il2CppArm64CallerScanResult
import io.github.ffenuss.modkit.patch.Il2CppArm64CallerScanner
import io.github.ffenuss.modkit.patch.Il2CppNativeMutationDraftBuilder
import io.github.ffenuss.modkit.patch.Il2CppPatchTargetBrowser
import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.patch.MutationApplyCoordinator
import io.github.ffenuss.modkit.patch.MutationApplyOutcome
import io.github.ffenuss.modkit.patch.MutationPreflightEngine
import io.github.ffenuss.modkit.patch.MutationPreflightResult
import io.github.ffenuss.modkit.patch.NativeCodeWindow
import io.github.ffenuss.modkit.patch.NativeMutationDraft
import io.github.ffenuss.modkit.patch.NativePatchPresetCatalog
import io.github.ffenuss.modkit.patch.PatchBuildSelectionMerger
import io.github.ffenuss.modkit.patch.PatchPreparationPlan
import io.github.ffenuss.modkit.patch.PreparationTargetStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ManualNativePatchSection(
    target: AnalysisTargetDescriptor,
    analysis: FastAnalysisResult,
    preparation: PatchPreparationPlan,
    onStagingReady: (MutationApplyOutcome) -> Unit = { },
    onStagingInvalidated: () -> Unit = { },
    onBuildRequested: (MutationApplyOutcome) -> Unit = { },
    externalBusy: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val key = analysis.index.artifactSha256 + ":" + preparation.preparedAtEpochMs

    var selectedTargetId by remember(key) { mutableStateOf<String?>(null) }
    var targetFilter by remember(key) { mutableStateOf("") }
    var replacementHex by remember(key) { mutableStateOf("") }
    var codeWindow by remember(key) {
        mutableStateOf<NativeCodeWindow?>(null)
    }
    var codeWindowBusy by remember(key) {
        mutableStateOf(false)
    }
    var callerScanBusy by remember(key) {
        mutableStateOf(false)
    }
    var callerScan by remember(key) {
        mutableStateOf<Il2CppArm64CallerScanResult?>(null)
    }
    var callerScanError by remember(key) {
        mutableStateOf<String?>(null)
    }
    var callerScanCancellation by remember(key) {
        mutableStateOf<AtomicCancellationSignal?>(null)
    }
    var showRawHex by remember(key) {
        mutableStateOf(false)
    }
    var customReturnValue by remember(key) {
        mutableStateOf("")
    }
    var projectCodeOnly by remember(key) { mutableStateOf(true) }
    var includeSensitiveSurfaces by remember(key) {
        mutableStateOf(false)
    }
    var selectedOpportunityIds by remember(key) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var showAllDeferred by remember(key) {
        mutableStateOf(false)
    }
    var showAllActionable by remember(key) {
        mutableStateOf(false)
    }
    var queuedDrafts by remember(key) {
        mutableStateOf<List<NativeMutationDraft>>(emptyList())
    }
    var automaticQueuedTargetIds by remember(key) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var manualQueuedTargetIds by remember(key) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var draft by remember(key) { mutableStateOf<NativeMutationDraft?>(null) }
    var preflight by remember(key) { mutableStateOf<MutationPreflightResult?>(null) }
    var applyOutcome by remember(key) { mutableStateOf<MutationApplyOutcome?>(null) }
    var busy by remember(key) { mutableStateOf(false) }
    var progress by remember(key) { mutableStateOf<EngineProgress?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var cancellation by remember(key) {
        mutableStateOf<AtomicCancellationSignal?>(null)
    }
    var opportunities by remember(key) {
        mutableStateOf<List<GameplayModificationOpportunity>>(emptyList())
    }
    var findingOpportunities by remember(key) {
        mutableStateOf(false)
    }
    var findingOpportunitiesError by remember(key) {
        mutableStateOf<String?>(null)
    }
    var oneTapBuildStatus by remember(key) {
        mutableStateOf<String?>(null)
    }

    val eligibleCount = remember(key) {
        preparation.targets.count(::isManualNativeEligible)
    }
    val assemblyCSharpCount = remember(key) {
        preparation.targets.count {
            isManualNativeEligible(it) &&
                Il2CppPatchTargetBrowser.isProjectCode(
                    it.target,
                )
        }
    }
    val effectiveProjectCodeOnly =
        projectCodeOnly && assemblyCSharpCount > 0

    fun openCodeEditor(targetId: String) {
        if (busy || codeWindowBusy) return
        selectedTargetId = targetId
        replacementHex = ""
        callerScanCancellation?.cancel()
        callerScanCancellation = null
        callerScanBusy = false
        callerScan = null
        callerScanError = null
        codeWindow = null
        showRawHex = false
        customReturnValue = ""
        draft = null
        preflight = null
        applyOutcome = null
        error = null
        codeWindowBusy = true

        scope.launch {
            try {
                val opened =
                    withContext(Dispatchers.IO) {
                        Il2CppNativeMutationDraftBuilder
                            .readCodeWindow(
                                result = analysis,
                                targetId = targetId,
                                analysisResultsRoot =
                                    File(
                                        context.filesDir,
                                        "analysis-results",
                                    ),
                            )
                    }
                codeWindow = opened
                replacementHex = opened.originalHex
            } catch (failure: Throwable) {
                error =
                    failure.message
                        ?: failure.javaClass.simpleName
            } finally {
                codeWindowBusy = false
            }
        }
    }

    fun closeCodeEditor() {
        callerScanCancellation?.cancel()
        callerScanCancellation = null
        callerScanBusy = false
        callerScan = null
        callerScanError = null
        codeWindow = null
        showRawHex = false
        customReturnValue = ""
        replacementHex = ""
        selectedTargetId = null
        draft = null
        preflight = null
        applyOutcome = null
        error = null
    }

    fun scanIncomingCallers() {
        val selectedId =
            selectedTargetId
                ?: return
        val selected =
            preparation.targets
                .firstOrNull {
                    it.target.id ==
                        selectedId &&
                        isManualNativeEligible(it)
                }
                ?.target
                ?: return
        if (
            callerScanBusy ||
            !selected.abi
                .orEmpty()
                .equals(
                    "arm64-v8a",
                    ignoreCase = true,
                )
        ) {
            return
        }

        val signal =
            AtomicCancellationSignal()
        callerScanCancellation
            ?.cancel()
        callerScanCancellation =
            signal
        callerScanBusy = true
        callerScan = null
        callerScanError = null

        scope.launch {
            try {
                callerScan =
                    withContext(
                        Dispatchers.IO,
                    ) {
                        Il2CppArm64CallerScanner
                            .scan(
                                result = analysis,
                                target = selected,
                                analysisResultsRoot =
                                    File(
                                        context.filesDir,
                                        "analysis-results",
                                    ),
                                cancellation =
                                    signal,
                            )
                    }
            } catch (_: AnalysisCancelledException) {
                callerScanError =
                    "Поиск входящих вызовов отменён."
            } catch (failure: Throwable) {
                callerScanError =
                    failure.message
                        ?: failure.javaClass
                            .simpleName
            } finally {
                callerScanBusy = false
                if (
                    callerScanCancellation ===
                    signal
                ) {
                    callerScanCancellation =
                        null
                }
            }
        }
    }

    LaunchedEffect(
        key,
        effectiveProjectCodeOnly,
    ) {
        findingOpportunities = true
        findingOpportunitiesError = null
        selectedOpportunityIds = emptySet()
        try {
            val found =
                withContext(Dispatchers.Default) {
                    GameplayModificationFinder.find(
                        result = analysis,
                        preparation = preparation,
                        projectCodeOnly =
                            effectiveProjectCodeOnly,
                        limit =
                            MAX_SUGGESTED_MODIFICATIONS,
                        perCategoryLimit =
                            MAX_SUGGESTED_PER_CATEGORY,
                    )
                }
            opportunities = found
            // The normal action path is now scan -> one-tap build. Do not
            // silently opt users into local purchase-entitlement test patches.
            selectedOpportunityIds = found
                .filter {
                    it.selectable &&
                        it.category !=
                            GameplayModificationCategory.OWNER_ENTITLEMENT
                }
                .map { it.id }
                .toSet()
        } catch (failure: Throwable) {
            opportunities = emptyList()
            findingOpportunitiesError =
                failure.message
                    ?: failure.javaClass.simpleName
        } finally {
            findingOpportunities = false
        }
    }

    val displayedOpportunities =
        if (includeSensitiveSurfaces) {
            opportunities
        } else {
            opportunities.filterNot {
                it.category ==
                    GameplayModificationCategory
                        .SENSITIVE_SURFACE
            }
        }
    val actionableOpportunities =
        displayedOpportunities.filter { it.selectable }
    val deferredOpportunities =
        displayedOpportunities.filterNot { it.selectable }
    val selectedOpportunities =
        actionableOpportunities.filter {
            it.id in selectedOpportunityIds
        }
    /**
     * Checkboxes now form a complete operation, not merely a staging queue.
     * Draft materialization is performed off the UI thread; the single
     * combined preflight runs before any original APK is copied or changed.
     */
    fun applySelectionAndBuild() {
        if (busy || externalBusy) {
            error = "Дождитесь завершения текущей операции."
            return
        }
        val chosen = selectedOpportunities.toList()
        val existing = queuedDrafts.toList()
        if (chosen.isEmpty() && existing.isEmpty()) {
            error = "Сначала отметьте хотя бы один готовый мод."
            return
        }
        if (chosen.any {
                !it.selectable ||
                    it.replacementHex.isNullOrBlank()
            }
        ) {
            error = "Один из выбранных модов не имеет проверенного шаблона изменения."
            return
        }

        val signal = AtomicCancellationSignal()
        cancellation = signal
        busy = true
        error = null
        progress = null
        applyOutcome = null
        oneTapBuildStatus = "1/4 · Подготовка выбранных методов…"
        onStagingInvalidated()

        scope.launch {
            try {
                val generated =
                    withContext(Dispatchers.IO) {
                        chosen.map { opportunity ->
                            if (signal.isCancelled()) {
                                throw AnalysisCancelledException()
                            }
                            Il2CppNativeMutationDraftBuilder.build(
                                result = analysis,
                                targetId = opportunity.targetId,
                                replacementHex =
                                    requireNotNull(
                                        opportunity.replacementHex,
                                    ),
                                analysisResultsRoot =
                                    File(
                                        context.filesDir,
                                        "analysis-results",
                                    ),
                                stagingRoot =
                                    File(
                                        context.filesDir,
                                        "patch-staging",
                                    ),
                            )
                        }
                    }
                val combined = PatchBuildSelectionMerger.merge(
                    queued = existing,
                    selected = generated,
                    targetId = { it.request.targetId },
                )
                require(combined.isNotEmpty()) {
                    "Набор изменений пуст."
                }

                oneTapBuildStatus = "2/4 · Проверка SHA, диапазонов и конфликтов…"
                val checked = withContext(Dispatchers.Default) {
                    MutationPreflightEngine.validate(
                        preparation = preparation,
                        requests = combined.map {
                            it.request
                        },
                    )
                }
                require(checked.readyForApply) {
                    (
                        checked.globalBlockers +
                            checked.blockedItems.flatMap {
                                it.blockers
                            }
                    ).distinct().firstOrNull()
                        ?: "Проверка набора изменений не пройдена."
                }

                oneTapBuildStatus = "3/4 · Применение к тестовому APK…"
                val outcome = MutationApplyCoordinator.apply(
                    context = context,
                    target = target,
                    analysis = analysis,
                    preparation = preparation,
                    requests = combined.map {
                        it.request
                    },
                    cancellation = signal,
                    progress = ProgressSink { update ->
                        scope.launch { progress = update }
                    },
                )
                require(outcome.applied) {
                    outcome.blockers.firstOrNull()
                        ?: "Изменения не прошли проверку промежуточной сборки."
                }
                queuedDrafts = combined
                selectedOpportunityIds = emptySet()
                automaticQueuedTargetIds =
                    automaticQueuedTargetIds +
                        generated.map {
                            it.request.targetId
                        }
                applyOutcome = outcome
                onStagingReady(outcome)
                oneTapBuildStatus =
                    "4/4 · Изменения применены и проверены. " +
                        "Подпись итогового APK запущена — результат появится ниже."
                onBuildRequested(outcome)
            } catch (_: AnalysisCancelledException) {
                error = "Подготовка и сборка отменены. Оригинал не изменён."
                oneTapBuildStatus = null
            } catch (failure: Throwable) {
                error = "Сборка остановлена: " +
                    (failure.message ?: failure.javaClass.simpleName)
                oneTapBuildStatus = null
            } finally {
                busy = false
                cancellation = null
            }
        }
    }

    val normalizedFilter = targetFilter.trim().lowercase()
    val visibleEligible = remember(
        key,
        normalizedFilter,
        effectiveProjectCodeOnly,
        includeSensitiveSurfaces,
    ) {
        preparation.targets
            .asSequence()
            .filter(::isManualNativeEligible)
            .filter { prepared ->
                !effectiveProjectCodeOnly ||
                    Il2CppPatchTargetBrowser.isProjectCode(
                        prepared.target,
                    )
            }
            .filter { prepared ->
                includeSensitiveSurfaces ||
                    GameplayModificationFinder
                        .sensitiveSurfaceLabel(
                            prepared.target,
                        ) == null
            }
            .filter { prepared ->
                Il2CppPatchTargetBrowser.matches(
                    target = prepared.target,
                    query = normalizedFilter,
                )
            }
            .take(MAX_VISIBLE_TARGETS)
            .toList()
    }
    val selectedPrepared = remember(
        key,
        selectedTargetId,
    ) {
        selectedTargetId?.let { selectedId ->
            preparation.targets.firstOrNull {
                it.target.id == selectedId &&
                    isManualNativeEligible(it)
            }
        }
    }
    val selectedBinding = remember(
        key,
        selectedTargetId,
    ) {
        selectedPrepared?.target?.let {
            Il2CppPatchTargetBrowser.bindingFor(
                result = analysis,
                target = it,
            )
        }
    }
    val selectedReturnKind =
        selectedBinding?.returnKind
            ?: Il2CppNativeReturnKind.UNKNOWN
    val selectedSensitiveLabel =
        selectedPrepared
            ?.target
            ?.let {
                GameplayModificationFinder
                    .sensitiveSurfaceLabel(it)
            }
    val presets =
        if (selectedSensitiveLabel != null) {
            emptyList()
        } else {
            selectedPrepared
            ?.target
            ?.abi
            ?.let { abi ->
                NativePatchPresetCatalog.forProvenReturnKind(
                    abi = abi,
                    returnKind = selectedReturnKind,
                )
            }
            .orEmpty()
        }

    val selectedSharedBodyCount = remember(
        key,
        selectedTargetId,
    ) {
        selectedPrepared?.target?.let { selected ->
            preparation.targets.count {
                it.target.runtimeId == "unity_il2cpp" &&
                    it.target.artifact ==
                    selected.artifact &&
                    it.target.fileOffset ==
                    selected.fileOffset
            }
        } ?: 0
    }

    if (codeWindowBusy || codeWindow != null) {
        Dialog(
            onDismissRequest = { },
            properties =
                DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false,
                ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                val window = codeWindow
                if (window == null) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            "Открываю код метода…",
                            style =
                                MaterialTheme.typography
                                    .headlineSmall,
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth(),
                        )
                        Text(
                            selectedPrepared
                                ?.target
                                ?.displayName
                                ?: "Чтение exact native body",
                            style =
                                MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    val evidenceTarget =
                        selectedPrepared?.target
                    val readableSource =
                        evidenceTarget?.let {
                            Il2CppPatchTargetBrowser
                                .reconstructedSourceView(
                                    result = analysis,
                                    target = it,
                                )
                        } ?: "// Metadata-контекст метода недоступен."
                    val arm64Disassembly =
                        remember(
                            window.originalHex,
                            window.binaryVirtualAddress,
                            window.fileOffset,
                            window.abi,
                        ) {
                            if (
                                window.abi.equals(
                                    "arm64-v8a",
                                    ignoreCase = true,
                                )
                            ) {
                                runCatching {
                                    AArch64Disassembler
                                        .disassemble(
                                            code =
                                                Il2CppNativeMutationDraftBuilder
                                                    .parseHex(
                                                        window.originalHex,
                                                    ),
                                            startAddress =
                                                window.binaryVirtualAddress
                                                    ?: window.fileOffset,
                                            maxInstructions = 64,
                                        )
                                }.getOrNull()
                            } else {
                                null
                            }
                        }
                    val arm64MethodAnalysis =
                        arm64Disassembly?.let {
                            AArch64MethodAnalyzer
                                .analyze(it)
                        }
                    val arm64ControlFlow =
                        arm64Disassembly?.let {
                            AArch64ControlFlowGraphBuilder
                                .build(it)
                        }
                    val outgoingCalls =
                        if (
                            evidenceTarget != null &&
                            arm64Disassembly != null
                        ) {
                            Il2CppArm64CallResolver
                                .resolveOutgoingCalls(
                                    result = analysis,
                                    sourceTarget =
                                        evidenceTarget,
                                    disassembly =
                                        arm64Disassembly,
                                )
                        } else {
                            emptyList()
                        }
                    val runtimeAddress =
                        evidenceTarget?.let {
                            target ->
                            analysis.runtimeEvidence
                                ?.addressConfirmations
                                .orEmpty()
                                .firstOrNull {
                                    it.targetId ==
                                        target.id
                                }
                                ?.runtimeVirtualAddress
                        }
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(
                                    rememberScrollState(),
                                )
                                .padding(20.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth(),
                            verticalAlignment =
                                Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Код метода",
                                modifier =
                                    Modifier.weight(1f),
                                style =
                                    MaterialTheme.typography
                                        .headlineSmall,
                                fontWeight =
                                    FontWeight.Bold,
                            )
                            OutlinedButton(
                                onClick =
                                    ::closeCodeEditor,
                                enabled = !busy,
                            ) {
                                Text("Закрыть")
                            }
                        }
                        Text(
                            window.targetDisplayName,
                            fontWeight =
                                FontWeight.SemiBold,
                        )
                        selectedSensitiveLabel?.let { label ->
                            Text(
                                "Sensitive surface: " + label +
                                    ". Этот экран работает только как инспектор; редактирование и сохранение отключены.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Редактор открыт поверх списка найденного — " +
                                "после сохранения или закрытия вы вернётесь " +
                                "ровно к тому же месту.",
                            style =
                                MaterialTheme.typography.bodySmall,
                        )

                        Card(
                            Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Читаемый C#-вид",
                                    fontWeight =
                                        FontWeight.SemiBold,
                                )
                                Text(
                                    readableSource,
                                    fontFamily =
                                        FontFamily.Monospace,
                                    style =
                                        MaterialTheme.typography
                                            .bodySmall,
                                )
                            }
                        }

                        Text(
                            "Важно: если APK собран через IL2CPP, точный текст, " +
                                "который был в исходном .cs файле, внутри APK уже не хранится. " +
                                "Unity преобразует C# в C++ и затем в ARM64. Поэтому ModKit " +
                                "может восстановить класс, имя метода, часть сигнатуры и связать " +
                                "их с точным native body, но не вернуть комментарии, локальные " +
                                "имена и исходное тело один-в-один без оригинального проекта/символов.",
                            style =
                                MaterialTheme.typography.bodySmall,
                        )

                        Text(
                            "Native: libil2cpp.so · " +
                                window.abi +
                                " · offset 0x" +
                                window.fileOffset
                                    .toString(16) +
                                (
                                    window.binaryVirtualAddress
                                        ?.let {
                                            " · VA 0x" +
                                                it.toString(16)
                                        }
                                        .orEmpty()
                                    ) +
                                (
                                    runtimeAddress
                                        ?.let {
                                            " · runtime 0x" +
                                                it.toString(16)
                                        }
                                        .orEmpty()
                                    ) +
                                " · " +
                                window.byteLength +
                                " байт",
                            style =
                                MaterialTheme.typography.bodySmall,
                        )

                        arm64MethodAnalysis?.let {
                            methodAnalysis ->
                            Card(
                                Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(
                                            7.dp,
                                        ),
                                ) {
                                    Text(
                                        "Распознанная логика",
                                        fontWeight =
                                            FontWeight.SemiBold,
                                    )
                                    Text(
                                        methodAnalysis.shape.title +
                                            " · уверенность: " +
                                            methodAnalysis.confidence,
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                    Text(
                                        methodAnalysis.pseudoCode,
                                        fontFamily =
                                            FontFamily.Monospace,
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                    methodAnalysis.facts
                                        .forEach {
                                            fact ->
                                            Text(
                                                "• " + fact,
                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall,
                                            )
                                        }
                                    arm64ControlFlow
                                        ?.let {
                                            cfg ->
                                            Text(
                                                "CFG: блоков " +
                                                    cfg.blocks
                                                        .size +
                                                    " · рёбер " +
                                                    cfg.edges
                                                        .size +
                                                    " · внешних переходов " +
                                                    cfg.externalTargets
                                                        .size +
                                                    " · complete=" +
                                                    cfg.completeInsideWindow,
                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall,
                                            )
                                        }
                                    if (
                                        outgoingCalls
                                            .isNotEmpty()
                                    ) {
                                        Text(
                                            "Прямые вызовы",
                                            fontWeight =
                                                FontWeight
                                                    .SemiBold,
                                        )
                                        outgoingCalls
                                            .take(12)
                                            .forEach {
                                                call ->
                                                Text(
                                                    "• 0x" +
                                                        call.callSiteAddress
                                                            .toString(16) +
                                                        " → " +
                                                        (
                                                            call.targetDisplayName
                                                                ?: (
                                                                    "0x" +
                                                                        call.targetAddress
                                                                            .toString(
                                                                                16,
                                                                            )
                                                                    )
                                                            ) +
                                                        if (
                                                            call.exactIl2CppTarget
                                                        ) {
                                                            " · exact IL2CPP"
                                                        } else {
                                                            " · unresolved"
                                                        },
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

                        if (
                            evidenceTarget != null &&
                            window.abi.equals(
                                "arm64-v8a",
                                ignoreCase = true,
                            )
                        ) {
                            Card(
                                Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(
                                            7.dp,
                                        ),
                                ) {
                                    Text(
                                        "Граф вызовов",
                                        fontWeight =
                                            FontWeight.SemiBold,
                                    )
                                    Text(
                                        "Исходящие прямые BL-вызовы уже показаны выше. " +
                                            "Обратный проход по доказанным границам методов найдёт, " +
                                            "кто напрямую вызывает выбранную IL2CPP-функцию.",
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                    if (
                                        callerScanBusy
                                    ) {
                                        LinearProgressIndicator(
                                            Modifier
                                                .fillMaxWidth(),
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                callerScanCancellation
                                                    ?.cancel()
                                            },
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth(),
                                        ) {
                                            Text(
                                                "Отменить поиск callers",
                                            )
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick =
                                                ::scanIncomingCallers,
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth(),
                                        ) {
                                            Text(
                                                "Найти кто вызывает этот метод",
                                            )
                                        }
                                    }

                                    callerScanError
                                        ?.let {
                                            message ->
                                            Text(
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
                                    callerScan
                                        ?.let {
                                            scan ->
                                            Text(
                                                "Callers: " +
                                                    scan.callers
                                                        .size +
                                                    " · проверено тел: " +
                                                    scan.scannedBodies +
                                                    " · байт: " +
                                                    scan.scannedBytes,
                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall,
                                            )
                                            if (
                                                scan.truncatedByMethodLimit ||
                                                scan.truncatedByResultLimit
                                            ) {
                                                Text(
                                                    "Результат ограничен внутренним лимитом; " +
                                                        "это не считается доказательством отсутствия других callers.",
                                                    style =
                                                        MaterialTheme
                                                            .typography
                                                            .bodySmall,
                                                )
                                            }
                                            if (
                                                scan.callers
                                                    .isEmpty()
                                            ) {
                                                Text(
                                                    "Прямых BL-callers в просканированных доказанных телах не найдено.",
                                                    style =
                                                        MaterialTheme
                                                            .typography
                                                            .bodySmall,
                                                )
                                            } else {
                                                scan.callers
                                                    .take(24)
                                                    .forEach {
                                                        caller ->
                                                        Text(
                                                            "• " +
                                                                caller
                                                                    .callerDisplayNames
                                                                    .take(3)
                                                                    .joinToString(
                                                                        " / ",
                                                                    ) +
                                                                " · callsite 0x" +
                                                                caller
                                                                    .callSiteBinaryVirtualAddress
                                                                    .toString(
                                                                        16,
                                                                    ),
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
                        }

                        arm64Disassembly?.let {
                            disassembly ->
                            Card(
                                Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(
                                            7.dp,
                                        ),
                                ) {
                                    Text(
                                        "ARM64-разбор",
                                        fontWeight =
                                            FontWeight.SemiBold,
                                    )
                                    Text(
                                        "Распознано инструкций: " +
                                            disassembly
                                                .recognizedCount +
                                            " · неизвестных: " +
                                            disassembly
                                                .unknownCount,
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                    disassembly.summary
                                        .forEach {
                                            Text(
                                                "• " + it,
                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall,
                                            )
                                        }
                                    Text(
                                        disassembly.instructions
                                            .take(24)
                                            .joinToString(
                                                "\n",
                                            ) {
                                                it.text
                                            },
                                        fontFamily =
                                            FontFamily.Monospace,
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                    if (
                                        disassembly.instructions
                                            .size > 24
                                    ) {
                                        Text(
                                            "Показаны первые 24 из " +
                                                disassembly
                                                    .instructions
                                                    .size +
                                                " инструкций.",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                    }
                                }
                            }
                        }

                        if (
                            selectedSensitiveLabel == null &&
                            presets.isNotEmpty() &&
                            selectedSharedBodyCount == 1
                        ) {
                            Text(
                                "Готовые изменения",
                                fontWeight =
                                    FontWeight.SemiBold,
                            )
                            presets.forEach { preset ->
                                val checked =
                                    replacementHex
                                        .trim()
                                        .equals(
                                            preset.replacementHex,
                                            ignoreCase = true,
                                        )
                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    verticalAlignment =
                                        Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            enabled ->
                                            replacementHex =
                                                if (enabled) {
                                                    preset.replacementHex
                                                } else {
                                                    window.originalHex
                                                }
                                            draft = null
                                            preflight = null
                                            applyOutcome = null
                                            error = null
                                        },
                                    )
                                    Column {
                                        Text(preset.label)
                                        Text(
                                            preset.description,
                                            style =
                                                MaterialTheme.typography
                                                    .bodySmall,
                                        )
                                    }
                                }
                            }
                        }

                        if (
                            selectedSensitiveLabel == null &&
                            selectedReturnKind in
                                setOf(
                                    Il2CppNativeReturnKind.INTEGER,
                                    Il2CppNativeReturnKind.FLOAT32,
                                    Il2CppNativeReturnKind.FLOAT64,
                                )
                        ) {
                            Text(
                                "Произвольное возвращаемое значение",
                                fontWeight =
                                    FontWeight.SemiBold,
                            )
                            OutlinedTextField(
                                value =
                                    customReturnValue,
                                onValueChange = {
                                    customReturnValue = it
                                },
                                label = {
                                    Text(
                                        when (
                                            selectedReturnKind
                                        ) {
                                            Il2CppNativeReturnKind.INTEGER ->
                                                "Integer"
                                            Il2CppNativeReturnKind.FLOAT32 ->
                                                "Float"
                                            else ->
                                                "Double"
                                        },
                                    )
                                },
                                supportingText = {
                                    Text(
                                        "ModKit сам соберёт короткий ARM64 return-body; " +
                                            "граница метода и preflight всё равно проверяются перед сохранением.",
                                    )
                                },
                                singleLine = true,
                                modifier =
                                    Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        AArch64ScalarReturnEncoder
                                            .encodeHex(
                                                returnKind =
                                                    selectedReturnKind,
                                                valueText =
                                                    customReturnValue,
                                            )
                                    }.onSuccess {
                                        encoded ->
                                        val encodedSize =
                                            Il2CppNativeMutationDraftBuilder
                                                .parseHex(
                                                    encoded,
                                                )
                                                .size
                                        val provenSpan =
                                            window
                                                .nextMethodFileOffset
                                                ?.minus(
                                                    window.fileOffset,
                                                )
                                        require(
                                            if (
                                                provenSpan != null
                                            ) {
                                                encodedSize
                                                    .toLong() <=
                                                    provenSpan
                                            } else {
                                                encodedSize <= 4
                                            },
                                        ) {
                                            if (
                                                provenSpan != null
                                            ) {
                                                "Новый return-body занимает " +
                                                    encodedSize +
                                                    " байт, а доказанная граница метода — " +
                                                    provenSpan +
                                                    " байт."
                                            } else {
                                                "Следующая граница метода не доказана; " +
                                                    "многословный ARM64 return-body заблокирован."
                                            }
                                        }
                                        replacementHex =
                                            encoded
                                        draft = null
                                        preflight = null
                                        applyOutcome = null
                                        error = null
                                    }.onFailure {
                                        failure ->
                                        error =
                                            failure.message
                                                ?: failure
                                                    .javaClass
                                                    .simpleName
                                    }
                                },
                                enabled =
                                    customReturnValue
                                        .isNotBlank(),
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "Сформировать ARM64 return",
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showRawHex =
                                    !showRawHex
                            },
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (showRawHex) {
                                    "Скрыть низкоуровневый HEX"
                                } else {
                                    "Показать низкоуровневый HEX"
                                },
                            )
                        }

                        if (showRawHex) {
                            if (selectedSensitiveLabel != null) {
                                Card(
                                    Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        window.originalHex,
                                        modifier =
                                            Modifier.padding(14.dp),
                                        fontFamily =
                                            FontFamily.Monospace,
                                        style =
                                            MaterialTheme.typography
                                                .bodySmall,
                                    )
                                }
                                Text(
                                    "HEX показан только для исследования. Редактирование sensitive surface отключено.",
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                OutlinedTextField(
                                    value = replacementHex,
                                    onValueChange = {
                                        replacementHex = it
                                        draft = null
                                        preflight = null
                                        applyOutcome = null
                                    },
                                    label = {
                                        Text(
                                            "Точный ARM64 body (hex)",
                                        )
                                    },
                                    supportingText = {
                                        Text(
                                            "Это машинный код. Используйте его только " +
                                                "для точной ручной правки; ARM64 — полными " +
                                                "4-байтовыми инструкциями.",
                                        )
                                    },
                                    singleLine = false,
                                    minLines = 6,
                                    maxLines = 14,
                                    textStyle =
                                        MaterialTheme.typography
                                            .bodyMedium
                                            .copy(
                                                fontFamily =
                                                    FontFamily.Monospace,
                                            ),
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        if (
                            selectedSharedBodyCount > 1
                        ) {
                            Text(
                                "Сохранение заблокировано: этот native body " +
                                    "разделяется " +
                                    selectedSharedBodyCount +
                                    " metadata-методами.",
                                color =
                                    MaterialTheme.colorScheme.error,
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                        }

                        error?.let {
                            Text(
                                it,
                                color =
                                    MaterialTheme.colorScheme.error,
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                        }

                        Button(
                            onClick = {
                                val currentTarget =
                                    evidenceTarget
                                if (currentTarget == null) {
                                    error =
                                        "Точная цель метода потеряна."
                                    return@Button
                                }
                                error = null
                                runCatching {
                                    val built =
                                        Il2CppNativeMutationDraftBuilder
                                            .build(
                                                result = analysis,
                                                targetId =
                                                    currentTarget.id,
                                                replacementHex =
                                                    replacementHex,
                                                analysisResultsRoot =
                                                    File(
                                                        context.filesDir,
                                                        "analysis-results",
                                                    ),
                                                stagingRoot =
                                                    File(
                                                        context.filesDir,
                                                        "patch-staging",
                                                    ),
                                            )
                                    val candidate =
                                        queuedDrafts
                                            .filterNot {
                                                it.request
                                                    .targetId ==
                                                    currentTarget.id
                                            } +
                                            built
                                    val combined =
                                        MutationPreflightEngine
                                            .validate(
                                                preparation =
                                                    preparation,
                                                requests =
                                                    candidate
                                                        .map {
                                                            it.request
                                                        },
                                            )
                                    require(
                                        combined.readyForApply,
                                    ) {
                                        (
                                            combined.globalBlockers +
                                                combined.blockedItems
                                                    .flatMap {
                                                        it.blockers
                                                    }
                                            )
                                            .distinct()
                                            .firstOrNull()
                                            ?: "Изменение не прошло preflight."
                                    }
                                    queuedDrafts =
                                        candidate
                                    manualQueuedTargetIds =
                                        manualQueuedTargetIds +
                                            currentTarget.id
                                    automaticQueuedTargetIds =
                                        automaticQueuedTargetIds -
                                            currentTarget.id
                                    onStagingInvalidated()
                                    closeCodeEditor()
                                }.onFailure {
                                    failure ->
                                    error =
                                        failure.message
                                            ?: failure
                                                .javaClass
                                                .simpleName
                                }
                            },
                            enabled =
                                !busy &&
                                    selectedSensitiveLabel == null &&
                                    evidenceTarget != null &&
                                    selectedSharedBodyCount == 1 &&
                                    replacementHex.isNotBlank(),
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (
                                    selectedSensitiveLabel != null
                                ) {
                                    "Сохранение отключено для sensitive surface"
                                } else {
                                    "Сохранить изменение и закрыть"
                                },
                            )
                        }

                        OutlinedButton(
                            onClick =
                                ::closeCodeEditor,
                            enabled = !busy,
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Text("Закрыть без сохранения")
                        }
                    }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text("Ручной Patch Lab", fontWeight = FontWeight.SemiBold)
            Text(
                "Расширенный режим для точной подтверждённой IL2CPP-цели. " +
                    "Новые байты не применяются, пока SHA, диапазон и preflight не совпадут.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (eligibleCount == 0) {
                Text(
                    "Нет целей с подтверждённой бинарной привязкой и file offset.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            Text(
                "Доступные модификации",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "ModKit учитывает имя метода, контекст класса и IL2CPP metadata. " +
                    "Галочки доступны только для доказанных binary-целей; поля модели данных " +
                    "показываются отдельно как подсказки и не патчатся вслепую.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = includeSensitiveSurfaces,
                    onCheckedChange = { checked ->
                        includeSensitiveSurfaces = checked
                        selectedOpportunityIds = emptySet()
                        selectedTargetId = null
                        replacementHex = ""
                        codeWindow = null
                        draft = null
                        preflight = null
                        applyOutcome = null
                        error = null
                    },
                )
                Column {
                    Text(
                        "Показывать billing / purchase / auth / anti-cheat",
                    )
                    Text(
                        "Опциональный режим анализа: код, сигнатуры, связи и runtime-evidence. " +
                            "Для этих поверхностей автопатчи и сохранение изменений отключены.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (findingOpportunities) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                )
                Text(
                    "Поиск модификаций выполняется в фоне…",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (actionableOpportunities.isEmpty()) {
                Text(
                    "Пока нет модификаций, которые ModKit может безопасно предложить галочкой. " +
                        "Это лучше, чем показывать ложные «бессмертие/скорость» по случайному совпадению текста.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Найдено кандидатов: " +
                        displayedOpportunities.size +
                        " · подготовлено автоматических изменений: " +
                        actionableOpportunities.size +
                        " · требует дополнительных доказательств: " +
                        deferredOpportunities.size,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Проверяемые изменения уже отмечены автоматически, " +
                        "кроме тестовых локальных entitlement-флагов. " +
                        "Можно снять ненужные галочки перед сборкой.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = ::applySelectionAndBuild,
                    enabled =
                        selectedOpportunities.isNotEmpty() &&
                            !busy &&
                            !externalBusy &&
                            !findingOpportunities,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Автоматически собрать выбранные моды (" +
                            selectedOpportunities.size + ")",
                    )
                }
                OutlinedButton(
                    onClick = {
                        selectedOpportunityIds =
                            if (
                                selectedOpportunityIds.size ==
                                actionableOpportunities.size
                            ) {
                                emptySet()
                            } else {
                                actionableOpportunities
                                    .map { it.id }
                                    .toSet()
                            }
                        onStagingInvalidated()
                        applyOutcome = null
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (
                            selectedOpportunityIds.size ==
                            actionableOpportunities.size
                        ) {
                            "Снять выбор со всех"
                        } else {
                            "Выбрать все готовые (" +
                                actionableOpportunities.size +
                                ")"
                        },
                    )
                }
                val visibleActionable =
                    if (showAllActionable) {
                        actionableOpportunities
                    } else {
                        actionableOpportunities.take(12)
                    }
                visibleActionable.forEach { opportunity ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked =
                                opportunity.id in
                                    selectedOpportunityIds,
                            enabled = !busy,
                            onCheckedChange = { checked ->
                                selectedOpportunityIds =
                                    if (checked) {
                                        selectedOpportunityIds +
                                            opportunity.id
                                    } else {
                                        selectedOpportunityIds -
                                            opportunity.id
                                    }
                                onStagingInvalidated()
                                applyOutcome = null
                            },
                        )
                        Column {
                            Text(opportunity.title)
                            Text(
                                opportunity.targetDisplayName,
                                style =
                                    MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                opportunity.evidenceSummary,
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color =
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = {
                                    openCodeEditor(
                                        opportunity.targetId,
                                    )
                                },
                                enabled =
                                    !busy &&
                                        !codeWindowBusy,
                            ) {
                                Text("Открыть код")
                            }
                        }
                    }
                }

                if (actionableOpportunities.size > 12) {
                    OutlinedButton(
                        onClick = { showAllActionable = !showAllActionable },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (showAllActionable) {
                                "Свернуть список готовых модов"
                            } else {
                                "Показать остальные готовые (" +
                                    (actionableOpportunities.size - 12) +
                                    ")"
                            },
                        )
                    }
                }

                Button(
                    onClick = {
                        error = null
                        runCatching {
                            val generated =
                                selectedOpportunities.map { opportunity ->
                                    Il2CppNativeMutationDraftBuilder.build(
                                        result = analysis,
                                        targetId = opportunity.targetId,
                                        replacementHex =
                                            requireNotNull(
                                                opportunity.replacementHex,
                                            ),
                                        analysisResultsRoot =
                                            File(
                                                context.filesDir,
                                                "analysis-results",
                                            ),
                                        stagingRoot =
                                            File(
                                                context.filesDir,
                                                "patch-staging",
                                            ),
                                    )
                                }
                            val replacedTargets =
                                generated.map {
                                    it.request.targetId
                                }.toSet()
                            val candidate =
                                queuedDrafts.filterNot {
                                    it.request.targetId in
                                        replacedTargets
                                } + generated
                            val combined =
                                MutationPreflightEngine.validate(
                                    preparation = preparation,
                                    requests =
                                        candidate.map {
                                            it.request
                                        },
                                )
                            require(combined.readyForApply) {
                                (
                                    combined.globalBlockers +
                                        combined.blockedItems
                                            .flatMap {
                                                it.blockers
                                            }
                                    )
                                    .distinct()
                                    .firstOrNull()
                                    ?: "Выбранные модификации не прошли preflight."
                            }
                            queuedDrafts = candidate
                            automaticQueuedTargetIds =
                                automaticQueuedTargetIds +
                                    replacedTargets
                            manualQueuedTargetIds =
                                manualQueuedTargetIds -
                                    replacedTargets
                            selectedOpportunityIds =
                                emptySet()
                            codeWindow = null
                            draft = null
                            preflight = null
                            replacementHex = ""
                            selectedTargetId = null
                            applyOutcome = null
                            onStagingInvalidated()
                        }.onFailure { failure ->
                            error =
                                failure.message
                                    ?: failure.javaClass.simpleName
                        }
                    },
                    enabled =
                        selectedOpportunities.isNotEmpty() &&
                            !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Добавить выбранные модификации (" +
                            selectedOpportunities.size +
                            ")",
                    )
                }
            }

            Button(
                onClick = ::applySelectionAndBuild,
                enabled =
                    !busy &&
                        !externalBusy &&
                        !findingOpportunities &&
                        (
                            selectedOpportunities.isNotEmpty() ||
                                queuedDrafts.isNotEmpty()
                        ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (busy && oneTapBuildStatus != null) {
                        "Подготовка и сборка…"
                    } else {
                        "Применить и собрать APK (" +
                            (
                                selectedOpportunities
                                    .map { it.targetId } +
                                    queuedDrafts
                                        .map { it.request.targetId }
                            ).distinct().size +
                            ")"
                    },
                )
            }
            Text(
                "Одна кнопка выполнит подготовку, общую проверку, " +
                    "применение к тестовому APK и подпись. " +
                    "Добавлять изменения в отдельный список не требуется.",
                style = MaterialTheme.typography.bodySmall,
            )
            oneTapBuildStatus?.let { status ->
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.takeIf {
                oneTapBuildStatus == null &&
                    (selectedOpportunities.isNotEmpty() ||
                        queuedDrafts.isNotEmpty())
            }?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            findingOpportunitiesError?.let { message ->
                Text(
                    "Поиск модификаций: " + message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (deferredOpportunities.isNotEmpty()) {
                val deferredSummary =
                    deferredOpportunities
                        .groupingBy { it.category.title }
                        .eachCount()
                        .entries
                        .sortedBy { it.key }
                        .joinToString(" · ") {
                            it.key + ": " + it.value
                        }
                Text(
                    "Найдены дополнительные кандидаты без безопасного автопатча: " +
                        deferredSummary,
                    style = MaterialTheme.typography.bodySmall,
                )
                val visibleDeferred =
                    if (showAllDeferred) {
                        deferredOpportunities
                    } else {
                        deferredOpportunities.take(
                            MAX_VISIBLE_DEFERRED_MODIFICATIONS,
                        )
                    }
                visibleDeferred.forEach { opportunity ->
                    Column(
                        modifier =
                            Modifier.fillMaxWidth(),
                        verticalArrangement =
                            Arrangement.spacedBy(2.dp),
                    ) {
                        Text(opportunity.title)
                        Text(
                            opportunity.targetDisplayName,
                            style =
                                MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            opportunity.blocker
                                ?: opportunity.evidenceSummary,
                            style =
                                MaterialTheme.typography.bodySmall,
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (
                            preparation.targets.any {
                                it.target.id ==
                                    opportunity.targetId &&
                                    isManualNativeEligible(it)
                            }
                        ) {
                            OutlinedButton(
                                onClick = {
                                    openCodeEditor(
                                        opportunity.targetId,
                                    )
                                },
                                enabled =
                                    !busy &&
                                        !codeWindowBusy,
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (
                                        opportunity.category ==
                                        GameplayModificationCategory
                                            .SENSITIVE_SURFACE
                                    ) {
                                        "Открыть код (только анализ)"
                                    } else {
                                        "Открыть код / ручное изменение"
                                    },
                                )
                            }
                        }
                    }
                }
                if (
                    deferredOpportunities.size >
                    MAX_VISIBLE_DEFERRED_MODIFICATIONS
                ) {
                    OutlinedButton(
                        onClick = {
                            showAllDeferred = !showAllDeferred
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (showAllDeferred) {
                                "Свернуть кандидатов"
                            } else {
                                "Показать все кандидаты (" +
                                    deferredOpportunities.size +
                                    ")"
                            },
                        )
                    }
                }
            }

            Text(
                "Ручной выбор метода",
                fontWeight = FontWeight.SemiBold,
            )
            Text("Цель", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = targetFilter,
                onValueChange = { targetFilter = it },
                label = { Text("Поиск метода") },
                supportingText = {
                    Text(
                        "Имя класса/метода, image/assembly или target id. " +
                            "Доступно подтверждённых целей: " +
                            eligibleCount,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (assemblyCSharpCount > 0) {
                Text(
                    if (effectiveProjectCodeOnly) {
                        "Сейчас показан код проекта: игровых сборок (" +
                            assemblyCSharpCount +
                            "). Это обычно скрипты самой игры/приложения; " +
                            "системные и библиотечные методы скрыты."
                    } else {
                        "Показаны все подтверждённые IL2CPP-методы, " +
                            "включая Unity/.NET/плагины."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        projectCodeOnly = !projectCodeOnly
                        selectedOpportunityIds = emptySet()
                        selectedTargetId = null
                        replacementHex = ""
                        codeWindow = null
                        draft = null
                        preflight = null
                        applyOutcome = null
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (effectiveProjectCodeOnly) {
                            "Показать библиотеки и системные методы"
                        } else {
                            "Только код игры/приложения (" +
                                assemblyCSharpCount +
                                ")"
                        },
                    )
                }
            }
            if (normalizedFilter.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        targetFilter = ""
                        selectedTargetId = null
                        replacementHex = ""
                        codeWindow = null
                        draft = null
                        preflight = null
                        applyOutcome = null
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Сбросить поиск")
                }
            }
            visibleEligible.forEach { prepared ->
                val selected = selectedTargetId == prepared.target.id
                OutlinedButton(
                    onClick = {
                        selectedTargetId = prepared.target.id
                        replacementHex = ""
                        codeWindow = null
                        draft = null
                        preflight = null
                        applyOutcome = null
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        (if (selected) "✓ " else "") +
                            prepared.target.displayName +
                            "\n" +
                            Il2CppPatchTargetBrowser.originLabel(
                                prepared.target,
                            ),
                        maxLines = 3,
                    )
                }
            }
            if (visibleEligible.isEmpty()) {
                Text(
                    "По этому запросу подтверждённых методов не найдено.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (
                normalizedFilter.isBlank() &&
                eligibleCount > MAX_VISIBLE_TARGETS
            ) {
                Text(
                    "Показаны первые " + MAX_VISIBLE_TARGETS +
                        " из " + eligibleCount +
                        ". Введите имя метода или класса для поиска.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            selectedPrepared?.let { selected ->
                val evidenceTarget = selected.target
                val selectedAbi =
                    requireNotNull(evidenceTarget.abi)
                val selectedFileOffset =
                    requireNotNull(
                        evidenceTarget.fileOffset,
                    )
                Text(
                    "Выбрано: " + evidenceTarget.displayName,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "ABI: " + selectedAbi +
                        " · file offset: 0x" +
                        selectedFileOffset
                            .toString(16) +
                        (
                            evidenceTarget.metadataToken
                                ?.let {
                                    " · token: 0x" +
                                        it.toString(16)
                                }
                                .orEmpty()
                            ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Il2CppPatchTargetBrowser
                    .imageName(evidenceTarget)
                    ?.let { image ->
                        Text(
                            "Image/assembly: " + image,
                            style =
                                MaterialTheme.typography.bodySmall,
                        )
                    }
                Text(
                    "Источник: " +
                        Il2CppPatchTargetBrowser.originLabel(
                            evidenceTarget,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Что делает по структуре: " +
                        Il2CppPatchTargetBrowser.methodHint(
                            evidenceTarget,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Как выбирать шаблон: " +
                        Il2CppPatchTargetBrowser.presetAdvice(
                            result = analysis,
                            target = evidenceTarget,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )

                selectedSensitiveLabel?.let { label ->
                    Text(
                        "Sensitive surface: " + label +
                            ". Режим только для анализа; изменение/обход этой поверхности не сохраняется.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text(
                    "Return type: " +
                        Il2CppPatchTargetBrowser.returnKindLabel(
                            selectedReturnKind,
                        ) +
                        (
                            selectedBinding?.returnTypeProof
                                ?.let { " · proof: " + it }
                                .orEmpty()
                            ),
                    style = MaterialTheme.typography.bodySmall,
                )

                if (selectedSharedBodyCount > 1) {
                    Text(
                        "Этот native body используется " +
                            selectedSharedBodyCount +
                            " IL2CPP-методами. Patch одной metadata-цели " +
                            "заблокирован; выберите метод с уникальным executable offset.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedButton(
                    onClick = {
                        openCodeEditor(
                            evidenceTarget.id,
                        )
                    },
                    enabled =
                        !busy &&
                            !codeWindowBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (
                            codeWindow?.targetId ==
                            evidenceTarget.id
                        ) {
                            "Перечитать код метода"
                        } else if (
                            selectedSensitiveLabel != null
                        ) {
                            "Открыть код метода (только анализ)"
                        } else {
                            "Открыть код метода"
                        },
                    )
                }

                if (
                    codeWindowBusy &&
                    selectedTargetId ==
                    evidenceTarget.id
                ) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Чтение native-кода из libil2cpp.so…",
                        style =
                            MaterialTheme.typography.bodySmall,
                    )
                }

                if (
                    selectedSensitiveLabel == null &&
                    presets.isNotEmpty() &&
                    selectedSharedBodyCount == 1
                ) {
                    Text(
                        "Готовые шаблоны для " +
                            selectedAbi,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Что именно произойдёт: «void» сразу завершает метод; " +
                            "«0» возвращает 0/false/null-подобное значение; " +
                            "«1» возвращает 1/true для bool/int-подобного результата. " +
                            "Это не готовая игровая функция — эффект зависит от выбранного метода.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    presets.forEach { preset ->
                        val checked =
                            replacementHex.trim().equals(
                                preset.replacementHex,
                                ignoreCase = true,
                            )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { enabled ->
                                    replacementHex =
                                        if (enabled) {
                                            preset.replacementHex
                                        } else {
                                            ""
                                        }
                                    draft = null
                                    preflight = null
                                    applyOutcome = null
                                    error = null
                                },
                            )
                            Column {
                                Text(preset.label)
                                Text(
                                    preset.description,
                                    style =
                                        MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (
                    selectedSensitiveLabel == null &&
                    presets.isEmpty() &&
                    selectedSharedBodyCount == 1
                ) {
                    Text(
                        if (selectedReturnKind == Il2CppNativeReturnKind.UNKNOWN) {
                            "Готовые действия скрыты: ModKit пока не доказал return type этого метода. " +
                                "Имя метода не используется как доказательство."
                        } else {
                            "Для доказанного типа возврата пока нет готового безопасного ARM64 действия."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (selectedPrepared == null) {
                Text(
                    "Шаг 1: найдите и выберите подтверждённый метод. " +
                        "После выбора появятся готовые ARM64-шаблоны и ручной ввод байтов.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (
                selectedPrepared != null &&
                selectedSensitiveLabel == null &&
                codeWindow == null &&
                replacementHex.isNotBlank()
            ) {
            OutlinedTextField(
                value = replacementHex,
                onValueChange = {
                    replacementHex = it
                    draft = null
                    preflight = null
                    applyOutcome = null
                },
                label = { Text("Новые байты (hex)") },
                supportingText = {
                    Text("Например: 1F 20 03 D5")
                },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val targetId = selectedTargetId
                    if (targetId == null) {
                        error = "Сначала выберите подтверждённую цель."
                        return@Button
                    }
                    busy = true
                    error = null
                    applyOutcome = null
                    runCatching {
                        val built = Il2CppNativeMutationDraftBuilder.build(
                            result = analysis,
                            targetId = targetId,
                            replacementHex = replacementHex,
                            analysisResultsRoot = File(
                                context.filesDir,
                                "analysis-results",
                            ),
                            stagingRoot = File(
                                context.filesDir,
                                "patch-staging",
                            ),
                        )
                        val checked = MutationPreflightEngine.validate(
                            preparation = preparation,
                            requests = listOf(built.request),
                        )
                        draft = built
                        preflight = checked
                    }.onFailure { failure ->
                        error = failure.message ?: failure.javaClass.simpleName
                        draft = null
                        preflight = null
                    }
                    busy = false
                },
                enabled = !busy &&
                    selectedTargetId != null &&
                    selectedSharedBodyCount == 1 &&
                    replacementHex.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Проверить изменение")
            }
            }

            draft?.let { currentDraft ->
                Text(
                    "Исходные байты: " + currentDraft.originalHex,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Новые байты: " + currentDraft.replacementHex,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            preflight?.let { checked ->
                if (checked.readyForApply) {
                    Text(
                        "Preflight пройден: диапазон и payload готовы к staging.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    checked.globalBlockers.forEach {
                        Text("• " + it, style = MaterialTheme.typography.bodySmall)
                    }
                    checked.blockedItems
                        .flatMap { it.blockers }
                        .distinct()
                        .take(5)
                        .forEach {
                            Text("• " + it, style = MaterialTheme.typography.bodySmall)
                        }
                }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    progress?.currentTask ?: "Выполняется проверка…",
                    style = MaterialTheme.typography.bodySmall,
                )
                progress?.currentArtifact?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { cancellation?.cancel() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Отменить")
                }
            }

            if (preflight?.readyForApply == true && draft != null) {
                Button(
                    onClick = {
                        val currentDraft =
                            draft ?: return@Button
                        val candidate =
                            queuedDrafts
                                .filterNot {
                                    it.request.targetId ==
                                        currentDraft.request.targetId
                                } +
                                currentDraft
                        val combined =
                            MutationPreflightEngine.validate(
                                preparation = preparation,
                                requests =
                                    candidate.map {
                                        it.request
                                    },
                            )
                        if (combined.readyForApply) {
                            queuedDrafts = candidate
                            val targetId =
                                currentDraft.request.targetId
                            manualQueuedTargetIds =
                                manualQueuedTargetIds +
                                    targetId
                            automaticQueuedTargetIds =
                                automaticQueuedTargetIds -
                                    targetId
                            onStagingInvalidated()
                            codeWindow = null
                            draft = null
                            preflight = null
                            replacementHex = ""
                            selectedTargetId = null
                            applyOutcome = null
                            error = null
                        } else {
                            error =
                                (
                                    combined.globalBlockers +
                                        combined.blockedItems
                                            .flatMap {
                                                it.blockers
                                            }
                                    )
                                    .distinct()
                                    .firstOrNull()
                                    ?: "Набор изменений не прошёл preflight."
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Добавить в список изменений",
                    )
                }
            }

            val queuedTargetIds =
                queuedDrafts
                    .map { it.request.targetId }
                    .toSet()
            val queuedAutomaticCount =
                automaticQueuedTargetIds.count {
                    it in queuedTargetIds
                }
            val queuedManualCount =
                manualQueuedTargetIds.count {
                    it in queuedTargetIds
                }
            Text(
                "Изменений: автоматических " +
                    queuedAutomaticCount +
                    " · ручных " +
                    queuedManualCount +
                    " · всего " +
                    queuedDrafts.size,
                fontWeight =
                    FontWeight.SemiBold,
            )

            if (queuedDrafts.isNotEmpty()) {
                Text(
                    "Список изменений (" +
                        queuedDrafts.size +
                        ")",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Можно добавить несколько разных методов. " +
                        "Перед staging весь набор проверяется вместе " +
                        "на SHA, пересечения диапазонов и конфликты.",
                    style = MaterialTheme.typography.bodySmall,
                )
                queuedDrafts.forEach { queued ->
                    Card(
                        Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(
                                    5.dp,
                                ),
                        ) {
                            Text(
                                queued.targetDisplayName,
                                fontWeight =
                                    FontWeight.SemiBold,
                            )
                            Text(
                                "Новые байты: " +
                                    queued.replacementHex,
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                            )
                            OutlinedButton(
                                onClick = {
                                    val targetId =
                                        queued.request.targetId
                                    queuedDrafts =
                                        queuedDrafts.filterNot {
                                            it.request.id ==
                                                queued.request.id
                                        }
                                    automaticQueuedTargetIds =
                                        automaticQueuedTargetIds -
                                            targetId
                                    manualQueuedTargetIds =
                                        manualQueuedTargetIds -
                                            targetId
                                    onStagingInvalidated()
                                    applyOutcome = null
                                },
                                modifier =
                                    Modifier.fillMaxWidth(),
                            ) {
                                Text("Удалить из списка")
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        val combined =
                            MutationPreflightEngine.validate(
                                preparation = preparation,
                                requests =
                                    queuedDrafts.map {
                                        it.request
                                    },
                            )
                        if (!combined.readyForApply) {
                            error =
                                (
                                    combined.globalBlockers +
                                        combined.blockedItems
                                            .flatMap {
                                                it.blockers
                                            }
                                    )
                                    .distinct()
                                    .firstOrNull()
                                    ?: "Набор изменений не прошёл preflight."
                            return@Button
                        }

                        val signal =
                            AtomicCancellationSignal()
                        cancellation = signal
                        busy = true
                        error = null
                        applyOutcome = null
                        progress = null

                        scope.launch {
                            try {
                                val outcome =
                                    MutationApplyCoordinator.apply(
                                        context = context,
                                        target = target,
                                        analysis = analysis,
                                        preparation =
                                            preparation,
                                        requests =
                                            queuedDrafts.map {
                                                it.request
                                            },
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
                                applyOutcome = outcome
                                if (outcome.applied) {
                                    onStagingReady(outcome)
                                } else {
                                    error =
                                        outcome.blockers
                                            .firstOrNull()
                                            ?: "Staging не выполнен."
                                }
                            } catch (
                                _: AnalysisCancelledException,
                            ) {
                                error =
                                    "Применение изменений отменено."
                            } catch (
                                failure: Throwable,
                            ) {
                                error =
                                    failure.message
                                        ?: failure.javaClass
                                            .simpleName
                            } finally {
                                busy = false
                                cancellation = null
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Применить " +
                            queuedDrafts.size +
                            " изменений в staging APK",
                    )
                }
            }

            applyOutcome?.takeIf { it.applied }?.let { outcome ->
                val staging = outcome.staging
                Text(
                    "Staging готов. Изменений: " +
                        (staging?.diffs?.size ?: 0),
                    fontWeight = FontWeight.SemiBold,
                )
                staging?.outputFiles.orEmpty().forEach { file ->
                    Text(
                        file.name + " · " + file.length() + " байт",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (staging?.strippedSignatureEntries?.isNotEmpty() == true) {
                    Text(
                        "Старая APK-подпись удалена из staging. " +
                            "Перед установкой требуется новый этап sign/verify.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = {
                        onBuildRequested(outcome)
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Собрать APK")
                }
                Text(
                    "ModKit вернёт изменённый .so в исходный ABI-путь APK, " +
                        "затем выполнит align, подпись и проверку готового пакета.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun isManualNativeEligible(
    prepared: io.github.ffenuss.modkit.patch.PreparedTarget,
): Boolean =
    (
        prepared.status ==
            PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE ||
            prepared.status ==
            PreparationTargetStatus.READY
        ) &&
        prepared.target.runtimeId == "unity_il2cpp" &&
        prepared.target.fileOffset != null &&
        prepared.target.abi != null

private const val MAX_VISIBLE_TARGETS = 24
private const val MAX_SUGGESTED_MODIFICATIONS = 256
private const val MAX_SUGGESTED_PER_CATEGORY = 32
private const val MAX_VISIBLE_DEFERRED_MODIFICATIONS = 6
