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
}
