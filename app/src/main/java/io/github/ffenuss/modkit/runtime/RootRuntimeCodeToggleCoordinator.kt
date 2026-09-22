package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.sandbox.RootSandboxLiveToggleCoordinator
import io.github.ffenuss.modkit.sandbox.SandboxLiveToggleTarget

data class RootRuntimeCodeToggleTarget(
    val id: String,
    val title: String,
    val runtimeAddress: Long,
    val mappedPath: String?,
    val originalWord: Long,
)

object RootRuntimeCodeToggleCoordinator {
    private val AARCH64_NOP =
        byteArrayOf(
            0x1f,
            0x20,
            0x03,
            0xd5.toByte(),
        )

    fun setNopEnabled(
        packageName: String,
        pid: Int,
        target: RootRuntimeCodeToggleTarget,
        enabled: Boolean,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ) {
        require(pid > 0) {
            "Code-toggle PID must be positive."
        }
        require(
            target.runtimeAddress > 0L &&
                target.runtimeAddress %
                    4L ==
                0L
        ) {
            "ARM64 code-toggle address must be 4-byte aligned."
        }
        require(
            target.originalWord in
                0L..0xffff_ffffL
        ) {
            "ARM64 instruction word is out of range."
        }

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = pid,
                )
        val regions =
            ProcMapsParser.parse(
                capture.capture.text,
            )
        val executable =
            regions.filter {
                target.runtimeAddress >=
                    it.start &&
                    target.runtimeAddress +
                        4L <=
                    it.endExclusive &&
                    it.executable
            }
        require(
            executable.size == 1
        ) {
            "Code-toggle target is not inside exactly one executable mapping."
        }
        val region =
            executable.single()
        target.mappedPath
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                expectedPath ->
                require(
                    region.path ==
                        expectedPath
                ) {
                    "Executable mapping identity changed since the code trace."
                }
            }

        val original =
            ByteArray(4) {
                index ->
                (
                    target.originalWord ushr
                        (
                            index *
                                8
                            )
                    ).toByte()
            }

        RootSandboxLiveToggleCoordinator
            .setEnabled(
                packageName =
                    packageName,
                pid = pid,
                target =
                    SandboxLiveToggleTarget(
                        id = target.id,
                        title =
                            target.title,
                        runtimeAddress =
                            target
                                .runtimeAddress,
                        originalBytes =
                            original,
                        replacementBytes =
                            AARCH64_NOP,
                    ),
                enabled =
                    enabled,
                cancellation =
                    cancellation,
                runner = runner,
            )
    }
}
