package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import io.github.ffenuss.modkit.patch.Il2CppNativeMutationDraftBuilder
import io.github.ffenuss.modkit.patch.MutationApplyCoordinator
import io.github.ffenuss.modkit.patch.MutationApplyOutcome
import io.github.ffenuss.modkit.patch.MutationPreflightEngine
import io.github.ffenuss.modkit.patch.MutationPreflightResult
import io.github.ffenuss.modkit.patch.NativeMutationDraft
import io.github.ffenuss.modkit.patch.PatchPreparationPlan
import io.github.ffenuss.modkit.patch.PreparationTargetStatus
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun ManualNativePatchSection(
    target: AnalysisTargetDescriptor,
    analysis: FastAnalysisResult,
    preparation: PatchPreparationPlan,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val key = analysis.index.artifactSha256 + ":" + preparation.preparedAtEpochMs

    var selectedTargetId by remember(key) { mutableStateOf<String?>(null) }
    var replacementHex by remember(key) { mutableStateOf("") }
    var draft by remember(key) { mutableStateOf<NativeMutationDraft?>(null) }
    var preflight by remember(key) { mutableStateOf<MutationPreflightResult?>(null) }
    var applyOutcome by remember(key) { mutableStateOf<MutationApplyOutcome?>(null) }
    var busy by remember(key) { mutableStateOf(false) }
    var progress by remember(key) { mutableStateOf<EngineProgress?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var cancellation by remember(key) {
        mutableStateOf<AtomicCancellationSignal?>(null)
    }

    val eligible = preparation.targets.filter { prepared ->
        prepared.status == PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE ||
            prepared.status == PreparationTargetStatus.READY
    }.filter { prepared ->
        prepared.target.runtimeId == "unity_il2cpp" &&
            prepared.target.fileOffset != null &&
            prepared.target.abi != null
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

            if (eligible.isEmpty()) {
                Text(
                    "Нет целей с подтверждённой бинарной привязкой и file offset.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            Text("Цель", fontWeight = FontWeight.SemiBold)
            eligible.take(MAX_VISIBLE_TARGETS).forEach { prepared ->
                val selected = selectedTargetId == prepared.target.id
                OutlinedButton(
                    onClick = {
                        selectedTargetId = prepared.target.id
                        draft = null
                        preflight = null
                        applyOutcome = null
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        (if (selected) "✓ " else "") +
                            prepared.target.displayName,
                        maxLines = 2,
                    )
                }
            }
            if (eligible.size > MAX_VISIBLE_TARGETS) {
                Text(
                    "Показаны первые " + MAX_VISIBLE_TARGETS +
                        " из " + eligible.size +
                        ". Поиск по целям будет добавлен в следующем UI-проходе.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

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
                    replacementHex.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Проверить изменение")
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
                        val currentDraft = draft ?: return@Button
                        val signal = AtomicCancellationSignal()
                        cancellation = signal
                        busy = true
                        error = null
                        applyOutcome = null
                        progress = null

                        scope.launch {
                            try {
                                val outcome = MutationApplyCoordinator.apply(
                                    context = context,
                                    target = target,
                                    analysis = analysis,
                                    preparation = preparation,
                                    requests = listOf(currentDraft.request),
                                    cancellation = signal,
                                    progress = ProgressSink { update ->
                                        scope.launch { progress = update }
                                    },
                                )
                                applyOutcome = outcome
                                if (!outcome.applied) {
                                    error = outcome.blockers.firstOrNull()
                                        ?: "Staging не выполнен."
                                }
                            } catch (_: AnalysisCancelledException) {
                                error = "Применение изменения отменено."
                            } catch (failure: Throwable) {
                                error = failure.message ?: failure.javaClass.simpleName
                            } finally {
                                busy = false
                                cancellation = null
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Применить в staging APK")
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

private const val MAX_VISIBLE_TARGETS = 8
