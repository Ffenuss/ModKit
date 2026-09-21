package io.github.ffenuss.modkit.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.runtime.RootAccessProbeResult
import io.github.ffenuss.modkit.runtime.RootProcessDiscovery
import io.github.ffenuss.modkit.sandbox.RootSandboxAndroidProfileManager
import io.github.ffenuss.modkit.sandbox.RootSandboxRuntimePatchCoordinator
import io.github.ffenuss.modkit.sandbox.SandboxProfileStore
import io.github.ffenuss.modkit.sandbox.SandboxRuntimeActivationSession
import io.github.ffenuss.modkit.sandbox.StoredSandboxProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SandboxScreen(
    onBack: () -> Unit,
    onOpenRootRuntime: (String) -> Unit,
    initialRootProbe: RootAccessProbeResult? = null,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val store = remember { SandboxProfileStore(appContext) }
    val repository = remember { InstalledAppRepository(appContext) }

    var profiles by remember {
        mutableStateOf(runCatching { store.list() }.getOrDefault(emptyList()))
    }
    var rootProbe by remember { mutableStateOf(initialRootProbe) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastLaunchedPackage by remember { mutableStateOf<String?>(null) }
    var activeSession by remember {
        mutableStateOf<SandboxRuntimeActivationSession?>(null)
    }

    fun refresh() {
        profiles = runCatching { store.list() }.getOrDefault(emptyList())
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val text = appContext.contentResolver
                            .openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            ?: error("Не удалось прочитать выбранный профиль.")
                        store.save(text)
                    }
                }
                message = result.fold(
                    onSuccess = {
                        refresh()
                        "Профиль добавлен в ModKit Sandbox."
                    },
                    onFailure = {
                        "Не удалось импортировать профиль: " +
                            (it.message ?: it.javaClass.simpleName)
                    },
                )
            }
        }
    }

    fun checkRoot() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    RootProcessDiscovery.probe(AtomicCancellationSignal())
                }
            }
            rootProbe = result.getOrElse {
                RootAccessProbeResult(
                    available = false,
                    uid = null,
                    message = it.message ?: it.javaClass.simpleName,
                )
            }
            busy = false
        }
    }

    fun launchSandbox(stored: StoredSandboxProfile) {
        if (busy) return
        busy = true
        message = "Проверяем APK и готовим отдельный Android sandbox…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val app = repository.find(stored.profile.packageName)
                        ?: error("Приложение " + stored.profile.packageName + " сейчас не установлено.")
                    require(app.versionCode == stored.profile.versionCode) {
                        "Версия приложения изменилась: sandbox-профиль нужно пересобрать."
                    }
                    val signal =
                        AtomicCancellationSignal()
                    val launch =
                        RootSandboxAndroidProfileManager.installExistingAndLaunch(
                            packageName = stored.profile.packageName,
                            cancellation = signal,
                        )
                    val session =
                        try {
                            RootSandboxRuntimePatchCoordinator.activateProfile(
                                context = appContext,
                                app = app,
                                profile = stored.profile,
                                expectedPid = launch.pid,
                                cancellation = signal,
                            )
                        } catch (failure: Throwable) {
                            throw IllegalStateException(
                                "Sandbox-копия запущена (PID " +
                                    launch.pid +
                                    "), но выбранные моды не активированы; " +
                                    "частичные записи откатились: " +
                                    (failure.message
                                        ?: failure.javaClass.simpleName),
                                failure,
                            )
                        }
                    launch to session
                }
            }
            result.onSuccess {
                val launch =
                    it.first
                val session =
                    it.second
                lastLaunchedPackage =
                    launch.packageName
                activeSession =
                    session
                message =
                    "Sandbox-копия запущена в Android user " +
                        launch.userId +
                        " · PID " +
                        launch.pid +
                        ". Отдельные app-data/сохранения активны; оригинал не удалялся и не очищался. " +
                        "Профиль SHA-проверен: применено " +
                        session.appliedCount +
                        ", уже было активно " +
                        session.alreadyActiveCount +
                        "."
                rootProbe = RootAccessProbeResult(true, 0, "Root подтверждён: uid=0.")
            }.onFailure {
                message = "Sandbox не запущен: " +
                    (it.message ?: it.javaClass.simpleName)
            }
            busy = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedButton(onClick = onBack) { Text("← Назад") }
        }
        item {
            Text(
                "ModKit Sandbox",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Наш ParallelSpace-слой: профиль модов хранится отдельно от APK. " +
                    "На root-устройствах ModKit создаёт отдельный Android managed-profile, " +
                    "добавляет туда уже установленную игру и запускает её с отдельными app-data. " +
                    "Оригинальный package/user 0 не удаляется и не очищается.",
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Root sandbox", fontWeight = FontWeight.SemiBold)
                    Text(rootProbe?.message ?: "Root ещё не проверен.")
                    Button(
                        onClick = ::checkRoot,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Проверить root") }
                    Text(
                        "Root sandbox теперь SHA-проверяет профиль, останавливает только точный sandbox PID на время code patch, " +
                            "сверяет исходные bytes, выполняет read-back и автоматически откатывает частично применённый профиль при ошибке. " +
                            "Без root отдельный clone-package backend будет использовать другой package id и никогда не должен заменять оригинал.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    importer.launch(arrayOf("application/json", "text/json", "text/plain"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Импортировать .modkit.json") }
        }
        if (busy) item { CircularProgressIndicator() }
        message?.let { current ->
            item { Text(current, style = MaterialTheme.typography.bodySmall) }
        }
        if (profiles.isEmpty()) {
            item {
                Text(
                    "Sandbox-профилей пока нет. В Root Process Lab выбери найденные моды и нажми «Добавить в ModKit Sandbox».",
                )
            }
        }
        items(profiles, key = { it.id }) { stored ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        stored.profile.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stored.profile.packageName + " · versionCode " + stored.profile.versionCode,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Модов: " + stored.profile.modifications.size +
                            " · SHA " + stored.profile.artifactSha256.take(16) + "…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { launchSandbox(stored) },
                        enabled = !busy && rootProbe?.available == true,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Запустить отдельную sandbox-копию") }
                    if (lastLaunchedPackage == stored.profile.packageName) {
                        OutlinedButton(
                            onClick = { onOpenRootRuntime(stored.profile.packageName) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Открыть runtime sandbox-процесса") }
                    }
                    val session =
                        activeSession
                    if (
                        session != null &&
                        session.packageName ==
                        stored.profile.packageName
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!busy) {
                                    busy = true
                                    message = "Откатываем моды текущего sandbox-процесса…"
                                    scope.launch {
                                        val rollback =
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    RootSandboxRuntimePatchCoordinator.rollbackSession(
                                                        session = session,
                                                        cancellation = AtomicCancellationSignal(),
                                                    )
                                                }
                                            }
                                        if (rollback.isSuccess) {
                                            activeSession = null
                                            message = "Моды этого sandbox-процесса откатились к исходным байтам."
                                        } else {
                                            message =
                                                "Rollback не подтверждён: " +
                                                    (rollback.exceptionOrNull()?.message
                                                        ?: "неизвестная ошибка")
                                        }
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Откатить активированные моды") }
                    }
                    OutlinedButton(
                        onClick = {
                            runCatching { store.delete(stored.id) }
                            refresh()
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Удалить профиль из ModKit Sandbox") }
                }
            }
        }
    }
}
