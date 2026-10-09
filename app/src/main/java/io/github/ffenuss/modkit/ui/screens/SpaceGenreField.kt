package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.GameGenre
import io.github.ffenuss.modkit.space.SavedSpaceMenus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The existing genre label opens correction; there is no extra toolbar button. */
@Composable
fun SpaceGenreField(file: File, genre: String, onChanged: (GameGenre) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(file) { mutableStateOf(false) }
    var saving by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = true }, enabled = !saving,
        modifier = Modifier.semantics { contentDescription = "Изменить жанр приложения" }) {
        Text(if (saving) "Сохраняем жанр…" else "Жанр: $genre")
    }
    error?.let { Text(it) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Жанр приложения") },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text("Выбор изменит порядок найденных пунктов и сохранится для следующего анализа.") }
            items(GameGenre.entries) { selected ->
                TextButton(onClick = {
                    open = false; saving = true; error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { SavedSpaceMenus.updateGenre(context, file, selected) }
                            onChanged(selected)
                        } catch (failure: Exception) {
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            error = failure.message ?: "Не удалось сохранить жанр"
                        } finally { saving = false }
                    }
                }) { Text(selected.title) }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } })
}
