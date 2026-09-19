package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.ArtifactEntry
import io.github.ffenuss.modkit.analysis.BinaryFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeNativeLookupCoordinatorTest {
    @Test
    fun mappedArm64ModuleSelectsExactArm64StaticElf() {
        val inventory = listOf(
            RuntimeMappedModule(
                path =
                    "/data/app/pkg/lib/arm64/libsample.so",
                fileName = MODULE,
                device = "103:02",
                inode = 42,
                regionCount = 2,
                executableRegionCount = 1,
                fileZeroBaseCandidates =
                    setOf(0x70000000),
                staticArtifactMatches = listOf(
                    "base.apk:lib/arm64-v8a/$MODULE",
                    "base.apk:lib/x86_64/$MODULE",
                ),
            ),
        )
        val entries = listOf(
            entry("arm64-v8a"),
            entry("x86_64"),
        )

        val selected =
            RepackedRuntimeNativeLookupCoordinator
                .selectStaticModule(
                    moduleName = MODULE,
                    inventory = inventory,
                    artifactEntries = entries,
                )

        assertEquals("arm64-v8a", selected.abi)
    }

    @Test
    fun multipleRuntimeFileIdentitiesFailClosed() {
        val inventory = listOf(
            mapped("/data/app/a/lib/arm64/$MODULE", 1),
            mapped("/data/app/b/lib/arm64/$MODULE", 2),
        )

        val failure = runCatching {
            RepackedRuntimeNativeLookupCoordinator
                .selectStaticModule(
                    moduleName = MODULE,
                    inventory = inventory,
                    artifactEntries =
                        listOf(entry("arm64-v8a")),
                )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "multiple runtime file identities",
            ),
        )
    }

    @Test
    fun ambiguousStaticElfWithoutRuntimeAbiFailsClosed() {
        val inventory = listOf(
            RuntimeMappedModule(
                path = "/data/app/pkg/$MODULE",
                fileName = MODULE,
                device = "103:02",
                inode = 42,
                regionCount = 2,
                executableRegionCount = 1,
                fileZeroBaseCandidates = setOf(0x70000000),
                staticArtifactMatches = listOf(
                    "base.apk:lib/arm64-v8a/$MODULE",
                    "base.apk:lib/x86_64/$MODULE",
                ),
            ),
        )

        val failure = runCatching {
            RepackedRuntimeNativeLookupCoordinator
                .selectStaticModule(
                    moduleName = MODULE,
                    inventory = inventory,
                    artifactEntries = listOf(
                        entry("arm64-v8a"),
                        entry("x86_64"),
                    ),
                )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "ambiguous",
                ignoreCase = true,
            ),
        )
    }

    private fun mapped(
        path: String,
        inode: Long,
    ) = RuntimeMappedModule(
        path = path,
        fileName = MODULE,
        device = "103:02",
        inode = inode,
        regionCount = 2,
        executableRegionCount = 1,
        fileZeroBaseCandidates = setOf(0x70000000),
        staticArtifactMatches = listOf(
            "base.apk:lib/arm64-v8a/$MODULE",
        ),
    )

    private fun entry(
        abi: String,
    ) = ArtifactEntry(
        container = "base.apk",
        path = "lib/$abi/$MODULE",
        size = 1024,
        compressedSize = 512,
        crc32 = 1,
        format = BinaryFormat.ELF,
        abi = abi,
    )

    companion object {
        private const val MODULE = "libsample.so"
    }
}
