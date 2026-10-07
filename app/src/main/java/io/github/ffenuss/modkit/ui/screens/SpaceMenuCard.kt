package io.github.ffenuss.modkit.ui.screens

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import java.io.File

@Composable
fun SpaceMenuCard(result: FastAnalysisResult) {
    val context = LocalContext.current
    var message by remember(result.index.artifactSha256) { mutableStateOf<String?>(null) }
    val menu = result.spaceMenu
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Меню для пространства", style = MaterialTheme.typography.titleMedium)
            if (menu == null) {
                Text(result.engineWarnings.firstOrNull { it.startsWith("Меню пространства не создано:") }
                    ?: "Профиль меню ещё не подготовлен для этого результата.")
            } else {
                Text(menu.packageName)
                Text("Движок: " + menu.plan.engines.joinToString { it.title })
                Text("Жанр: ${menu.plan.genre.genre.title}" + if (menu.plan.genre.evidence.isNotEmpty()) " · предварительная оценка" else "")
                Text("Приоритет поиска: " + menu.plan.searchPriorities.joinToString())
                Text("Кандидатов: ${menu.candidates}. Статических рецептов: ${menu.staticRecipes}.")
                if (menu.truncated) Text("Результат неполный: проверьте ограничения анализа.")
                Text("В пространстве откроется меню этого приложения. Исполнитель игровых изменений ещё не подключён; найденные пункты показываются с доказательствами без активных переключателей.")
                Button(onClick = {
                    try {
                        io.github.ffenuss.modkit.space.SpaceMenuHandoff.open(context, File(menu.profilePath))
                        message = "Меню передано. Выберите приложение в пространстве."
                    } catch (error: Exception) { message = error.message ?: "Не удалось открыть пространство" }
                }, modifier = Modifier.fillMaxWidth()) { Text("Передать меню в пространство") }
                message?.let { Text(it) }
            }
        }
    }
}
