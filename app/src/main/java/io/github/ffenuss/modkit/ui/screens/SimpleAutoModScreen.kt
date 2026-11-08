package io.github.ffenuss.modkit.ui.screens

import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.patch.AutoModRecipe
import io.github.ffenuss.modkit.runtime.*
import io.github.ffenuss.modkit.ui.AutoModViewModel

@Composable
fun SimpleAutoModScreen(target: AnalysisTargetDescriptor, result: FastAnalysisResult, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val model = remember(activity) { ViewModelProvider(activity)[AutoModViewModel::class.java] }
    val state by model.state.collectAsState()
    val installStatus by RepackedRuntimeInstallStatusStore.status.collectAsState()
    var expert by rememberSaveable(result.index.artifactSha256) { mutableStateOf(false) }
    var query by rememberSaveable(result.index.artifactSha256) { mutableStateOf("") }
    var showUnavailable by rememberSaveable { mutableStateOf(false) }
    var testDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var metricExperimentOpen by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.permissionReturned() }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) model.save(uri) }
    LaunchedEffect(result.index.artifactSha256) { model.initialize(target, result) }
    LaunchedEffect(state.needsInstallPermission, state.busy) {
        if (state.needsInstallPermission && !state.busy) {
            model.permissionLaunched()
            runCatching { permission.launch(AndroidRepackedRuntimeInstaller.unknownSourcesSettingsIntent(context)
                .apply { removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
                .onFailure { model.showError(it.message ?: "Настройки установки недоступны.") }
        }
    }
    BackHandler(state.busy) { model.cancel() }
    if (metricExperimentOpen && target is AnalysisTargetDescriptor.InstalledPackage) {
        NativeMetricRecorderScreen(target, result) { metricExperimentOpen = false }
        return
    }
    if (target is AnalysisTargetDescriptor.InstalledPackage) {
        // Installed packages must never lead to build/install of a ModKit-
        // signed replacement. This path keeps the original signature and
        // app data, and launches only the SHA-verified existing package.
        Scaffold(
            topBar = {
                Surface(tonalElevation = 1.dp) {
                    Row(Modifier.statusBarsPadding().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack, enabled = !state.busy) {
                            Text("Назад")
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("ModKit · оригинал", style = MaterialTheme.typography.titleMedium)
                            Text(target.label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
        ) { insets ->
            LazyColumn(Modifier.fillMaxSize().padding(insets),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Без удаления и переустановки",
                                style = MaterialTheme.typography.titleLarge)
                            Text(
                                "ModKit проверяет, что установленный APK совпадает с проанализированным, " +
                                    "и запускает оригинальное приложение. Его подпись, файлы и данные не меняются.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Важное ограничение: это пока обычный запуск оригинала, " +
                                    "не виртуальный ModKit Space. Меню и поиск кандидатов не означают, " +
                                    "что мод может изменить чужой процесс без root.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                state.error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error) }
                }
                state.notice?.let { message ->
                    item { Text(message, style = MaterialTheme.typography.bodyMedium) }
                }
                if (state.busy) {
                    item {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.operation, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::cancel) { Text("Отмена") }
                    }
                }
                item {
                    Button(
                        onClick = model::launchOriginal,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Запустить оригинальное приложение") }
                }
                item {
                    OutlinedButton(
                        onClick = { model.exportDiagnostics() },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Экспортировать результаты анализа") }
                }
                if (result.elfInventory?.records?.any {
                    it.scalarExportCandidates.isNotEmpty()
                } == true) {
                    item {
                        OutlinedButton(
                            onClick = { metricExperimentOpen = true },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Исследовать показатель · только наблюдение") }
                    }
                }
                if (state.recipes.isEmpty() && !state.busy) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Подтверждённых игровых модов нет",
                                    style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Слова вроде Health, Level и Damage могут относиться " +
                                        "к диагностике и служебным функциям даже в файловом менеджере. " +
                                        "Без доказанной игровой механики они не выдаются как моды.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                } else if (state.recipes.isNotEmpty()) {
                    item {
                        Text("Статические кандидаты (не рабочие моды)",
                            style = MaterialTheme.typography.titleMedium)
                    }
                    items(state.recipes.take(30), key = { it.id }) { recipe ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(recipe.title, style = MaterialTheme.typography.titleSmall)
                                Text(recipe.targetLabel,
                                    style = MaterialTheme.typography.bodySmall)
                                Text(recipe.blocker ?: "Игровой эффект не подтверждён.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        return
    }
    if (expert) {
        BackHandler { expert = false }
        AutoModScreen(target, result) { expert = false }
        return
    }
    val record = state.built
    val resultVisible = state.showingResult && record != null
    val status = installStatus.takeIf { record != null && it.packageName == record.plan.packageName && it.updatedAtEpochMs >= record.builtAt }
    Scaffold(
        topBar = {
            Surface(tonalElevation = 1.dp) {
                Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onBack, enabled = !state.busy) { Text("Назад") }
                        Spacer(Modifier.weight(1f))
                        Box {
                            TextButton(onClick = { menuOpen = true }, enabled = !state.busy) { Text("Ещё") }
                            DropdownMenu(menuOpen, { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Экспертный режим") }, onClick = { menuOpen = false; expert = true })
                                DropdownMenuItem(text = { Text("Исследовать числовой показатель · без патча") },
                                    enabled = target is AnalysisTargetDescriptor.InstalledPackage &&
                                        result.elfInventory?.records?.any { it.scalarExportCandidates.isNotEmpty() } == true,
                                    onClick = { menuOpen = false; metricExperimentOpen = true })
                                DropdownMenuItem(text = { Text("Экспорт диагностики") }, onClick = {
                                    menuOpen = false; model.exportDiagnostics()
                                })
                            }
                        }
                    }
                    Text(target.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (resultVisible) "3 / 3  •  Результат" else "2 / 3  •  Выберите изменения",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp, tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.operation, style = MaterialTheme.typography.labelLarge)
                                state.progress?.currentTask?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                            }
                            TextButton(onClick = model::cancel) { Text("Отмена") }
                        }
                    } else if (resultVisible) {
                        if (status?.kind == RepackedRuntimeInstallStatusKind.USER_ACTION_REQUIRED) {
                            Text("Android ждёт подтверждения установки", style = MaterialTheme.typography.labelLarge)
                            if (RepackedRuntimeInstallConfirmationStore.availableFor(status.sessionId)) {
                                Button(onClick = {
                                    runCatching {
                                        check(RepackedRuntimeInstallConfirmationStore.open(context, requireNotNull(status.sessionId))) {
                                            "Подтверждение больше недоступно. Повторите установку."
                                        }
                                    }.onFailure { model.showError(it.message.orEmpty()) }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Подтвердить установку") }
                            } else {
                                Text(
                                    "Подтверждение уже было открыто или его сессия завершена. " +
                                        "Проверьте системный диалог либо начните установку заново.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            status?.let { Text(when (it.kind) {
                                RepackedRuntimeInstallStatusKind.SUCCESS -> "Приложение установлено"
                                RepackedRuntimeInstallStatusKind.FAILURE -> "Установка не выполнена: ${it.message ?: it.statusCode}"
                                else -> it.message ?: "Ожидаем ответ Android"
                            }, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(
                            "Это экспериментальный переподписанный APK, а не мод поверх официальной игры. " +
                                "Не удаляйте оригинал ради установки: подпись и проверки магазина могут отличаться.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = model::install, modifier = Modifier.weight(1f)) {
                                Text("Тестовая установка")
                            }
                            OutlinedButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 29) model.save() else folder.launch(null)
                            }, modifier = Modifier.weight(1f)) { Text("Сохранить") }
                        }
                    } else {
                        Button(onClick = model::build, enabled = state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.selected.isEmpty()) "Выберите изменения" else "Создать мод · ${state.selected.size}")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (resultVisible) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Экспериментальная APK-сборка", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(if (record!!.plan.apks.size == 1) "Один подписанный APK" else "Комплект из ${record.plan.apks.size} APK — установка вместе")
                            Text("Патчи и подпись проверены. Игровой эффект пока не подтверждён.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Text("Применено", style = MaterialTheme.typography.titleMedium) }
                items(record!!.changes) { Text("✓  $it", style = MaterialTheme.typography.bodyMedium) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Проверка в приложении", style = MaterialTheme.typography.titleMedium)
                            Text("1. Установите сборку.\n2. Запустите приложение и проверьте выбранное действие.\n3. Сохраните наблюдение.")
                            record.userObservation?.let { Text("Ваше наблюдение: $it\nАвтоматическим подтверждением не является.", style = MaterialTheme.typography.bodySmall) }
                            OutlinedButton(onClick = {
                                runCatching {
                                    val launch = context.packageManager.getLaunchIntentForPackage(record.plan.packageName)
                                        ?: error("Сначала установите сборку.")
                                    context.startActivity(launch)
                                }.onFailure { model.showError(it.message.orEmpty()) }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Запустить приложение") }
                            TextButton(onClick = { testDialog = true }) { Text("Записать результат проверки") }
                        }
                    }
                }
                item { TextButton(onClick = model::chooseAgain) { Text("Изменить выбор и создать новую сборку") } }
            } else {
                item {
                    Text("Настройте свой мод", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Доступно рецептов: ${state.recipes.count { it.selectable }}. Эффект каждого нужно проверить после установки.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.built != null) item { TextButton(onClick = model::showPreviousResult) { Text("Открыть предыдущую сборку") } }
                if (state.recipes.isEmpty() && !state.busy) item {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Готовые рецепты не найдены", style = MaterialTheme.typography.titleMedium)
                        Text("Доступный анализ не подтвердил поддерживаемые изменения. Подробности и ручные инструменты есть в экспертном режиме.")
                        TextButton(onClick = model::discover) { Text("Повторить проверку") }
                    } }
                }
                if (state.recipes.isNotEmpty()) {
                    item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Поиск изменений") }, singleLine = true) }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(showUnavailable, { showUnavailable = it })
                            Text("Показать недоступные (${state.recipes.count { !it.selectable }})", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val visible = state.recipes.filter { (showUnavailable || it.selectable) &&
                        (query.isBlank() || "${it.title} ${it.category} ${it.targetLabel}".contains(query, true)) }
                    visible.groupBy { it.category }.forEach { (category, recipes) ->
                        item(key = "category:$category") { Text(category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                        items(recipes, key = { it.id }) { recipe ->
                            RecipeCard(recipe, recipe.id in state.selected, !state.busy,
                                { model.setScalarValue(recipe.id, it) }) { model.toggle(recipe.id) }
                        }
                    }
                }
            }
        }
    }
    if (testDialog) AlertDialog(onDismissRequest = { testDialog = false }, title = { Text("Результат вашей проверки") },
        text = { Text("Отметьте наблюдение после выполнения игрового действия. ModKit сохранит его отдельно от автоматических проверок.") },
        confirmButton = { TextButton(onClick = { testDialog = false; model.recordObservation("Эффект наблюдается") }) { Text("Эффект есть") } },
        dismissButton = { TextButton(onClick = { testDialog = false; model.recordObservation("Эффект не наблюдается или есть ошибка") }) { Text("Не сработало") } })
}

@Composable
private fun RecipeCard(recipe: AutoModRecipe, selected: Boolean, enabled: Boolean, setValue: (String) -> Unit, toggle: () -> Unit) {
    var valuesOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(enabled && recipe.selectable, onClick = toggle),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Checkbox(selected, onCheckedChange = { toggle() }, enabled = enabled && recipe.selectable)
            Column(Modifier.weight(1f).padding(start = 4.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(recipe.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(recipe.targetLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(recipe.blocker ?: recipe.description, style = MaterialTheme.typography.bodySmall)
                if (recipe.selectable && recipe.scalarValues.isNotEmpty()) Box {
                    TextButton(onClick = { valuesOpen = true }, enabled = enabled) { Text("Значение: ${recipe.scalarValue}") }
                    DropdownMenu(valuesOpen, { valuesOpen = false }) {
                        recipe.scalarValues.forEach { choice -> DropdownMenuItem(text = { Text(choice.value) },
                            onClick = { valuesOpen = false; setValue(choice.value) }) }
                    }
                }
                Text(if (recipe.selectable) "Рецепт доступен · Эффект не проверен" else "Нужен дополнительный анализ",
                    style = MaterialTheme.typography.labelSmall, color = if (recipe.selectable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
