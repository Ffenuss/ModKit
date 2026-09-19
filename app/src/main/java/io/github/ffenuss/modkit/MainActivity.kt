package io.github.ffenuss.modkit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallStatusHandler
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallStatusStore
import io.github.ffenuss.modkit.ui.ModKitApp
import io.github.ffenuss.modkit.ui.theme.ModKitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RepackedRuntimeInstallStatusStore.restore(applicationContext)
        handleInstallStatus(intent)
        setContent {
            ModKitTheme {
                ModKitApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallStatus(intent)
    }

    private fun handleInstallStatus(intent: Intent?) {
        val confirmation = RepackedRuntimeInstallStatusHandler.handle(
            context = applicationContext,
            intent = intent,
        )
        if (confirmation != null) {
            startActivity(confirmation)
        }
    }
}
