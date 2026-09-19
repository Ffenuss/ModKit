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
            val method = ProcMapsCaptureReader::class.java
                .getDeclaredMethod(
                    "readFile",
                    java.io.File::class.java,
                    ProcMapsCaptureSource::class.java,
                    Int::class.javaObjectType,
                    CancellationSignal::class.java,
                    Int::class.javaPrimitiveType,
                )
            method.isAccessible = true
            val capture = method.invoke(
                ProcMapsCaptureReader,
                file,
                ProcMapsCaptureSource.NON_ROOT_PROCESS,
                42,
                NeverCancelled,
                1024,
            ) as ProcMapsCapture

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
