package io.github.ffenuss.modkit.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactIndexTest {
    @Test
    fun preservesMultiRuntimeTags() {
        val index = ArtifactIndex(
            artifactSha256 = "abc",
            entries = emptyList(),
            runtimeTags = setOf("android_dex", "native_elf", "unity_il2cpp"),
        )
        assertEquals(3, index.runtimeTags.size)
        assertTrue("unity_il2cpp" in index.runtimeTags)
    }
}
