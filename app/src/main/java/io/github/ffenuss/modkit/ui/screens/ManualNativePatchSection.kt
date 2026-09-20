package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.patch.GameplayModificationFinder
import io.github.ffenuss.modkit.patch.Il2CppNativeMutationDraftBuilder
import io.github.ffenuss.modkit.patch.Il2CppPatchTargetBrowser
import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.patch.MutationApplyCoordinator
import io.github.ffenuss.modkit.patch.MutationApplyOutcome
import io.github.ffenuss.modkit.patch.MutationPreflightEngine
import io.github.ffenuss.modkit.patch.MutationPreflightResult
import io.github.ffenuss.modkit.patch.NativeMutationDraft
import io.github.ffenuss.modkit.patch.NativePatchPresetCatalog
import io.github.ffenuss.modkit.patch.PatchPreparationPlan
import io.github.ffenuss.modkit.patch.PreparationTargetStatus
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun ManualNativePatchSection(
    target: AnalysisTargetDescriptor,
    analysis: FastAnalysisResult,
    preparation: PatchPreparationPlan,
    onStagingReady: (MutationApplyOutcome) -> Unit = { },
    onStagingInvalidated: () -> Unit = { },
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val key = analysis.index.artifactSha256 + ":" + preparation.preparedAtEpochMs

    var selectedTargetId by remember(key) { mutableStateOf<String?>(null) }
    var targetFilter by remember(key) { mutableStateOf("") }
    var replacementHex by remember(key) { mutableStateOf("") }
    var projectCodeOnly by remember(key) { mutableStateOf(true) }
    var selectedOpportunityIds by remember(key) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var queuedDrafts by remember(key) {
        mutableStateOf<List<NativeMutationDraft>>(emptyList())
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

    val eligibleCount = remember(key) {
        preparation.targets.count(::isManualNativeEligible)
    }
    val assemblyCSharpCount = remember(key) {
        preparation.targets.count {
            isManualNativeEligible(it) &&
                Il2CppPatchTargetBrowser.isAssemblyCSharp(
                    it.target,
                )
        }
    }
    val effectiveProjectCodeOnly =
        projectCodeOnly && assemblyCSharpCount > 0
    val opportunities = remember(
        key,
        effectiveProjectCodeOnly,
    ) {
        GameplayModificationFinder.find(
            result = analysis,
            preparation = preparation,
            projectCodeOnly = effectiveProjectCodeOnly,
            limit = MAX_SUGGESTED_MODIFICATIONS,
        )
    }
    val selectedOpportunities =
        opportunities.filter {
            it.id in selectedOpportunityIds &&
                it.selectable
        }
    val normalizedFilter = targetFilter.trim().lowercase()
    val visibleEligible = remember(
        key,
        normalizedFilter,
        effectiveProjectCodeOnly,
    ) {
        preparation.targets
            .asSequence()
            .filter(::isManualNativeEligible)
            .filter { prepared ->
                !effectiveProjectCodeOnly ||
                    Il2CppPatchTargetBrowser.isAssemblyCSharp(
                        prepared.target,
                    )
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
    val presets =
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
                "Предлагаемые модификации",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "ModKit сам ищет gameplay-кандидаты среди точных IL2CPP-методов. " +
                    "Активная галочка означает только конкретную доказуемую операцию над exact method; " +
                    "игровой эффект всё равно проверяется после сборки.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (opportunities.isEmpty()) {
                Text(
                    "Автоматически распознаваемых gameplay-кандидатов в текущем наборе не найдено.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val selectableCount =
                    opportunities.count { it.selectable }
                Text(
                    "Найдено кандидатов: " + opportunities.size +
                        " · можно выбрать сейчас: " + selectableCount,
                    style = MaterialTheme.typography.bodySmall,
                )
                opportunities.forEach { opportunity ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked =
                                opportunity.id in
                                    selectedOpportunityIds,
                            enabled = opportunity.selectable && !busy,
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
                                opportunity.blocker
                                    ?: opportunity.evidenceSummary,
                                style =
                                    MaterialTheme.typography.bodySmall,
                                color =
                                    if (opportunity.blocker != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
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
                            selectedOpportunityIds =
                                emptySet()
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
                        "Сейчас показан код проекта: Assembly-CSharp (" +
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

                if (
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

            if (selectedPrepared != null) {
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
                            onStagingInvalidated()
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
                                    queuedDrafts =
                                        queuedDrafts.filterNot {
                                            it.request.id ==
                                                queued.request.id
                                        }
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
private const val MAX_SUGGESTED_MODIFICATIONS = 24
