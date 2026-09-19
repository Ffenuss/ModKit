package io.github.ffenuss.modkit.analysis

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppFastDumpEngineTest {
    @Test
    fun extractsMetadataAndWritesHumanReadableDump() {
        val dir = Files.createTempDirectory("modkit-il2cpp-engine").toFile()
        val apk = File(dir, "sample.apk")
        val metadata = metadataFixture()

        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/bin/Data/Managed/Metadata/global-metadata.dat"))
            zip.write(metadata)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libil2cpp.so"))
            val elf = ByteArray(64)
            elf[0] = 0x7f
            elf[1] = 'E'.code.toByte()
            elf[2] = 'L'.code.toByte()
            elf[3] = 'F'.code.toByte()
            elf[4] = 2
            elf[5] = 1
            elf[18] = 183.toByte()
            elf[19] = 0
            zip.write(elf)
            zip.closeEntry()
        }

        try {
            val indexed = FastArtifactIndexer.index(
                files = listOf(apk),
                cancellation = neverCancelled(),
                progress = ProgressSink { },
            )
            assertTrue(indexed.routingPlan.engines.any {
                it.id == "il2cpp.fast-dump" && it.availableNow
            })

            val workspace = AnalysisWorkspace(
                index = indexed.index,
                sources = indexed.index.sources.zip(listOf(apk)).map { (descriptor, file) ->
                    WorkspaceSource(descriptor, file)
                },
            )
            val result = Il2CppFastDumpEngine.analyze(
                workspace = workspace,
                outputRoot = File(dir, "results"),
                cancellation = neverCancelled(),
                progress = ProgressSink { },
            )

            assertEquals(29, result.metadata.metadataVersion)
            assertEquals("Game.Player", result.metadata.types.single().fullName)
            assertTrue(File(result.dumpFilePath).isFile)
            val dump = File(result.dumpFilePath).readText()
            assertTrue("class Player" in dump)
            assertTrue("health" in dump)
            assertTrue("Hit(" in dump)
            assertTrue("exact_native_binding=false" in dump)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun metadataFixture(): ByteArray {
        val bytes = ByteArray(1024)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0xFAB11BAF.toInt())
        buffer.putInt(4, 29)

        fun pair(index: Int, offset: Int, size: Int) {
            buffer.putInt(8 + index * 8, offset)
            buffer.putInt(8 + index * 8 + 4, size)
        }

        pair(2, 300, 64)
        pair(5, 500, 32)
        pair(11, 600, 12)
        pair(19, 700, 88)

        val strings = byteArrayOf(
            'P'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte(),
            'e'.code.toByte(), 'r'.code.toByte(), 0,
            'G'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'e'.code.toByte(), 0,
            'H'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte(), 0,
            'h'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
            't'.code.toByte(), 'h'.code.toByte(), 0,
        )
        strings.copyInto(bytes, 300)

        buffer.putInt(700, 0)
        buffer.putInt(704, 7)
        buffer.putInt(732, 0)
        buffer.putInt(736, 0)
        buffer.putShort(764, 1.toShort())
        buffer.putShort(768, 1.toShort())
        buffer.putInt(784, 0x02000001)

        buffer.putInt(500, 12)
        buffer.putInt(504, 0)
        buffer.putInt(520, 0x06000001)
        buffer.putShort(524, 0x0006.toShort())
        buffer.putShort(530, 0.toShort())

        buffer.putInt(600, 16)
        buffer.putInt(604, 4)
        buffer.putInt(608, 0x04000001)
        return bytes
    }

    private fun neverCancelled() = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
}
