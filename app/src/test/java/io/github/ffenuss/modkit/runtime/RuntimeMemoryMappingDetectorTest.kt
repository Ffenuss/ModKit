package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMemoryMappingDetectorTest {
    @Test
    fun findsOnlyExecutableAnonymousMemfdAshmemOrDeletedMappings() {
        val regions = ProcMapsParser.parse(
            """
            1000-2000 r-xp 00000000 00:00 0 [anon:jit-code-cache]
            3000-4000 rw-p 00000000 00:00 0 [anon:data]
            5000-6000 r-xp 00000000 00:00 0 /memfd:jit-cache
            7000-8000 r-xp 00000000 00:00 0 /dev/ashmem/dalvik
            9000-a000 r-xp 00000000 103:02 42 /data/app/pkg/libx.so (deleted)
            b000-c000 r-xp 00000000 103:02 43 /data/app/pkg/libnormal.so
            """.trimIndent(),
        )

        val candidates = RuntimeMemoryMappingDetector.candidates(regions)

        assertEquals(4, candidates.size)
        assertTrue(candidates.any { it.path == "[anon:jit-code-cache]" })
        assertTrue(candidates.any { it.path == "/memfd:jit-cache" })
        assertTrue(candidates.any { it.path == "/dev/ashmem/dalvik" })
        assertTrue(
            candidates.any {
                it.path == "/data/app/pkg/libx.so (deleted)"
            },
        )
    }
}
