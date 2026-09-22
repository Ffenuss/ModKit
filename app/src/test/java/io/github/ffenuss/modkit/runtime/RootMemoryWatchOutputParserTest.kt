package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootMemoryWatchOutputParserTest {
    @Test
    fun parsesHardwareWatchSummaryAndRanksHits() {
        val parsed =
            RootMemoryWatchOutputParser.parse(
                """
                MODKIT_ROOT_WATCH_V1
                INFO	THREADS	23
                INFO	WATCH	0x1000	4	5000
                HIT	0x70001004	2	321	0x1000
                HIT	0x70002008	7	322	0x1000
                END	2	9	0
                """.trimIndent(),
            )

        assertEquals(
            23,
            parsed.watchedThreads,
        )
        assertEquals(
            9,
            parsed.totalTraps,
        )
        assertFalse(
            parsed.truncated,
        )
        assertTrue(
            parsed.errors.isEmpty(),
        )
        assertEquals(
            2,
            parsed.hits.size,
        )
        assertEquals(
            0x70002008L,
            parsed.hits.first().pc,
        )
        assertEquals(
            7,
            parsed.hits.first().count,
        )
    }

    @Test
    fun missingMarkerAndHelperErrorAreRejected() {
        val parsed =
            RootMemoryWatchOutputParser.parse(
                "ERROR	PTRACE	Denied\n",
            )

        assertEquals(
            2,
            parsed.errors.size,
        )
        assertTrue(
            parsed.errors.any {
                it.contains(
                    "PTRACE",
                )
            },
        )
        assertTrue(
            parsed.errors.any {
                it.contains(
                    "marker",
                    ignoreCase = true,
                )
            },
        )
    }
}
