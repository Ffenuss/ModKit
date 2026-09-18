package io.github.ffenuss.modkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.ffenuss.modkit.ui.screens.ExpertLabScreen
import io.github.ffenuss.modkit.ui.screens.TargetSelectionScreen

private enum class Screen { TARGET, EXPERT_LAB }

@Composable
fun ModKitApp() {
    var screen by remember { mutableStateOf(Screen.TARGET) }
    when (screen) {
        Screen.TARGET -> TargetSelectionScreen(onOpenExpertLab = { screen = Screen.EXPERT_LAB })
        Screen.EXPERT_LAB -> ExpertLabScreen(onBack = { screen = Screen.TARGET })
    }
}
