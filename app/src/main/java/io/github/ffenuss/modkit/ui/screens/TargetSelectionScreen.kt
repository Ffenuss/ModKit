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

@Composable
fun TargetSelectionScreen(
    onSelectGames: () -> Unit,
    onSelectApps: () -> Unit,
    onSelectFile: () -> Unit,
    onOpenRootProcessLab: () -> Unit,
    onOpenAniimoQuickStart: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(20.dp),
        verticalArrangement =
            Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "ModKit",
            style =
                MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("v" + BuildConfig.VERSION_NAME)
        Text(
            "Без root: анализ и repack. С root: запуск оригинального приложения, " +
                "подключение к его процессу и MK overlay без переподписи исходного APK.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "Aniimo Quick Start",
                    style =
                        MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Открывает Root Process Lab уже отфильтрованным по Aniimo. " +
                        "Нажмите установленную Aniimo: ModKit запустит игру, дождётся " +
                        "основного PID и поднимет плавающий MK overlay.",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onOpenAniimoQuickStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Открыть Aniimo Loader")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Root / Live",
                    style =
                        MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Список процессов и установленных приложений, live value scan, " +
                        "изменение и Freeze подтверждённых локальных значений, " +
                        "поведенческое обучение и runtime-профили.",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onOpenRootProcessLab,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Root Process Lab")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Установленные пакеты",
                    style =
                        MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Игры и обычные приложения показываются отдельно. " +
                        "Для выбранного пакета доступен анализ и дамп APK-набора.",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onSelectGames,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Выбрать игру")
                }
                Button(
                    onClick = onSelectApps,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Выбрать приложение")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "APK / файл",
                    style =
                        MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Открывает APK, APK-set, ZIP или бинарный файл " +
                        "для статического анализа.",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = onSelectFile,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Выбрать APK / файл")
                }
            }
        }
    }
}
