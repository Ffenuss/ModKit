package io.github.ffenuss.modkit.analysis

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArtifactPackageLoaderTest {
    private val running = object : CancellationSignal { override fun isCancelled() = false }
    private val silent = ProgressSink {}
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }.toByteArray()
    private fun apk() = zip(listOf("AndroidManifest.xml" to byteArrayOf(1), "classes.dex" to "fixture".toByteArray()))
    @Test fun expandsApksAndKeepsStableOriginalNamesAndClearsOnlyOwnFiles() {
        val root = Files.createTempDirectory("apk-set-test").toFile()
        try {
            val input = root.resolve("game.apks").apply { writeBytes(zip(listOf("original/base.apk" to apk(), "splits/arm64.apk" to apk()))) }
            val loaded = ArtifactPackageLoader.open(input, root.resolve("staging"), running, silent)
            assertEquals(listOf("base.apk", "arm64.apk"), loaded.files.map { it.name })
            assertTrue(loaded.files.all { it.readBytes().contentEquals(apk()) })
            val sources = loaded.files.toList(); loaded.close()
            assertTrue(input.exists()); assertTrue(sources.none { it.exists() })
        } finally { root.deleteRecursively() }
    }
    @Test fun directApkRemainsOriginalAndIsNotDeleted() {
        val root = Files.createTempDirectory("single-apk").toFile()
        try {
            val input = root.resolve("base.apk").apply { writeBytes(apk()) }
            ArtifactPackageLoader.open(input, root.resolve("staging"), running, silent).use { assertEquals(listOf(input), it.files) }
            assertTrue(input.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun rawCodeAndGenericAssetArchivesRetainTheirExistingAnalysisPath() {
        val root = Files.createTempDirectory("raw-artifacts").toFile()
        try {
            val raw = root.resolve("library.so").apply { writeBytes(byteArrayOf(127, 69, 76, 70, 2)) }
            ArtifactPackageLoader.open(raw, root.resolve("staging"), running, silent).use { assertEquals(listOf(raw), it.files) }
            val assets = root.resolve("data.zip").apply { writeBytes(zip(listOf("game.lua" to "fixture".toByteArray()))) }
            ArtifactPackageLoader.open(assets, root.resolve("staging"), running, silent).use { assertEquals(listOf(assets), it.files) }
            assertTrue(raw.exists()); assertTrue(assets.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun duplicateNamesAndSizeOverflowDoNotLeaveStaging() {
        val root = Files.createTempDirectory("bounded-apks").toFile()
        try {
            val input = root.resolve("bad.zip").apply { writeBytes(zip(listOf("a/base.apk" to apk(), "b/base.apk" to apk()))) }
            assertThrows(IllegalArgumentException::class.java) { ArtifactPackageLoader.open(input, root.resolve("staging"), running, silent) }
            input.writeBytes(zip(listOf("base.apk" to apk())))
            assertThrows(IllegalArgumentException::class.java) { ArtifactPackageLoader.open(input, root.resolve("staging"), running, silent, maxTotalBytes = 1) }
            assertTrue(root.resolve("staging").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationDuringExtractionCleansOperation() {
        val root = Files.createTempDirectory("cancel-apks").toFile()
        try {
            val input = root.resolve("game.xapk").apply { writeBytes(zip(listOf("base.apk" to apk()))) }
            var cancelled = false
            val signal = object : CancellationSignal { override fun isCancelled() = cancelled }
            assertThrows(AnalysisCancelledException::class.java) {
                ArtifactPackageLoader.open(input, root.resolve("staging"), signal, ProgressSink { cancelled = true })
            }
            assertTrue(root.resolve("staging").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
