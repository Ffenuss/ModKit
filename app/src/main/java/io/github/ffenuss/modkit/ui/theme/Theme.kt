package io.github.ffenuss.modkit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF315B4D), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EADF), onPrimaryContainer = Color(0xFF112B23),
    secondary = Color(0xFF516477), secondaryContainer = Color(0xFFDCE7F3),
    background = Color(0xFFF6F7F2), surface = Color(0xFFF6F7F2),
    surfaceContainer = Color(0xFFEDF0E9), onSurface = Color(0xFF1B211E),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA7D3BE), onPrimary = Color(0xFF12382A),
    primaryContainer = Color(0xFF284A3C), onPrimaryContainer = Color(0xFFD6EADF),
    secondary = Color(0xFFB4C8DE), secondaryContainer = Color(0xFF34485C),
    background = Color(0xFF111713), surface = Color(0xFF111713), surfaceContainer = Color(0xFF1D251F),
)

@Composable
fun ModKitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp)),
        content = content)
}
