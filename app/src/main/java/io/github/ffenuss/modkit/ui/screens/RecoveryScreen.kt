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
        Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Продолжить / запустить цель снова") }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Удалить состояние") }
    }
}
