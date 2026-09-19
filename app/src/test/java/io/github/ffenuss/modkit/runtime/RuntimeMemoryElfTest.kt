package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMemoryElfTest {
    @Test
    fun validatesBoundedElf64WhenExecutablePtLoadMatchesMappingOffset() {
        val image = elf64(
            executableOffset = 0x1000,
            programHeaderCount = 1,
        )
        val reader = ByteArrayReader(BASE, image)
        val candidate = candidate(fileOffset = 0x1000)

        val evidence = RuntimeMemoryElfValidator.validate(
            candidate = candidate,
            reader = reader,
            cancellation = AtomicCancellationSignal(),
        )

        assertEquals(
            RuntimeMemoryElfValidationStatus.VALIDATED,
            evidence.status,
        )
        assertTrue(evidence.validated)
        assertEquals(true, evidence.is64Bit)
        assertEquals(183, evidence.machine)
        assertEquals(3, evidence.elfType)
        assertEquals(1, evidence.loadSegmentCount)
        assertEquals(1, evidence.executableLoadSegmentCount)
        assertTrue(evidence.executableCandidateSegmentMatched)
        assertTrue(evidence.bytesRead <= 64 + 56)
        assertTrue(reader.maxRequestedBytes <= 64)
    }

    @Test
    fun elfMagicWithoutExecutablePtLoadOffsetMatchRemainsBlocked() {
        val image = elf64(
            executableOffset = 0x2000,
            programHeaderCount = 1,
        )
        val evidence = RuntimeMemoryElfValidator.validate(
            candidate = candidate(fileOffset = 0x1000),
            reader = ByteArrayReader(BASE, image),
            cancellation = AtomicCancellationSignal(),
        )

        assertEquals(
            RuntimeMemoryElfValidationStatus.BLOCKED,
            evidence.status,
        )
        assertFalse(evidence.validated)
        assertTrue(
            evidence.blockers.any {
                "file offset" in it
            },
        )
    }

    @Test
    fun missingElfMagicIsNotPromotedToMemoryElf() {
        val bytes = ByteArray(256)
        val evidence = RuntimeMemoryElfValidator.validate(
            candidate = candidate(fileOffset = 0),
            reader = ByteArrayReader(BASE, bytes),
            cancellation = AtomicCancellationSignal(),
        )

        assertEquals(
            RuntimeMemoryElfValidationStatus.NOT_ELF,
            evidence.status,
        )
        assertFalse(evidence.validated)
    }

    @Test
    fun unreadableHeaderIsRetainedAsDiagnosticNotNegativeProof() {
        val evidence = RuntimeMemoryElfValidator.validate(
            candidate = candidate(fileOffset = 0),
            reader = RuntimeMemoryReader { _, _, _ -> null },
            cancellation = AtomicCancellationSignal(),
        )

        assertEquals(
            RuntimeMemoryElfValidationStatus.NOT_READABLE,
            evidence.status,
        )
        assertFalse(evidence.validated)
        assertTrue(evidence.blockers.isNotEmpty())
    }

    @Test
    fun oversizedProgramHeaderTableIsRejectedBeforeSecondRead() {
        val image = elf64(
            executableOffset = 0,
            programHeaderCount = 1024,
            programHeaderEntrySize = 256,
        )
        val reader = ByteArrayReader(BASE, image)

        val evidence = RuntimeMemoryElfValidator.validate(
            candidate = candidate(fileOffset = 0),
            reader = reader,
            cancellation = AtomicCancellationSignal(),
        )

        assertEquals(
            RuntimeMemoryElfValidationStatus.BLOCKED,
            evidence.status,
        )
        assertFalse(evidence.validated)
        assertEquals(1, reader.readCount)
    }

    private fun candidate(
        fileOffset: Long,
    ) = RuntimeMemoryMappingCandidate(
        start = BASE + fileOffset,
        endExclusive = BASE + fileOffset + 0x1000,
        permissions = "r-xp",
        path = "/memfd:runtime-image",
        fileOffset = fileOffset,
        fileZeroAddressCandidate = BASE,
        reason = "Executable mapping is backed by memfd.",
    )

    private fun elf64(
        executableOffset: Long,
        programHeaderCount: Int,
        programHeaderEntrySize: Int = 56,
    ): ByteArray {
        val bytes = ByteArray(512)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1

        buffer.putShort(16, 3)
        buffer.putShort(18, 183.toShort())
        buffer.putLong(32, 64L)
        buffer.putShort(54, programHeaderEntrySize.toShort())
        buffer.putShort(56, programHeaderCount.toShort())

        if (programHeaderCount > 0 && programHeaderEntrySize >= 56) {
            val ph = 64
            buffer.putInt(ph, 1)
            buffer.putInt(ph + 4, 5)
            buffer.putLong(ph + 8, executableOffset)
            buffer.putLong(ph + 16, executableOffset)
            buffer.putLong(ph + 24, executableOffset)
            buffer.putLong(ph + 32, 0x1000)
            buffer.putLong(ph + 40, 0x1000)
            buffer.putLong(ph + 48, 0x1000)
        }
        return bytes
    }

    private class ByteArrayReader(
        private val base: Long,
        private val bytes: ByteArray,
    ) : RuntimeMemoryReader {
        var readCount: Int = 0
            private set
        var maxRequestedBytes: Int = 0
            private set

        override fun read(
            address: Long,
            size: Int,
            cancellation: io.github.ffenuss.modkit.analysis.CancellationSignal,
        ): ByteArray? {
            readCount++
            maxRequestedBytes = maxOf(maxRequestedBytes, size)
            val offset = address - base
            if (offset < 0L || offset > Int.MAX_VALUE) return null
            val start = offset.toInt()
            if (start > bytes.size || size > bytes.size - start) return null
            return bytes.copyOfRange(start, start + size)
        }
    }

    companion object {
        private const val BASE = 0x70000000L
    }
}
