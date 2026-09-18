package io.github.ffenuss.modkit.analysis

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object TargetMaterializer {
    fun fromUri(
        context: Context,
        uri: Uri,
        cancellation: CancellationSignal,
    ): File {
        val directory = File(context.cacheDir, "analysis-input").apply { mkdirs() }
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "target.bin"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "target.bin" }
        val output = File(directory, "${System.nanoTime()}-$safeName")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть выбранный файл" }
                FileOutputStream(output).buffered(128 * 1024).use { sink ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (cancellation.isCancelled()) throw AnalysisCancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                    sink.flush()
                }
            }
            return output
        } catch (t: Throwable) {
            output.delete()
            throw t
        }
    }
}
