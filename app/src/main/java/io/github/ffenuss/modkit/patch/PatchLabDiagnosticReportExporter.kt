package io.github.ffenuss.modkit.patch

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.ffenuss.modkit.BuildConfig
import java.io.File

object PatchLabDiagnosticReportExporter {
    fun share(
        context: Context,
        report: File,
    ) {
        require(
            report.isFile &&
                report.extension.equals(
                    "zip",
                    ignoreCase = true,
                ),
        ) {
            "Patch Lab diagnostic report is unavailable."
        }

        val uri =
            FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID +
                    ".files",
                report,
            )
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(
                    Intent.EXTRA_STREAM,
                    uri,
                )
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "ModKit Patch Lab diagnostic report",
                )
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        context.startActivity(
            Intent.createChooser(
                intent,
                "Экспорт отчёта Patch Lab",
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
    }
}
