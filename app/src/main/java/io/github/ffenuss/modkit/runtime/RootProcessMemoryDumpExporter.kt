package io.github.ffenuss.modkit.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.ffenuss.modkit.BuildConfig
import java.io.File

object RootProcessMemoryDumpExporter {
    fun share(
        context: Context,
        dump: File,
    ) {
        require(
            dump.isFile &&
                dump.extension.equals(
                    "zip",
                    ignoreCase = true,
                ),
        ) {
            "Runtime dump недоступен."
        }
        val uri =
            FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID +
                    ".files",
                dump,
            )
        val intent =
            Intent(
                Intent.ACTION_SEND,
            ).apply {
                type =
                    "application/zip"
                putExtra(
                    Intent.EXTRA_STREAM,
                    uri,
                )
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "ModKit root process dump",
                )
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        context.startActivity(
            Intent.createChooser(
                intent,
                "Экспорт runtime dump",
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
    }
}
