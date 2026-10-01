package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeArtifactDetectorTest {
    @Test
    fun detectsDexAndIl2CppMetadataCandidatesFromLiveBytes() {
        val bytes =
            ByteArray(8192)
        val dex = 0
        "dex\n035\u0000"
            .toByteArray(
                Charsets.US_ASCII,
            )
            .copyInto(
                bytes,
                dex,
            )
        putU32le(
            bytes,
            dex + 32,
            4096,
        )

        val metadata = 4096
        byteArrayOf(
            0xaf.toByte(),
            0x1b,
            0xb1.toByte(),
            0xfa.toByte(),
        ).copyInto(
            bytes,
            metadata,
        )
        putU32le(
            bytes,
            metadata + 4,
            31,
        )
        putU32le(
            bytes,
            metadata + 8,
            0x100,
        )
        putU32le(
            bytes,
            metadata + 12,
            0x200,
        )

        val region =
            ProcMapsParser.parse(
                "1000-3000 rw-p 00000000 00:00 0 [heap]\n",
            ).single()

        val found =
            RootRuntimeArtifactDetector
                .detect(
                    region = region,
                    sliceAddress =
                        0x1000,
                    bytes = bytes,
                    offset = 0,
                    length =
                        bytes.size,
                )

        val dexFound =
            found.single {
                it.kind ==
                    RootRuntimeArtifactKind
                        .DEX
            }
        assertEquals(
            0x1000L,
            dexFound.address,
        )
        assertEquals(
            4096L,
            dexFound.estimatedSize,
        )

        val metadataFound =
            found.single {
                it.kind ==
                    RootRuntimeArtifactKind
                        .IL2CPP_METADATA
            }
        assertEquals(
            0x2000L,
            metadataFound.address,
        )
        assertTrue(
            metadataFound
                .evidence
                .contains("31"),
        )
    }

    private fun putU32le(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] =
            (value and 0xff)
                .toByte()
        bytes[offset + 1] =
            (
                value ushr 8 and
                    0xff
                ).toByte()
        bytes[offset + 2] =
            (
                value ushr 16 and
                    0xff
                ).toByte()
        bytes[offset + 3] =
            (
                value ushr 24 and
                    0xff
                ).toByte()
    }
}
