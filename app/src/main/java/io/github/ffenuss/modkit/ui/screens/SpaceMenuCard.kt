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
    var genre by remember(menu?.profilePath) { mutableStateOf(menu?.plan?.genre?.genre?.title.orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Меню для пространства", style = MaterialTheme.typography.titleMedium)
            if (menu == null) {
                Text(result.engineWarnings.firstOrNull { it.startsWith("Меню пространства не создано:") }
                    ?: "Профиль меню ещё не подготовлен для этого результата.")
            } else {
                Text(menu.packageName)
                Text("Движок: " + menu.plan.engines.joinToString { it.title })
                SpaceGenreField(File(menu.profilePath), genre) { genre = it.title }
                Text("Кандидатов: ${menu.candidates}. Статических рецептов: ${menu.staticRecipes}.")
                Text("Для включения в пространстве: ${menu.runtimeRecipes}")
                if (menu.truncated) Text("Результат неполный: проверьте ограничения анализа.")
                Text("После установки откройте пространство: оно автоматически получит это меню из ModKit. Выберите оригинальное приложение в списке пространства.")
                Text("Исполнимые native-рецепты появятся переключателями в меню приложения. Перед включением пространство проверит версию, библиотеку и исходные байты. Другие кандидаты пока не включаются.")
                Button(onClick = {
                    try {
                        io.github.ffenuss.modkit.space.SpaceMenuHandoff.open(context, File(menu.profilePath))
                        message = "Меню передано. Выберите приложение в пространстве."
                    } catch (error: Exception) { message = error.message ?: "Не удалось открыть пространство" }
                }, modifier = Modifier.fillMaxWidth()) { Text("Открыть пространство") }
                message?.let { Text(it) }
            }
        }
    }
}
