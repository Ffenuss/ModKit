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
import io.github.ffenuss.modkit.BuildConfig

@Composable
fun TargetSelectionScreen(onOpenExpertLab: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("ModKit", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("v${BuildConfig.VERSION_NAME} · быстрый анализ → доказательства → изменения → APK")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Выберите цель", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Button(onClick = { }, modifier = Modifier.fillMaxWidth()) { Text("Установленное приложение") }
                Button(onClick = { }, modifier = Modifier.fillMaxWidth()) { Text("APK / APK-set / файл") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Основной процесс", fontWeight = FontWeight.SemiBold)
                Text("1. Быстрый анализ")
                Text("2. Выводы")
                Text("3. Дополнительное подтверждение только при необходимости")
                Text("4. Runtime/root только если статических доказательств недостаточно")
                Text("5. Подготовка изменений → сборка → проверенный APK")
            }
        }

        OutlinedButton(onClick = onOpenExpertLab, modifier = Modifier.fillMaxWidth()) {
            Text("Expert Lab")
        }
    }
}
