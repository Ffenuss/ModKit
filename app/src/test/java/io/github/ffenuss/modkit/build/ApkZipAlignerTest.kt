package io.github.ffenuss.modkit.build

import java.nio.file.Files

import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ProgressSink
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkZipAlignerTest {
    @Test
    fun alignsStoredNativeLibraryTo16KiBAndOtherStoredEntriesTo4Bytes() {
        val root = Files.createTempDirectory("modkit-zipalign-").toFile()
        try {
            val input = File(root, "input.apk")
            val output = File(root, "aligned.apk")
            val native = ByteArray(257) { (it and 0xff).toByte() }
            val asset = "plain-asset".toByteArray()

            ZipOutputStream(input.outputStream().buffered()).use { zip ->
                putDeflated(zip, "AndroidManifest.xml", byteArrayOf(1, 2, 3))
                putStored(zip, "assets/raw.bin", asset)
                putStored(zip, "lib/arm64-v8a/libsample.so", native)
            }

            val aligned = ApkZipAligner.align(
                input = input,
                output = output,
                cancellation = NeverCancelled,
                progress = NoProgress,
            )

            assertTrue(aligned.verification.verified)
            val nativeRecord = aligned.verification.records.single {
                it.entryName == "lib/arm64-v8a/libsample.so"
            }
            val assetRecord = aligned.verification.records.single {
                it.entryName == "assets/raw.bin"
            }
            assertEquals(0L, nativeRecord.dataOffset % (16 * 1024))
            assertEquals(0L, assetRecord.dataOffset % 4)

            ZipFile(output).use { zip ->
                assertArrayEquals(
                    native,
                    zip.getInputStream(
                        zip.getEntry("lib/arm64-v8a/libsample.so"),
                    ).readBytes(),
                )
                assertArrayEquals(
                    asset,
                    zip.getInputStream(zip.getEntry("assets/raw.bin")).readBytes(),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifierRejectsOrdinaryUnalignedStoredLibrary() {
        val root = Files.createTempDirectory("modkit-zipalign-block-").toFile()
        try {
            val input = File(root, "unaligned.apk")
            ZipOutputStream(input.outputStream().buffered()).use { zip ->
                putDeflated(zip, "AndroidManifest.xml", byteArrayOf(1))
                putStored(
                    zip,
                    "lib/arm64-v8a/libsample.so",
                    ByteArray(33) { 7 },
                )
            }

            val verification = ZipAlignmentVerifier.verify(input)

            assertTrue(
                verification.records.any {
                    it.entryName == "lib/arm64-v8a/libsample.so"
                },
            )
            assertTrue(
                !verification.verified ||
                    verification.records.single {
                        it.entryName == "lib/arm64-v8a/libsample.so"
                    }.dataOffset % (16 * 1024) == 0L,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun putDeflated(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putStored(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val crc = CRC32().apply { update(bytes) }.value
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private object NeverCancelled : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }

    private object NoProgress : ProgressSink {
        override fun publish(progress: io.github.ffenuss.modkit.domain.EngineProgress) = Unit
    }
}
