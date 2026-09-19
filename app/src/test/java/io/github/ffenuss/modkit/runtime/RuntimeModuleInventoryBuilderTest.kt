package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.ArtifactEntry
import io.github.ffenuss.modkit.analysis.BinaryFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeModuleInventoryBuilderTest {
    @Test
    fun distinguishesStaticArtifactModuleFromRuntimeOnlyModule() {
        val regions = ProcMapsParser.parse(
            """
            70000000-70001000 r--p 00000000 103:02 41 /data/app/pkg/lib/arm64/libknown.so
            70001000-70010000 r-xp 00001000 103:02 41 /data/app/pkg/lib/arm64/libknown.so
            71000000-71001000 r--p 00000000 103:02 42 /data/user/0/pkg/files/liblate.so
            71001000-71010000 r-xp 00001000 103:02 42 /data/user/0/pkg/files/liblate.so
            72000000-72001000 rw-p 00000000 00:00 0 [anon:ignored]
            """.trimIndent(),
        )
        val artifactEntries = listOf(
            ArtifactEntry(
                container = "base.apk",
                path = "lib/arm64-v8a/libknown.so",
                size = 123,
                format = BinaryFormat.ELF,
                abi = "arm64-v8a",
            ),
        )

        val modules = RuntimeModuleInventoryBuilder.build(
            regions = regions,
            artifactEntries = artifactEntries,
        )

        assertEquals(2, modules.size)
        val known = modules.single { it.fileName == "libknown.so" }
        assertTrue(known.presentInStaticArtifact)
        assertFalse(known.runtimeOnlyRelativeToArtifact)
        assertEquals(
            listOf("base.apk:lib/arm64-v8a/libknown.so"),
            known.staticArtifactMatches,
        )

        val late = modules.single { it.fileName == "liblate.so" }
        assertFalse(late.presentInStaticArtifact)
        assertTrue(late.runtimeOnlyRelativeToArtifact)
        assertEquals(1, late.executableRegionCount)
    }

    @Test
    fun samePathDifferentInodesRemainSeparateRuntimeModules() {
        val regions = ProcMapsParser.parse(
            """
            70000000-70001000 r-xp 00000000 103:02 41 /data/app/pkg/libsame.so
            71000000-71001000 r-xp 00000000 103:02 42 /data/app/pkg/libsame.so
            """.trimIndent(),
        )

        val modules = RuntimeModuleInventoryBuilder.build(
            regions = regions,
            artifactEntries = emptyList(),
        )

        assertEquals(2, modules.size)
        assertEquals(setOf(41L, 42L), modules.map { it.inode }.toSet())
    }
}
