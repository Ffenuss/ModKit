package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeValueWriterTest {
    @Test
    fun coordinatorWritesOnlyCurrentHitAndVerifiesReadBack() {
        val base = 0x1000L
        val memory =
            ByteArray(64)
        putInt(
            memory,
            8,
            100,
        )
        val runner =
            MemoryRunner(
                base = base,
                memory = memory,
            )
        val previous =
            RootRuntimeValueScanResult(
                packageName = PACKAGE,
                pid = PID,
                capturedAtEpochMs = 1,
                snapshot =
                    RuntimeValueScanSnapshot(
                        valueType =
                            RuntimeValueType.INT32,
                        hits =
                            listOf(
                                RuntimeValueHit(
                                    address =
                                        base + 8,
                                    bits = 100,
                                    regionStart =
                                        base,
                                    regionEndExclusive =
                                        base +
                                            memory.size,
                                    regionFileOffset =
                                        0,
                                    regionPath =
                                        "[heap]",
                                ),
                            ),
                        scannedBytes = 64,
                        scannedRegions = 1,
                        truncatedByHitLimit =
                            false,
                        truncatedByByteLimit =
                            false,
                    ),
            )

        val result =
            RootRuntimeValueWriteCoordinator
                .writeHit(
                    previous = previous,
                    address = base + 8,
                    valueText = "250",
                    cancellation =
                        AtomicCancellationSignal(),
                    runner = runner,
                )

        assertTrue(result.verified)
        assertEquals("100", result.oldValue)
        assertEquals("250", result.newValue)
        assertEquals(
            250,
            readInt(
                memory,
                8,
            ),
        )
        assertEquals(
            "250",
            result.updatedScan
                .snapshot.hits.single()
                .displayValue(
                    RuntimeValueType.INT32,
                ),
        )
    }

    @Test
    fun writerPayloadUsesOnlyOctalEscapes() {
        val runner =
            RecordingRunner()
        val writer =
            RootProcMemRuntimeMemoryWriter(
                pid = PID,
                runner = runner,
            )

        assertTrue(
            writer.write(
                address = 4096,
                bytes =
                    byteArrayOf(
                        0,
                        0x27,
                        0x5c,
                        0xff.toByte(),
                    ),
                cancellation =
                    AtomicCancellationSignal(),
            ),
        )
        assertEquals(
            "printf '\\000\\047\\134\\377' | " +
                "dd of=/proc/123/mem bs=1 seek=4096 count=4 " +
                "conv=notrunc status=none 2>/dev/null",
            runner.command,
        )
    }

    private class RecordingRunner :
        RootCommandRunner {
        var command: String? = null

        override fun run(
            command: String,
            maxOutputBytes: Int,
            cancellation:
                CancellationSignal,
        ): RootCommandResult {
            this.command = command
            return RootCommandResult(
                exitCode = 0,
                output =
                    ByteArray(0),
                truncated = false,
            )
        }
    }

    private class MemoryRunner(
        private val base: Long,
        private val memory: ByteArray,
    ) : RootCommandRunner {
        private var cmdlineReads = 0

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
                    ok(PID.toString())
                command ==
                    "cat /proc/$PID/cmdline" -> {
                    cmdlineReads++
                    ok(
                        PACKAGE +
                            "\u0000",
                    )
                }
                command ==
                    "cat /proc/$PID/maps" ->
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
                    "dd if=/proc/$PID/mem ",
                ) ->
                    readCommand(command)
                command.startsWith(
                    "printf '",
                ) &&
                    command.contains(
                        "dd of=/proc/$PID/mem",
                    ) ->
                    writeCommand(command)
                else ->
                    RootCommandResult(
                        exitCode = 1,
                        output =
                            (
                                "unexpected: " +
                                    command
                                ).toByteArray(),
                        truncated = false,
                    )
            }

        private fun readCommand(
            command: String,
        ): RootCommandResult {
            val skip =
                Regex("""skip=(\d+)""")
                    .find(command)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
                    ?: return failure()
            val count =
                Regex("""count=(\d+)""")
                    .find(command)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()
                    ?: return failure()
            val offset =
                (skip - base)
                    .toInt()
            if (
                offset < 0 ||
                offset + count >
                memory.size
            ) {
                return failure()
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

        private fun writeCommand(
            command: String,
        ): RootCommandResult {
            val payload =
                command.substringAfter(
                    "printf '",
                ).substringBefore("' | dd")
            val bytes =
                Regex("""\\([0-7]{3})""")
                    .findAll(payload)
                    .map {
                        it.groupValues[1]
                            .toInt(8)
                            .toByte()
                    }
                    .toList()
                    .toByteArray()
            val seek =
                Regex("""seek=(\d+)""")
                    .find(command)
                    ?.groupValues
                    ?.get(1)
                    ?.toLong()
                    ?: return failure()
            val offset =
                (seek - base)
                    .toInt()
            if (
                offset < 0 ||
                offset + bytes.size >
                memory.size
            ) {
                return failure()
            }
            bytes.copyInto(
                memory,
                destinationOffset =
                    offset,
            )
            return RootCommandResult(
                exitCode = 0,
                output =
                    ByteArray(0),
                truncated = false,
            )
        }

        private fun ok(
            text: String,
        ) =
            RootCommandResult(
                exitCode = 0,
                output =
                    text.toByteArray(),
                truncated = false,
            )

        private fun failure() =
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
                (value ushr (index * 8))
                    .toByte()
        }
    }

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        var value = 0
        repeat(4) {
            index ->
            value =
                value or
                    (
                        (bytes[offset + index]
                            .toInt() and 0xff) shl
                            (index * 8)
                        )
        }
        return value
    }

    companion object {
        private const val PACKAGE =
            "com.example.game"
        private const val PID = 123
    }
}
