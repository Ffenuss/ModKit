package io.github.ffenuss.modkit.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.ffenuss.modkit.data.InstalledAppTarget

enum class InstalledTargetKind {
    GAMES,
    APPLICATIONS,
}

@Composable
fun InstalledAppsScreen(
    apps: List<InstalledAppTarget>,
    loading: Boolean,
    error: String?,
    kind: InstalledTargetKind,
    dumpingPackageName: String?,
    dumpNotice: String?,
    onBack: () -> Unit,
    onKindChanged: (InstalledTargetKind) -> Unit,
    onSelect: (InstalledAppTarget) -> Unit,
    onDump: (InstalledAppTarget) -> Unit,
) {
    var query by remember(apps) {
        mutableStateOf("")
    }
    var showSystem by remember(apps) {
        mutableStateOf(false)
    }

    val normalizedQuery = query.trim().lowercase()
    val filtered =
        remember(
            apps,
            normalizedQuery,
            kind,
            showSystem,
        ) {
            apps.filter { app ->
                val queryMatches =
                    normalizedQuery.isBlank() ||
                        app.label.lowercase()
                            .contains(normalizedQuery) ||
                        app.packageName.lowercase()
                            .contains(normalizedQuery)
                val kindMatches =
                    when (kind) {
                        InstalledTargetKind.GAMES ->
                            app.isGame
                        InstalledTargetKind.APPLICATIONS ->
                            !app.isGame
                    }
                val systemMatches =
                    showSystem || !app.isSystemApp
                queryMatches &&
                    kindMatches &&
                    systemMatches
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(20.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onBack) {
            Text("← Назад")
        }

        Text(
            if (kind == InstalledTargetKind.GAMES) {
                "Выбор игры"
            } else {
                "Выбор приложения"
            },
            style =
                MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        when {
            loading ->
                CircularProgressIndicator()

            error != null ->
                Text(
                    "Ошибка: " + error,
                    color =
                        MaterialTheme.colorScheme.error,
                )

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected =
                            kind ==
                                InstalledTargetKind.GAMES,
                        onClick = {
                            onKindChanged(
                                InstalledTargetKind.GAMES,
                            )
                        },
                        label = { Text("Игры") },
                    )
                    FilterChip(
                        selected =
                            kind ==
                                InstalledTargetKind
                                    .APPLICATIONS,
                        onClick = {
                            onKindChanged(
                                InstalledTargetKind
                                    .APPLICATIONS,
                            )
                        },
                        label = {
                            Text("Приложения")
                        },
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = {
                        Text(
                            "Поиск по названию или пакету",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                FilterChip(
                    selected = showSystem,
                    onClick = {
                        showSystem = !showSystem
                    },
                    label = {
                        Text(
                            if (showSystem) {
                                "Системные показаны"
                            } else {
                                "Показать системные"
                            },
                        )
                    },
                )

                Text(
                    "Найдено: " +
                        filtered.size +
                        ". Нажмите «Анализ» или " +
                        "«Дамп APK».",
                    style =
                        MaterialTheme.typography.bodySmall,
                )

                dumpNotice?.let { notice ->
                    Card(
                        modifier =
                            Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            notice,
                            modifier =
                                Modifier.padding(12.dp),
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        filtered,
                        key = { it.packageName },
                    ) { app ->
                        Card(
                            modifier =
                                Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier =
                                    Modifier.padding(14.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(
                                        10.dp,
                                    ),
                            ) {
                                Row(
                                    verticalAlignment =
                                        Alignment
                                            .CenterVertically,
                                    horizontalArrangement =
                                        Arrangement.spacedBy(
                                            12.dp,
                                        ),
                                ) {
                                    InstalledAppIcon(
                                        packageName =
                                            app.packageName,
                                        label = app.label,
                                    )
                                    Column(
                                        modifier =
                                            Modifier.weight(1f),
                                        verticalArrangement =
                                            Arrangement
                                                .spacedBy(3.dp),
                                    ) {
                                        Text(
                                            app.label,
                                            fontWeight =
                                                FontWeight
                                                    .SemiBold,
                                        )
                                        Text(
                                            if (app.isGame) {
                                                "Игра"
                                            } else {
                                                "Приложение"
                                            },
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .labelMedium,
                                        )
                                        Text(
                                            app.packageName,
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                        Text(
                                            "v" +
                                                (
                                                    app.versionName
                                                        ?: "?"
                                                ) +
                                                " · APK: " +
                                                app.apkFiles.size +
                                                if (
                                                    app.isSystemApp
                                                ) {
                                                    " · system"
                                                } else {
                                                    ""
                                                },
                                            style =
                                                MaterialTheme
                                                    .typography
                                                    .bodySmall,
                                        )
                                    }
                                }

                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(
                                            8.dp,
                                        ),
                                ) {
                                    Button(
                                        onClick = {
                                            onSelect(app)
                                        },
                                        enabled =
                                            dumpingPackageName ==
                                                null,
                                        modifier =
                                            Modifier.weight(1f),
                                    ) {
                                        Text("Анализ")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onDump(app)
                                        },
                                        enabled =
                                            dumpingPackageName ==
                                                null,
                                        modifier =
                                            Modifier.weight(1f),
                                    ) {
                                        Text(
                                            if (
                                                dumpingPackageName ==
                                                    app.packageName
                                            ) {
                                                "Дамп…"
                                            } else {
                                                "Дамп APK"
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                if (
                                    kind ==
                                    InstalledTargetKind.GAMES
                                ) {
                                    "Игры не найдены. " +
                                        "Попробуйте включить " +
                                        "системные пакеты или " +
                                        "изменить поиск."
                                } else {
                                    "Приложения не найдены. " +
                                        "Попробуйте включить " +
                                        "системные пакеты или " +
                                        "изменить поиск."
                                },
                                style =
                                    MaterialTheme.typography
                                        .bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppIcon(
    packageName: String,
    label: String,
) {
    val context = LocalContext.current
    val bitmap =
        remember(packageName) {
            runCatching {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(
                        width = 96,
                        height = 96,
                    )
                    .asImageBitmap()
            }.getOrNull()
        }

    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap),
            contentDescription = label,
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(
                        RoundedCornerShape(12.dp),
                    ),
        )
    } else {
        Box(
            modifier =
                Modifier
                    .size(52.dp)
                    .clip(
                        RoundedCornerShape(12.dp),
                    )
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label
                    .trim()
                    .take(1)
                    .uppercase(),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
