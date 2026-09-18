package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.RunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FastArtifactIndexerTest {
    @Test
    fun detectsValidatedIl2CppPairAndAbi() {
        val dir = Files.createTempDirectory("modkit-index-test").toFile()
        val apk = dir.resolve("sample.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(), '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0))
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

            zip.putNextEntry(ZipEntry("assets/bin/Data/Managed/Metadata/global-metadata.dat"))
            zip.write(byteArrayOf(0xaf.toByte(), 0x1b, 0xb1.toByte(), 0xfa.toByte(), 29, 0, 0, 0))
            zip.closeEntry()
        }

        val progress = mutableListOf<RunState>()
        val result = FastArtifactIndexer.index(
            files = listOf(apk),
            cancellation = object : CancellationSignal {
                override fun isCancelled(): Boolean = false
            },
            progress = ProgressSink { progress += it.state },
        )

        assertEquals(3, result.index.entries.size)
        assertTrue("arm64-v8a" in result.index.detectedAbis)
        assertTrue(result.index.runtimeProfiles.any {
            it.runtimeId == "unity_il2cpp" && it.status == DetectionStatus.CONFIRMED
        })
        assertTrue(result.index.runtimeProfiles.any { it.runtimeId == "android_dex" })
        assertTrue(progress.last() == RunState.COMPLETED)

        dir.deleteRecursively()
    }

    @Test(expected = AnalysisCancelledException::class)
    fun cancellationStopsBeforeReadingTarget() {
        val file = Files.createTempFile("modkit-cancel", ".bin").toFile().apply {
            writeBytes(ByteArray(1024))
        }
        try {
            FastArtifactIndexer.index(
                files = listOf(file),
                cancellation = object : CancellationSignal {
                    override fun isCancelled(): Boolean = true
                },
                progress = ProgressSink { },
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun wasmExtensionWithoutMagicIsNotConfirmedAsWasm() {
        val dir = Files.createTempDirectory("modkit-wasm-test").toFile()
        val apk = dir.resolve("sample.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/module.wasm"))
            zip.write("not-wasm".toByteArray())
            zip.closeEntry()
        }

        val result = FastArtifactIndexer.index(
            files = listOf(apk),
            cancellation = object : CancellationSignal {
                override fun isCancelled(): Boolean = false
            },
            progress = ProgressSink { },
        )

        assertTrue(result.index.runtimeProfiles.none {
            it.runtimeId == "webassembly" && it.status == DetectionStatus.CONFIRMED
        })
        dir.deleteRecursively()
    }
}
