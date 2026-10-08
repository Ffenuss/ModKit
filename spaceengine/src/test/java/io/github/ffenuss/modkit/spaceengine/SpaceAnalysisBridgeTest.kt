package io.github.ffenuss.modkit.spaceengine

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpaceAnalysisBridgeTest {
    @Test fun reportsActualDexTablesAndPreservesInput() {
        val root = Files.createTempDirectory("space-bridge").toFile()
        try {
            val dex = ByteArray(112)
            byteArrayOf(100,101,120,10,48,51,53,0).copyInto(dex)
            ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(32,112); putInt(36,112); putInt(40,0x12345678)
                putInt(64,3); putInt(88,7); putInt(96,2)
            }
            val apk = root.resolve("base.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex")); zip.write(dex); zip.closeEntry()
            }
            val original = apk.readBytes()
            val messages = mutableListOf<String>()
            val report = SpaceAnalysisBridge.analyze(arrayOf(apk), root.resolve("analysis"), BooleanSupplier { false }, Consumer { messages.add(it) })
            assertTrue(report.contains("types=3; methods=7; classes=2"))
            assertTrue(report.contains("ELF · структурные записи: 0"))
            assertTrue(messages.isNotEmpty()); assertArrayEquals(original, apk.readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test(expected = AnalysisCancelledException::class) fun cancellationCrossesPlatformBoundary() {
        val file = Files.createTempFile("space-bridge-cancel", ".apk").toFile()
        try { SpaceAnalysisBridge.analyze(arrayOf(file), file.parentFile, BooleanSupplier { true }, Consumer {}) }
        finally { file.delete() }
    }
}
