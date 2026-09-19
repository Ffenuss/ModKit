package io.github.ffenuss.modkit.runtime

import java.security.MessageDigest
import java.util.zip.Adler32
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProbePayloadTest {
    @Test
    fun acceptsStructurallyValidProbeDexWithExactProviderDescriptor() {
        val dex = validProbeDex()

        val validation = RuntimeProbeDexValidator.validate(dex)

        assertTrue(validation.valid)
        assertTrue(validation.checksumMatches)
        assertTrue(validation.signatureMatches)
        assertTrue(validation.providerDescriptorPresent)
        assertTrue(validation.dexVersion == "035")
        assertTrue(validation.declaredFileSize == dex.size)
    }

    @Test
    fun checksumMutationFailsClosed() {
        val dex = validProbeDex()
        dex[dex.lastIndex] = (dex.last().toInt() xor 1).toByte()

        val validation = RuntimeProbeDexValidator.validate(dex)

        assertFalse(validation.valid)
        assertFalse(validation.checksumMatches)
        assertFalse(validation.signatureMatches)
    }

    @Test
    fun validDexWithoutProviderDescriptorIsNotAcceptedAsProbe() {
        val dex = validProbeDex(
            descriptor =
                "Lcom/example/NotTheModKitProvider;",
        )

        val validation = RuntimeProbeDexValidator.validate(dex)

        assertFalse(validation.valid)
        assertFalse(validation.providerDescriptorPresent)
    }

    companion object {
        fun validProbeDex(
            descriptor: String =
                RuntimeProbePayloadSource.PROVIDER_DESCRIPTOR,
        ): ByteArray {
            val bytes = ByteArray(512)
            val magic = byteArrayOf(
                'd'.code.toByte(),
                'e'.code.toByte(),
                'x'.code.toByte(),
                '\n'.code.toByte(),
                '0'.code.toByte(),
                '3'.code.toByte(),
                '5'.code.toByte(),
                0,
            )
            magic.copyInto(bytes, 0)
            putU32(bytes, 32, bytes.size)
            putU32(bytes, 36, 0x70)
            putU32(bytes, 40, 0x12345678)
            descriptor.toByteArray(Charsets.UTF_8)
                .copyInto(bytes, 128)

            val signature = MessageDigest.getInstance("SHA-1")
                .digest(bytes.copyOfRange(32, bytes.size))
            signature.copyInto(bytes, 12)

            val checksum = Adler32().apply {
                update(bytes, 12, bytes.size - 12)
            }.value
            putU32(bytes, 8, checksum.toInt())
            return bytes
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") {
                    "%02x".format(it.toInt() and 0xff)
                }

        private fun putU32(
            bytes: ByteArray,
            offset: Int,
            value: Int,
        ) {
            bytes[offset] = (value and 0xff).toByte()
            bytes[offset + 1] =
                ((value ushr 8) and 0xff).toByte()
            bytes[offset + 2] =
                ((value ushr 16) and 0xff).toByte()
            bytes[offset + 3] =
                ((value ushr 24) and 0xff).toByte()
        }
    }
}
