package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.domain.EngineProgress

@Composable
fun SimpleAnalysisScreen(title: String, progress: EngineProgress?, result: FastAnalysisResult?,
    active: Boolean, error: String?, cancelled: Boolean, cancelling: Boolean, stalledAgeMs: Long?,
    canSkipStalled: Boolean, partialNotice: String? = null, onOpenAutoMod: (() -> Unit)? = null,
    onCancel: () -> Unit, onRetry: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    Scaffold(bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) {
                    if (stalledAgeMs != null) {
                        Text("Операция долго не отвечает. Можно повторить этап или отменить анализ.")
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Повторить этап") }
                        if (canSkipStalled) TextButton(onClick = onSkip) { Text("Пропустить этап") }
                    }
                    OutlinedButton(onClick = onCancel, enabled = !cancelling, modifier = Modifier.fillMaxWidth()) {
                        Text(if (cancelling) "Отмена…" else "Отменить анализ")
                    }
                } else {
                    onOpenAutoMod?.let { Button(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Выбрать изменения") } }
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("К выбору приложения") }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("1 / 3  •  Анализ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(0.3f))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (active) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(when {
                        cancelling -> "Завершаем текущую операцию…"
                        cancelled -> "Анализ отменён"
                        error != null -> "Не удалось завершить анализ"
                        active -> progress?.currentTask ?: "Определяем технологии приложения"
                        else -> "Анализ завершён"
                    }, style = MaterialTheme.typography.titleMedium)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    partialNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    progress?.processed?.let { count -> Text(progress.total?.let { "$count / $it" } ?: "Обработано: $count",
                        style = MaterialTheme.typography.bodyMedium) }
                    if (result != null) Text("Результаты сохраняются по мере анализа.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Большие игры требуют больше времени. После анализа появится список доступных изменений.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
        }
    }
}
