package io.github.ffenuss.modkit.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.space.SpaceActiveProfileSelection
import io.github.ffenuss.modkit.space.SpaceActiveProfileStore
import io.github.ffenuss.modkit.space.SpaceBuildProfileIndex
import io.github.ffenuss.modkit.space.SpaceInstalledPackageLauncher
import io.github.ffenuss.modkit.space.SpaceLegacyMenuBridge
import io.github.ffenuss.modkit.space.SpaceTrustedHost
import io.github.ffenuss.modkit.space.SpaceLaunchStatus
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SpaceProfileRow(
    val record: AutoModBuildRecord,
    val status: SpaceLaunchStatus,
)

@Composable
fun SpaceProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var profiles by remember { mutableStateOf<List<SpaceProfileRow>>(emptyList()) }
    var originals by remember { mutableStateOf<List<InstalledAppTarget>>(emptyList()) }
    val originalPrefs = remember(context) {
        context.getSharedPreferences("space-original-package-v1", 0)
    }
    var selectedOriginal by remember {
        mutableStateOf(originalPrefs.getString("package", null))
    }
    var showLegacyBuilds by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedKey by remember { mutableStateOf(SpaceActiveProfileStore.read(context)) }
    var selecting by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    LaunchedEffect(refresh) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                SpaceBuildProfileIndex.load(context.applicationContext).map { record ->
                    SpaceProfileRow(
                        record,
                        SpaceInstalledPackageLauncher.status(context.applicationContext, record),
                    )
                }
            }
        }
        profiles = loaded.getOrDefault(emptyList())
        // The normal no-root path shows already installed, OS-recognized
        // games. This is an original-package launcher, NOT virtualization.
        originals = withContext(Dispatchers.IO) {
            InstalledAppRepository(context.applicationContext).load()
                .filter { it.isGame && it.hasLauncherActivity && !it.isSystemApp }
        }
        selectedKey = SpaceActiveProfileStore.read(context)
        error = loaded.exceptionOrNull()?.message
        loading = false
    }

    val selectedRecord = SpaceActiveProfileSelection.resolve(
        selectedKey, profiles.map { it.record to it.status },
    )

    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                TextButton(onClick = onBack) { Text("Назад") }
                Text("Space · игровые профили",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "По умолчанию Space запускает оригинальные игры, уже установленные " +
                        "в Android. Никакая игра не удаляется, не копируется и не " +
                        "переподписывается. Это пока обычный лаунчер, а не " +
                        "виртуальная среда с работающими модами.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (selectedOriginal != null && originals.any { it.packageName == selectedOriginal })
                        "Оригинальная активная игра: " + selectedOriginal
                    else "Выберите оригинальную установленную игру ниже.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val currentOriginal = originals.singleOrNull {
                    it.packageName == selectedOriginal
                }
                if (currentOriginal != null) {
                    Button(
                        onClick = {
                            try {
                                val intent = requireNotNull(
                                    context.packageManager.getLaunchIntentForPackage(currentOriginal.packageName)
                                ) { "Игра удалена или не имеет экрана запуска." }
                                check(intent.component?.packageName == currentOriginal.packageName) {
                                    "Android вернул экран другого пакета."
                                }
                                context.startActivity(
                                    Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                error = null
                            } catch (failure: Exception) {
                                error = failure.message ?: "Не удалось запустить оригинальную игру."
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Запустить оригинал · без установки") }
                }
                if (originals.isEmpty() && !loading) {
                    Text("Android не сообщил о доступных установленных играх. " +
                        "После установки официальной игры обновите список.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("Оригинальные игры", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (showLegacyBuilds) Text(
                    when {
                        selectedRecord != null ->
                            "Активный профиль: ${selectedRecord.plan.packageName}"
                        selectedKey != null ->
                            "Ранее выбранная сборка недоступна или изменилась. Выберите проверенную версию заново."
                        else -> "Активная игра пока не выбрана."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (showLegacyBuilds) Text(
                    "Ниже доступны старые профили переподписанных APK. " +
                        "Они несовместимы с установленными оригиналами и по умолчанию скрыты. " +
                        "Для нового режима никаких переподписанных сборок не нужно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showLegacyBuilds && selectedKey != null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                selecting = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        SpaceActiveProfileStore.clear(context.applicationContext)
                                    }
                                    selectedKey = null
                                    error = null
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Не удалось снять выбор игры."
                                } finally {
                                    selecting = false
                                }
                            }
                        },
                        enabled = !selecting,
                    ) { Text("Снять выбор") }
                }
                if (showLegacyBuilds && selectedRecord != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    // Do not advertise a stale profile or copy
                                    // static APK modifications into live commands.
                                    check(withContext(Dispatchers.IO) {
                                        SpaceLegacyMenuBridge.verified(context.applicationContext) != null
                                    }) { "Активный APK изменился. Обновите профили." }
                                    SpaceLegacyMenuBridge.announce(context)
                                    SpaceTrustedHost.open(context)
                                    error = null
                                } catch (failure: Exception) {
                                    // Keep the actionable error visible; a refresh
                                    // would overwrite it with a successful load.
                                    error = failure.message ?: "Не удалось открыть проверенный Space."
                                }
                            }
                        },
                        enabled = !loading && !selecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Открыть Space · только сведения об игре") }
                }
                OutlinedButton(
                    onClick = { refresh++ },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Обновить профили") }
            }
            if (loading) item { CircularProgressIndicator() }
            items(originals.take(60), key = { "original:" + it.packageName }) { game ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(game.label, style = MaterialTheme.typography.titleSmall)
                        Text(game.packageName, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            if (originalPrefs.edit().putString("package", game.packageName).commit()) {
                                selectedOriginal = game.packageName
                                error = null
                            } else {
                                error = "Не удалось сохранить выбор оригинальной игры."
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (selectedOriginal == game.packageName)
                                "Оригинал выбран" else "Выбрать оригинальную игру")
                        }
                    }
                }
            }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error) }
            }
            item {
                OutlinedButton(
                    onClick = { showLegacyBuilds = !showLegacyBuilds },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showLegacyBuilds) "Скрыть старые переподписанные сборки"
                        else "Показать старые переподписанные сборки")
                }
            }
            if (showLegacyBuilds && !loading && profiles.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Сохранённых сборок пока нет",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Проанализируйте игру, выберите проверенные рецепты и " +
                                "создайте APK. После этого появится отдельный профиль.")
                        }
                    }
                }
            }
            items(if (showLegacyBuilds) profiles else emptyList(),
                key = { it.record.plan.packageName }) { row ->
                val record = row.record
                val isActive = selectedRecord?.let { selected ->
                    selected.plan.packageName == record.plan.packageName &&
                        selected.plan.artifactSha256 == record.plan.artifactSha256
                } ?: false
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(record.plan.packageName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text("APK в комплекте: " + record.plan.apks.size +
                            " · Выбрано изменений: " + record.changes.size,
                            style = MaterialTheme.typography.bodySmall)
                        record.changes.take(3).forEach { change ->
                            Text("• " + change, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            when (row.status) {
                                SpaceLaunchStatus.READY ->
                                    "Подпись и SHA-256 установленного APK совпадают со сборкой"
                                SpaceLaunchStatus.NOT_INSTALLED ->
                                    "Сборка не установлена. Установите её через ModKit."
                                SpaceLaunchStatus.DIFFERENT_SIGNATURE ->
                                    "На устройстве другая подпись. Запуск этого профиля заблокирован."
                                SpaceLaunchStatus.NO_LAUNCHER ->
                                    "Установленный пакет не содержит доступной Activity запуска."
                                SpaceLaunchStatus.APK_CONTENT_MISMATCH ->
                                    "Содержимое установленного APK отличается от сохранённой сборки."
                            },
                            color = if (row.status == SpaceLaunchStatus.READY)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    selecting = true
                                    try {
                                        selectedKey = SpaceActiveProfileStore.activate(context, record)
                                        error = null
                                    } catch (failure: Exception) {
                                        error = failure.message ?: "Не удалось выбрать профиль."
                                        refresh++
                                    } finally {
                                        selecting = false
                                    }
                                }
                            },
                            enabled = row.status == SpaceLaunchStatus.READY && !isActive && !selecting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (isActive) "Активная игра" else "Выбрать активной игрой") }
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        SpaceInstalledPackageLauncher.launch(context, record)
                                    }.onFailure {
                                        error = it.message ?: "Не удалось запустить игру."
                                        refresh++
                                    }
                                }
                            },
                            enabled = row.status == SpaceLaunchStatus.READY,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Запустить установленную игру") }
                    }
                }
            }
        }
    }
}
