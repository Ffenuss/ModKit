package io.github.ffenuss.modkit.analysis

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnrealAssetInventoryEngineTest {
    private val live = object : CancellationSignal {
        override fun isCancelled() = false
    }

    @Test
    fun validatesPakV3Sha1AndActualUnrealPackageHeaderInZip() {
        val apk = Files.createTempFile("modkit-unreal", ".apk").toFile()
        try {
            val pak = pakFixture(corruptHash = false)
            ZipOutputStream(apk.outputStream().buffered()).use { zip ->
                put(zip, "assets/Paks/Game.pak", pak)
                put(zip, "assets/Game/Content/BP_Player.uasset", byteArrayOf(
                    0xC1.toByte(), 0x83.toByte(), 0x2A, 0x9E.toByte(), 0, 0, 0, 0,
                ))
                put(zip, "assets/Game/Content/Broken.umap", byteArrayOf(0, 0, 0, 0))
            }
            val indexed = FastArtifactIndexer.index(
                listOf(apk), live, ProgressSink { },
            )
            assertTrue(indexed.index.runtimeProfiles.any { it.runtimeId == "unreal" })
            val result = UnrealAssetInventoryEngine.analyze(
                workspace(indexed, apk), live, ProgressSink { },
            )
            assertEquals(3, result.records.size)
            assertEquals(1, result.verifiedPakCount)
            val pakRecord = result.records.single { it.kind == "pak" }
            assertEquals("PAK_INDEX_VERIFIED", pakRecord.status)
            assertEquals(3, pakRecord.pakVersion)
            assertEquals(true, pakRecord.pakIndexSha1Verified)
            assertEquals(false, pakRecord.encryptedIndex)
            assertEquals("UASSET_HEADER_VALID", result.records.single {
                it.kind == "uasset"
            }.status)
            assertEquals("UASSET_HEADER_UNKNOWN", result.records.single {
                it.kind == "umap"
            }.status)
            assertEquals(2, result.packagedContentCount)
        } finally { apk.delete() }
    }

    @Test
    fun rejectsCorruptedPakIndexWithoutClaimingVerifiedContainer() {
        val pak = pakFixture(corruptHash = true)
        val file = Files.createTempFile("modkit-unreal-corrupt", ".pak").toFile()
        try {
            file.writeBytes(pak)
            val index = ArtifactIndex(
                artifactSha256 = "sha",
                sources = listOf(ArtifactSource(file.name, file.length(), "sha")),
                entries = listOf(
                    ArtifactEntry(
                        container = file.name,
                        path = file.name,
                        size = file.length(),
                        tags = setOf("unreal_container_candidate"),
                    ),
                ),
                runtimeProfiles = listOf(RuntimeProfile(
                    "unreal", "Unreal Engine", DetectionStatus.LIKELY,
                    DetectionConfidence.MEDIUM, listOf(file.name),
                )),
            )
            val result = UnrealAssetInventoryEngine.analyze(
                AnalysisWorkspace(index, listOf(WorkspaceSource(index.sources.single(), file))),
                live, ProgressSink { },
            )
            assertEquals("PAK_INDEX_HASH_MISMATCH", result.records.single().status)
            assertEquals(false, result.records.single().pakIndexSha1Verified)
            assertEquals(0, result.verifiedPakCount)
        } finally { file.delete() }
    }

    @Test
    fun footerParserRejectsOutOfBoundsAndTooShortFiles() {
        assertNull(UnrealAssetInventoryEngine.parsePakFooter(ByteArray(12), 12))
        val footer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x5A6F12E1)
            putInt(3)
            putLong(9999L)
            putLong(32L)
            put(ByteArray(20))
        }.array()
        assertNull(UnrealAssetInventoryEngine.parsePakFooter(footer, 64))
        val valid = pakFixture(false)
        val parsed = UnrealAssetInventoryEngine.parsePakFooter(valid, valid.size.toLong())
        assertNotNull(parsed)
        assertEquals(3, parsed!!.version)
    }

    private fun workspace(result: FastAnalysisResult, file: File) =
        AnalysisWorkspace(
            result.index,
            result.index.sources.map { WorkspaceSource(it, file) },
        )

    private fun pakFixture(corruptHash: Boolean): ByteArray {
        val payload = ByteArray(32) { it.toByte() }
        val index = "Game/Content/BP_Player.uasset\u0000".toByteArray()
        val hash = MessageDigest.getInstance("SHA-1").digest(index)
        if (corruptHash) hash[0] = (hash[0].toInt() xor 0x55).toByte()
        val footer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x5A6F12E1)
            .putInt(3)
            .putLong(payload.size.toLong())
            .putLong(index.size.toLong())
            .put(hash)
            .array()
        return payload + index + footer
    }

    private fun put(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }
}
