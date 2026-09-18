package io.github.ffenuss.modkit.analysis

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class MaterializedTarget(
    val file: File,
    val sha256: String,
)

object TargetMaterializer {
    private const val HEARTBEAT_INTERVAL_MS = 1_500L

    fun fromUri(
        context: Context,
        uri: Uri,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): MaterializedTarget {
        val directory = File(context.cacheDir, "analysis-input").apply { mkdirs() }
        var displayName: String? = null
        var expectedSize: Long? = null
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0) displayName = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        expectedSize = cursor.getLong(sizeColumn).takeIf { it >= 0L }
                    }
                }
            }
        }

        val safeName = displayName
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(96)
            ?.ifBlank { null }
            ?: "target.bin"

        val output = File(directory, "${System.nanoTime()}-$safeName")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var lastHeartbeat = 0L

        fun publish(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastHeartbeat < HEARTBEAT_INTERVAL_MS) return
            lastHeartbeat = now
            progress.publish(
                EngineProgress(
                    engineId = "target.materialize",
                    scheduleClass = EngineScheduleClass.FAST,
                    state = RunState.RUNNING,
                    currentTask = "Копирование выбранного файла",
                    currentArtifact = safeName,
                    processed = copied,
                    total = expectedSize,
                    lastHeartbeatEpochMs = now,
                ),
            )
        }

        try {
            publish(force = true)
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Не удалось открыть выбранный файл" }
                FileOutputStream(output).buffered(128 * 1024).use { sink ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (cancellation.isCancelled()) throw AnalysisCancelledException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                        publish()
                    }
                    sink.flush()
                }
            }
            publish(force = true)
            return MaterializedTarget(
                file = output,
                sha256 = digest.digest().toHex(),
            )
        } catch (t: Throwable) {
            output.delete()
            throw t
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
