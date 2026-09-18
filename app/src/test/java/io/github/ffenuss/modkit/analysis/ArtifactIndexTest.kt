package io.github.ffenuss.modkit.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactIndexTest {
    @Test
    fun preservesMultiRuntimeProfiles() {
        val profiles = listOf(
            RuntimeProfile(
                runtimeId = "android_dex",
                title = "Android DEX",
                status = DetectionStatus.CONFIRMED,
                confidence = DetectionConfidence.HIGH,
                evidence = listOf("app.apk:classes.dex"),
            ),
            RuntimeProfile(
                runtimeId = "native_elf",
                title = "Native ELF",
                status = DetectionStatus.CONFIRMED,
                confidence = DetectionConfidence.HIGH,
                evidence = listOf("app.apk:lib/arm64-v8a/libx.so"),
            ),
        )
        val index = ArtifactIndex(
            artifactSha256 = "abc",
            sources = listOf(ArtifactSource("app.apk", 123, "abc")),
            entries = emptyList(),
            runtimeProfiles = profiles,
        )
        assertEquals(2, index.runtimeProfiles.size)
        assertTrue(index.runtimeProfiles.any { it.runtimeId == "native_elf" })
    }
}
