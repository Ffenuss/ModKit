package io.github.ffenuss.modkit.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.net.Uri
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
import io.github.ffenuss.modkit.patch.RuntimeRecipeSelectionPolicy
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
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.permissionReturned() }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val overlaySettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayGranted = Settings.canDrawOverlays(context)
        if (overlayGranted) model.launchWithOverlay()
        else model.showError("Разрешение на показ поверх других приложений не выдано.")
    }
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
    if (expert) {
        BackHandler { expert = false }
        AutoModScreen(target, result) { expert = false }
        return
    }
    val record = state.built
    val resultVisible = state.showingResult && record != null
    val runtimeBuild = record?.runtimeMenuItems?.isNotEmpty() == true
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
                                            "Подтверждение уже открыто. Если вы закрыли окно Android, повторите установку."
                                        }
                                    }.onFailure { model.showError(it.message.orEmpty()) }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Подтвердить установку") }
                            } else {
                                Text("Системное окно подтверждения уже запрошено. Если оно было закрыто, нажмите «Установить» ещё раз.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            status?.let { Text(when (it.kind) {
                                RepackedRuntimeInstallStatusKind.SUCCESS -> "Приложение установлено"
                                RepackedRuntimeInstallStatusKind.FAILURE -> "Установка не выполнена: ${it.message ?: it.statusCode}"
                                else -> it.message ?: "Ожидаем ответ Android"
                            }, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (runtimeBuild && status?.kind == RepackedRuntimeInstallStatusKind.SUCCESS) {
                            Button(onClick = {
                                overlayGranted = Settings.canDrawOverlays(context)
                                if (overlayGranted) model.launchWithOverlay()
                                else {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    if (Build.VERSION.SDK_INT < 30) {
                                        intent.data = Uri.parse("package:" + context.packageName)
                                    }
                                    runCatching { overlaySettings.launch(intent) }
                                        .onFailure { model.showError(it.message ?: "Настройки разрешения недоступны.") }
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (overlayGranted) "Запустить с мод-меню" else "Разрешить окно поверх игры")
                            }
                            if (!overlayGranted) Text(
                                "В системных настройках выберите ModKit и разрешите показ поверх других приложений.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = model::install, modifier = Modifier.weight(1f)) { Text("Установить") }
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
                            Text("Сборка создана", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(if (record!!.plan.apks.size == 1) "Один подписанный APK" else "Комплект из ${record.plan.apks.size} APK — установка вместе")
                            Text(if (runtimeBuild)
                                "Создан APK с ${record.runtimeMenuItems.size} переключателями. Моды выключены до вашего нажатия в меню MK."
                            else "Статические DEX-патчи и подпись проверены. Переключателей в игре нет.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Text(if (runtimeBuild) "Добавлено в меню (изначально выключено)" else "Применено статически",
                    style = MaterialTheme.typography.titleMedium) }
                items(record!!.changes) { Text("✓  $it", style = MaterialTheme.typography.bodyMedium) }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Проверка в приложении", style = MaterialTheme.typography.titleMedium)
                            Text(if (runtimeBuild)
                                "1. Установите сборку.\n2. Разрешите показ поверх других приложений.\n3. Запустите с мод-меню и включайте нужные изменения кнопкой MK."
                            else "1. Установите сборку.\n2. Запустите приложение и проверьте выбранное действие.\n3. Сохраните наблюдение.")
                            record.userObservation?.let { Text("Ваше наблюдение: $it\nАвтоматическим подтверждением не является.", style = MaterialTheme.typography.bodySmall) }
                            if (!runtimeBuild) OutlinedButton(onClick = {
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
                    Text("Нативные ARM64-рецепты будут выключены до включения в мод-меню. DEX-рецепты пока статические. Эффект проверяйте в игре.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Доступно переключателей: ${state.recipes.count(RuntimeRecipeSelectionPolicy::supports)}",
                        style = MaterialTheme.typography.labelMedium)
                }
                result.il2cppBinaryBinding?.let { binding ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Охват IL2CPP", style = MaterialTheme.typography.titleMedium)
                                result.il2cppFastDump?.metadata?.let { metadata ->
                                    Text("Прочитано методов: ${metadata.methods.size} / ${metadata.declaredMethodCount ?: "?"}")
                                }
                                Text("Точных нативных привязок: ${binding.exactBindingCount}")
                                if (binding.evidence.any { it.bindingIndex != null }) Text(
                                    "Полный индекс сохранён на устройстве. Предпросмотр в памяти: " +
                                        binding.evidence.sumOf { it.bindings.size } +
                                        ". Поиск и экспорт используют полный индекс.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (state.built != null) item { TextButton(onClick = model::showPreviousResult) { Text("Открыть предыдущую сборку") } }
                if (state.recipes.isEmpty() && !state.busy) item {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Готовые рецепты не найдены", style = MaterialTheme.typography.titleMedium)
                        val missingIl2Cpp = result.routingPlan.missingCapabilities
                            .firstOrNull { it.startsWith("IL2CPP:") }
                        result.flutterAssetInventory?.let { inventory ->
                            Text(
                                "Flutter: манифестов прочитано: " +
                                    inventory.validatedManifestCount +
                                    "; ресурсов в манифестах: " + inventory.listedAssetCount +
                                    "; обнаружено файлов: " + inventory.packagedAssetCount + ".",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Проверены ресурсы и нативные компоненты. " +
                                    "Анализ игровой логики Dart AOT пока недоступен.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        result.unrealAssetInventory?.let { inventory ->
                            Text(
                                "Unreal: изучено контейнеров и пакетов: " +
                                    inventory.records.size +
                                    "; подтверждённых индексов PAK: " +
                                    inventory.verifiedPakCount + ".",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Проверка Blueprint и игровых объектов ещё не поддерживается. " +
                                    "Этот результат — инвентаризация, а не найденные моды.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (inventory.records.isEmpty()) Text(
                                "PAK/IoStore не обнаружены в выбранных APK: ресурсы могли загружаться отдельно.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(missingIl2Cpp ?: "Доступный анализ не подтвердил поддерживаемые изменения. Подробности и ручные инструменты есть в экспертном режиме.")
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
                Text(if (RuntimeRecipeSelectionPolicy.supports(recipe)) "Переключатель в игре · Эффект не проверен"
                    else if (recipe.selectable && recipe.dex.isNotEmpty()) "Статический DEX-патч · Отключение не поддерживается"
                    else if (recipe.selectable) "Нет поддержки runtime-переключателя"
                    else "Нужен дополнительный анализ",
                    style = MaterialTheme.typography.labelSmall, color = if (recipe.selectable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
