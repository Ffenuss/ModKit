package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class StagingFileVerification(
    val fileName: String,
    val entryCount: Int,
    val manifestPresent: Boolean,
    val duplicateEntryNames: List<String>,
    val oldV1SignatureEntries: List<String>,
)

data class StagingVerificationResult(
    val verified: Boolean,
    val files: List<StagingFileVerification>,
    val blockers: List<String>,
)

/**
 * Verifies unsigned staging APK/APK-set containers before alignment/signing.
 *
 * Verification is streaming: every entry is read so CRC/decompression errors
 * are surfaced, but large entries are never retained in memory.
 */
object StagingApkVerifier {
    private const val BUFFER_BYTES = 128 * 1024
    private const val HEARTBEAT_MS = 1_500L

    fun verify(
        apply: MutationApplyResult,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): StagingVerificationResult {
        val blockers = mutableListOf<String>()
        val reports = mutableListOf<StagingFileVerification>()
        val byName = apply.outputFiles.groupBy(File::getName)

        byName.filterValues { it.size != 1 }.keys.forEach { name ->
            blockers += "Неоднозначный staging APK/APK-set файл: " + name
        }

        apply.outputFiles.forEachIndexed { fileIndex, file ->
            checkCancelled(cancellation)
            if (!file.isFile || !file.canRead()) {
                blockers += "Staging файл недоступен: " + file.name
                return@forEachIndexed
            }

            val report = runCatching {
                verifyContainer(
                    file = file,
                    cancellation = cancellation,
                    progress = progress,
                    fileIndex = fileIndex,
                    fileCount = apply.outputFiles.size,
                )
            }.getOrElse { failure ->
                if (failure is AnalysisCancelledException) throw failure
                blockers += file.name + ": " +
                    (failure.message ?: failure.javaClass.simpleName)
                return@forEachIndexed
            }
            reports += report

            if (!report.manifestPresent) {
                blockers += file.name + ": AndroidManifest.xml отсутствует."
            }
            if (report.duplicateEntryNames.isNotEmpty()) {
                blockers += file.name + ": ZIP содержит повторяющиеся entry names."
            }
            if (report.oldV1SignatureEntries.isNotEmpty()) {
                blockers += file.name + ": старая v1-подпись не удалена."
            }
        }

        apply.diffs.forEach { diff ->
            checkCancelled(cancellation)
            val file = byName[diff.container]?.singleOrNull()
            if (file == null) {
                blockers += "Не найден staging container для diff: " + diff.container
                return@forEach
            }

            val actual = runCatching {
                ZipFile(file).use { zip ->
                    val entry = zip.getEntry(diff.entryPath)
                        ?: error("Изменённый entry отсутствует: " + diff.entryPath)
                    when (diff.kind) {
                        MutationKind.NATIVE_IN_PLACE_BYTES ->
                            zip.getInputStream(entry).use { input ->
                                hashRange(
                                    input = input,
                                    offset = requireNotNull(diff.fileOffset),
                                    length = diff.length,
                                    cancellation = cancellation,
                                )
                            }

                        MutationKind.FILE_REPLACE,
                        MutationKind.RESOURCE_REPLACE ->
                            zip.getInputStream(entry).use { input ->
                                hashStream(input, cancellation)
                            }

                        else -> error(
                            "Staging verifier не поддерживает mutation kind " +
                                diff.kind,
                        )
                    }
                }
            }.getOrElse { failure ->
                if (failure is AnalysisCancelledException) throw failure
                blockers += diff.container + ":" + diff.entryPath + ": " +
                    (failure.message ?: failure.javaClass.simpleName)
                null
            }

            if (
                actual != null &&
                !actual.equals(diff.afterSha256, ignoreCase = true)
            ) {
                blockers += diff.container + ":" + diff.entryPath +
                    ": staged bytes не совпадают с mutation diff."
            }
        }

        return StagingVerificationResult(
            verified = blockers.isEmpty() &&
                reports.size == apply.outputFiles.size &&
                apply.outputFiles.isNotEmpty(),
            files = reports,
            blockers = blockers.distinct(),
        )
    }

    private fun verifyContainer(
        file: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        fileIndex: Int,
        fileCount: Int,
    ): StagingFileVerification {
        ZipFile(file).use { zip ->
            val seen = linkedSetOf<String>()
            val duplicates = linkedSetOf<String>()
            val signatures = mutableListOf<String>()
            var entriesRead = 0
            var manifest = false
            var lastHeartbeat = 0L

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                checkCancelled(cancellation)
                val entry = entries.nextElement()
                if (!seen.add(entry.name)) duplicates += entry.name
                if (entry.name == "AndroidManifest.xml") manifest = true
                if (isV1SignatureEntry(entry.name)) signatures += entry.name

                if (!entry.isDirectory) {
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            checkCancelled(cancellation)
                            val read = input.read(buffer)
                            if (read < 0) break
                        }
                    }
                }
                entriesRead++

                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    lastHeartbeat = now
                    progress.publish(
                        EngineProgress(
                            engineId = "build.staging-verify",
                            scheduleClass = EngineScheduleClass.CONFIRMATION,
                            state = RunState.RUNNING,
                            currentTask = "Проверка staging APK",
                            currentArtifact = file.name + " · " +
                                (fileIndex + 1) + "/" + fileCount +
                                " · " + entry.name,
                            processed = entriesRead.toLong(),
                            total = null,
                            lastHeartbeatEpochMs = now,
                        ),
                    )
                }
            }

            return StagingFileVerification(
                fileName = file.name,
                entryCount = entriesRead,
                manifestPresent = manifest,
                duplicateEntryNames = duplicates.toList().sorted(),
                oldV1SignatureEntries = signatures.distinct().sorted(),
            )
        }
    }

    private fun hashRange(
        input: InputStream,
        offset: Long,
        length: Long,
        cancellation: CancellationSignal,
    ): String {
        discardExactly(input, offset, cancellation)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = length
        while (remaining > 0L) {
            checkCancelled(cancellation)
            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
            require(read > 0) {
                "Staging entry завершился до mutation range."
            }
            digest.update(buffer, 0, read)
            remaining -= read
        }
        return digest.digest().toHex()
    }

    private fun hashStream(
        input: InputStream,
        cancellation: CancellationSignal,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            checkCancelled(cancellation)
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun discardExactly(
        input: InputStream,
        bytes: Long,
        cancellation: CancellationSignal,
    ) {
        var remaining = bytes
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0L) {
            checkCancelled(cancellation)
            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
            require(read > 0) {
                "Staging entry завершился до mutation offset."
            }
            remaining -= read
        }
    }

    private fun isV1SignatureEntry(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        val leaf = upper.substringAfterLast('/')
        return leaf == "MANIFEST.MF" ||
            leaf.endsWith(".SF") ||
            leaf.endsWith(".RSA") ||
            leaf.endsWith(".DSA") ||
            leaf.endsWith(".EC")
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
