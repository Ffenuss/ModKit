package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.patch.*
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.*
import org.junit.Test

class RepackedRuntimeDexSwitchInjectorTest {
    private val signal = AtomicCancellationSignal()
    private val owner = "Ldev/game/Player;"
    private fun dex(): ByteArray {
        val method = ImmutableMethod(owner, "getHealth", emptyList(), "I", AccessFlags.PUBLIC.value,
            emptySet(), emptySet(), ImmutableMethodImplementation(1,
                listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 7), ImmutableInstruction11x(Opcode.RETURN, 0)), emptyList(), emptyList()))
        val clazz = ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), null,
            emptySet(), emptyList(), emptyList(), emptyList(), listOf(method))
        val store = MemoryDataStore()
        try { DexPool.writeTo(store, ImmutableDexFile(Opcodes.forApi(28), listOf(clazz))); return store.data }
        finally { store.close() }
    }
    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { out -> entries.forEach { (name, bytes) ->
            out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry()
        } }
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun previous(files: List<File>) = RepackedRuntimeNativeProbeInjectionResult("a".repeat(64), "dev.game",
        files.first().name, setOf("x86_64"), mapOf("x86_64" to "b".repeat(64)), files.mapIndexed { i, file ->
            RepackedRuntimeNativeProbeInjectedSource(file.name, file.path, hash(file), file.path, hash(file),
                if (i == 0) setOf("x86_64") else emptySet())
        }, files.first().parent)
    private fun selection(bytes: ByteArray, index: Int) = DexRuntimeSelection(
        DexLocalPatchEngine.scanDex(bytes, index, "classes.dex", false, signal).opportunities.single(),
        DexRuntimeSwitchRewriter.switchId("health"))

    @Test fun rewritesSelectedSplitAndKeepsOtherEntriesAndOriginalFiles() {
        val root = Files.createTempDirectory("dex-runtime-apk").toFile()
        try {
            val bytes = dex()
            val base = File(root, "base.apk"); val split = File(root, "feature.apk"); val resource = File(root, "resources.apk")
            zip(base, mapOf("AndroidManifest.xml" to byteArrayOf(1, 2), "classes.dex" to bytes))
            zip(split, mapOf("classes.dex" to bytes, "assets/data" to byteArrayOf(3, 4)))
            zip(resource, mapOf("assets/large" to ByteArray(2048) { it.toByte() }))
            val previous = previous(listOf(base, split, resource))
            val result = RepackedRuntimeDexSwitchInjector.inject(previous, listOf(base.name, split.name, resource.name),
                listOf(selection(bytes, 1)), File(root, "out"), signal)
            assertEquals(previous.sources.map { it.outputSha256 }, listOf(base, split, resource).map(::hash))
            assertEquals(resource.path, result.sources[2].outputPath)
            ZipFile(result.sources[0].outputPath).use { z ->
                assertArrayEquals(bytes, z.getInputStream(z.getEntry("classes.dex")).readBytes())
                assertEquals(result.switchIds.single() + "\n", z.getInputStream(z.getEntry(RepackedRuntimeDexSwitchInjector.CATALOG)).reader().readText())
            }
            ZipFile(result.sources[1].outputPath).use { z ->
                assertArrayEquals(byteArrayOf(3, 4), z.getInputStream(z.getEntry("assets/data")).readBytes())
                assertFalse(bytes.contentEquals(z.getInputStream(z.getEntry("classes.dex")).readBytes()))
            }
            File(result.sources[1].outputPath).appendBytes(byteArrayOf(5))
            assertThrows(IllegalArgumentException::class.java) { RepackedRuntimeDexSwitchInjector.verify(result, signal) }
        } finally { root.deleteRecursively() }
    }

    @Test fun changedInputAndSourceOrderAreRejected() {
        val root = Files.createTempDirectory("dex-runtime-stale").toFile()
        try {
            val bytes = dex(); val base = File(root, "base.apk")
            zip(base, mapOf("classes.dex" to bytes))
            val previous = previous(listOf(base))
            assertThrows(IllegalArgumentException::class.java) {
                RepackedRuntimeDexSwitchInjector.inject(previous, listOf("other.apk"), listOf(selection(bytes, 0)), File(root, "out"), signal)
            }
            base.appendBytes(byteArrayOf(4))
            assertThrows(IllegalArgumentException::class.java) {
                RepackedRuntimeDexSwitchInjector.inject(previous, listOf(base.name), listOf(selection(bytes, 0)), File(root, "out"), signal)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun existingCatalogIsNotSilentlyReplaced() {
        val root = Files.createTempDirectory("dex-runtime-existing").toFile()
        try {
            val bytes = dex(); val base = File(root, "base.apk")
            zip(base, mapOf("classes.dex" to bytes, RepackedRuntimeDexSwitchInjector.CATALOG to byteArrayOf(1)))
            assertThrows(IllegalArgumentException::class.java) {
                RepackedRuntimeDexSwitchInjector.inject(previous(listOf(base)), listOf(base.name), listOf(selection(bytes, 0)), File(root, "out"), signal)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun staticResourceOnlyBuildChangesSelectedSplitWithoutAddingSwitches() {
        val root = Files.createTempDirectory("resource-runtime-apk").toFile()
        try {
            val base = File(root, "base.apk"); val split = File(root, "config.apk")
            val bytes = "{\"health\":20, \"untouched\":7}".toByteArray()
            val path = "assets/flutter_assets/game.json"
            zip(base, mapOf("classes.dex" to dex()))
            zip(split, mapOf(path to bytes, "assets/keep" to byteArrayOf(4, 5)))
            val previous = previous(listOf(base, split))
            val selected = EngineResourceMods.discover(previous.artifactSha256, 1, split.name, path,
                EngineResourceFormat.FLUTTER_JSON, bytes, signal).first().withScalarValue("99").resource!!
            val result = RepackedRuntimeDexSwitchInjector.inject(previous, listOf(base.name, split.name), emptyList(), File(root, "out"), signal, listOf(selected))
            assertTrue(result.switchIds.isEmpty())
            assertEquals(0, result.instrumentedMethods)
            assertEquals(base.path, result.sources.first().outputPath)
            ZipFile(result.sources.last().outputPath).use { z ->
                assertNull(z.getEntry(RepackedRuntimeDexSwitchInjector.CATALOG))
                assertEquals("{\"health\":99, \"untouched\":7}", z.getInputStream(z.getEntry(path)).reader().readText())
                assertArrayEquals(byteArrayOf(4, 5), z.getInputStream(z.getEntry("assets/keep")).readBytes())
            }
            assertEquals(previous.sources.map { it.outputSha256 }, listOf(base, split).map(::hash))
            assertThrows(IllegalArgumentException::class.java) { RepackedRuntimeDexSwitchInjector.inject(previous,
                listOf(base.name, split.name), emptyList(), File(root, "wrong"), signal, listOf(selected.copy(artifactSha256 = "b".repeat(64)))) }
        } finally { root.deleteRecursively() }
    }

    @Test fun combinesResourcesWithReversibleDexWithoutChangingOriginalValuesInDex() {
        val root = Files.createTempDirectory("mixed-resource-dex-apk").toFile()
        try {
            val base = File(root, "base.apk"); val bytes = dex()
            val path = "assets/Game/Config/DefaultGame.ini"
            val data = "[Player]\nHealth=20\n".toByteArray()
            zip(base, mapOf("classes.dex" to bytes, path to data))
            val previous = previous(listOf(base))
            val change = EngineResourceMods.discover(previous.artifactSha256, 0, base.name, path,
                EngineResourceFormat.UNREAL_INI, data, signal).single().withScalarValue("99").resource!!
            val result = RepackedRuntimeDexSwitchInjector.inject(previous, listOf(base.name), listOf(selection(bytes, 0)), File(root, "out"), signal, listOf(change))
            assertEquals(1, result.switchIds.size)
            assertEquals(1, result.instrumentedMethods)
            assertEquals(1, result.resourceChanges.size)
            assertEquals(setOf(path), result.sources.single().rewrittenResourceSha256.keys)
            ZipFile(result.sources.single().outputPath).use { z ->
                assertNotNull(z.getEntry(RepackedRuntimeDexSwitchInjector.CATALOG))
                assertEquals("[Player]\nHealth=99\n", z.getInputStream(z.getEntry(path)).reader().readText())
            }
        } finally { root.deleteRecursively() }
    }
}
