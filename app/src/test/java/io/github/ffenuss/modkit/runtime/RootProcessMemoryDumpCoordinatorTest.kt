package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProcessMemoryDumpCoordinatorTest {
    @Test
    fun dumpContainsMapsIndexAndReadableRuntimeBytes() {
        val root =
            Files.createTempDirectory(
                "modkit-root-dump-",
            ).toFile()
        try {
            val runner =
                FakeProcessRunner()
            val output =
                root.resolve(
                    "dump.zip",
                )

            val progress =
                mutableListOf<
                    RootProcessMemoryDumpProgress
                >()
            val result =
                RootProcessMemoryDumpCoordinator
                    .dump(
                        packageName =
                            PACKAGE,
                        outputFile =
                            output,
                        cancellation =
                            AtomicCancellationSignal(),
                        runner = runner,
                        progress = {
                            progress += it
                        },
                    )

            assertTrue(output.isFile)
            assertTrue(
                progress.any {
                    it.processedBytes > 0L
                },
            )
            assertTrue(
                progress.last()
                    .fraction >= 1f,
            )
            assertEquals(
                1,
                runner.memoryReadCommands,
            )
            assertEquals(
                2,
                result.dumpedRegions,
            )
            assertEquals(
                8192L,
                result.dumpedBytes,
            )
            assertTrue(
                !result.truncatedByByteLimit,
            )
            ZipFile(output).use {
                zip ->
                assertTrue(
                    zip.getEntry(
                        "maps.txt",
                    ) != null,
                )
                assertTrue(
                    zip.getEntry(
                        "process.txt",
                    ) != null,
                )
                assertTrue(
                    zip.getEntry(
                        "memory/index.tsv",
                    ) != null,
                )
                assertTrue(
                    zip.getEntry(
                        "runtime-artifacts/index.tsv",
                    ) != null,
                )
                val memoryEntries =
                    zip.entries()
                        .asSequence()
                        .filter {
                            it.name.startsWith(
                                "memory/",
                            ) &&
                                it.name.endsWith(
                                    ".bin",
                                )
                        }
                        .toList()
                assertTrue(
                    memoryEntries
                        .isNotEmpty(),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun quickLimitIsExplicitlyTruncatedWhileFullModeIsNot() {
        val root =
            Files.createTempDirectory(
                "modkit-root-dump-limit-",
            ).toFile()
        try {
            val output =
                root.resolve(
                    "quick.zip",
                )
            val result =
                RootProcessMemoryDumpCoordinator
                    .dump(
                        packageName =
                            PACKAGE,
                        outputFile =
                            output,
                        cancellation =
                            AtomicCancellationSignal(),
                        runner =
                            FakeProcessRunner(),
                        maxDumpBytes = 4096,
                    )

            assertTrue(
                result.truncatedByByteLimit,
            )
            assertEquals(
                4096L,
                result.dumpedBytes,
            )
            assertEquals(
                1,
                result.dumpedRegions,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private class FakeProcessRunner :
        RootCommandRunner {
        var memoryReadCommands =
            0
        private val memory =
            ByteArray(8192) {
                index ->
                (index and 0xff)
                    .toByte()
            }

        override fun run(
            command: String,
            maxOutputBytes: Int,
            cancellation:
                CancellationSignal,
        ): RootCommandResult =
            when {
                command == "id -u" ->
                    ok("0\n")
                command ==
                    "pidof $PACKAGE" ->
                    ok("$PID\n")
                command ==
                    "cat /proc/$PID/cmdline" ->
                    RootCommandResult(
                        exitCode = 0,
                        output =
                            (
                                PACKAGE +
                                    "\u0000"
                                ).toByteArray(),
                        truncated = false,
                    )
                command ==
                    "cat /proc/$PID/maps" ->
                    ok(
                        "1000-2000 rw-p 00000000 00:00 0 [heap]\n" +
                            "2000-3000 r--p 00000000 00:00 0 /data/app/$PACKAGE/base.apk\n",
                    )
                command.startsWith(
                    "dd if=/proc/$PID/mem ",
                ) ->
                    readMemory(
                        command,
                    )
                else ->
                    RootCommandResult(
                        exitCode = 1,
                        output =
                            ByteArray(0),
                        truncated = false,
                    )
            }

        private fun readMemory(
            command: String,
        ): RootCommandResult {
            memoryReadCommands++
            val regex =
                Regex(
                    "dd if=/proc/" +
                        PID +
                        "/mem bs=(\\d+) skip=(\\d+) count=(\\d+)",
                )
            val matches =
                regex.findAll(
                    command,
                ).toList()
            if (matches.isEmpty()) {
                return fail()
            }
            val output =
                ByteArrayOutputStream()
            for (match in matches) {
                val blockSize =
                    match.groupValues[1]
                        .toLong()
                val skipBlocks =
                    match.groupValues[2]
                        .toLong()
                val countBlocks =
                    match.groupValues[3]
                        .toLong()
                val address =
                    skipBlocks *
                        blockSize
                val byteCount =
                    countBlocks *
                        blockSize
                if (
                    blockSize <= 0L ||
                    byteCount <= 0L ||
                    byteCount >
                        Int.MAX_VALUE
                ) {
                    return fail()
                }
                val offset =
                    (address -
                        0x1000L)
                        .toInt()
                val count =
                    byteCount.toInt()
                if (
                    offset < 0 ||
                    offset + count >
                        memory.size
                ) {
                    return fail()
                }
                output.write(
                    memory,
                    offset,
                    count,
                )
            }
            return RootCommandResult(
                exitCode = 0,
                output =
                    output.toByteArray(),
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

        private fun fail() =
            RootCommandResult(
                exitCode = 1,
                output =
                    ByteArray(0),
                truncated = false,
            )
    }

    companion object {
        private const val PACKAGE =
            "com.example.game"
        private const val PID = 123
    }
}
