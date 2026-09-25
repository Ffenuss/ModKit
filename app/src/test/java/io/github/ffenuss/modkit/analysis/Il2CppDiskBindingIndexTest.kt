package io.github.ffenuss.modkit.analysis

import java.nio.file.Files
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppDiskBindingIndexTest {
    private val live = object : CancellationSignal {
        override fun isCancelled() = false
    }

    @Test
    fun lateMethodBeyondThirtyThousandCanBeResolvedWithoutMaterializingIt() {
        val dir = Files.createTempDirectory("modkit-unbounded-methods").toFile()
        try {
            val path = dir.resolve("complete.idx")
            val index = Il2CppDiskBindingIndexWriter(path, 160_000, live).use { writer ->
                writer.add(0, 0, 0, 0x1000, 0x100, Il2CppNativeReturnKind.INTEGER)
                writer.add(159_999, 1, 0, 0x2000, 0x200, Il2CppNativeReturnKind.BOOLEAN)
                writer.finish()
            }
            assertEquals(160_000, index.methodCount)
            assertEquals(2, index.boundCount)
            assertEquals(160_000L * 32, path.length())
            assertTrue(index.verify())
            assertNull(index.lookup(30_000))
            assertNull(index.lookup(160_000))
            val late = requireNotNull(index.lookup(159_999))
            assertEquals(1, late.slotIndex)
            assertEquals(0x2000L, late.functionVirtualAddress)
            assertEquals(0x200L, late.functionFileOffset)
            assertEquals(Il2CppNativeReturnKind.BOOLEAN, late.returnKind)
            RandomAccessFile(path, "rw").use {
                it.seek(159_999L * Il2CppDiskBindingIndex.RECORD_BYTES + 8)
                it.writeLong(0x1234L)
            }
            assertFalse("Indexed native bytes cannot be trusted after tampering", index.verify())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun lateGameplayBindingHasExactTypeAndMissingPermissionFailsClosed() {
        val dir = Files.createTempDirectory("modkit-late-gameplay").toFile()
        try {
            val path = dir.resolve("complete.idx")
            val index = Il2CppDiskBindingIndexWriter(path, 40_001, live).use { writer ->
                writer.add(0, 0, 0, 0x1000, 0x100, Il2CppNativeReturnKind.INTEGER)
                writer.add(40_000, 1, 0, 0x2000, 0x200, Il2CppNativeReturnKind.FLOAT32)
                writer.finish()
            }
            val meta = metadataFixture()
            val early = Il2CppMethodBinaryBinding(
                methodIndex = 0,
                managedIdentity = "Game.Player.getHealth",
                metadataToken = 0x06000001L,
                imageName = "Assembly-CSharp.dll",
                moduleName = "Assembly-CSharp.dll",
                slotIndex = 0,
                functionVirtualAddress = 0x1000,
                functionFileOffset = 0x100,
                returnTypeIndex = 1,
                returnKind = Il2CppNativeReturnKind.INTEGER,
            )
            val module = Il2CppCodeGenModuleEvidence(
                moduleName = "Assembly-CSharp.dll",
                moduleVirtualAddress = 0x4000,
                methodPointerCount = 2,
                methodPointersVirtualAddress = 0x5000,
                sampledPointers = 2,
                executablePointers = 2,
            )
            val evidence = Il2CppBinaryEvidence(
                libraryEntry = "lib/arm64-v8a/libil2cpp.so",
                machine = 183,
                pointerSize = 8,
                relativeRelocationCount = 2,
                codeRegistrationVirtualAddress = null,
                metadataRegistrationVirtualAddress = 0x6000,
                codegenRegisterVirtualAddress = null,
                moduleArrayDiscovery = "TEST",
                modules = listOf(module),
                bindings = listOf(early),
                blockers = emptyList(),
                bindingIndex = index,
            )
            val late = Il2CppOnDemandBindings.lateGameplayBindings(meta, evidence)
            assertEquals(1, late.size)
            assertEquals(40_000, late.single().methodIndex)
            assertEquals("Game.Player.getMoveSpeed", late.single().managedIdentity)
            assertEquals(Il2CppNativeReturnKind.FLOAT32, late.single().returnKind)
            assertEquals(0x200L, late.single().functionFileOffset)

            val result = FastAnalysisResult(
                index = ArtifactIndex("sha", emptyList(), emptyList()),
                routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
                elapsedMs = 1L,
                il2cppFastDump = Il2CppFastDumpResult(
                    metadataEntry = "global-metadata.dat",
                    libraryEntries = listOf("lib/arm64-v8a/libil2cpp.so"),
                    metadata = meta,
                    dumpFilePath = "dump.cs",
                    preview = "",
                    warnings = emptyList(),
                ),
                il2cppBinaryBinding = Il2CppBinaryBindingResult(
                    evidence = listOf(evidence),
                    exactBindingCount = index.boundCount,
                    warnings = emptyList(),
                ),
            )
            assertEquals(
                40_000,
                Il2CppOnDemandBindings.find(
                    result, 0x06000002L, "Assembly-CSharp.dll", evidence.libraryEntry,
                )?.methodIndex,
            )
            assertNull(
                Il2CppOnDemandBindings.find(
                    result, 0x06000002L, "Unknown.dll", evidence.libraryEntry,
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun metadataFixture(): Il2CppMetadataModel {
        val image = Il2CppImageDefinition(0, "Assembly-CSharp.dll", 0, 0, 1, 1)
        val type = Il2CppTypeDefinition(
            0, "Game", "Player", "Game.Player", 0, 2, 0, 0, 0x02000001L,
        )
        return Il2CppMetadataModel(
            sizeBytes = 2048,
            magicValid = true,
            metadataVersion = 31,
            layoutProfile = "IL2CPP_METADATA_V31",
            tableRanges = emptyList(),
            declaredTypeCount = 1,
            declaredMethodCount = 40_001,
            declaredFieldCount = 0,
            declaredImageCount = 1,
            images = listOf(image),
            types = listOf(type),
            methods = listOf(
                Il2CppMethodDefinition(
                    0, 0, "Game.Player", "getHealth", 0, 0x06000001L, 0, 1,
                ),
                Il2CppMethodDefinition(
                    40_000, 0, "Game.Player", "getMoveSpeed", 0, 0x06000002L, 0, 1,
                ),
            ),
            fields = emptyList(),
            structuredSupported = true,
            truncated = false,
            warnings = emptyList(),
        )
    }
}
