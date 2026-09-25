package io.github.ffenuss.modkit.analysis.nativecode

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.patch.AArch64ScalarReturnEncoder
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AArch64ReadOnlyBodyTest {
    // Golden encodings assembled independently with Keystone 0.9.2.
    private fun bytes(vararg words: Long) = words.flatMap { w -> (0..3).map { (w ushr (it * 8)).toByte() } }.toByteArray()
    private fun inspect(vararg words: Long) = AArch64ReadOnlyBody.inspect(bytes(*words))

    @Test fun computedFieldGetterIsNotLimitedToTwoInstructions() {
        assertTrue(inspect(0xb9401808, 0x11001d00, 0xd65f03c0).supported)
    }
    @Test fun checksBothConditionalReturnPaths() {
        val proof = inspect(0xb9401808, 0x7100011f, 0x5400006d, 0x52800020, 0xd65f03c0, 0x52800000, 0xd65f03c0)
        assertTrue(proof.reason, proof.supported)
        assertEquals(2, proof.returnSites)
    }
    @Test fun supportsConditionalSelectAndFloatingComputation() {
        assertTrue(inspect(0xb9401808, 0x7100011f, 0x1a9fd7e0, 0xd65f03c0).supported)
        assertTrue(inspect(0xbd401001, 0x1e201002, 0x1e220820, 0xd65f03c0).supported)
    }
    @Test fun rejectsCallsAndWritesEvenWhenAConstantIsReturned() {
        assertFalse(inspect(0x94000400, 0x52800020, 0xd65f03c0).supported)
        assertFalse(inspect(0xb9401808, 0x11000508, 0xb9001808, 0x2a0803e0, 0xd65f03c0).supported)
    }
    @Test fun rejectsLoopsTruncationAndIndirectJumps() {
        assertFalse(inspect(0x14000000).supported) // b to self
        assertFalse(inspect(0xb9401808, 0x11001d00).supported)
        assertFalse(inspect(0xd61f0100).supported) // br x8
        assertFalse(inspect(0x54001000, 0xd65f03c0).supported) // conditional path outside window
    }
    @Test fun recordsBtiAndIgnoresUnreachablePadding() {
        val proof = inspect(0xd503245f, 0x52800020, 0xd65f03c0, 0xffffffff)
        assertTrue(proof.supported)
        assertEquals(0xd503245fL, proof.entryLandingPad)
    }
    @Test fun emitsActualEncoderOutputForIndependentArm64Execution() {
        val rows = listOf(
            listOf("integer", "i", "20", "27", "999", bytes(0xb9401808, 0x11001d00, 0xd65f03c0)),
            listOf("conditional", "i", "20", "1", "0", bytes(0xb9401808, 0x7100011f, 0x1a9fd7e0, 0xd65f03c0)),
            listOf("floating", "f", "1.5", "3.0", "5", bytes(0xbd401001, 0x1e201002, 0x1e220820, 0xd65f03c0)),
        ).map { row ->
            val source = row[5] as ByteArray
            assertTrue(AArch64ReadOnlyBody.inspect(source).supported)
            val replacement = AArch64ScalarReturnEncoder.encodeHex(
                if (row[1] == "f") Il2CppNativeReturnKind.FLOAT32 else Il2CppNativeReturnKind.INTEGER, row[4] as String)
            row.take(5).joinToString("\t") + "\t" + source.joinToString(" ") { "%02X".format(it.toInt() and 255) } + "\t" + replacement
        }
        File("build/native-verification.tsv").apply { parentFile.mkdirs(); writeText(rows.joinToString("\n")) }
    }
}
