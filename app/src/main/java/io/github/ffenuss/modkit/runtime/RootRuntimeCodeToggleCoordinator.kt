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
    fun resolveLearnedSite(
        packageName: String,
        pid: Int,
        site: LearnedCodeAccessSite,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootCodeAccessSite {
        require(pid > 0) {
            "Code-site PID must be positive."
        }
        require(
            site.moduleIdentity
                .isNotBlank() &&
                site.moduleFileOffset >=
                0L
        ) {
            "Invalid persisted code-site identity."
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
        val matches =
            regions.filter {
                region ->
                region.executable &&
                    moduleIdentity(
                        region.path,
                    ) ==
                    site.moduleIdentity &&
                    site.moduleFileOffset >=
                    region.fileOffset &&
                    site.moduleFileOffset +
                        4L <=
                    region.fileOffset +
                        region.size
            }
        require(
            matches.size == 1
        ) {
            if (matches.isEmpty()) {
                "Persisted code site is not mapped in the current process."
            } else {
                "Persisted code-site module mapping is ambiguous."
            }
        }
        val region =
            matches.single()
        val runtimeAddress =
            region.start +
                (
                    site.moduleFileOffset -
                        region.fileOffset
                    )

        val word =
            site.instructionWord
        if (word != null) {
            val reader =
                RootProcMemRuntimeMemoryReader(
                    pid = pid,
                    runner = runner,
                )
            val current =
                reader.read(
                    address =
                        runtimeAddress,
                    size = 4,
                    cancellation =
                        cancellation,
                ) ?: error(
                    "Persisted code site is no longer readable.",
                )
            require(
                current.size == 4
            ) {
                "Persisted code-site read was truncated."
            }
            val currentWord =
                (
                    (current[0]
                        .toLong() and
                        0xffL) or
                        (
                            (current[1]
                                .toLong() and
                                0xffL) shl
                                8
                            ) or
                        (
                            (current[2]
                                .toLong() and
                                0xffL) shl
                                16
                            ) or
                        (
                            (current[3]
                                .toLong() and
                                0xffL) shl
                                24
                            )
                    )
            require(
                currentWord ==
                    word
            ) {
                "Persisted code-site instruction changed; explicit retrace is required."
            }
        }

        return RootCodeAccessSite(
            pc = runtimeAddress,
            instructionAddress =
                runtimeAddress,
            count =
                site.observedCount,
            tid = 0,
            faultAddress = 0L,
            moduleName =
                site.moduleIdentity,
            mappedPath =
                region.path,
            moduleFileOffset =
                site.moduleFileOffset,
            instructionText =
                site.instructionText,
            instructionWord =
                site.instructionWord,
            accessKind =
                site.accessKind,
            managedMethodCandidate =
                site.managedMethodCandidate,
        )
    }

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
    private fun moduleIdentity(
        path: String?,
    ): String? =
        path
            ?.removeSuffix(
                " (deleted)",
            )
            ?.substringAfterLast(
                '/',
            )
            ?.takeIf {
                it.isNotBlank()
            }

}
