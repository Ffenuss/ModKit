package io.github.ffenuss.modkit.analysis

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.ffenuss.modkit.BuildConfig
import java.io.File

object ExpertLabReportExporter {
    fun share(
        context: Context,
        report: File,
    ) {
        require(report.isFile) { "Expert Lab report is unavailable for export." }
        val uri = FileProvider.getUriForFile(
            context,
            BuildConfig.APPLICATION_ID + ".files",
            report,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ModKit Expert Lab technical report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Экспорт Expert Lab отчёта")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
