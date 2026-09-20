package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class RootCommandResult(
    val exitCode: Int,
    val output: ByteArray,
    val truncated: Boolean,
)

fun interface RootCommandRunner {
    fun run(
        command: String,
        maxOutputBytes: Int,
        cancellation: CancellationSignal,
    ): RootCommandResult
}

/**
 * Explicit root transport for Expert Lab.
 *
 * The caller supplies only internally generated commands. Target package names
 * are validated before they can become command arguments, and PIDs are parsed
 * as positive integers. Output is bounded and commands time out.
 */
class AndroidRootCommandRunner(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : RootCommandRunner {
    override fun run(
        command: String,
        maxOutputBytes: Int,
        cancellation: CancellationSignal,
    ): RootCommandResult {
        require(command.isNotBlank()) {
            "Root command must not be blank."
        }
        require(maxOutputBytes in 1..MAX_COMMAND_OUTPUT_BYTES) {
            "Root command output limit is invalid."
        }

        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (failure: Throwable) {
            throw IOException(
                "Root shell is unavailable: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }

        val executor = Executors.newSingleThreadExecutor()
        val outputFuture = executor.submit<Pair<ByteArray, Boolean>> {
            readBounded(
                input = process.inputStream,
                maxBytes = maxOutputBytes,
                onTruncated = process::destroy,
            )
        }

        try {
            val deadline =
                System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (
                !process.waitFor(
                    POLL_INTERVAL_MS,
                    TimeUnit.MILLISECONDS,
                )
            ) {
                if (cancellation.isCancelled()) {
                    process.destroy()
                    throw AnalysisCancelledException()
                }
                if (System.nanoTime() >= deadline) {
                    process.destroy()
                    throw IOException("Root command timed out.")
                }
            }

            if (cancellation.isCancelled()) {
                throw AnalysisCancelledException()
            }
            val output = try {
                outputFuture.get(
                    OUTPUT_JOIN_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
            } catch (failure: Throwable) {
                process.destroy()
                throw IOException(
                    "Root command output could not be collected.",
                    failure,
                )
            }
            return RootCommandResult(
                exitCode = process.exitValue(),
                output = output.first,
                truncated = output.second,
            )
        } finally {
            process.destroy()
            executor.shutdownNow()
        }
    }

    private fun readBounded(
        input: InputStream,
        maxBytes: Int,
        onTruncated: () -> Unit,
    ): Pair<ByteArray, Boolean> {
        input.use {
            val output =
                ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var retained = 0
            while (true) {
                val read = it.read(buffer)
                if (read < 0) {
                    return output.toByteArray() to false
                }
                val remaining = maxBytes - retained
                if (remaining <= 0) {
                    onTruncated()
                    return output.toByteArray() to true
                }
                val keep = minOf(read, remaining)
                output.write(buffer, 0, keep)
                retained += keep
                if (keep < read) {
                    onTruncated()
                    return output.toByteArray() to true
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 50L
        private const val OUTPUT_JOIN_TIMEOUT_MS = 1_000L
        private const val MAX_COMMAND_OUTPUT_BYTES = 16 * 1024 * 1024
    }
}

data class RootRuntimeCaptureResult(
    val packageName: String,
    val pid: Int,
    val capture: ProcMapsCapture,
)

/**
 * Concrete privileged runtime capture.
 *
 * Root is never invoked automatically. Expert Lab must first make a
 * root-last-resort decision and the user must explicitly start this executor.
 * The selected PID is proven by root-readable cmdline before and after maps
 * capture to fail closed on PID reuse.
 */
object RootRuntimeCaptureCoordinator {
    fun captureMaps(
        packageName: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner = AndroidRootCommandRunner(),
        maxMapsBytes: Int = ProcMapsCaptureReader.DEFAULT_MAX_BYTES,
    ): RootRuntimeCaptureResult {
        require(validPackageName(packageName)) {
            "Installed package name is invalid for root runtime capture."
        }
        checkCancelled(cancellation)

        val uid = runner.run(
            command = "id -u",
            maxOutputBytes = 256,
            cancellation = cancellation,
        )
        require(!uid.truncated) {
            "Root identity probe output was truncated."
        }
        require(
            uid.exitCode == 0 &&
                uid.output.toString(Charsets.UTF_8).trim() == "0",
        ) {
            "Root access was not granted by the device root manager."
        }

        val pidResult = runner.run(
            command = "pidof $packageName",
            maxOutputBytes = 4096,
            cancellation = cancellation,
        )
        require(!pidResult.truncated) {
            "Root process discovery output was truncated."
        }
        require(pidResult.exitCode == 0) {
            "Root process discovery could not find the target package."
        }
        val candidates = pidResult.output
            .toString(Charsets.UTF_8)
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
            .distinct()
            .sorted()
        require(candidates.isNotEmpty()) {
            "Root process discovery returned no valid PID."
        }

        val exactMain = candidates.filter { pid ->
            rootCmdline(
                pid = pid,
                runner = runner,
                cancellation = cancellation,
            ) == packageName
        }
        require(exactMain.size == 1) {
            if (exactMain.isEmpty()) {
                "No exact main process identity was confirmed with root."
            } else {
                "Multiple exact main-process PIDs were confirmed with root."
            }
        }
        val pid = exactMain.single()

        require(
            rootCmdline(
                pid = pid,
                runner = runner,
                cancellation = cancellation,
            ) == packageName,
        ) {
            "Target process identity changed before root maps capture."
        }

        val maps = runner.run(
            command = "cat /proc/$pid/maps",
            maxOutputBytes = maxMapsBytes,
            cancellation = cancellation,
        )
        require(maps.exitCode == 0) {
            "Root process maps capture failed."
        }
        require(!maps.truncated) {
            "Root process maps capture was truncated."
        }
        require(maps.output.isNotEmpty()) {
            "Root process maps capture was empty."
        }

        require(
            rootCmdline(
                pid = pid,
                runner = runner,
                cancellation = cancellation,
            ) == packageName,
        ) {
            "Target process identity changed during root maps capture."
        }

        return RootRuntimeCaptureResult(
            packageName = packageName,
            pid = pid,
            capture = ProcMapsCaptureReader.rootProcess(
                pid = pid,
                bytes = maps.output,
                maxBytes = maxMapsBytes,
            ),
        )
    }

    private fun rootCmdline(
        pid: Int,
        runner: RootCommandRunner,
        cancellation: CancellationSignal,
    ): String? {
        val result = runner.run(
            command = "cat /proc/$pid/cmdline",
            maxOutputBytes = 4096,
            cancellation = cancellation,
        )
        if (
            result.exitCode != 0 ||
            result.truncated ||
            result.output.isEmpty()
        ) {
            return null
        }
        return result.output
            .toString(Charsets.UTF_8)
            .substringBefore('\u0000')
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun validPackageName(
        packageName: String,
    ): Boolean =
        packageName.matches(
            Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"),
        )

    private fun checkCancelled(
        cancellation: CancellationSignal,
    ) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
