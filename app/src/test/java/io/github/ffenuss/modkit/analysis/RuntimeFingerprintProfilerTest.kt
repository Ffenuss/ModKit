package io.github.ffenuss.modkit.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeFingerprintProfilerTest {
    @Test
    fun preservesMultiLabelProfiles() {
        val entries = listOf(
            ArtifactEntry(
                container = "app.apk",
                path = "classes.dex",
                size = 8,
                format = BinaryFormat.DEX,
                tags = setOf("dex_candidate", "dex_valid"),
            ),
            ArtifactEntry(
                container = "app.apk",
                path = "lib/arm64-v8a/libapp.so",
                size = 64,
                format = BinaryFormat.ELF,
                abi = "arm64-v8a",
                tags = setOf("native_candidate", "elf_valid", "flutter_app"),
            ),
            ArtifactEntry(
                container = "app.apk",
                path = "lib/arm64-v8a/libflutter.so",
                size = 64,
                format = BinaryFormat.ELF,
                abi = "arm64-v8a",
                tags = setOf("native_candidate", "elf_valid", "flutter_engine"),
            ),
        )

        val profiles = RuntimeFingerprintProfiler.profile(entries)
        assertTrue(profiles.any { it.runtimeId == "android_dex" })
        assertTrue(profiles.any { it.runtimeId == "native_elf" })
        assertTrue(profiles.any { it.runtimeId == "flutter" })
        assertEquals(3, profiles.map { it.runtimeId }.toSet().size)
    }
}
