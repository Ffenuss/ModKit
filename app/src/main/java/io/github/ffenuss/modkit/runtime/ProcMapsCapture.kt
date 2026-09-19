package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

enum class ProcMapsCaptureSource {
    IMPORTED_SNAPSHOT,
    CURRENT_PROCESS,
    NON_ROOT_PROCESS,
    REPACKED_TEST_RUNTIME,
}

data class ProcMapsCapture(
    val source: ProcMapsCaptureSource,
    val pid: Int?,
    val capturedAtEpochMs: Long,
    val text: String,
    val sha256: String,
    val truncated: Boolean,
)

/**
 * Bounded /proc maps capture. A truncated capture is retained for diagnostics
 * but must never be used as exact runtime evidence.
 */
object ProcMapsCaptureReader {
    const val DEFAULT_MAX_BYTES = 8 * 1024 * 1024

    fun imported(
        text: String,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): ProcMapsCapture =
        fromBytes(
            source = ProcMapsCaptureSource.IMPORTED_SNAPSHOT,
            pid = null,
            bytes = text.toByteArray(Charsets.UTF_8),
            maxBytes = maxBytes,
        )

    fun currentProcess(
        cancellation: CancellationSignal,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): ProcMapsCapture =
        readFile(
            file = File("/proc/self/maps"),
            source = ProcMapsCaptureSource.CURRENT_PROCESS,
            pid = null,
            cancellation = cancellation,
            maxBytes = maxBytes,
        )

    fun process(
        pid: Int,
        cancellation: CancellationSignal,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): ProcMapsCapture {
        require(pid > 0) { "PID must be positive." }
        return readFile(
            file = File("/proc/$pid/maps"),
            source = ProcMapsCaptureSource.NON_ROOT_PROCESS,
            pid = pid,
            cancellation = cancellation,
            maxBytes = maxBytes,
        )
    }

    private fun readFile(
        file: File,
        source: ProcMapsCaptureSource,
        pid: Int?,
        cancellation: CancellationSignal,
        maxBytes: Int,
    ): ProcMapsCapture {
        require(maxBytes in 1..64 * 1024 * 1024) {
            "Invalid proc maps capture limit."
        }
        require(file.isFile && file.canRead()) {
            "Process maps are not readable: " + file.absolutePath
        }

        FileInputStream(file).use { input ->
            val bytes = readBounded(
                input = input,
                maxBytes = maxBytes,
                cancellation = cancellation,
            )
            return buildCapture(
                source = source,
                pid = pid,
                bytes = bytes.first,
                truncated = bytes.second,
            )
        }
    }

    private fun fromBytes(
        source: ProcMapsCaptureSource,
        pid: Int?,
        bytes: ByteArray,
        maxBytes: Int,
    ): ProcMapsCapture {
        require(maxBytes > 0) { "Capture limit must be positive." }
        val truncated = bytes.size > maxBytes
        val retained = if (truncated) bytes.copyOf(maxBytes) else bytes
        return buildCapture(
            source = source,
            pid = pid,
            bytes = retained,
            truncated = truncated,
        )
    }

    private fun buildCapture(
        source: ProcMapsCaptureSource,
        pid: Int?,
        bytes: ByteArray,
        truncated: Boolean,
    ): ProcMapsCapture =
        ProcMapsCapture(
            source = source,
            pid = pid,
            capturedAtEpochMs = System.currentTimeMillis(),
            text = bytes.toString(Charsets.UTF_8),
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            truncated = truncated,
        )

    private fun readBounded(
        input: InputStream,
        maxBytes: Int,
        cancellation: CancellationSignal,
    ): Pair<ByteArray, Boolean> {
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        var truncated = false
        while (true) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            val read = input.read(buffer)
            if (read < 0) break

            val remaining = maxBytes - total
            if (remaining <= 0) {
                truncated = true
                break
            }
            val keep = minOf(read, remaining)
            out.write(buffer, 0, keep)
            total += keep
            if (keep < read) {
                truncated = true
                break
            }
        }
        return out.toByteArray() to truncated
    }
}
