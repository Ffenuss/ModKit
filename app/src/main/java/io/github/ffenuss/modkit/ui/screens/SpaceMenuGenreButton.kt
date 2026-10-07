package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.GameGenre
import io.github.ffenuss.modkit.space.SavedSpaceMenus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SpaceMenuGenreButton(file: File, onChanged: (GameGenre) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(file) { mutableStateOf(false) }
    var saving by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    OutlinedButton(onClick = { open = true }, enabled = !saving) { Text(if (saving) "Сохраняем…" else "Уточнить жанр") }
    error?.let { Text(it) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Жанр приложения") },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text("Выбор изменит порядок найденных пунктов. Новый анализ не потребуется.") }
            items(GameGenre.entries) { genre ->
                TextButton(onClick = {
                    open = false; saving = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { SavedSpaceMenus.updateGenre(context, file, genre) }
                            onChanged(genre)
                        } catch (failure: Exception) { error = failure.message ?: "Не удалось сохранить жанр" }
                        finally { saving = false }
                    }
                }) { Text(genre.title) }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } })
}
