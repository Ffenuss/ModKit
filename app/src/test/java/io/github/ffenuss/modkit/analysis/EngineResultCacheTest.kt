package io.github.ffenuss.modkit.analysis

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineResultCacheTest {
    @Test
    fun reusesFastDumpOnlyForSameArtifactAndExistingOutput() {
        val root = Files.createTempDirectory("modkit-engine-cache").toFile()
        try {
            val dumpFile = File(root, "dump.cs").apply { writeText("// cached") }
            val result = Il2CppFastDumpResult(
                metadataEntry = "base.apk:global-metadata.dat",
                libraryEntries = listOf("base.apk:lib/arm64-v8a/libil2cpp.so"),
                metadata = metadataModel(),
                dumpFilePath = dumpFile.absolutePath,
                preview = "cached preview",
                warnings = listOf("cached warning"),
            )
            val cache = EngineResultCache(File(root, "cache"))

            assertTrue(cache.saveIl2CppFastDump("abc123", result))
            assertEquals(result, cache.loadIl2CppFastDump("abc123"))
            assertNull(cache.loadIl2CppFastDump("different-sha"))

            dumpFile.delete()
            assertNull(cache.loadIl2CppFastDump("abc123"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reusesDexInventoryForSameArtifact() {
        val root = Files.createTempDirectory("modkit-dex-cache").toFile()
        try {
            val inventory = DexInventoryResult(
                records = listOf(
                    DexInventoryRecord(
                        container = "base.apk",
                        entryPath = "classes.dex",
                        size = 160,
                        version = "039",
                        declaredFileSize = 160,
                        headerSize = 112,
                        standardEndian = true,
                        stringIdsCount = 2,
                        typeIdsCount = 1,
                        protoIdsCount = 1,
                        fieldIdsCount = 1,
                        methodIdsCount = 1,
                        classDefsCount = 0,
                        dataSize = 8,
                        warnings = emptyList(),
                    ),
                ),
                warnings = emptyList(),
            )
            val cache = EngineResultCache(File(root, "cache"))

            assertTrue(cache.saveDexInventory("dex-sha", inventory))
            assertEquals(inventory, cache.loadDexInventory("dex-sha"))
            assertNull(cache.loadDexInventory("other-sha"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reusesBinaryBindingWithExactEvidence() {
        val root = Files.createTempDirectory("modkit-binding-cache").toFile()
        try {
            val binding = Il2CppBinaryBindingResult(
                evidence = listOf(
                    Il2CppBinaryEvidence(
                        libraryEntry = "base.apk:lib/arm64-v8a/libil2cpp.so",
                        machine = 183,
                        pointerSize = 8,
                        relativeRelocationCount = 0,
                        codeRegistrationVirtualAddress = 0x1000,
                        metadataRegistrationVirtualAddress = 0x2000,
                        codegenRegisterVirtualAddress = 0x3000,
                        moduleArrayDiscovery = "CODE_REGISTRATION_PAIR_0",
                        modules = listOf(
                            Il2CppCodeGenModuleEvidence(
                                moduleName = "Assembly-CSharp.dll",
                                moduleVirtualAddress = 0x4000,
                                methodPointerCount = 1,
                                methodPointersVirtualAddress = 0x5000,
                                sampledPointers = 1,
                                executablePointers = 1,
                            ),
                        ),
                        bindings = listOf(
                            Il2CppMethodBinaryBinding(
                                methodIndex = 0,
                                managedIdentity = "Game.Player.Hit",
                                metadataToken = 0x06000001,
                                imageName = "Assembly-CSharp.dll",
                                moduleName = "Assembly-CSharp.dll",
                                slotIndex = 0,
                                functionVirtualAddress = 0x6000,
                                functionFileOffset = 0x800,
                            ),
                        ),
                        blockers = emptyList(),
                    ),
                ),
                exactBindingCount = 1,
                warnings = emptyList(),
            )
            val cache = EngineResultCache(File(root, "cache"))

            val indexed = withVerifiedDiskIndex(root, binding)
            assertTrue(cache.saveIl2CppBinaryBinding("artifact-sha", indexed))
            val restored = cache.loadIl2CppBinaryBinding("artifact-sha")
            assertEquals(indexed, restored)
            assertTrue(restored?.exactBindingAvailable == true)
            assertNull(cache.loadIl2CppBinaryBinding("other-artifact"))
            requireNotNull(indexed.evidence.single().bindingIndex).let {
                File(it.path).delete()
            }
            assertNull(
                "A deleted disk binding index must force a fresh analysis",
                cache.loadIl2CppBinaryBinding("artifact-sha"),
            )
        } finally {
            root.deleteRecursively()
        }
    }



    @Test
    fun reusesArtifactIndexOnlyForSameArtifactKey() {
        val root = Files.createTempDirectory("modkit-index-cache").toFile()
        try {
            val index = ArtifactIndex(
                artifactSha256 = "index-sha",
                sources = listOf(ArtifactSource("base.apk", 123, "source-sha")),
                entries = listOf(
                    ArtifactEntry(
                        container = "base.apk",
                        path = "classes.dex",
                        size = 42,
                        format = BinaryFormat.DEX,
                        tags = setOf("dex_candidate", "dex_valid"),
                    ),
                ),
                detectedAbis = setOf("arm64-v8a"),
                runtimeProfiles = listOf(
                    RuntimeProfile(
                        runtimeId = "android_dex",
                        title = "Android DEX",
                        status = DetectionStatus.CONFIRMED,
                        confidence = DetectionConfidence.HIGH,
                        evidence = listOf("base.apk:classes.dex"),
                    ),
                ),
            )
            val cache = EngineResultCache(File(root, "cache"))

            assertTrue(cache.saveArtifactIndex("index-sha", index))
            assertEquals(index, cache.loadArtifactIndex("index-sha"))
            assertNull(cache.loadArtifactIndex("other-sha"))
        } finally {
            root.deleteRecursively()
        }
    }



    @Test
    fun restorePartialResultRebuildsEvidenceGraphFromCompletedCacheStages() {
        val root = Files.createTempDirectory("modkit-partial-restore").toFile()
        try {
            val artifactSha =
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            val dumpFile = File(root, "dump.cs").apply { writeText("// restored") }
            val index = ArtifactIndex(
                artifactSha256 = artifactSha,
                sources = listOf(ArtifactSource("base.apk", 321, artifactSha)),
                entries = emptyList(),
                detectedAbis = setOf("arm64-v8a"),
                runtimeProfiles = listOf(
                    RuntimeProfile(
                        runtimeId = "unity_il2cpp",
                        title = "Unity IL2CPP",
                        status = DetectionStatus.CONFIRMED,
                        confidence = DetectionConfidence.HIGH,
                        evidence = listOf(
                            "base.apk:assets/bin/Data/Managed/Metadata/global-metadata.dat",
                            "base.apk:lib/arm64-v8a/libil2cpp.so",
                        ),
                    ),
                ),
            )
            val dump = Il2CppFastDumpResult(
                metadataEntry = "base.apk:global-metadata.dat",
                libraryEntries = listOf("base.apk:lib/arm64-v8a/libil2cpp.so"),
                metadata = metadataModel(),
                dumpFilePath = dumpFile.absolutePath,
                preview = "restored",
                warnings = emptyList(),
            )
            val binary = Il2CppBinaryBindingResult(
                evidence = listOf(
                    Il2CppBinaryEvidence(
                        libraryEntry = "base.apk:lib/arm64-v8a/libil2cpp.so",
                        machine = 183,
                        pointerSize = 8,
                        relativeRelocationCount = 0,
                        codeRegistrationVirtualAddress = 0x1000,
                        metadataRegistrationVirtualAddress = 0x2000,
                        codegenRegisterVirtualAddress = 0x3000,
                        moduleArrayDiscovery = "CODE_REGISTRATION_PAIR_0",
                        modules = listOf(
                            Il2CppCodeGenModuleEvidence(
                                moduleName = "Assembly-CSharp.dll",
                                moduleVirtualAddress = 0x4000,
                                methodPointerCount = 1,
                                methodPointersVirtualAddress = 0x5000,
                                sampledPointers = 1,
                                executablePointers = 1,
                            ),
                        ),
                        bindings = listOf(
                            Il2CppMethodBinaryBinding(
                                methodIndex = 0,
                                managedIdentity = "Game.Player.Hit",
                                metadataToken = 0x06000001,
                                imageName = "Assembly-CSharp.dll",
                                moduleName = "Assembly-CSharp.dll",
                                slotIndex = 0,
                                functionVirtualAddress = 0x6000,
                                functionFileOffset = 0x800,
                            ),
                        ),
                        blockers = emptyList(),
                    ),
                ),
                exactBindingCount = 1,
                warnings = emptyList(),
            )
            val cache = EngineResultCache(File(root, "cache"))

            assertTrue(cache.saveArtifactIndex(artifactSha, index))
            assertTrue(cache.saveIl2CppFastDump(artifactSha, dump))
            assertTrue(cache.saveIl2CppBinaryBinding(
                artifactSha, withVerifiedDiskIndex(root, binary),
            ))

            val restored = requireNotNull(cache.restorePartialResult(artifactSha))
            assertEquals(artifactSha, restored.index.artifactSha256)
            assertEquals(
                setOf(
                    EngineResultCache.ARTIFACT_INDEX_ENGINE_ID,
                    EngineResultCache.IL2CPP_FAST_DUMP_ENGINE_ID,
                    EngineResultCache.IL2CPP_BINARY_BINDING_ENGINE_ID,
                ),
                restored.engineCacheHits,
            )
            assertEquals(1, restored.evidenceGraph?.targets?.size)
            assertEquals(
                UserFindingStatus.CONFIRMED,
                restored.evidenceGraph?.targets?.single()?.userStatus,
            )
            assertTrue(restored.confirmationQueue.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withVerifiedDiskIndex(
        root: File,
        result: Il2CppBinaryBindingResult,
    ): Il2CppBinaryBindingResult {
        val live = object : CancellationSignal {
            override fun isCancelled() = false
        }
        return result.copy(evidence = result.evidence.mapIndexed { i, item ->
            val index = Il2CppDiskBindingIndexWriter(
                File(root, "exact-binding-$i.idx"), 1, live,
            ).use { writer ->
                writer.add(
                    methodIndex = 0,
                    slotIndex = 0,
                    moduleIndex = 0,
                    functionVa = 0x6000,
                    fileOffset = 0x800,
                    returnKind = Il2CppNativeReturnKind.UNKNOWN,
                )
                writer.finish()
            }
            item.copy(bindingIndex = index)
        })
    }

    private fun metadataModel() = Il2CppMetadataModel(
        sizeBytes = 1024,
        magicValid = true,
        metadataVersion = 29,
        layoutProfile = "IL2CPP_METADATA_V27_V30",
        tableRanges = listOf(Il2CppTableRange("methods", 100, 32)),
        declaredTypeCount = 1,
        declaredMethodCount = 1,
        declaredFieldCount = 0,
        declaredImageCount = 1,
        images = listOf(
            Il2CppImageDefinition(
                index = 0,
                name = "Assembly-CSharp.dll",
                assemblyIndex = 0,
                typeStart = 0,
                typeCount = 1,
                token = 1,
            ),
        ),
        types = listOf(
            Il2CppTypeDefinition(
                index = 0,
                namespace = "Game",
                name = "Player",
                fullName = "Game.Player",
                methodStart = 0,
                methodCount = 1,
                fieldStart = 0,
                fieldCount = 0,
                token = 0x02000001,
            ),
        ),
        methods = listOf(
            Il2CppMethodDefinition(
                index = 0,
                declaringTypeIndex = 0,
                declaringType = "Game.Player",
                name = "Hit",
                parameterCount = 0,
                token = 0x06000001,
                flags = 6,
            ),
        ),
        fields = emptyList(),
        structuredSupported = true,
        truncated = false,
        warnings = emptyList(),
    )
}
