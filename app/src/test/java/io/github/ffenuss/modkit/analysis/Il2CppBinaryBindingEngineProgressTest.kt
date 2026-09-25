package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppBinaryBindingEngineProgressTest {
    private val entryName = "lib/arm64-v8a/libil2cpp.so"

    @Test fun fastExtractionReportsAllBytesAndNextPhaseCanReplaceTheCounter() {
        val root = Files.createTempDirectory("modkit-il2cpp-progress").toFile()
        try {
            val bytes = ByteArray(3 * 1024 * 1024) { index ->
                (index * 37 + index / 131).toByte()
            }
            val archive = createArchive(root, bytes)
            val output = File(root, "extracted.so")
            val events = mutableListOf<EngineProgress>()

            Il2CppBinaryBindingEngine.extract(
                archive = archive,
                entryName = entryName,
                output = output,
                expectedSize = bytes.size.toLong(),
                cancellation = neverCancelled(),
                progress = ProgressSink { events += it },
            )

            assertArrayEquals(bytes, output.readBytes())
            assertTrue("Start and terminal progress are mandatory even when copy is fast", events.size >= 2)
            val first = events.first()
            val last = events.last()
            assertEquals(0L, first.processed)
            assertEquals(bytes.size.toLong(), first.total)
            assertEquals("IL2CPP: извлечение libil2cpp.so", first.currentTask)
            assertEquals(bytes.size.toLong(), last.processed)
            assertEquals(bytes.size.toLong(), last.total)
            assertEquals("IL2CPP: libil2cpp.so извлечена", last.currentTask)
            assertEquals(RunState.RUNNING, last.state)
            assertTrue("Byte counter must be monotonic", events.zipWithNext().all { (a, b) ->
                (a.processed ?: -1L) <= (b.processed ?: -1L)
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun indexedSizeMismatchCannotPublishCompletedOrLeavePartialLibrary() {
        val root = Files.createTempDirectory("modkit-il2cpp-invalid-size").toFile()
        try {
            val bytes = ByteArray(128 * 1024) { it.toByte() }
            val archive = createArchive(root, bytes)
            val output = File(root, "extracted.so")
            val events = mutableListOf<EngineProgress>()

            val failure = runCatching {
                Il2CppBinaryBindingEngine.extract(
                    archive, entryName, output, bytes.size.toLong() + 1L,
                    neverCancelled(), ProgressSink { events += it },
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse("A failed extraction must remove incomplete output", output.exists())
            assertFalse(events.any { it.currentTask == "IL2CPP: libil2cpp.so извлечена" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cancellationDeletesThePartialExtraction() {
        val root = Files.createTempDirectory("modkit-il2cpp-cancel").toFile()
        try {
            val archive = createArchive(root, ByteArray(256 * 1024) { it.toByte() })
            val output = File(root, "extracted.so")
            val cancelled = object : CancellationSignal {
                override fun isCancelled() = true
            }

            val failure = runCatching {
                Il2CppBinaryBindingEngine.extract(
                    archive, entryName, output, 256 * 1024L,
                    cancelled, ProgressSink { },
                )
            }.exceptionOrNull()
            assertTrue(failure is AnalysisCancelledException)
            assertFalse("Cancelled extraction must not leave a partial library", output.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createArchive(root: File, bytes: ByteArray): File =
        File(root, "game.apk").also { archive ->
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

    private fun neverCancelled() = object : CancellationSignal {
        override fun isCancelled() = false
    }
}
