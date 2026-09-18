package io.github.ffenuss.modkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.ffenuss.modkit.ui.ModKitApp
import io.github.ffenuss.modkit.ui.theme.ModKitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ModKitTheme {
                ModKitApp()
            }
        }
    }
}
