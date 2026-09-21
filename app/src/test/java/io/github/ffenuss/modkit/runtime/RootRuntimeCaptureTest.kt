package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeCaptureTest {
    @Test
    fun rootMapsCaptureRequiresStableExactMainProcessIdentity() {
        val runner = FakeRunner(
            pid = 321,
            commandLines =
                ArrayDeque(
                    listOf(
                        PACKAGE,
                        PACKAGE,
                        PACKAGE,
                    ),
                ),
        )

        val result = RootRuntimeCaptureCoordinator.captureMaps(
            packageName = PACKAGE,
            cancellation = AtomicCancellationSignal(),
            runner = runner,
        )

        assertEquals(PACKAGE, result.packageName)
        assertEquals(321, result.pid)
        assertEquals(
            ProcMapsCaptureSource.ROOT_PROCESS,
            result.capture.source,
        )
        assertTrue(result.capture.text.contains("libsample.so"))
    }

    @Test
    fun deniedRootAccessFailsBeforeProcessDiscovery() {
        val runner = object : RootCommandRunner {
            var calls = 0

            override fun run(
                command: String,
                maxOutputBytes: Int,
                cancellation:
                    io.github.ffenuss.modkit.analysis.CancellationSignal,
            ): RootCommandResult {
                calls++
                return RootCommandResult(
                    exitCode = 1,
                    output = "permission denied".toByteArray(),
                    truncated = false,
                )
            }
        }

        val failure = runCatching {
            RootRuntimeCaptureCoordinator.captureMaps(
                packageName = PACKAGE,
                cancellation = AtomicCancellationSignal(),
                runner = runner,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "Root access",
                ignoreCase = true,
            ),
        )
        assertEquals(1, runner.calls)
    }

    @Test
    fun pidReuseDuringPrivilegedCaptureFailsClosed() {
        val runner = FakeRunner(
            pid = 654,
            commandLines =
                ArrayDeque(
                    listOf(
                        PACKAGE,
                        PACKAGE,
                        "com.example.other",
                    ),
                ),
        )

        val failure = runCatching {
            RootRuntimeCaptureCoordinator.captureMaps(
                packageName = PACKAGE,
                cancellation = AtomicCancellationSignal(),
                runner = runner,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "changed during root maps capture",
            ),
        )
    }

    @Test
    fun fallsBackToPsWhenPidofIsUnavailable() {
        val runner =
            object : RootCommandRunner {
                override fun run(
                    command: String,
                    maxOutputBytes: Int,
                    cancellation:
                        io.github.ffenuss.modkit.analysis.CancellationSignal,
                ): RootCommandResult =
                    when (command) {
                        "id -u" ->
                            RootCommandResult(
                                0,
                                "0".toByteArray(),
                                false,
                            )
                        "pidof $PACKAGE" ->
                            RootCommandResult(
                                1,
                                ByteArray(0),
                                false,
                            )
                        "ps -A -o PID,USER,NAME" ->
                            RootCommandResult(
                                0,
                                (
                                    "PID USER NAME\n" +
                                        "987 u0_a42 $PACKAGE\n"
                                    ).toByteArray(),
                                false,
                            )
                        "cat /proc/987/cmdline" ->
                            RootCommandResult(
                                0,
                                (
                                    PACKAGE +
                                        "\u0000"
                                    ).toByteArray(),
                                false,
                            )
                        "cat /proc/987/maps" ->
                            RootCommandResult(
                                0,
                                (
                                    "70000000-70001000 rw-p 00000000 00:00 0 [heap]\n"
                                    ).toByteArray(),
                                false,
                            )
                        else ->
                            RootCommandResult(
                                1,
                                ByteArray(0),
                                false,
                            )
                    }
            }

        val result =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        PACKAGE,
                    cancellation =
                        AtomicCancellationSignal(),
                    runner = runner,
                )

        assertEquals(987, result.pid)
        assertTrue(
            result.capture.text
                .contains("[heap]"),
        )
    }

    @Test
    fun rootMemoryReaderUsesPageAlignedFastReadAndReturnsExactRange() {
        val page =
            ByteArray(4096)
        page[0] = 0x7f
        page[1] = 0x45
        page[2] = 0x4c
        page[3] = 0x46
        val expected =
            page.copyOfRange(
                0,
                4,
            )
        var calls = 0
        val runner =
            RootCommandRunner {
                    command,
                    maxOutputBytes,
                    _,
                ->
                calls++
                assertEquals(
                    "dd if=/proc/123/mem bs=4096 skip=1 count=1 status=none 2>/dev/null",
                    command,
                )
                assertEquals(
                    4096,
                    maxOutputBytes,
                )
                RootCommandResult(
                    exitCode = 0,
                    output = page,
                    truncated = false,
                )
            }

        val actual =
            RootProcMemRuntimeMemoryReader(
                pid = 123,
                runner = runner,
            ).read(
                address = 4096,
                size = 4,
                cancellation =
                    AtomicCancellationSignal(),
            )

        assertEquals(1, calls)
        assertEquals(
            expected.toList(),
            requireNotNull(actual).toList(),
        )
    }

    @Test
    fun rootMemoryReaderRejectsShortRead() {
        val runner =
            RootCommandRunner {
                    _,
                    _,
                    _,
                ->
                RootCommandResult(
                    exitCode = 0,
                    output =
                        byteArrayOf(
                            1,
                            2,
                        ),
                    truncated = false,
                )
            }

        val actual =
            RootProcMemRuntimeMemoryReader(
                pid = 123,
                runner = runner,
            ).read(
                address = 4096,
                size = 4,
                cancellation =
                    AtomicCancellationSignal(),
            )

        assertEquals(null, actual)
    }

    @Test
    fun truncatedPrivilegedMapsNeverBecomeEvidence() {
        val runner = FakeRunner(
            pid = 777,
            commandLines =
                ArrayDeque(
                    listOf(
                        PACKAGE,
                        PACKAGE,
                    ),
                ),
            mapsTruncated = true,
        )

        val failure = runCatching {
            RootRuntimeCaptureCoordinator.captureMaps(
                packageName = PACKAGE,
                cancellation = AtomicCancellationSignal(),
                runner = runner,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "truncated",
                ignoreCase = true,
            ),
        )
    }

    private class FakeRunner(
        private val pid: Int,
        private val commandLines: ArrayDeque<String>,
        private val mapsTruncated: Boolean = false,
    ) : RootCommandRunner {
        override fun run(
            command: String,
            maxOutputBytes: Int,
            cancellation:
                io.github.ffenuss.modkit.analysis.CancellationSignal,
        ): RootCommandResult =
            when {
                command == "id -u" ->
                    result("0")
                command == "pidof $PACKAGE" ->
                    result(pid.toString())
                command == "cat /proc/$pid/cmdline" ->
                    result(
                        (commandLines.removeFirstOrNull() ?: PACKAGE) +
                            "\u0000",
                    )
                command == "cat /proc/$pid/maps" ->
                    RootCommandResult(
                        exitCode = 0,
                        output =
                            (
                                "70000000-70001000 r--p 00000000 103:02 42 " +
                                    "/data/app/pkg/lib/arm64/libsample.so\n" +
                                    "70001000-70002000 r-xp 00001000 103:02 42 " +
                                    "/data/app/pkg/lib/arm64/libsample.so\n"
                                ).toByteArray(),
                        truncated = mapsTruncated,
                    )
                else ->
                    RootCommandResult(
                        exitCode = 1,
                        output = ByteArray(0),
                        truncated = false,
                    )
            }

        private fun result(
            text: String,
        ) = RootCommandResult(
            exitCode = 0,
            output = text.toByteArray(),
            truncated = false,
        )
    }

    companion object {
        private const val PACKAGE = "com.example.target"
    }
}
