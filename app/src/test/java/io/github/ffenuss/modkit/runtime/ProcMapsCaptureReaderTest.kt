package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcMapsCaptureReaderTest {
    @Test
    fun importedCaptureIsBoundedAndMarkedTruncated() {
        val capture = ProcMapsCaptureReader.imported(
            text = "0123456789",
            maxBytes = 4,
        )

        assertEquals(ProcMapsCaptureSource.IMPORTED_SNAPSHOT, capture.source)
        assertEquals("0123", capture.text)
        assertTrue(capture.truncated)
        assertEquals(64, capture.sha256.length)
    }

    @Test
    fun fileCaptureReadsCompleteSmallSnapshot() {
        val root = Files.createTempDirectory("modkit-proc-maps-").toFile()
        try {
            val file = root.resolve("maps").apply {
                writeText(
                    "1000-2000 r-xp 00000000 00:00 1 /lib/test.so\n",
                    Charsets.UTF_8,
                )
            }
            val capture = ProcMapsCaptureReader.fromFile(
                file = file,
                source = ProcMapsCaptureSource.NON_ROOT_PROCESS,
                pid = 42,
                cancellation = NeverCancelled,
                maxBytes = 1024,
            )

            assertFalse(capture.truncated)
            assertEquals(42, capture.pid)
            assertTrue("/lib/test.so" in capture.text)
        } finally {
            root.deleteRecursively()
        }
    }

    private object NeverCancelled : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
}
