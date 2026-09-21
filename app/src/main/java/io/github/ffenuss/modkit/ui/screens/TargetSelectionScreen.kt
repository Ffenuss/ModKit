package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ffenuss.modkit.BuildConfig
import io.github.ffenuss.modkit.runtime.RootAccessProbeResult

@Composable
fun TargetSelectionScreen(
    onSelectInstalled: () -> Unit,
    onSelectFile: () -> Unit,
    onOpenExpertLab: () -> Unit,
    rootProbe: RootAccessProbeResult?,
    rootChecking: Boolean,
    onCheckRoot: () -> Unit,
    onOpenRootProcessLab: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("ModKit", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("v${BuildConfig.VERSION_NAME}")
        Text(
            "Выберите приложение. Сначала выполняется быстрый пассивный анализ; глубокие движки запускаются только по найденным runtime и доказательствам.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Новая проверка", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Button(onClick = onSelectInstalled, modifier = Modifier.fillMaxWidth()) {
                    Text("Установленное приложение")
                }
                Button(onClick = onSelectFile, modifier = Modifier.fillMaxWidth()) {
                    Text("APK / APK-set / файл")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Root / процесс",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    rootProbe?.message
                        ?: "Root ещё не проверен.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = onCheckRoot,
                    enabled = !rootChecking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (rootChecking) {
                            "Проверяется root…"
                        } else {
                            "Проверить root"
                        },
                    )
                }
                Button(
                    onClick = onOpenRootProcessLab,
                    enabled =
                        rootProbe?.available == true &&
                            !rootChecking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Подключиться к процессу"
                    )
                }
                Text(
                    "После подключения можно выбрать запущенную игру/приложение, снять runtime dump, искать и уточнять значения, pointer chain, писать значения и freeze.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Как работает основной режим", fontWeight = FontWeight.SemiBold)
                Text("1  Быстрый анализ и runtime-профиль")
                Text("2  Понятные выводы и ранние результаты")
                Text("3  Дополнительное подтверждение только спорных целей")
                Text("4  Runtime/root только если статики недостаточно")
                Text("5  Подготовка изменений → сборка → проверенный APK")
            }
        }

        OutlinedButton(onClick = onOpenExpertLab, modifier = Modifier.fillMaxWidth()) {
            Text("Expert Lab — запуск отдельных инструментов")
        }
    }
}
