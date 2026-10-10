package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.space.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun SavedSpaceMenusScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menus by remember { mutableStateOf<List<SavedSpaceMenu>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { menus = withContext(Dispatchers.IO) { SavedSpaceMenus.load(context) } }
    Scaffold(bottomBar = {
        OutlinedButton(onClick = onBack, modifier = Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth()) { Text("Назад") }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Сохранённые меню", style = MaterialTheme.typography.headlineMedium)
                Text("Для каждого приложения сохраняется своё меню. Пространство получает меню автоматически при открытии; кнопка позволяет отправить его повторно без нового анализа. После обновления игры потребуется проверить новую версию.")
                SpaceDownloadButton(Modifier.fillMaxWidth())
                message?.let { Text(it) }
                if (menus == null) Text("Читаем профили…")
                else if (menus!!.isEmpty()) Text("Пока нет меню. Выполните анализ APK или установленного приложения.")
            }
            items(menus.orEmpty(), key = { it.packageName }) { menu ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(menu.label, style = MaterialTheme.typography.titleMedium)
                        Text(menu.packageName)
                        SpaceGenreField(menu.file, menu.genre) {
                            scope.launch { menus = withContext(Dispatchers.IO) { SavedSpaceMenus.load(context) } }
                        }
                        Text("Пунктов: ${menu.items}, статических рецептов: ${menu.staticRecipes}.")
                        if (menu.truncated) Text("Анализ содержит ограничения")
                        Text("Для включения в пространстве: ${menu.runtimeRecipes}")
                        Button(onClick = {
                            try { SpaceMenuHandoff.open(context, menu.file); message = "Пространство открыто. Меню ${menu.label} доступно для автоматической загрузки." }
                            catch (error: Exception) { message = error.message ?: "Не удалось передать меню" }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Передать меню") }
                    }
                }
            }
        }
    }
}
