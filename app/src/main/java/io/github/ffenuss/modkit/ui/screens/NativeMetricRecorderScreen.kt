package io.github.ffenuss.modkit.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.NativeMetricExperimentPlanner
import io.github.ffenuss.modkit.analysis.NativeVisibleCounterParser
import io.github.ffenuss.modkit.observation.NativeMetricAccessibilityService
import io.github.ffenuss.modkit.observation.NativeMetricCaptureSession
import io.github.ffenuss.modkit.observation.NativeMetricObservationExport
import io.github.ffenuss.modkit.observation.userInstruction

private data class NativeMetricCandidateChoice(
    val container: String,
    val entry: String,
    val abi: String,
    val symbol: String,
) {
    val title: String get() = symbol.take(45) + " · " + abi + " · " + entry.substringAfterLast('/')
}

/**
 * User consent is separate from this UI. It only configures one exact game,
 * native export, action and UI label. The floating service capture button
 * records the game counter; ON/OFF are manual steps, not code execution.
 */
@Composable
fun NativeMetricRecorderScreen(
    target: AnalysisTargetDescriptor.InstalledPackage,
    result: FastAnalysisResult,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val connected by NativeMetricAccessibilityService.connected.collectAsState()
    val state by NativeMetricCaptureSession.state.collectAsState()
    val choices = remember(result) {
        result.elfInventory?.records.orEmpty().asSequence()
            .filter { !it.abiPathMismatch && it.indexedAbi == it.architecture }
            .flatMap { record ->
                record.scalarExportCandidates.asSequence()
                    .filter { !it.selectable && it.abi == record.architecture }
                    .map { NativeMetricCandidateChoice(
                        record.container, record.entryPath, it.abi, it.symbol,
                    ) }
            }
            .distinct()
            .take(64)
            .toList()
    }
    var chosen by remember(result.index.artifactSha256) {
        mutableStateOf<NativeMetricCandidateChoice?>(null)
    }
    var choiceOpen by remember { mutableStateOf(false) }
    var actionName by remember { mutableStateOf("") }
    var metricName by remember { mutableStateOf("") }
    var replacementInput by remember { mutableStateOf("7") }
    var notice by remember { mutableStateOf<String?>(null) }
    val hasSession = state.plan != null

    fun exit() {
        NativeMetricCaptureSession.clear()
        onBack()
    }
    BackHandler { exit() }

    Scaffold { insets ->
        LazyColumn(
            Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                TextButton(onClick = { exit() }) { Text("Назад") }
                Text("Наблюдение игрового показателя", style = MaterialTheme.typography.headlineSmall)
                Text("Цель: " + target.label + " · " + target.packageName,
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Только ручная запись", style = MaterialTheme.typography.titleMedium)
                        Text("Включение службы специальных возможностей Android — отдельное решение. " +
                            "Во время эксперимента появится кнопка поверх выбранной игры. " +
                            "По нажатию ModKit читает только выбранную числовую строку её интерфейса. " +
                            "Без скриншотов, записи других приложений, жестов и передачи данных.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Изменения ON/OFF пользователь выполняет самостоятельно в контролируемой тестовой среде. " +
                            "ModKit их не выполняет и не подтверждает происхождение переключений. " +
                            "Сбор показателя НЕ доказывает назначение функции.",
                            style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (connected) "Служба подключена Android."
                            else "Служба выключена. При необходимости включите её вручную в настройках.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Настройки специальных возможностей")
                        }
                    }
                }
            }
            if (!hasSession) {
                item {
                    Text("Выберите один экспорт ELF из анализа", style = MaterialTheme.typography.titleMedium)
                    if (choices.isEmpty()) {
                        Text("Подходящих экспортированных скалярных функций не обнаружено. " +
                            "Запись не запускается без конкретного кандидата.",
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Box {
                            OutlinedButton(onClick = { choiceOpen = true },
                                modifier = Modifier.fillMaxWidth()) {
                                Text(chosen?.title ?: "Выбрать функцию")
                            }
                            DropdownMenu(expanded = choiceOpen, onDismissRequest = { choiceOpen = false }) {
                                choices.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.title) },
                                        onClick = { chosen = option; choiceOpen = false },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = actionName,
                            onValueChange = { actionName = it.take(120) },
                            label = { Text("Название действия в игре") },
                            placeholder = { Text("Например: Шаг") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = metricName,
                            onValueChange = { metricName = it.take(80) },
                            label = { Text("Точный текст названия показателя") },
                            placeholder = { Text("Например: Health") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = replacementInput,
                            onValueChange = { replacementInput = it.filter(Char::isDigit).take(5) },
                            label = { Text("Пробное значение функции (план, без применения)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                runCatching {
                                    check(connected) { "Сначала включите службу Android." }
                                    val option = requireNotNull(chosen)
                                    NativeVisibleCounterParser.requireLabel(metricName)
                                    val proposed = replacementInput.toIntOrNull()
                                        ?: error("Введите целое значение.")
                                    val plan = NativeMetricExperimentPlanner.fromAnalysis(
                                        result, target.packageName,
                                        option.container, option.entry, option.abi, option.symbol,
                                        actionName, metricName, proposed,
                                    )
                                    val launch = context.packageManager
                                        .getLaunchIntentForPackage(target.packageName)
                                        ?: error("Выбранное приложение не установлено или не запускается.")
                                    NativeMetricCaptureSession.begin(plan)
                                    try {
                                        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (failure: Exception) {
                                        NativeMetricCaptureSession.clear()
                                        throw failure
                                    }
                                    notice = null
                                }.onFailure {
                                    notice = it.message ?: "Не удалось начать запись."
                                }
                            },
                            enabled = connected && chosen != null &&
                                actionName.isNotBlank() && metricName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Начать запись и открыть игру")
                        }
                    }
                }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                if (state.active) "Запись: " + state.observations.size + " / 8"
                                else "Наблюдения: " + state.observations.size + " / 8",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            state.nextPhase?.let {
                                Text(it.userInstruction(), style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("Снимайте значение только после одного указанного действия. " +
                                "При уходе в другие приложения запись не производится.",
                                style = MaterialTheme.typography.bodySmall)
                            state.observations.forEach { sample ->
                                Text(sample.phase.name + " · " + sample.nodeText,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            state.error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                            if (state.complete) {
                                Button(onClick = { NativeMetricCaptureSession.evaluate() },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text("Проверить повторяемость")
                                }
                                state.association?.let { association ->
                                    Text("Результат: только наблюдаемая связь. " +
                                        "Обычное изменение: " + association.baselineDelta +
                                        ", с вмешательством: " + association.interventionDelta,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text("Рабочий мод не подтверждён. Никаких патчей не создано.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = {
                                    runCatching {
                                        NativeMetricObservationExport.save(context, state)
                                    }.onSuccess { notice = "Отчёт сохранён в Загрузки: " + it }
                                        .onFailure { notice = it.message }
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Сохранить отчёт (только по запросу)")
                                }
                            }
                            OutlinedButton(onClick = {
                                NativeMetricCaptureSession.clear()
                                notice = "Запись и все временные наблюдения очищены."
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("Стоп и очистить запись")
                            }
                        }
                    }
                }
            }
            notice?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.primary) }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}
