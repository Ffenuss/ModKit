package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFastValueScanCoordinatorTest {
    @Test
    fun parsesBoundedNativeScanOutput() {
        val parsed =
            RootFastValueScanCoordinator
                .parseOutput(
                    """
                    MODKIT_ROOT_FAST_SCAN_V1
                    HIT\t0x1000\t0x1c
                    HIT\t0x2000\t0x1c
                    END\t2\t134217728\t31\t1
                    """.trimIndent(),
                )

        assertTrue(
            parsed.errors.isEmpty(),
        )
        assertEquals(
            listOf(
                0x1000L to 0x1cL,
                0x2000L to 0x1cL,
            ),
            parsed.hits,
        )
        assertEquals(
            134217728L,
            parsed.stats.scannedBytes,
        )
        assertEquals(
            31,
            parsed.stats.scannedRegions,
        )
        assertTrue(
            parsed.stats.helperTruncated,
        )
    }

    @Test
    fun missingMarkerFailsParserContract() {
        val parsed =
            RootFastValueScanCoordinator
                .parseOutput(
                    "END\t0\t0\t0\t0",
                )

        assertFalse(
            parsed.errors.isEmpty(),
        )
    }
}
