package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeSignatureStripperTest {
    @Test
    fun stripsOnlyLegacySignatureEntriesAndPreservesSource() {
        val root = Files.createTempDirectory("modkit-repacked-strip-").toFile()
        try {
            val source = File(root, "source.apk")
            createApk(source)
            val sourceBytes = source.readBytes()
            val output = File(root, "sanitized.apk")

            val result = RepackedRuntimeSignatureStripper.strip(
                input = source,
                output = output,
                cancellation = AtomicCancellationSignal(),
            )

            assertArrayEquals(sourceBytes, source.readBytes())
            assertTrue(output.isFile)
            assertTrue(
                result.strippedSignatureEntries.containsAll(
                    listOf(
                        "META-INF/MANIFEST.MF",
                        "META-INF/CERT.SF",
                        "META-INF/CERT.RSA",
                    ),
                ),
            )

            ZipFile(output).use { zip ->
                assertFalse(zip.getEntry("META-INF/MANIFEST.MF") != null)
                assertFalse(zip.getEntry("META-INF/CERT.SF") != null)
                assertFalse(zip.getEntry("META-INF/CERT.RSA") != null)
                assertTrue(zip.getEntry("META-INF/services/example") != null)
                assertTrue(zip.getEntry("AndroidManifest.xml") != null)
                assertEquals(
                    "payload",
                    zip.getInputStream(requireNotNull(zip.getEntry("assets/data.txt")))
                        .bufferedReader()
                        .use { it.readText() },
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesToOverwriteSourceApk() {
        val root = Files.createTempDirectory("modkit-repacked-strip-same-").toFile()
        try {
            val source = File(root, "source.apk")
            createApk(source)

            val failure = runCatching {
                RepackedRuntimeSignatureStripper.strip(
                    input = source,
                    output = source,
                    cancellation = AtomicCancellationSignal(),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createApk(file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            addDeflated(out, "AndroidManifest.xml", "manifest".toByteArray())
            addDeflated(out, "META-INF/MANIFEST.MF", "manifest-sig".toByteArray())
            addDeflated(out, "META-INF/CERT.SF", "sf".toByteArray())
            addDeflated(out, "META-INF/CERT.RSA", "rsa".toByteArray())
            addDeflated(out, "META-INF/services/example", "keep".toByteArray())
            addStored(out, "assets/data.txt", "payload".toByteArray())
        }
    }

    private fun addDeflated(
        out: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.DEFLATED
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
    }

    private fun addStored(
        out: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
    }
}
