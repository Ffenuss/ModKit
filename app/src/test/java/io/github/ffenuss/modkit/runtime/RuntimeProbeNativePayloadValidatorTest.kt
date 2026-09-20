package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProbeNativePayloadValidatorTest {
    @Test
    fun acceptsMatchingArm64SharedObjectWithBridgeMarker() {
        val bytes = fakeElf(
            is64Bit = true,
            machine = 183,
            includeMarker = true,
        )

        val validation =
            RuntimeProbeNativePayloadValidator.validate(
                bytes = bytes,
                abi = "arm64-v8a",
            )

        assertTrue(validation.valid)
        assertTrue(validation.is64Bit == true)
        assertTrue(validation.machine == 183)
    }

    @Test
    fun rejectsAbiMachineMismatch() {
        val bytes = fakeElf(
            is64Bit = false,
            machine = 40,
            includeMarker = true,
        )

        val validation =
            RuntimeProbeNativePayloadValidator.validate(
                bytes = bytes,
                abi = "arm64-v8a",
            )

        assertFalse(validation.valid)
        assertTrue(
            validation.blockers.any {
                "does not match ABI" in it
            },
        )
    }

    @Test
    fun rejectsPayloadWithoutExpectedJniBridgeMarker() {
        val bytes = fakeElf(
            is64Bit = false,
            machine = 3,
            includeMarker = false,
        )

        val validation =
            RuntimeProbeNativePayloadValidator.validate(
                bytes = bytes,
                abi = "x86",
            )

        assertFalse(validation.valid)
        assertTrue(
            validation.blockers.any {
                "JNI bridge marker" in it
            },
        )
    }

    private fun fakeElf(
        is64Bit: Boolean,
        machine: Int,
        includeMarker: Boolean,
    ): ByteArray {
        val markers = listOf(
            "RuntimeNativeBridge_nativeResolveLoadedSymbol",
            "RuntimeNativeBridge_nativeStartPassiveDlsymTrace",
            "RuntimeNativeBridge_nativeStopPassiveDlsymTrace",
            "RuntimeNativeBridge_nativeStartPassiveJniTrace",
            "RuntimeNativeBridge_nativeStopPassiveJniTrace",
        ).map {
            it.toByteArray(Charsets.US_ASCII)
        }
        val bytes = ByteArray(512)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = if (is64Bit) 2 else 1
        bytes[5] = 1
        putU16(bytes, 16, 3)
        putU16(bytes, 18, machine)
        if (includeMarker) {
            var offset = 64
            markers.forEach { marker ->
                marker.copyInto(bytes, offset)
                offset += marker.size + 8
            }
        }
        return bytes
    }

    private fun putU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] =
            ((value ushr 8) and 0xff).toByte()
    }
}
