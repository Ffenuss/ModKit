package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.BuildConfig

@Composable
fun TargetSelectionScreen(onSelectGames: () -> Unit, onSelectApps: () -> Unit, onSelectFile: () -> Unit) {
    Scaffold { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Spacer(Modifier.height(24.dp))
                Text("MODKIT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(14.dp))
                Text("Ваше приложение.\nВаши изменения.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Выберите приложение. Мы найдём доступные рецепты и подготовим APK прямо на телефоне.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("01  •  На этом телефоне", style = MaterialTheme.typography.labelMedium)
                        Text("Выберите игру\nили приложение", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Button(onClick = onSelectGames, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Выбрать игру") }
                        OutlinedButton(onClick = onSelectApps, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Выбрать приложение") }
                    }
                }
            }
            item {
                OutlinedCard(onClick = onSelectFile, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("02  •  Из файла", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Открыть APK", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Выберите установочный файл на устройстве.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Text("Анализ → выбор изменений → сборка", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Text("Без root и облачных сервисов. Доступность изменений зависит от кода приложения.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
