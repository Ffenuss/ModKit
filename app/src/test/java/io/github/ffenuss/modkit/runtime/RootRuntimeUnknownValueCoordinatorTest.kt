package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeUnknownValueCoordinatorTest {
    @Test
    fun diskBaselineFindsOnlyValuesThatIncreased() {
        val root =
            Files.createTempDirectory(
                "modkit-unknown-baseline-",
            ).toFile()
        try {
            val base = 0x4000L
            val memory =
                ByteArray(64)
            putInt(memory, 0, 10)
            putInt(memory, 4, 10)
            putInt(memory, 8, 10)
            val runner =
                MemoryRunner(
                    base = base,
                    memory = memory,
                )
            val baseline =
                RootRuntimeUnknownValueCoordinator
                    .captureBaseline(
                        packageName = PACKAGE,
                        valueType =
                            RuntimeValueType.INT32,
                        snapshotFile =
                            root.resolve(
                                "baseline.bin",
                            ),
                        cancellation =
                            AtomicCancellationSignal(),
                        runner = runner,
                        maxBytes = 64,
                    )

            assertEquals(64L, baseline.capturedBytes)
            assertTrue(
                baseline.segments.isNotEmpty(),
            )

            putInt(memory, 0, 11)
            putInt(memory, 4, 9)
            putInt(memory, 8, 10)

            val result =
                RootRuntimeUnknownValueCoordinator
                    .compareBaseline(
                        baseline = baseline,
                        refinement =
                            RuntimeValueRefinement
                                .INCREASED,
                        cancellation =
                            AtomicCancellationSignal(),
                        runner = runner,
                    )

            assertEquals(1, result.snapshot.hits.size)
            assertEquals(
                base,
                result.snapshot.hits.single()
                    .address,
            )
            assertEquals(
                "11",
                result.snapshot.hits.single()
                    .displayValue(
                        RuntimeValueType.INT32,
                    ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedReaderCanReuseAnAlreadyProvenProcessCycle() {
        val root =
            Files.createTempDirectory(
                "modkit-unknown-verified-",
            ).toFile()
        try {
            val base = 0x4800L
            val memory =
                ByteArray(64)
            putInt(memory, 0, 20)
            val runner =
                MemoryRunner(
                    base = base,
                    memory = memory,
                )
            val baseline =
                RootRuntimeUnknownValueCoordinator
                    .captureBaseline(
                        packageName = PACKAGE,
                        valueType =
                            RuntimeValueType.INT32,
                        snapshotFile =
                            root.resolve(
                                "baseline.bin",
                            ),
                        cancellation =
                            AtomicCancellationSignal(),
                        runner = runner,
                        maxBytes = 64,
                    )

            putInt(memory, 0, 21)
            val reader =
                RuntimeMemoryReader {
                        address,
                        size,
                        _,
                    ->
                    val offset =
                        (address - base)
                            .toInt()
                    if (
                        offset < 0 ||
                        offset + size >
                        memory.size
                    ) {
                        null
                    } else {
                        memory.copyOfRange(
                            offset,
                            offset + size,
                        )
                    }
                }

            val result =
                RootRuntimeUnknownValueCoordinator
                    .compareBaselineVerified(
                        baseline = baseline,
                        refinement =
                            RuntimeValueRefinement
                                .CHANGED,
                        reader = reader,
                        cancellation =
                            AtomicCancellationSignal(),
                    )

            assertEquals(
                1,
                result.snapshot.hits.size,
            )
            assertEquals(
                base,
                result.snapshot.hits
                    .single()
                    .address,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun baselineFailsClosedWhenPidChanges() {
        val root =
            Files.createTempDirectory(
                "modkit-unknown-pid-",
            ).toFile()
        try {
            val base = 0x5000L
            val memory =
                ByteArray(64)
            val runner =
                MemoryRunner(
                    base = base,
                    memory = memory,
                )
            val baseline =
                RootRuntimeUnknownValueCoordinator
                    .captureBaseline(
                        packageName = PACKAGE,
                        valueType =
                            RuntimeValueType.INT32,
                        snapshotFile =
                            root.resolve(
                                "baseline.bin",
                            ),
                        cancellation =
                            AtomicCancellationSignal(),
                        runner = runner,
                        maxBytes = 64,
                    )
            runner.pid = 999

            val failure =
                runCatching {
                    RootRuntimeUnknownValueCoordinator
                        .compareBaseline(
                            baseline = baseline,
                            refinement =
                                RuntimeValueRefinement
                                    .CHANGED,
                            cancellation =
                                AtomicCancellationSignal(),
                            runner = runner,
                        )
                }.exceptionOrNull()

            assertTrue(
                failure is IllegalArgumentException,
            )
            assertTrue(
                failure?.message.orEmpty()
                    .contains(
                        "PID процесса изменился",
                    ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private class MemoryRunner(
        private val base: Long,
        private val memory: ByteArray,
    ) : RootCommandRunner {
        var pid: Int = PID

        override fun run(
            command: String,
            maxOutputBytes: Int,
            cancellation:
                CancellationSignal,
        ): RootCommandResult =
            when {
                command == "id -u" ->
                    ok("0")
                command ==
                    "pidof $PACKAGE" ->
                    ok(pid.toString())
                command ==
                    "cat /proc/$pid/cmdline" ->
                    ok(
                        PACKAGE +
                            "\u0000",
                    )
                command ==
                    "cat /proc/$pid/maps" ->
                    ok(
                        base.toString(16) +
                            "-" +
                            (
                                base +
                                    memory.size
                                ).toString(16) +
                            " rw-p 00000000 00:00 0 [heap]\n",
                    )
                command.startsWith(
                    "dd if=/proc/$pid/mem ",
                ) ->
                    readMemory(
                        command,
                    )
                else ->
                    fail()
            }

        private fun readMemory(
            command: String,
        ): RootCommandResult {
            val skip =
                Regex("""skip=(\d+)""")
                    .find(command)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
                    ?: return fail()
            val count =
                Regex("""count=(\d+)""")
                    .find(command)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?: return fail()
            val offset =
                (skip - base)
                    .toInt()
            if (
                offset < 0 ||
                offset + count >
                memory.size
            ) {
                return fail()
            }
            return RootCommandResult(
                exitCode = 0,
                output =
                    memory.copyOfRange(
                        offset,
                        offset + count,
                    ),
                truncated = false,
            )
        }

        private fun ok(
            value: String,
        ) =
            RootCommandResult(
                exitCode = 0,
                output =
                    value.toByteArray(),
                truncated = false,
            )

        private fun fail() =
            RootCommandResult(
                exitCode = 1,
                output =
                    ByteArray(0),
                truncated = false,
            )
    }

    private fun putInt(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        repeat(4) {
            index ->
            bytes[offset + index] =
                (
                    value ushr
                        (index * 8)
                    ).toByte()
        }
    }

    companion object {
        private const val PACKAGE =
            "com.example.game"
        private const val PID = 321
    }
}
