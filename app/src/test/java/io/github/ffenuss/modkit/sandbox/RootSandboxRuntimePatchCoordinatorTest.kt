package io.github.ffenuss.modkit.sandbox

import io.github.ffenuss.modkit.analysis.ElfLoadSegment
import io.github.ffenuss.modkit.runtime.ProcMapRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RootSandboxRuntimePatchCoordinatorTest {
    @Test
    fun resolvesExecutableFileOffsetThroughConfirmedLoadBias() {
        val modification =
            SandboxModification(
                id = "damage",
                title = "Damage",
                category = "DAMAGE",
                moduleName = "libgame.so",
                fileOffset = 0x1100L,
                replacementHex = "c0035fd6",
                abi = "arm64-v8a",
                runtimeId = "native",
            )
        val segments =
            listOf(
                ElfLoadSegment(
                    virtualAddress = 0x0L,
                    memorySize = 0x1000L,
                    fileOffset = 0x0L,
                    fileSize = 0x1000L,
                    executable = false,
                ),
                ElfLoadSegment(
                    virtualAddress = 0x1000L,
                    memorySize = 0x2000L,
                    fileOffset = 0x1000L,
                    fileSize = 0x2000L,
                    executable = true,
                ),
            )
        val regions =
            listOf(
                ProcMapRegion(
                    start = 0x70000000L,
                    endExclusive = 0x70001000L,
                    permissions = "r--p",
                    fileOffset = 0x0L,
                    device = "fd:00",
                    inode = 42L,
                    path = "/data/app/libgame.so",
                ),
                ProcMapRegion(
                    start = 0x70001000L,
                    endExclusive = 0x70003000L,
                    permissions = "r-xp",
                    fileOffset = 0x1000L,
                    device = "fd:00",
                    inode = 42L,
                    path = "/data/app/libgame.so",
                ),
            )
        val original =
            byteArrayOf(
                0x1f,
                0x20,
                0x03,
                0xd5.toByte(),
            )
        val replacement =
            byteArrayOf(
                0xc0.toByte(),
                0x03,
                0x5f,
                0xd6.toByte(),
            )

        val plan =
            RootSandboxRuntimePatchCoordinator
                .buildPlanFromSegments(
                    modification = modification,
                    loadSegments = segments,
                    regions = regions,
                    originalBytes = original,
                    replacementBytes = replacement,
                )

        assertEquals(
            0x1100L,
            plan.binaryVirtualAddress,
        )
        assertEquals(
            0x70001100L,
            plan.runtimeAddress,
        )
        assertArrayEquals(
            original,
            plan.originalBytes,
        )
        assertArrayEquals(
            replacement,
            plan.replacementBytes,
        )
    }
}
