package io.github.ffenuss.modkit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.patch.PatchLabDiagnosticReportExporter
import io.github.ffenuss.modkit.patch.PatchLabDiagnosticReportWriter
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Export completed stages directly; opening a report must not restart failed analysis. */
@Composable
fun AnalysisReportExportButton(title: String, result: FastAnalysisResult) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        error = null
        scope.launch {
            val exportContext = coroutineContext
            try {
                val report = withContext(Dispatchers.IO) {
                    PatchLabDiagnosticReportWriter.write(
                        File(context.filesDir, "expert-lab-export"), title, result, null,
                        cancellation = object : CancellationSignal {
                            override fun isCancelled(): Boolean = !exportContext.isActive
                        },
                    )
                }
                PatchLabDiagnosticReportExporter.share(context, report)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Не удалось подготовить отчёт."
            } finally { busy = false }
        }
    }) { Text(if (busy) "Подготавливаем отчёт…" else "Отправить отчёт анализа") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
