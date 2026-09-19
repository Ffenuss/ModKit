package io.github.ffenuss.modkit.ui.screens

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.domain.EngineProgress

@Composable
fun AnalysisScreen(
    title: String,
    progress: EngineProgress?,
    result: FastAnalysisResult?,
    active: Boolean,
    error: String?,
    cancelled: Boolean,
    cancelling: Boolean,
    stalledAgeMs: Long?,
    canSkipStalled: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        when {
            error != null -> {
                item {
                    Text("Ошибка: " + error, color = MaterialTheme.colorScheme.error)
                }
                item {
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text("Назад")
                    }
                }
            }

            else -> {
                if (cancelled) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Анализ отменён", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (result != null) {
                                        "Уже полученные результаты сохранены и доступны ниже. Они не считаются полным анализом."
                                    } else {
                                        "Полезные частичные результаты ещё не успели сформироваться."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (active) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    when {
                                        stalledAgeMs != null -> "Этап не отвечает"
                                        cancelling -> "Отмена выполняется…"
                                        else -> progress?.currentTask ?: "Подготовка анализа…"
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                )
                                progress?.currentArtifact?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                                progress?.processed?.let { processed ->
                                    Text(
                                        progress.total?.let { total -> "$processed / $total" }
                                            ?: "Обработано: $processed",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                progress?.lastHeartbeatEpochMs?.let { heartbeat ->
                                    val ageSeconds = ((System.currentTimeMillis() - heartbeat) / 1000L)
                                        .coerceAtLeast(0L)
                                    Text(
                                        "Heartbeat: " + ageSeconds + " с назад",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }

                                if (stalledAgeMs != null) {
                                    Text(
                                        "Нет heartbeat " + (stalledAgeMs / 1000L) + " с. Можно повторить этап или остановить анализ.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                                            Text("Повторить")
                                        }
                                        if (canSkipStalled) {
                                            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                                                Text("Пропустить")
                                            }
                                        }
                                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                                            Text("Стоп")
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = onCancel,
                                        enabled = !cancelling,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(if (cancelling) "Отмена…" else "Отменить")
                                    }
                                }
                            }
                        }
                    }
                }

                if (result != null) {
                    item {
                        Text(
                            if (active) "Ранние результаты" else "Результат анализа",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    item {
                        val index = result.index
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("FAST inventory", fontWeight = FontWeight.SemiBold)
                                Text("Готов за " + result.elapsedMs + " мс")
                                Text("SHA-256", fontWeight = FontWeight.SemiBold)
                                Text(index.artifactSha256, style = MaterialTheme.typography.bodySmall)
                                Text("Файлов в индексе: " + index.entries.size)
                                Text(
                                    "ABI: " + index.detectedAbis
                                        .ifEmpty { setOf("не определено") }
                                        .joinToString(),
                                )
                                Text("Runtime: " + index.runtimeProfiles.size)
                                if (index.truncated) {
                                    Text(
                                        "Индекс ограничен безопасным лимитом.",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }

                    if (result.engineCacheHits.isNotEmpty()) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("Переиспользовано", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        result.engineCacheHits.sorted().joinToString(),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Результаты привязаны к SHA цели и версии движка.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }

                    items(result.index.runtimeProfiles, key = { it.runtimeId }) { runtime ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(runtime.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    runtime.status.name + " · " + runtime.confidence.name,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                runtime.evidence.take(3).forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    result.il2cppFastDump?.let { dump ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("IL2CPP fast dump", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "metadata v" + (dump.metadata.metadataVersion ?: "?") +
                                            " · images " + dump.metadata.images.size +
                                            " · types " + dump.metadata.types.size +
                                            " · methods " + dump.metadata.methods.size +
                                            " · fields " + dump.metadata.fields.size,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        if (dump.metadata.structuredSupported) {
                                            "EXACT_METADATA: структурная реконструкция готова"
                                        } else {
                                            "Metadata magic подтверждён; layout ещё не поддержан"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text("Dump: " + dump.dumpFilePath, style = MaterialTheme.typography.bodySmall)
                                    dump.warnings.take(3).forEach {
                                        Text("• " + it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    result.il2cppEvidence?.let { evidence ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("Evidence Graph", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Уровень доказательств: " + evidence.proofLevel.name,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "CHANGE_READY: " + if (evidence.changeReady) "да" else "нет",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (!evidence.changeReady) {
                                        Text(
                                            "Что мешает следующему уровню:",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        evidence.blockers.take(5).forEach { blocker ->
                                            Text(
                                                "• " + blocker.code + ": " + blocker.message,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        if (evidence.blockers.isEmpty()) {
                                            Text(
                                                "• INTERNAL_ERROR: причина блокировки не определена",
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    result.il2cppBinaryBinding?.let { binary ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("IL2CPP binary confirmation", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (binary.exactBindingAvailable) {
                                            "EXACT_BINARY: подтверждено методов " + binary.exactBindingCount
                                        } else {
                                            "EXACT_BINARY пока не доказан"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Проверено библиотек: " + binary.evidence.size,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    binary.evidence
                                        .flatMap { it.bindings }
                                        .take(5)
                                        .forEach { binding ->
                                            Text(
                                                "• " + binding.managedIdentity +
                                                    " · token 0x" + binding.metadataToken.toString(16) +
                                                    " · VA 0x" + binding.functionVirtualAddress.toString(16) +
                                                    (binding.functionFileOffset?.let {
                                                        " · file+0x" + it.toString(16)
                                                    } ?: ""),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    binary.warnings.take(4).forEach {
                                        Text("• " + it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }

                    if (result.engineWarnings.isNotEmpty()) {
                        item {
                            Text("Ошибки отдельных движков", fontWeight = FontWeight.SemiBold)
                        }
                        items(result.engineWarnings) {
                            Text("• " + it, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    val planned = result.routingPlan.engines.filter {
                        it.scheduleClass != io.github.ffenuss.modkit.domain.EngineScheduleClass.FAST
                    }
                    if (planned.isNotEmpty()) {
                        item {
                            Text("План движков", fontWeight = FontWeight.SemiBold)
                        }
                        items(planned, key = { it.id }) { engine ->
                            Text(
                                "• " + engine.id + " · " + engine.scheduleClass.name +
                                    " · " + if (engine.availableNow) "доступен" else "ещё не перенесён",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    if (result.routingPlan.missingCapabilities.isNotEmpty()) {
                        item {
                            Text("Что ещё не завершено", fontWeight = FontWeight.SemiBold)
                        }
                        items(result.routingPlan.missingCapabilities.take(8)) {
                            Text("• " + it, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (result.index.warnings.isNotEmpty()) {
                        item {
                            Text("Предупреждения", fontWeight = FontWeight.SemiBold)
                        }
                        items(result.index.warnings) {
                            Text("• " + it, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (!active) {
                        item {
                            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                                Text("Выбрать другую цель")
                            }
                        }
                    }
                } else if (cancelled) {
                    item {
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text("Выбрать другую цель")
                        }
                    }
                }
            }
        }
    }
}
