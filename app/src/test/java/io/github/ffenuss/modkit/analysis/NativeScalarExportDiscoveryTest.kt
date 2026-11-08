package io.github.ffenuss.modkit.analysis

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

/**
 * The positive scanner integration consumes the actual ABI-specific ELF
 * emitted by Android NDK for the independent native game fixture.
 */
class NativeScalarExportDiscoveryTest {
    @Test fun exportedFixtureIsDiscoveredFromRealNdKElfWithoutKnownNameDictionary() {
        val apk = sequenceOf(
            File("../nativefixture/build/outputs/apk/debug/nativefixture-debug.apk"),
            File("nativefixture/build/outputs/apk/debug/nativefixture-debug.apk"),
        ).firstOrNull { it.isFile }
            ?: error("Build :nativefixture:assembleDebug before JVM discovery test")

        for (abi in listOf("arm64-v8a", "x86_64")) {
            val library = File.createTempFile("modkit-scalar-", ".so")
            try {
                ZipFile(apk).use { zip ->
                    val entry = requireNotNull(
                        zip.getEntry("lib/$abi/libmodkit_native_fixture.so")
                    ) { "Actual NDK $abi library was not included in the APK" }
                    zip.getInputStream(entry).use { input ->
                        library.outputStream().use { input.copyTo(it) }
                    }
                }
                ElfImage.open(library, AtomicCancellationSignal()).use { elf ->
                    val candidates = NativeScalarExportDiscovery.scan(
                        elf, AtomicCancellationSignal()
                    )
                    val fixture = candidates.single { it.symbol == "native_fixture_step" }
                    assertEquals(abi, fixture.abi)
                    assertEquals(2, fixture.value)
                    assertFalse("Structural discovery is never an enabled game mod",
                        fixture.selectable)
                    assertTrue(fixture.blocker.contains("unverified"))
                    assertEquals(elf.fileOffsetForVa(fixture.binaryVirtualAddress,
                        if (abi == "arm64-v8a") 8 else 6), fixture.fileOffset)
                    assertTrue(elf.isExecutableVa(fixture.binaryVirtualAddress))
                    val prepared = NativeScalarExportDiscovery.propose(fixture, 7)
                    assertEquals(fixture.patchVirtualAddress, prepared.patchVirtualAddress)
                    assertEquals(if (abi == "arm64-v8a") "40008052" else "02000000",
                        prepared.expectedHex)
                    assertEquals(if (abi == "arm64-v8a") "e0008052" else "07000000",
                        prepared.replacementHex)

                    try {
                        NativeScalarExportDiscovery.propose(fixture, 2)
                        fail("A no-op is not a mutation")
                    } catch (_: IllegalArgumentException) { }
                    try {
                        NativeScalarExportDiscovery.propose(
                            fixture.copy(expectedPatchHex = "ffffffff"), 7)
                        fail("Stale original bytes must reject the proposed patch")
                    } catch (_: IllegalArgumentException) { }

                    val cancelled = AtomicCancellationSignal().apply { cancel() }
                    try {
                        NativeScalarExportDiscovery.scan(elf, cancelled)
                        fail("Cancelled discovery should stop")
                    } catch (_: AnalysisCancelledException) { }
                }
            } finally {
                library.delete()
            }
        }
    }

    @Test fun decoderRejectsWrongOpcodeRegisterAndSideEffects() {
        fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val scan = NativeScalarExportDiscovery::recognizeBody
        assertEquals(2, scan("arm64-v8a", bytes("40008052c0035fd6")))
        assertEquals(7, scan("arm64-v8a", bytes("e0008052c0035fd6")))
        assertEquals(2, scan("x86_64", bytes("b802000000c3")))
        assertNull(scan("arm64-v8a", bytes("41008052c0035fd6"))) // W1, not W0
        assertNull(scan("arm64-v8a", bytes("400080d2c0035fd6"))) // X0, not W0
        assertNull(scan("arm64-v8a", bytes("400080521f2003d5"))) // NOP, not RET
        assertNull(scan("arm64-v8a", bytes("40008052c0035fd6" + "00000000")))
        assertNull(scan("x86_64", bytes("b80200000090"))) // NOP, not RET
        assertNull(scan("x86_64", bytes("b802000001c3"))) // value > bounded 16-bit
        assertNull(scan("x86_64", bytes("b902000000c3"))) // ECX, not EAX
        assertNull(scan("armeabi-v7a", bytes("b802000000c3")))
    }
}
