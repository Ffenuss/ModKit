package io.github.ffenuss.modkit.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppMetadataReaderTest {
    @Test
    fun defaultLargeGameLimitsExceedLegacyHundredThousandCap() {
        val limits = Il2CppMetadataReader.Limits()

        assertTrue(limits.maxMethods > 100_000)
        assertTrue(limits.maxFields > 100_000)
        assertTrue(limits.maxTypes > 30_000)
    }

    @Test
    fun reconstructsV29TypeMethodAndField() {
        val bytes = fixture(version = 29)
        val file = Files.createTempFile("modkit-il2cpp", ".dat").toFile()
        file.writeBytes(bytes)
        try {
            val model = Il2CppMetadataReader.read(
                file = file,
                cancellation = neverCancelled(),
                progress = ProgressSink { },
            )
            assertTrue(model.magicValid)
            assertTrue(model.structuredSupported)
            assertFalse(model.truncated)
            assertEquals(29, model.metadataVersion)
            assertEquals("Game.Player", model.types.single().fullName)
            assertEquals("Hit", model.methods.single().name)
            assertEquals("health", model.fields.single().name)
            assertEquals("Game.Player", model.methods.single().declaringType)
            assertEquals("Game.Player", model.fields.single().declaringType)
            assertEquals("Assembly-CSharp.dll", model.images.single().name)
            assertEquals(0, model.images.single().typeStart)
            assertEquals(1, model.images.single().typeCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun boundedLimitReportsParsedAndDeclaredCounts() {
        val bytes = fixture(version = 29)
        val file = Files.createTempFile(
            "modkit-il2cpp-bounded",
            ".dat",
        ).toFile()
        file.writeBytes(bytes)
        try {
            val model = Il2CppMetadataReader.read(
                file = file,
                cancellation = neverCancelled(),
                progress = ProgressSink { },
                limits = Il2CppMetadataReader.Limits(
                    maxMethods = 0,
                ),
            )

            assertTrue(model.truncated)
            assertEquals(1, model.declaredMethodCount)
            assertTrue(model.methods.isEmpty())
            assertTrue(
                model.warnings.any {
                    it.contains("methods 0/1")
                },
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun unsupportedVersionIsValidatedWithoutFakeReconstruction() {
        val bytes = ByteArray(256)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0xFAB11BAF.toInt())
        buffer.putInt(4, 99)
        val file = Files.createTempFile("modkit-il2cpp-unsupported", ".dat").toFile()
        file.writeBytes(bytes)
        try {
            val model = Il2CppMetadataReader.read(file, neverCancelled(), ProgressSink { })
            assertTrue(model.magicValid)
            assertFalse(model.structuredSupported)
            assertTrue(model.types.isEmpty())
            assertTrue(model.warnings.isNotEmpty())
        } finally {
            file.delete()
        }
    }

    private fun fixture(version: Int): ByteArray {
        val bytes = ByteArray(1024)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0xFAB11BAF.toInt())
        buffer.putInt(4, version)

        fun pair(index: Int, offset: Int, size: Int) {
            buffer.putInt(8 + index * 8, offset)
            buffer.putInt(8 + index * 8 + 4, size)
        }

        pair(2, 300, 64)
        pair(5, 500, 32)
        pair(11, 600, 12)
        pair(19, 700, 88)
        pair(20, 800, 40)

        val strings = byteArrayOf(
            'P'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte(),
            'e'.code.toByte(), 'r'.code.toByte(), 0,
            'G'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'e'.code.toByte(), 0,
            'H'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte(), 0,
            'h'.code.toByte(), 'e'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
            't'.code.toByte(), 'h'.code.toByte(), 0,
            'A'.code.toByte(), 's'.code.toByte(), 's'.code.toByte(), 'e'.code.toByte(),
            'm'.code.toByte(), 'b'.code.toByte(), 'l'.code.toByte(), 'y'.code.toByte(),
            '-'.code.toByte(), 'C'.code.toByte(), 'S'.code.toByte(), 'h'.code.toByte(),
            'a'.code.toByte(), 'r'.code.toByte(), 'p'.code.toByte(), '.'.code.toByte(),
            'd'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 0,
        )
        strings.copyInto(bytes, 300)

        buffer.putInt(700, 0)
        buffer.putInt(704, 7)
        buffer.putInt(732, 0)
        buffer.putInt(736, 0)
        buffer.putShort(764, 1.toShort())
        buffer.putShort(768, 1.toShort())
        buffer.putInt(784, 0x02000001)

        buffer.putInt(500, 12)
        buffer.putInt(504, 0)
        buffer.putInt(520, 0x06000001)
        buffer.putShort(524, 0x0006.toShort())
        buffer.putShort(530, 0.toShort())

        buffer.putInt(600, 16)
        buffer.putInt(604, 4)
        buffer.putInt(608, 0x04000001)

        buffer.putInt(800, 23)
        buffer.putInt(804, 0)
        buffer.putInt(808, 0)
        buffer.putInt(812, 1)
        buffer.putInt(828, 1)
        return bytes
    }

    private fun neverCancelled() = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
}
