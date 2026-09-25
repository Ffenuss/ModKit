package io.github.ffenuss.modkit.analysis

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalElfInventoryProgressTest {
    private val live = object : CancellationSignal {
        override fun isCancelled() = false
    }

    @Test
    fun largeElfCopyReportsRealMonotonicBytesAndAlwaysFinishesAtExactSize() {
        val dir = Files.createTempDirectory("modkit-elf-progress").toFile()
        try {
            val bytes = ByteArray(3 * 1024 * 1024) { (it * 29).toByte() }
            val file = dir.resolve("libminecraftpe.so")
            val progress = mutableListOf<Long>()
            UniversalElfInventoryEngine.copyBounded(
                input = ByteArrayInputStream(bytes),
                output = file,
                expectedSize = bytes.size.toLong(),
                cancellation = live,
                onProgress = progress::add,
            )
            assertArrayEquals(bytes, file.readBytes())
            assertTrue(progress.size >= 3)
            assertTrue(progress.first() == 0L)
            assertTrue(progress.last() == bytes.size.toLong())
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
            assertTrue(
                "Progress includes genuine intermediate milestones rather than a fake timer",
                progress.drop(1).dropLast(1).any { it in 1L until bytes.size.toLong() },
            )
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun cancelledAndShortCopiesDeletePartialOutput() {
        val dir = Files.createTempDirectory("modkit-elf-cancel").toFile()
        try {
            val bytes = ByteArray(1024 * 1024) { (it / 7).toByte() }
            val partial = dir.resolve("cancelled.so")
            var checks = 0
            val cancelled = object : CancellationSignal {
                override fun isCancelled(): Boolean = ++checks > 2
            }
            val failure = runCatching {
                UniversalElfInventoryEngine.copyBounded(
                    ByteArrayInputStream(bytes), partial, bytes.size.toLong(),
                    cancelled, onProgress = { },
                )
            }.exceptionOrNull()
            assertTrue(failure is AnalysisCancelledException)
            assertFalse(partial.exists())

            val shortFile = dir.resolve("short.so")
            val short = runCatching {
                UniversalElfInventoryEngine.copyBounded(
                    ByteArrayInputStream(bytes.copyOfRange(0, bytes.size / 2)),
                    shortFile, bytes.size.toLong(),
                    live, onProgress = { },
                )
            }.exceptionOrNull()
            assertTrue(short is IllegalArgumentException)
            assertFalse(shortFile.exists())
        } finally { dir.deleteRecursively() }
    }
}
