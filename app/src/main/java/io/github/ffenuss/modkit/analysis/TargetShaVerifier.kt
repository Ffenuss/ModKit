package io.github.ffenuss.modkit.analysis

import android.content.Context
import android.net.Uri
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

data class TargetShaVerification(
    val expectedSha256: String,
    val actualSha256: String?,
    val matches: Boolean,
    val sources: List<ArtifactSource>,
    val blockerCode: String?,
    val blockerMessage: String?,
)

/**
 * Revalidates the exact source target before Patch/Build preparation.
 *
 * This never trusts package name, URI label or a prior RVA. The same canonical
 * ArtifactIdentity used by FAST analysis is recomputed from current bytes.
 */
object TargetShaVerifier {
    private const val HEARTBEAT_MS = 1_500L

    fun verify(
        context: Context,
        target: AnalysisTargetDescriptor,
        expectedSha256: String,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): TargetShaVerification {
        val expected = expectedSha256.lowercase()
        return runCatching {
            val sources = when (target) {
                is AnalysisTargetDescriptor.FileUri -> listOf(
                    hashUri(
                        context = context,
                        uri = Uri.parse(target.uri),
                        displayName = target.label,
                        cancellation = cancellation,
                        progress = progress,
                    ),
                )
                is AnalysisTargetDescriptor.InstalledPackage -> {
                    val installed = InstalledAppRepository(context)
                        .find(target.packageName)
                        ?: return TargetShaVerification(
                            expectedSha256 = expected,
                            actualSha256 = null,
                            matches = false,
                            sources = emptyList(),
                            blockerCode = "SOURCE_PACKAGE_UNAVAILABLE",
                            blockerMessage = "Исходное установленное приложение больше недоступно.",
                        )
                    installed.apkFiles.mapIndexed { index, file ->
                        hashFile(
                            file = file,
                            cancellation = cancellation,
                            progress = progress,
                            index = index,
                            totalFiles = installed.apkFiles.size,
                        )
                    }
                }
            }
            val actual = ArtifactIdentity.combine(sources)
            TargetShaVerification(
                expectedSha256 = expected,
                actualSha256 = actual,
                matches = actual == expected,
                sources = sources,
                blockerCode = if (actual == expected) null else "TARGET_SHA_MISMATCH",
                blockerMessage = if (actual == expected) {
                    null
                } else {
                    "Текущие байты цели отличаются от проанализированной версии. Старые executable binding и RVA использовать нельзя."
                },
            )
        }.getOrElse { failure ->
            if (failure is AnalysisCancelledException) throw failure
            TargetShaVerification(
                expectedSha256 = expected,
                actualSha256 = null,
                matches = false,
                sources = emptyList(),
                blockerCode = "SOURCE_SHA_VERIFY_FAILED",
                blockerMessage = failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    private fun hashFile(
        file: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        index: Int,
        totalFiles: Int,
    ): ArtifactSource {
        require(file.isFile && file.canRead()) { "Source APK is not readable: ${file.absolutePath}" }
        FileInputStream(file).use { input ->
            val sha = hashStream(
                input = input,
                artifact = file.name,
                expectedBytes = file.length(),
                cancellation = cancellation,
                progress = progress,
                fileIndex = index,
                totalFiles = totalFiles,
            )
            return ArtifactSource(file.name, file.length(), sha)
        }
    }

    private fun hashUri(
        context: Context,
        uri: Uri,
        displayName: String,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): ArtifactSource {
        var bytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        var lastHeartbeat = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось повторно открыть выбранный файл" }
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (cancellation.isCancelled()) throw AnalysisCancelledException()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                bytes += read
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    lastHeartbeat = now
                    progress.publish(
                        EngineProgress(
                            engineId = "prepare.sha-verify",
                            scheduleClass = EngineScheduleClass.CONFIRMATION,
                            state = RunState.RUNNING,
                            currentTask = "Проверка SHA исходной цели",
                            currentArtifact = displayName,
                            processed = bytes,
                            total = null,
                            lastHeartbeatEpochMs = now,
                        ),
                    )
                }
            }
        }
        return ArtifactSource(
            displayName = displayName,
            size = bytes,
            sha256 = digest.digest().toHex(),
        )
    }

    private fun hashStream(
        input: InputStream,
        artifact: String,
        expectedBytes: Long,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        fileIndex: Int,
        totalFiles: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var processed = 0L
        var lastHeartbeat = 0L
        while (true) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            processed += read
            val now = System.currentTimeMillis()
            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = now
                progress.publish(
                    EngineProgress(
                        engineId = "prepare.sha-verify",
                        scheduleClass = EngineScheduleClass.CONFIRMATION,
                        state = RunState.RUNNING,
                        currentTask = "Проверка SHA исходной цели",
                        currentArtifact = "$artifact · ${fileIndex + 1}/$totalFiles",
                        processed = processed,
                        total = expectedBytes,
                        lastHeartbeatEpochMs = now,
                    ),
                )
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
