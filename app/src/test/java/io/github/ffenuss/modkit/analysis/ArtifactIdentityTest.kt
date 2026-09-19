package io.github.ffenuss.modkit.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArtifactIdentityTest {
    @Test
    fun singleSourceIdentityIsTheSourceSha() {
        val source = ArtifactSource(
            displayName = "base.apk",
            size = 123,
            sha256 = "A".repeat(64),
        )

        assertEquals("a".repeat(64), ArtifactIdentity.combine(listOf(source)))
    }

    @Test
    fun splitSetIdentityIsOrderIndependentButNameAndSizeBound() {
        val base = ArtifactSource("base.apk", 100, "1".repeat(64))
        val split = ArtifactSource("split_config.arm64_v8a.apk", 50, "2".repeat(64))

        val first = ArtifactIdentity.combine(listOf(base, split))
        val reordered = ArtifactIdentity.combine(listOf(split, base))
        assertEquals(first, reordered)

        val renamed = ArtifactIdentity.combine(
            listOf(base, split.copy(displayName = "other.apk")),
        )
        val resized = ArtifactIdentity.combine(
            listOf(base, split.copy(size = 51)),
        )
        assertNotEquals(first, renamed)
        assertNotEquals(first, resized)
    }
}
