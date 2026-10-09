package io.github.ffenuss.modkit.space

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedSpaceTest {
    private val old = "03498720af5c326fc3a399d7c96aed5fdea2f378ec1799580bb119e1bcbc4b5f"
    private val replacement = "b44a6c2b53689c16cb08da3ca22e4aba20d9d0d1ecbc26df13e15c04b6665073"

    @Test fun onlyExplicitSingleSignersAreTrusted() {
        assertTrue(TrustedSpace.acceptsSignerFingerprints(listOf(old)))
        assertTrue(TrustedSpace.acceptsSignerFingerprints(listOf(replacement)))
        for (fingerprints in listOf(emptyList(), listOf("a".repeat(64)),
            listOf(old, replacement), listOf(replacement, replacement), listOf(replacement.uppercase()))) {
            assertFalse(TrustedSpace.acceptsSignerFingerprints(fingerprints))
        }
    }
}
