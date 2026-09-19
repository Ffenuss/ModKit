package io.github.ffenuss.modkit.build

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.ffenuss.modkit.BuildConfig
import java.util.ArrayList

object BuildArtifactExporter {
    fun share(
        context: Context,
        result: VerifiedBuildResult,
    ) {
        require(result.files.isNotEmpty()) { "No built APK files are available for export." }
        val authority = BuildConfig.APPLICATION_ID + ".files"
        val uris = result.files.map { built ->
            FileProvider.getUriForFile(
                context,
                authority,
                built.file,
            )
        }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uris.single())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/vnd.android.package-archive"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList<Uri>(uris),
                )
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "ModKit verified APK output",
            )
        }

        context.startActivity(
            Intent.createChooser(intent, "Экспорт готового APK")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
