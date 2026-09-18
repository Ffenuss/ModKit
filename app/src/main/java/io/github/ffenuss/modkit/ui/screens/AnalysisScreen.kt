package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    error: String?,
    cancelled: Boolean,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        when {
            result != null -> {
                val index = result.index
                Text("Быстрый анализ готов за ${result.elapsedMs} мс")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("SHA-256", fontWeight = FontWeight.SemiBold)
                        Text(index.artifactSha256, style = MaterialTheme.typography.bodySmall)
                        Text("Файлов в индексе: ${index.entries.size}")
                        Text("ABI: ${index.detectedAbis.ifEmpty { setOf("не определено") }.joinToString()}")
                        Text("Runtime: ${index.runtimeProfiles.size}")
                        if (index.truncated) Text("Индекс ограничен лимитом entries.", color = MaterialTheme.colorScheme.error)
                    }
                }
                index.runtimeProfiles.forEach { runtime ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(runtime.title, fontWeight = FontWeight.SemiBold)
                            Text("${runtime.status} · ${runtime.confidence}", style = MaterialTheme.typography.bodySmall)
                            runtime.evidence.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                val targeted = result.routingPlan.targeted
                if (targeted.isNotEmpty()) {
                    Text("Следующие релевантные движки", fontWeight = FontWeight.SemiBold)
                    targeted.forEach { engine ->
                        Text(
                            "• ${engine.id}: ${if (engine.availableNow) "готов" else "ещё не перенесён"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (result.routingPlan.missingCapabilities.isNotEmpty()) {
                    Text("Что ещё нужно доделать", fontWeight = FontWeight.SemiBold)
                    result.routingPlan.missingCapabilities.take(6).forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (index.warnings.isNotEmpty()) {
                    Text("Предупреждения", fontWeight = FontWeight.SemiBold)
                    index.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Выбрать другую цель") }
            }
            error != null -> {
                Text("Ошибка: $error", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
            }
            cancelled -> {
                Text("Анализ отменён. Уже готовые данные не считаются подтверждённым полным результатом.")
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
            }
            else -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress?.currentTask ?: "Подготовка быстрого анализа…")
                progress?.currentArtifact?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                progress?.processed?.let { processed ->
                    Text(
                        progress.total?.let { total -> "$processed / $total" } ?: "Обработано: $processed",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Отменить") }
            }
        }
    }
}
