package io.github.ffenuss.modkit.build

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.patch.MutationDiff
import io.github.ffenuss.modkit.patch.MutationKind
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class MutationDiffVerification(
    val verified: Boolean,
    val blockers: List<String>,
)

/**
 * Re-checks mutation diff contents on unsigned or signed APK/APK-set outputs.
 * Signature entries are ignored; only the target entry bytes are validated.
 */
object MutationDiffVerifier {
    private const val BUFFER_BYTES = 128 * 1024

    fun verify(
        files: List<File>,
        diffs: List<MutationDiff>,
        cancellation: CancellationSignal,
    ): MutationDiffVerification {
        val blockers = mutableListOf<String>()
        val byName = files.groupBy(File::getName)

        diffs.forEach { diff ->
            checkCancelled(cancellation)
            val file = byName[diff.container]?.singleOrNull()
            if (file == null) {
                blockers += "Не найден output container для diff: " + diff.container
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
                            "Diff verifier не поддерживает mutation kind " + diff.kind,
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
                    ": output bytes не совпадают с mutation diff."
            }
        }

        return MutationDiffVerification(
            verified = blockers.isEmpty(),
            blockers = blockers.distinct(),
        )
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
            require(read > 0) { "Entry завершился до mutation range." }
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
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = bytes
        while (remaining > 0L) {
            checkCancelled(cancellation)
            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
            require(read > 0) { "Entry завершился до mutation offset." }
            remaining -= read
        }
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
