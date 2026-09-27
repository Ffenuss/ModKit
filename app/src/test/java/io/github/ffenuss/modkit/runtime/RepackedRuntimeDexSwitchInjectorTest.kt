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

    private fun installerDex(): ByteArray {
        val pm = "Landroid/content/pm/PackageManager;"
        val string = "Ljava/lang/String;"
        val method = ImmutableMethod(owner, "queryInstaller", listOf(
            ImmutableMethodParameter(pm, emptySet(), null), ImmutableMethodParameter(string, emptySet(), null)),
            string, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(2, listOf(
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0,
                    org.jf.dexlib2.immutable.reference.ImmutableMethodReference(pm, "getInstallerPackageName", listOf(string), string)),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                emptyList(), emptyList()))
        val clazz = ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", emptyList(), null,
            emptySet(), emptyList(), emptyList(), listOf(method), emptyList())
        val out = MemoryDataStore()
        try { DexPool.writeTo(out, ImmutableDexFile(Opcodes.forApi(28), listOf(clazz))); return out.data }
        finally { out.close() }
    }

    @Test fun preservesObservedInstallerAcrossCodeSplitWithoutAnyDexModSelection() {
        val root = Files.createTempDirectory("installer-split").toFile()
        try {
            val base = File(root, "base.apk"); val split = File(root, "feature.apk"); val resources = File(root, "resources.apk")
            val originalDex = installerDex()
            zip(base, mapOf("classes.dex" to dex()))
            zip(split, mapOf("classes.dex" to originalDex, "assets/keep" to byteArrayOf(7)))
            zip(resources, mapOf("assets/data" to byteArrayOf(8)))
            val prior = previous(listOf(base, split, resources))
            val record = OriginalInstallerRecord("dev.game", "dev.original.store", prior.artifactSha256)
            val result = RepackedRuntimeDexSwitchInjector.inject(prior, listOf(base.name, split.name, resources.name),
                emptyList(), File(root, "out"), signal, record)
            assertEquals(1, result.installerRedirectedCalls)
            assertTrue(result.switchIds.isEmpty())
            assertEquals(record, result.originalInstaller)
            assertEquals(resources.path, result.sources[2].outputPath)
            assertEquals(prior.sources.map { it.outputSha256 }, listOf(base, split, resources).map(::hash))
            ZipFile(result.sources[0].outputPath).use { zip ->
                assertArrayEquals(record.encode(), zip.getInputStream(zip.getEntry(OriginalInstallerRecord.ENTRY)).readBytes())
                assertNull(zip.getEntry(RepackedRuntimeDexSwitchInjector.CATALOG))
            }
            ZipFile(result.sources[1].outputPath).use { zip ->
                assertNull(zip.getEntry(OriginalInstallerRecord.ENTRY))
                assertFalse(originalDex.contentEquals(zip.getInputStream(zip.getEntry("classes.dex")).readBytes()))
                assertArrayEquals(byteArrayOf(7), zip.getInputStream(zip.getEntry("assets/keep")).readBytes())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun wrongInstallerSourceAndExistingObservationAreRejected() {
        val root = Files.createTempDirectory("installer-provenance").toFile()
        try {
            val base = File(root, "base.apk"); zip(base, mapOf("classes.dex" to installerDex()))
            val prior = previous(listOf(base))
            for (record in listOf(OriginalInstallerRecord("dev.other", "dev.store", prior.artifactSha256),
                OriginalInstallerRecord("dev.game", "dev.store", "c".repeat(64)))) {
                assertThrows(IllegalArgumentException::class.java) {
                    RepackedRuntimeDexSwitchInjector.inject(prior, listOf(base.name), emptyList(), File(root, "out"), signal, record)
                }
            }
            zip(base, mapOf("classes.dex" to installerDex(), OriginalInstallerRecord.ENTRY to byteArrayOf(1)))
            assertThrows(IllegalArgumentException::class.java) {
                RepackedRuntimeDexSwitchInjector.inject(previous(listOf(base)), listOf(base.name), emptyList(), File(root, "out"), signal,
                    OriginalInstallerRecord("dev.game", "dev.store", prior.artifactSha256))
            }
        } finally { root.deleteRecursively() }
    }
}
