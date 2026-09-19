package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.AnalysisRunState

@Composable
fun RecoveryScreen(
    state: AnalysisRunState.Interrupted,
    onResume: () -> Unit,
    onOpenPartial: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Прерванный анализ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.target.label, fontWeight = FontWeight.SemiBold)
                Text(state.message)
                state.previousProgress?.currentTask?.let { Text("Последний этап: $it") }
                state.previousProgress?.currentArtifact?.let { Text("Последний файл: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) {
            Text("Продолжить анализ")
        }
        if (state.partialAvailable) {
            OutlinedButton(onClick = onOpenPartial, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть частичные результаты")
            }
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Text("Удалить состояние")
        }
    }
}


@Composable
fun RestoringPartialScreen(
    state: AnalysisRunState.RestoringPartial,
) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Восстановление результатов",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.target.label, fontWeight = FontWeight.SemiBold)
                Text(state.message)
                Text(
                    "Файлы APK повторно не анализируются: читаются только завершённые SHA-привязанные стадии.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
