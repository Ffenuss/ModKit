package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Verify the bytes actually sent to a session, including changes after the initial file check. */
object VerifiedApkTransfer {
    fun copy(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        expectedSha256: String,
        cancellation: CancellationSignal,
        progress: (Long) -> Unit = {},
    ): Long {
        require(expectedSize > 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var total = 0L
        progress(0)
        while (true) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total = Math.addExact(total, read.toLong())
            require(total <= expectedSize) { "APK вырос во время передачи установщику." }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            progress(total)
        }
        require(total == expectedSize) { "APK обрезан во время передачи установщику." }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual.equals(expectedSha256, ignoreCase = true)) {
            "APK изменился во время передачи установщику. Установка отменена."
        }
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
        return total
    }
}
