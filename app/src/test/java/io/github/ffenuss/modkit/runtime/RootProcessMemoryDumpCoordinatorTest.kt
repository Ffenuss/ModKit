package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.CancellationSignal
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
                        maxDumpBytes = 8192,
                    )

            assertTrue(output.isFile)
            assertTrue(
                result.dumpedRegions >= 1,
            )
            assertTrue(
                result.dumpedBytes > 0,
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

    private class FakeProcessRunner :
        RootCommandRunner {
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
                (skip - 0x1000L)
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
