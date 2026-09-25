package io.github.ffenuss.modkit.analysis

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlutterAssetInventoryEngineTest {
    private val active = object : CancellationSignal {
        override fun isCancelled() = false
    }

    @Test
    fun readsModernBinaryManifestAndDetectsBothNativeFlutterLibraries() {
        val apk = Files.createTempFile("modkit-flutter", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream().buffered()).use { zip ->
                put(zip, "lib/arm64-v8a/libflutter.so", elfHeader())
                put(zip, "lib/arm64-v8a/libapp.so", elfHeader())
                put(zip, "assets/flutter_assets/AssetManifest.bin", manifest())
                put(zip, "assets/flutter_assets/images/player.png", byteArrayOf(1, 2))
            }
            val indexed = FastArtifactIndexer.index(
                listOf(apk), active, ProgressSink { },
            )
            val profile = indexed.index.runtimeProfiles.single { it.runtimeId == "flutter" }
            assertEquals(DetectionStatus.LIKELY, profile.status)
            val plan = EngineRouter.plan(indexed.index)
            assertTrue(plan.targeted.any {
                it.id == FlutterAssetInventoryEngine.ID && it.availableNow
            })
            assertFalse(plan.targeted.first { it.id == "flutter.dart-aot" }.availableNow)
            assertTrue(RoutedEngineScheduler.supportsEngine(FlutterAssetInventoryEngine.ID))
            val analyzed = FlutterAssetInventoryEngine.analyze(
                AnalysisWorkspace(
                    indexed.index,
                    indexed.index.sources.map { WorkspaceSource(it, apk) },
                ),
                active, ProgressSink { },
            )
            assertEquals(1, analyzed.validatedManifestCount)
            assertEquals(2, analyzed.listedAssetCount)
            assertEquals(2, analyzed.runtimeLibraries.size)
            assertTrue(analyzed.runtimeLibraries.all { it.validatedElf })
            assertEquals(2, analyzed.packagedAssetCount)
            val record = analyzed.manifests.single()
            assertEquals("MANIFEST_BIN_PARSED", record.status)
            assertTrue(record.sampledAssets.contains("assets/images/player.png"))
        } finally {
            apk.delete()
        }
    }

    @Test
    fun corruptAndTruncatedManifestsNeverProduceFalseAssetCounts() {
        val bytes = manifest()
        assertEquals(2, FlutterStandardMessageCodec.decodeAssetManifest(bytes).size)
        val malformed = bytes.copyOf(bytes.size - 3)
        assertTrue(runCatching {
            FlutterStandardMessageCodec.decodeAssetManifest(malformed)
        }.isFailure)
        assertTrue(runCatching {
            FlutterStandardMessageCodec.decodeAssetManifest(bytes + byteArrayOf(3))
        }.isFailure)
        val duplicate = ByteArrayOutputStream().also {
            it.write(13); it.write(2)
            string(it, "duplicate.png"); it.write(12); it.write(0)
            string(it, "duplicate.png"); it.write(12); it.write(0)
        }.toByteArray()
        assertTrue(runCatching {
            FlutterStandardMessageCodec.decodeAssetManifest(duplicate)
        }.isFailure)
    }

    @Test
    fun binaryManifestAbsentReportsMissingInputNotGameplayMods() {
        val apk = Files.createTempFile("modkit-flutter-empty", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                put(zip, "lib/arm64-v8a/libflutter.so", elfHeader())
                put(zip, "lib/arm64-v8a/libapp.so", elfHeader())
            }
            val indexed = FastArtifactIndexer.index(listOf(apk), active, ProgressSink { })
            val inventory = FlutterAssetInventoryEngine.analyze(
                AnalysisWorkspace(
                    indexed.index,
                    indexed.index.sources.map { WorkspaceSource(it, apk) },
                ),
                active, ProgressSink { },
            )
            assertEquals(0, inventory.validatedManifestCount)
            assertEquals(0, inventory.listedAssetCount)
            assertTrue(inventory.warnings.any { "AssetManifest" in it })
        } finally { apk.delete() }
    }

    private fun manifest(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(13); out.write(2) // map of two asset paths
        string(out, "assets/images/player.png")
        out.write(12); out.write(1) // one variant metadata map
        out.write(13); out.write(1)
        string(out, "asset"); string(out, "assets/images/player.png")
        string(out, "assets/images/enemy.png")
        out.write(12); out.write(1)
        out.write(13); out.write(1)
        string(out, "asset"); string(out, "assets/images/enemy.png")
        return out.toByteArray()
    }

    private fun string(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size < 254)
        out.write(7); out.write(bytes.size); out.write(bytes)
    }

    private fun elfHeader(): ByteArray = ByteArray(64).also {
        it[0] = 0x7f; it[1] = 'E'.code.toByte()
        it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = 2; it[5] = 1
        it[18] = 183.toByte()
    }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
}
