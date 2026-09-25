package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRouterTest {
    @Test
    fun il2cppRoutesFastDumpWithoutRunningUnrelatedBackends() {
        val index = ArtifactIndex(
            artifactSha256 = "sha",
            sources = listOf(ArtifactSource("app.apk", 1, "sha")),
            entries = listOf(
                ArtifactEntry(
                    container = "base.apk",
                    path = "lib/arm64-v8a/libil2cpp.so",
                    size = 256,
                    format = BinaryFormat.ELF,
                    abi = "arm64-v8a",
                    tags = setOf("il2cpp_binary", "elf_valid"),
                ),
                ArtifactEntry(
                    container = "base.apk",
                    path = "assets/bin/Data/Managed/Metadata/global-metadata.dat",
                    size = 256,
                    format = BinaryFormat.IL2CPP_METADATA,
                    tags = setOf("il2cpp_metadata", "il2cpp_metadata_valid"),
                ),
            ),
            runtimeProfiles = listOf(
                RuntimeProfile(
                    runtimeId = "unity_il2cpp",
                    title = "Unity / IL2CPP",
                    status = DetectionStatus.CONFIRMED,
                    confidence = DetectionConfidence.HIGH,
                    evidence = listOf("metadata", "libil2cpp"),
                ),
            ),
        )

        val plan = EngineRouter.plan(index)
        assertTrue(plan.targeted.any { it.id == "il2cpp.fast-dump" && it.availableNow })
        assertFalse(plan.engines.any { it.id == "flutter.dart-aot" })
        assertTrue(plan.engines.first { it.id == "il2cpp.fast-dump" }.scheduleClass == EngineScheduleClass.TARGETED)
        assertTrue(plan.confirmation.any { it.id == "il2cpp.codegen-bind" && it.availableNow })
        assertFalse(plan.missingCapabilities.any { "IL2CPP exact CodeGen" in it })
    }
    @Test
    fun binaryWithoutValidatedMetadataDoesNotScheduleIl2CppStages() {
        val entries = listOf(
            ArtifactEntry(
                container = "base.apk",
                path = "lib/arm64-v8a/libil2cpp.so",
                size = 256,
                format = BinaryFormat.ELF,
                abi = "arm64-v8a",
                tags = setOf("il2cpp_binary", "elf_valid"),
            ),
        )
        val index = ArtifactIndex(
            artifactSha256 = "sha",
            sources = listOf(ArtifactSource("base.apk", 256, "sha")),
            entries = entries,
            runtimeProfiles = RuntimeFingerprintProfiler.profile(entries),
        )
        val plan = EngineRouter.plan(index)
        assertFalse(plan.engines.any { it.id == "il2cpp.fast-dump" })
        assertFalse(plan.engines.any { it.id == "il2cpp.codegen-bind" })
        assertTrue(plan.engines.any { it.id == "elf.universal-inventory" && it.availableNow })
        assertTrue(plan.missingCapabilities.any {
            "IL2CPP:" in it && "global-metadata.dat" in it
        })
    }

    @Test
    fun staleConfirmedProfileCannotScheduleAnInvalidMetadataEntry() {
        val entries = listOf(
            ArtifactEntry(
                container = "base.apk",
                path = "lib/arm64-v8a/libil2cpp.so",
                size = 256,
                format = BinaryFormat.ELF,
                tags = setOf("il2cpp_binary", "elf_valid"),
            ),
            ArtifactEntry(
                container = "base.apk",
                path = "assets/bin/Data/Managed/Metadata/global-metadata.dat",
                size = 256,
                format = BinaryFormat.UNKNOWN,
                tags = setOf("il2cpp_metadata"),
            ),
        )
        val index = ArtifactIndex(
            artifactSha256 = "sha",
            sources = listOf(ArtifactSource("base.apk", 512, "sha")),
            entries = entries,
            runtimeProfiles = listOf(
                RuntimeProfile(
                    runtimeId = "unity_il2cpp",
                    title = "Unity / IL2CPP",
                    status = DetectionStatus.CONFIRMED,
                    confidence = DetectionConfidence.HIGH,
                    evidence = listOf("binary", "invalid metadata"),
                ),
            ),
        )
        val plan = EngineRouter.plan(index)
        assertFalse(plan.engines.any { it.id.startsWith("il2cpp.") })
        assertTrue(plan.missingCapabilities.any { "global-metadata.dat" in it })
    }

    @Test
    fun unrealPakGetsRealStructuralBackendButNotUnprovenGameplayPatches() {
        val pak = ArtifactEntry(
            container = "base.apk",
            path = "assets/Paks/Game.pak",
            size = 4096L,
            tags = setOf("unreal_container_candidate"),
        )
        val index = ArtifactIndex(
            artifactSha256 = "sha",
            sources = listOf(ArtifactSource("base.apk", 4096L, "sha")),
            entries = listOf(pak),
            runtimeProfiles = RuntimeFingerprintProfiler.profile(listOf(pak)),
        )
        val plan = EngineRouter.plan(index)
        assertTrue(plan.targeted.any {
            it.id == UnrealAssetInventoryEngine.ID && it.availableNow
        })
        assertFalse(plan.targeted.first { it.id == "unreal.deep" }.availableNow)
        assertTrue(plan.missingCapabilities.any { "Blueprint" in it })
        assertTrue(RoutedEngineScheduler.supportsEngine(UnrealAssetInventoryEngine.ID))
    }

}
