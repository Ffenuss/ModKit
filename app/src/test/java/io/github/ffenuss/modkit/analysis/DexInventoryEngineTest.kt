package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexInventoryEngineTest {
    @Test
    fun readsOnlyStructuralHeaderForDirectDex() {
        val root = Files.createTempDirectory("modkit-dex-inventory-").toFile()
        try {
            val dex = root.resolve("classes.dex")
            dex.writeBytes(sampleDex())

            val result = DexInventoryEngine.analyze(
                workspace = AnalysisWorkspace(
                    index = ArtifactIndex(
                        artifactSha256 = SHA,
                        sources = listOf(
                            ArtifactSource(
                                displayName = dex.name,
                                size = dex.length(),
                                sha256 = SHA,
                            ),
                        ),
                        entries = listOf(
                            ArtifactEntry(
                                container = dex.name,
                                path = dex.name,
                                size = dex.length(),
                                format = BinaryFormat.DEX,
                            ),
                        ),
                    ),
                    sources = listOf(
                        WorkspaceSource(
                            descriptor = ArtifactSource(
                                dex.name,
                                dex.length(),
                                SHA,
                            ),
                            file = dex,
                        ),
                    ),
                ),
                cancellation = AtomicCancellationSignal(),
                progress = ProgressSink { _: EngineProgress -> },
            )

            val record = result.records.single()
            assertEquals("039", record.version)
            assertEquals(160L, record.declaredFileSize)
            assertEquals(112L, record.headerSize)
            assertTrue(record.standardEndian)
            assertEquals(2L, record.stringIdsCount)
            assertEquals(1L, record.typeIdsCount)
            assertEquals(1L, record.protoIdsCount)
            assertEquals(1L, record.fieldIdsCount)
            assertEquals(1L, record.methodIdsCount)
            assertEquals(0L, record.classDefsCount)
            assertEquals(8L, record.dataSize)
            assertTrue(record.warnings.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun inventoriesDexInsideApkWithoutWholeEntryExtraction() {
        val root = Files.createTempDirectory("modkit-dex-zip-").toFile()
        try {
            val apk = root.resolve("sample.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(sampleDex())
                zip.closeEntry()
            }

            val result = DexInventoryEngine.analyze(
                workspace = AnalysisWorkspace(
                    index = ArtifactIndex(
                        artifactSha256 = SHA,
                        sources = listOf(
                            ArtifactSource(
                                displayName = apk.name,
                                size = apk.length(),
                                sha256 = SHA,
                            ),
                        ),
                        entries = listOf(
                            ArtifactEntry(
                                container = apk.name,
                                path = "classes.dex",
                                size = 160,
                                format = BinaryFormat.DEX,
                            ),
                        ),
                    ),
                    sources = listOf(
                        WorkspaceSource(
                            descriptor = ArtifactSource(
                                apk.name,
                                apk.length(),
                                SHA,
                            ),
                            file = apk,
                        ),
                    ),
                ),
                cancellation = AtomicCancellationSignal(),
                progress = ProgressSink { },
            )

            assertEquals(1, result.records.size)
            assertEquals("classes.dex", result.records.single().entryPath)
            assertFalse(result.records.single().warnings.isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reverseEndianDexDoesNotInventTableCounts() {
        val root = Files.createTempDirectory("modkit-dex-reverse-").toFile()
        try {
            val bytes = sampleDex()
            writeU32(bytes, 40, 0x78563412L)
            val dex = root.resolve("classes.dex")
            dex.writeBytes(bytes)

            val result = DexInventoryEngine.analyze(
                workspace = workspaceForFile(dex),
                cancellation = AtomicCancellationSignal(),
                progress = ProgressSink { },
            )

            val record = result.records.single()
            assertFalse(record.standardEndian)
            assertEquals(null, record.methodIdsCount)
            assertTrue(
                record.warnings.any {
                    it.contains("Reverse-endian")
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun workspaceForFile(
        dex: java.io.File,
    ): AnalysisWorkspace {
        val source = ArtifactSource(
            displayName = dex.name,
            size = dex.length(),
            sha256 = SHA,
        )
        return AnalysisWorkspace(
            index = ArtifactIndex(
                artifactSha256 = SHA,
                sources = listOf(source),
                entries = listOf(
                    ArtifactEntry(
                        container = dex.name,
                        path = dex.name,
                        size = dex.length(),
                        format = BinaryFormat.DEX,
                    ),
                ),
            ),
            sources = listOf(
                WorkspaceSource(source, dex),
            ),
        )
    }

    private fun sampleDex(): ByteArray {
        val bytes = ByteArray(160)
        val magic = "dex\n039\u0000".toByteArray(Charsets.US_ASCII)
        magic.copyInto(bytes, 0)
        writeU32(bytes, 32, bytes.size.toLong())
        writeU32(bytes, 36, 112)
        writeU32(bytes, 40, 0x12345678L)

        writeU32(bytes, 56, 2)
        writeU32(bytes, 60, 112)
        writeU32(bytes, 64, 1)
        writeU32(bytes, 68, 120)
        writeU32(bytes, 72, 1)
        writeU32(bytes, 76, 124)
        writeU32(bytes, 80, 1)
        writeU32(bytes, 84, 136)
        writeU32(bytes, 88, 1)
        writeU32(bytes, 92, 144)
        writeU32(bytes, 96, 0)
        writeU32(bytes, 100, 0)
        writeU32(bytes, 104, 8)
        writeU32(bytes, 108, 152)
        return bytes
    }

    private fun writeU32(
        bytes: ByteArray,
        offset: Int,
        value: Long,
    ) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
