package io.github.ffenuss.modkit.analysis

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PortableArtifactIndexerTest {
    private val running = object : CancellationSignal { override fun isCancelled() = false }
    private val silent = ProgressSink { }
    @Test fun sharesIndexAndContentCache() {
        val root = Files.createTempDirectory("portable-index").toFile()
        try {
            val apk = root.resolve("base.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(byteArrayOf(100,101,120,10,48,51,53,0)); zip.closeEntry()
            }
            var cached: ArtifactIndex? = null
            val store = object : ArtifactIndexStore {
                override fun loadArtifactIndex(artifactSha256: String) = cached?.takeIf { it.artifactSha256 == artifactSha256 }
                override fun saveArtifactIndex(artifactSha256: String, index: ArtifactIndex) { cached = index }
            }
            val first = PortableArtifactIndexer.index(listOf(apk), running, silent, cache = store)
            val second = PortableArtifactIndexer.index(listOf(apk), running, silent, cache = store)
            assertFalse(first.cacheHit); assertTrue(second.cacheHit); assertEquals(first.index, second.index)
            assertEquals(BinaryFormat.DEX, first.index.entries.single().format)
        } finally { root.deleteRecursively() }
    }
    @Test(expected = AnalysisCancelledException::class) fun cancellationInsideArchiveIsNotDowngradedToWarning() {
        val apk = Files.createTempFile("portable-cancel", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex")); zip.write(ByteArray(112)); zip.closeEntry()
            }
            var checks = 0
            val signal = object : CancellationSignal { override fun isCancelled() = ++checks >= 3 }
            PortableArtifactIndexer.index(listOf(apk), signal, silent, knownSha256 = mapOf(apk.absolutePath to "a".repeat(64)))
        } finally { apk.delete() }
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsAmbiguousContainerNames() {
        val root = Files.createTempDirectory("portable-duplicate").toFile()
        try {
            val a = root.resolve("a/base.apk").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
            val b = root.resolve("b/base.apk").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(2)) }
            PortableArtifactIndexer.index(listOf(a,b), running, silent)
        } finally { root.deleteRecursively() }
    }
}
