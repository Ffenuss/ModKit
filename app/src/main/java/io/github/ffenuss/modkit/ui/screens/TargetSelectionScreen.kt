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
            "Выберите, что нужно открыть. ModKit работает без root: " +
                "можно анализировать установленную игру, обычное приложение " +
                "или APK-файл.",
            style = MaterialTheme.typography.bodyMedium,
        )

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
                        "Для любого выбранного пакета доступен анализ и " +
                        "дамп установочного набора APK на устройство.",
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

        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement =
                    Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Дампер приложений",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Дамп сохраняет base APK и все split APK, SHA-256 " +
                        "каждого файла и индекс содержимого APK. " +
                        "На Android 10+ архив появляется в Downloads/ModKit.",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
