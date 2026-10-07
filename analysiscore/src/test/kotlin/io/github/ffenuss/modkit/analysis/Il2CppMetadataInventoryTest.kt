package io.github.ffenuss.modkit.analysis

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class Il2CppMetadataInventoryTest {
    private val running = object : CancellationSignal { override fun isCancelled() = false }
    private val silent = ProgressSink { }
    private fun apk(file: File, bytes: ByteArray) {
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("assets/bin/Data/Managed/Metadata/global-metadata.dat"))
            it.write(bytes); it.closeEntry()
        }
    }
    private fun workspace(file: File): AnalysisWorkspace {
        val index = PortableArtifactIndexer.index(listOf(file), running, silent).index
        return AnalysisWorkspace(index, listOf(WorkspaceSource(index.sources.single(), file)))
    }
    @Test fun extractsRealNamesTokensAndCleansWorkspaceWithoutChangingApk() {
        val root = Files.createTempDirectory("metadata-inventory").toFile()
        try {
            val source = root.resolve("split.apk"); apk(source, MetadataFixture.bytes(29))
            val original = source.readBytes(); val output = root.resolve("work")
            val result = Il2CppMetadataInventoryEngine.analyze(workspace(source), output, running, silent)
            val record = result.records.single()
            assertEquals("split.apk", record.container); assertTrue(record.structuredSupported)
            assertEquals("Game.Player", record.typeSamples.single().fullName)
            assertEquals("Hit", record.methodSamples.single().name)
            assertEquals(0x06000001L, record.methodSamples.single().token)
            assertEquals("health", record.fieldSamples.single().name)
            assertEquals("Game.Player", record.fieldSamples.single().declaringType)
            assertEquals(1, record.parsedImages); assertArrayEquals(original, source.readBytes())
            assertTrue(output.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun unknownLayoutHasNoInventedDefinitions() {
        val root = Files.createTempDirectory("metadata-unsupported").toFile()
        try {
            val source = root.resolve("base.apk"); apk(source, MetadataFixture.bytes(99))
            val record = Il2CppMetadataInventoryEngine.analyze(workspace(source), root.resolve("work"), running, silent).records.single()
            assertTrue(record.magicValid); assertFalse(record.structuredSupported)
            assertEquals(0, record.parsedMethods); assertTrue(record.methodSamples.isEmpty()); assertTrue(record.warnings.isNotEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun sizeLimitSkipsMetadataAndDeletesTemporaryFiles() {
        val root = Files.createTempDirectory("metadata-limit").toFile()
        try {
            val source = root.resolve("base.apk"); apk(source, MetadataFixture.bytes(29))
            val output = root.resolve("work")
            val result = Il2CppMetadataInventoryEngine.analyze(workspace(source), output, running, silent,
                Il2CppMetadataInventoryEngine.Limits(maxFileBytes = 512))
            assertTrue(result.records.isEmpty()); assertTrue(result.warnings.any { "byte limit" in it })
            assertTrue(output.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationDuringExtractionPropagatesAndCleansTemporaryFiles() {
        val root = Files.createTempDirectory("metadata-cancel").toFile()
        try {
            val source = root.resolve("base.apk"); apk(source, MetadataFixture.bytes(29))
            var cancelled = false
            val signal = object : CancellationSignal { override fun isCancelled() = cancelled }
            val output = root.resolve("work")
            try {
                Il2CppMetadataInventoryEngine.analyze(workspace(source), output, signal, ProgressSink { cancelled = true })
                fail("Cancellation must not become a warning")
            } catch (expected: AnalysisCancelledException) { assertTrue(output.listFiles().orEmpty().isEmpty()) }
        } finally { root.deleteRecursively() }
    }
    @Test fun overlappingOwnershipRemainsUnresolvedRatherThanChoosingFirstType() {
        val file = Files.createTempFile("metadata-overlap", ".dat").toFile()
        try {
            val bytes = MetadataFixture.bytes(29)
            val image = bytes.copyOfRange(800, 840)
            bytes.copyInto(bytes, 788, 700, 788)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(8 + 19 * 8 + 4, 176)
            buffer.putInt(8 + 20 * 8, 900)
            image.copyInto(bytes, 900)
            file.writeBytes(bytes)
            val model = Il2CppMetadataReader.read(file, running, silent)
            assertTrue(model.truncated); assertTrue(model.warnings.any { "ambiguous" in it })
            assertEquals(-1, model.fields.single().declaringTypeIndex)
        } finally { file.delete() }
    }
    @Test fun bogusHugeFieldRangesAreBoundedByParsedFieldTable() {
        val file = Files.createTempFile("metadata-fields", ".dat").toFile()
        try {
            val bytes = MetadataFixture.bytes(29)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(768, (-1).toShort())
            file.writeBytes(bytes)
            val model = Il2CppMetadataReader.read(file, running, silent)
            assertEquals(1, model.fields.size); assertEquals("Game.Player", model.fields.single().declaringType)
            assertTrue(model.truncated); assertTrue(model.warnings.any { "exceed the declared" in it })
        } finally { file.delete() }
    }
}
