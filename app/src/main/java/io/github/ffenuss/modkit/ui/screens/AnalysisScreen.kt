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
import io.github.ffenuss.modkit.analysis.DetectionConfidence
import io.github.ffenuss.modkit.analysis.DetectionStatus
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.patch.Il2CppPatchTargetBrowser

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
    partialNotice: String? = null,
    onOpenAutoMod: (() -> Unit)? = null,
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
                if (partialNotice != null) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Частичные результаты", fontWeight = FontWeight.SemiBold)
                                Text(partialNotice, style = MaterialTheme.typography.bodySmall)
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
                            when {
                                active -> "Ранние результаты"
                                partialNotice != null -> "Сохранённые результаты"
                                else -> "Результат анализа"
                            },
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
                                Text("Быстрый анализ", fontWeight = FontWeight.SemiBold)
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
                                        "Повторно использовано завершённых стадий: " +
                                            result.engineCacheHits.size,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Повторное использование разрешено только при совпадении SHA цели и версии движка.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }

                    result.evidenceGraph?.takeIf { it.targets.isNotEmpty() }?.let { graph ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Text("Статус технических доказательств", fontWeight = FontWeight.SemiBold)
                                    val summary = graph.summary
                                    Text(
                                        "Сигналы: " + summary.found +
                                            " · проверяется: " + summary.confirming +
                                            " · подтверждённые цели: " + summary.confirmed +
                                            " · готовые изменения: " + summary.ready,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Это не количество найденных модов: runtime-сигнатуры " +
                                            "и точные методы учитываются отдельно от подготовленных изменений.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (summary.runtimeRequired > 0 || summary.couldNotConfirm > 0) {
                                        Text(
                                            "Runtime требуется: " + summary.runtimeRequired +
                                                " · не удалось подтвердить: " + summary.couldNotConfirm,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            val exactIl2Cpp =
                                graph.targets.filter {
                                    it.runtimeId ==
                                        "unity_il2cpp" &&
                                        it.fileOffset != null &&
                                        (
                                            it.userStatus ==
                                                UserFindingStatus.CONFIRMED ||
                                                it.userStatus ==
                                                UserFindingStatus.READY
                                            )
                                }
                            val projectMethods =
                                exactIl2Cpp.filter {
                                    Il2CppPatchTargetBrowser
                                        .isProjectCode(it)
                                }
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement =
                                        Arrangement.spacedBy(5.dp),
                                ) {
                                    Text(
                                        "Что это значит",
                                        fontWeight =
                                            FontWeight.SemiBold,
                                    )
                                    if (projectMethods.isNotEmpty()) {
                                        Text(
                                            "Подтверждено методов игровых сборок: " +
                                                projectMethods.size +
                                                ". Patch Lab ищет в них конкретные игровые " +
                                                "изменения; это ещё не готовые моды.",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                        Text(
                                            "Примеры: " +
                                                projectMethods
                                                    .take(4)
                                                    .joinToString {
                                                        it.displayName
                                                    },
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                    } else if (exactIl2Cpp.isNotEmpty()) {
                                        Text(
                                            "Точные IL2CPP-методы найдены: " +
                                                exactIl2Cpp.size +
                                                ", но проектная игровая сборка пока не выделена.",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                    } else {
                                        Text(
                                            "Точных IL2CPP-методов для изменения " +
                                                "пока нет. Найденные runtime ниже " +
                                                "показывают технологии, а не готовые патчи.",
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                    }
                                    Text(
                                        "Тысячи технических методов Unity/.NET " +
                                            "здесь больше не выводятся списком. " +
                                            "Полный поиск доступен в Patch Lab.",
                                        style =
                                            MaterialTheme
                                                .typography
                                                .bodySmall,
                                    )
                                }
                            }
                        }
                    }

                    if (result.confirmationQueue.isNotEmpty()) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("Дополнительное подтверждение", fontWeight = FontWeight.SemiBold)
                                    result.confirmationQueue.take(5).forEach { request ->
                                        Text(
                                            "• " + request.reason +
                                                if (request.availableNow) {
                                                    " · будет выполнено автоматически"
                                                } else {
                                                    " · требуется следующий runtime-этап"
                                                },
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
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
                                    detectionStatusLabel(runtime.status) +
                                        " · уверенность: " + confidenceLabel(runtime.confidence),
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
                                    Text("IL2CPP dump", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "metadata v" + (dump.metadata.metadataVersion ?: "?") +
                                            " · images " + dump.metadata.images.size +
                                            "/" +
                                            (dump.metadata.declaredImageCount
                                                ?: dump.metadata.images.size) +
                                            " · types " + dump.metadata.types.size +
                                            "/" +
                                            (dump.metadata.declaredTypeCount
                                                ?: dump.metadata.types.size) +
                                            " · methods " + dump.metadata.methods.size +
                                            "/" +
                                            (dump.metadata.declaredMethodCount
                                                ?: dump.metadata.methods.size) +
                                            " · fields " + dump.metadata.fields.size +
                                            "/" +
                                            (dump.metadata.declaredFieldCount
                                                ?: dump.metadata.fields.size),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (dump.metadata.truncated) {
                                        Text(
                                            "Metadata прочитана частично. " +
                                                "Точная IL2CPP-привязка не будет объявлена, " +
                                                "пока все требуемые таблицы не реконструированы.",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Text(
                                        if (dump.metadata.structuredSupported) {
                                            "Метаданные структурно подтверждены"
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
                                    Text("Подтверждения", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (evidence.changeReady) {
                                            "Статус: готово к применению"
                                        } else if (evidence.binaryIdentityExact) {
                                            "Статус: бинарная цель подтверждена"
                                        } else if (evidence.metadataIdentityExact) {
                                            "Статус: метаданные подтверждены, проверяется бинарная привязка"
                                        } else {
                                            "Статус: найдено, требуется подтверждение"
                                        },
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
                                                "• " + blocker.message,
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
                                    Text("IL2CPP: точная привязка", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (binary.exactBindingAvailable) {
                                            "Точно подтверждено методов: " + binary.exactBindingCount
                                        } else {
                                            "Точная бинарная привязка пока не подтверждена"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Проверено библиотек: " + binary.evidence.size,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "Учтено relative relocations: " +
                                            binary.evidence.sumOf {
                                                it.relativeRelocationCount
                                            },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    binary.evidence
                                        .flatMap { it.blockers }
                                        .distinct()
                                        .take(3)
                                        .forEach { blocker ->
                                            Text(
                                                "• " +
                                                    il2CppBindingBlockerLabel(
                                                        blocker,
                                                    ),
                                                style =
                                                    MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    val projectExamples =
                                        result.evidenceGraph
                                            ?.targets
                                            .orEmpty()
                                            .asSequence()
                                            .filter {
                                                it.runtimeId ==
                                                    "unity_il2cpp" &&
                                                    it.fileOffset != null &&
                                                    Il2CppPatchTargetBrowser
                                                        .isAssemblyCSharp(
                                                            it,
                                                        )
                                            }
                                            .map {
                                                it.displayName
                                            }
                                            .distinct()
                                            .take(5)
                                            .toList()
                                    if (projectExamples.isNotEmpty()) {
                                        Text(
                                            "Примеры кода проекта: " +
                                                projectExamples
                                                    .joinToString(),
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
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
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text("Предупреждения анализа", fontWeight = FontWeight.SemiBold)
                                    result.engineWarnings.take(4).forEach { warning ->
                                        Text(
                                            "• " + warning.substringAfter(": ", warning),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
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
                        if (onOpenAutoMod != null) {
                            item {
                                Button(
                                    onClick = onOpenAutoMod,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("AutoMod / Patch Lab")
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
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

private fun il2CppBindingBlockerLabel(
    blocker: String,
): String =
    when {
        blocker.startsWith(
            "AMBIGUOUS_RELOCATED_CODEGEN_MODULES:",
        ) ->
            "Несколько relocation-кандидатов подходят для " +
                blocker.substringAfter(':') +
                " IL2CPP image; автоматический выбор запрещён."
        blocker.startsWith(
            "PARTIAL_RELOCATED_CODEGEN_MODULE_SET:",
        ) ->
            "Точно восстановлена только часть CodeGenModule: " +
                blocker.substringAfter(':') +
                ". Неподтверждённые images не используются как доказательство."
        blocker == "METADATA_IMAGE_MAP_UNAVAILABLE" ->
            "В metadata нет подтверждённой карты IL2CPP images."
        blocker == "CODE_REGISTRATION_SYMBOL_UNRESOLVED" ->
            "Экспорт CodeRegistration не найден; используется статический fallback."
        blocker == "CODEGEN_MODULE_ARRAY_UNRESOLVED" ||
            blocker == "STRIPPED_CODEGEN_MODULE_ARRAY_UNRESOLVED" ->
            "Не удалось однозначно восстановить таблицу CodeGenModule из ELF."
        blocker == "AMBIGUOUS_CODEGEN_MODULE_ARRAY" ->
            "Найдено несколько несовместимых кандидатов CodeGenModule; автоматический выбор запрещён."
        blocker == "CODEGEN_FALLBACK_SCAN_LIMIT_REACHED" ->
            "Достигнут безопасный лимит статического поиска CodeGenModule."
        blocker == "NO_METHOD_TOKEN_SLOT_BINDINGS" ->
            "CodeGenModule найден, но точное token → executable pointer сопоставление не доказано."
        else -> "Статическая IL2CPP-привязка: " + blocker
    }

private fun detectionStatusLabel(status: DetectionStatus): String = when (status) {
    DetectionStatus.CONFIRMED -> "Подтверждено"
    DetectionStatus.LIKELY -> "Вероятно"
    DetectionStatus.SIGNAL -> "Есть признаки"
}

private fun confidenceLabel(confidence: DetectionConfidence): String = when (confidence) {
    DetectionConfidence.HIGH -> "высокая"
    DetectionConfidence.MEDIUM -> "средняя"
    DetectionConfidence.LOW -> "низкая"
}

private fun findingStatusLabel(status: UserFindingStatus): String = when (status) {
    UserFindingStatus.FOUND -> "Найдено"
    UserFindingStatus.CONFIRMING -> "Идёт подтверждение"
    UserFindingStatus.CONFIRMED -> "Подтверждено"
    UserFindingStatus.READY -> "Готово к изменению"
    UserFindingStatus.RUNTIME_REQUIRED -> "Требуется runtime-подтверждение"
    UserFindingStatus.COULD_NOT_CONFIRM -> "Статически подтвердить не удалось"
}
