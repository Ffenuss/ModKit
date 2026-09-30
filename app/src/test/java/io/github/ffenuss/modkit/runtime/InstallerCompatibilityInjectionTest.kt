package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerCompatibilityInjectionTest {
    private val signal = AtomicCancellationSignal()
    private val pm = "Landroid/content/pm/PackageManager;"
    private val string = "Ljava/lang/String;"

    @Test
    fun confirmedInstallerCheckIsAdaptedInSplitAndObservationIsStoredOnlyInBase() {
        val root = Files.createTempDirectory("installer-compat-injection").toFile()
        try {
            val base = File(root, "base.apk")
            val split = File(root, "feature.apk")
            val originalDex = installerDex()
            zip(base, mapOf("classes.dex" to harmlessDex()))
            zip(split, mapOf("classes.dex" to originalDex, "assets/keep" to byteArrayOf(7)))

            val previous = previous(listOf(base, split))
            val record = OriginalInstallerRecord(
                packageName = "dev.game",
                installerPackageName = "dev.original.store",
                artifactSha256 = previous.artifactSha256,
            )
            val result = RepackedRuntimeDexSwitchInjector.inject(
                previous,
                listOf(base.name, split.name),
                emptyList(),
                File(root, "out"),
                signal,
                originalInstaller = record,
            )

            assertEquals(1, result.installerConfirmedChecks)
            assertEquals(1, result.installerRedirectedCalls)
            assertEquals(record, result.originalInstaller)
            assertTrue(result.switchIds.isEmpty())
            assertEquals(0, result.instrumentedMethods)

            ZipFile(result.sources[0].outputPath).use { zip ->
                assertArrayEquals(
                    record.encode(),
                    zip.getInputStream(zip.getEntry(OriginalInstallerRecord.ENTRY)).readBytes(),
                )
                assertNull(zip.getEntry(RepackedRuntimeDexSwitchInjector.CATALOG))
            }
            ZipFile(result.sources[1].outputPath).use { zip ->
                assertNull(zip.getEntry(OriginalInstallerRecord.ENTRY))
                assertFalse(
                    originalDex.contentEquals(
                        zip.getInputStream(zip.getEntry("classes.dex")).readBytes(),
                    ),
                )
                assertArrayEquals(
                    byteArrayOf(7),
                    zip.getInputStream(zip.getEntry("assets/keep")).readBytes(),
                )
            }
            assertEquals(
                previous.sources.map { it.outputSha256 },
                listOf(base, split).map(::hash),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun installerObservationMustBelongToExactArtifactAndPackage() {
        val root = Files.createTempDirectory("installer-compat-binding").toFile()
        try {
            val base = File(root, "base.apk")
            zip(base, mapOf("classes.dex" to installerDex()))
            val previous = previous(listOf(base))

            val wrongArtifact = OriginalInstallerRecord(
                "dev.game",
                "dev.store",
                "c".repeat(64),
            )
            assertThrows(IllegalArgumentException::class.java) {
                RepackedRuntimeDexSwitchInjector.inject(
                    previous,
                    listOf(base.name),
                    emptyList(),
                    File(root, "out-a"),
                    signal,
                    originalInstaller = wrongArtifact,
                )
            }

            val wrongPackage = OriginalInstallerRecord(
                "dev.other",
                "dev.store",
                previous.artifactSha256,
            )
            assertThrows(IllegalArgumentException::class.java) {
                RepackedRuntimeDexSwitchInjector.inject(
                    previous,
                    listOf(base.name),
                    emptyList(),
                    File(root, "out-b"),
                    signal,
                    originalInstaller = wrongPackage,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun confirmedInstallerCheckWithoutObservedInstalledSourceFailsClosed() {
        val root = Files.createTempDirectory("installer-compat-no-origin").toFile()
        try {
            val base = File(root, "base.apk")
            zip(base, mapOf("classes.dex" to installerDex()))
            val previous = previous(listOf(base))

            val failure = assertThrows(IllegalArgumentException::class.java) {
                RepackedRuntimeDexSwitchInjector.inject(
                    previous,
                    listOf(base.name),
                    emptyList(),
                    File(root, "out"),
                    signal,
                    originalInstaller = null,
                )
            }
            assertTrue(
                failure.message.orEmpty().contains("исходный установщик", ignoreCase = true),
            )
            assertArrayEquals(
                installerDex(),
                ZipFile(base).use { zip ->
                    zip.getInputStream(zip.getEntry("classes.dex")).readBytes()
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun installerDex(): ByteArray {
        val query = ImmutableMethodReference(
            pm,
            "getInstallerPackageName",
            listOf(string),
            string,
        )
        val equals = ImmutableMethodReference(
            string,
            "equals",
            listOf("Ljava/lang/Object;"),
            "Z",
        )
        val code: List<Instruction> = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, query),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            ImmutableInstruction21c(
                Opcode.CONST_STRING,
                3,
                ImmutableStringReference("dev.original.store"),
            ),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 3, 0, 0, 0, equals),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 4),
            ImmutableInstruction21t(Opcode.IF_EQZ, 4, 4),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction11x(Opcode.RETURN, 0),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )
        val method = ImmutableMethod(
            "Ldev/game/InstallGate;",
            "allowed",
            listOf(
                ImmutableMethodParameter(pm, emptySet(), null),
                ImmutableMethodParameter(string, emptySet(), null),
            ),
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(5, code, emptyList(), emptyList()),
        )
        return dex("Ldev/game/InstallGate;", listOf(method))
    }

    private fun harmlessDex(): ByteArray {
        val method = ImmutableMethod(
            "Ldev/game/Other;",
            "value",
            emptyList(),
            "I",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(
                1,
                listOf(
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
                emptyList(),
                emptyList(),
            ),
        )
        return dex("Ldev/game/Other;", listOf(method))
    }

    private fun dex(owner: String, methods: List<ImmutableMethod>): ByteArray {
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptySet(),
            emptyList(),
            emptyList(),
            methods,
            emptyList(),
        )
        val store = MemoryDataStore()
        return try {
            DexPool.writeTo(
                store,
                ImmutableDexFile(Opcodes.forApi(30), listOf(clazz)),
            )
            store.data
        } finally {
            store.close()
        }
    }

    private fun previous(files: List<File>) =
        RepackedRuntimeNativeProbeInjectionResult(
            artifactSha256 = "a".repeat(64),
            packageName = "dev.game",
            baseSourceDisplayName = files.first().name,
            selectedAbis = setOf("x86_64"),
            payloadSha256ByAbi = mapOf("x86_64" to "b".repeat(64)),
            sources = files.mapIndexed { index, file ->
                RepackedRuntimeNativeProbeInjectedSource(
                    sourceDisplayName = file.name,
                    inputPath = file.path,
                    inputSha256 = hash(file),
                    outputPath = file.path,
                    outputSha256 = hash(file),
                    nativePayloadAbis = if (index == 0) setOf("x86_64") else emptySet(),
                )
            },
            outputRootPath = files.first().parent,
        )

    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun hash(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}
