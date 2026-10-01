package io.github.ffenuss.modkit.sandbox

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.runtime.AndroidRootCommandRunner
import io.github.ffenuss.modkit.runtime.RootCommandRunner
import io.github.ffenuss.modkit.runtime.RootProcMemRuntimeMemoryReader
import io.github.ffenuss.modkit.runtime.RootRuntimeCaptureCoordinator

data class SandboxLiveToggleTarget(
    val id: String,
    val title: String,
    val runtimeAddress: Long,
    val originalBytes: ByteArray,
    val replacementBytes: ByteArray,
)

/**
 * Fast fail-closed live toggle used by the sandbox overlay.
 *
 * The exact sandbox PID is revalidated on every toggle. Only bytes that match
 * one of the two known states (original/replacement) are ever changed.
 */
object RootSandboxLiveToggleCoordinator {
    private const val MAX_PATCH_BYTES = 64

    fun setEnabled(
        packageName: String,
        pid: Int,
        target: SandboxLiveToggleTarget,
        enabled: Boolean,
        cancellation: CancellationSignal,
        runner: RootCommandRunner = AndroidRootCommandRunner(),
    ) {
        require(pid > 0) { "Sandbox PID must be positive." }
        require(
            target.originalBytes.isNotEmpty() &&
                target.originalBytes.size <= MAX_PATCH_BYTES &&
                target.originalBytes.size == target.replacementBytes.size,
        ) {
            "Invalid live-toggle byte range."
        }

        val capture =
            RootRuntimeCaptureCoordinator.captureMaps(
                packageName = packageName,
                cancellation = cancellation,
                runner = runner,
                expectedPid = pid,
            )
        require(capture.pid == pid) {
            "Sandbox process identity changed."
        }

        requireProcessNotStopped(
            pid = pid,
            runner = runner,
            cancellation = cancellation,
        )
        stopProcess(
            pid = pid,
            runner = runner,
            cancellation = cancellation,
        )
        try {
            val reader =
                RootProcMemRuntimeMemoryReader(
                    pid = pid,
                    runner = runner,
                )
            val current =
                reader.read(
                    address = target.runtimeAddress,
                    size = target.originalBytes.size,
                    cancellation = cancellation,
                ) ?: error(
                    "Live target is no longer readable: " +
                        target.title,
                )
            val desired =
                if (enabled) {
                    target.replacementBytes
                } else {
                    target.originalBytes
                }
            val opposite =
                if (enabled) {
                    target.originalBytes
                } else {
                    target.replacementBytes
                }

            if (current.contentEquals(desired)) {
                return
            }
            require(current.contentEquals(opposite)) {
                "Runtime bytes changed outside ModKit for " +
                    target.title +
                    "; live toggle refused."
            }

            writeBytes(
                pid = pid,
                address = target.runtimeAddress,
                bytes = desired,
                runner = runner,
                cancellation = cancellation,
            )
            val after =
                reader.read(
                    address = target.runtimeAddress,
                    size = desired.size,
                    cancellation = cancellation,
                ) ?: error(
                    "Live toggle write succeeded but read-back failed.",
                )
            require(after.contentEquals(desired)) {
                runCatching {
                    writeBytes(
                        pid = pid,
                        address = target.runtimeAddress,
                        bytes = opposite,
                        runner = runner,
                        cancellation = NeverCancelled,
                    )
                }
                "Live toggle read-back mismatch; previous bytes were restored."
            }
        } finally {
            resumeProcess(
                pid = pid,
                runner = runner,
            )
        }
    }

    private fun writeBytes(
        pid: Int,
        address: Long,
        bytes: ByteArray,
        runner: RootCommandRunner,
        cancellation: CancellationSignal,
    ) {
        checkCancelled(cancellation)
        val escaped =
            bytes.joinToString(separator = "") { byte ->
                "\\" +
                    (byte.toInt() and 0xff)
                        .toString(8)
                        .padStart(3, '0')
            }
        val command =
            "printf '" +
                escaped +
                "' | dd of=/proc/" +
                pid +
                "/mem bs=1 seek=" +
                address +
                " count=" +
                bytes.size +
                " conv=notrunc status=none 2>/dev/null"
        val result =
            runner.run(
                command = command,
                maxOutputBytes = 512,
                cancellation = cancellation,
            )
        require(
            result.exitCode == 0 &&
                !result.truncated,
        ) {
            "Kernel rejected live sandbox patch write."
        }
    }

    private fun requireProcessNotStopped(
        pid: Int,
        runner: RootCommandRunner,
        cancellation: CancellationSignal,
    ) {
        val state =
            processState(
                pid = pid,
                runner = runner,
                cancellation = cancellation,
            )
        require(state != 'T' && state != 't') {
            "Sandbox process is already stopped by another debugger/job."
        }
    }

    private fun stopProcess(
        pid: Int,
        runner: RootCommandRunner,
        cancellation: CancellationSignal,
    ) {
        val stop =
            runner.run(
                command = "kill -STOP " + pid,
                maxOutputBytes = 512,
                cancellation = cancellation,
            )
        require(stop.exitCode == 0) {
            "Could not pause sandbox process for live toggle."
        }
        repeat(20) {
            checkCancelled(cancellation)
            val state =
                processState(
                    pid = pid,
                    runner = runner,
                    cancellation = cancellation,
                )
            if (state == 'T' || state == 't') {
                return
            }
            Thread.sleep(20L)
        }
        resumeProcess(pid, runner)
        error("Sandbox process did not enter stopped state.")
    }

    private fun resumeProcess(
        pid: Int,
        runner: RootCommandRunner,
    ) {
        runCatching {
            runner.run(
                command = "kill -CONT " + pid,
                maxOutputBytes = 512,
                cancellation = NeverCancelled,
            )
        }
    }

    private fun processState(
        pid: Int,
        runner: RootCommandRunner,
        cancellation: CancellationSignal,
    ): Char? {
        val status =
            runner.run(
                command = "cat /proc/" + pid + "/status",
                maxOutputBytes = 64 * 1024,
                cancellation = cancellation,
            )
        require(status.exitCode == 0 && !status.truncated) {
            "Sandbox process status is unavailable."
        }
        return status.output
            .toString(Charsets.UTF_8)
            .lineSequence()
            .firstOrNull { it.startsWith("State:") }
            ?.substringAfter("State:")
            ?.trim()
            ?.firstOrNull()
    }

    private fun checkCancelled(
        cancellation: CancellationSignal,
    ) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }

    private object NeverCancelled : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
}
