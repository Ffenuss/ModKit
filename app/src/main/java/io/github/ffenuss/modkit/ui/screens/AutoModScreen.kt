package io.github.ffenuss.modkit.ui.screens

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
import io.github.ffenuss.modkit.patch.AutoModPreparationCoordinator
import io.github.ffenuss.modkit.patch.PatchPreparationPlan
import io.github.ffenuss.modkit.patch.PreparationTargetStatus
import kotlinx.coroutines.launch

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
    var error by remember(result.index.artifactSha256) { mutableStateOf<String?>(null) }
    var cancellation by remember(result.index.artifactSha256) {
        mutableStateOf<AtomicCancellationSignal?>(null)
    }

    fun prepareChanges() {
        if (preparing) return
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
                            "Подтверждено: " + summary.confirmed +
                                " · готово: " + summary.ready +
                                " · подтверждается: " + summary.confirming,
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                }
            }
        }

        if (preparing) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            progress?.currentTask ?: "Подготовка изменений…",
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
                            Text("Отменить подготовку")
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
                            "Найдено: " + summary.found +
                                " · подтверждено: " + summary.confirmed +
                                " · готово: " + summary.ready +
                                " · требуют подтверждения: " + summary.requireConfirmation,
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

            items(prepared.targets.take(12), key = { it.target.id }) { preparedTarget ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(preparedTarget.target.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            preparationStatusLabel(preparedTarget.status),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        preparedTarget.blockers.firstOrNull()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = ::prepareChanges,
                enabled = !preparing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (plan == null) "Подготовить изменения" else "Обновить подготовку")
            }
        }

        item {
            Button(
                onClick = { },
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Собрать APK")
            }
            Text(
                when {
                    plan == null ->
                        "Сначала выполните подготовку изменений."
                    plan?.automaticApplyAllowed != true ->
                        "Сборка остаётся заблокированной, пока нет изменений, полностью готовых к безопасному применению."
                    else ->
                        "Конвейер сборки APK ещё не подключён к этому экрану."
                },
                style = MaterialTheme.typography.bodySmall,
            )
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
