package io.github.ffenuss.modkit.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class RuntimeNativeLookupTraceTest {
    @Test
    fun confirmedDlsymRequiresBoundProcessModuleAndExecutableAddress() {
        val maps = mapsText()
        val runtime = runtimeEvidence(maps)
        val capture = RuntimeNativeTraceParser.capture(
            artifactSha256 = SHA,
            processIdentity = PACKAGE,
            processIdentityConfirmed = true,
            pid = PID,
            source = RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            text = "DLSYM\tlibsample.so\tResolveTarget\t0x70020100",
            capturedAtEpochMs = 1234,
        )

        val validation = RuntimeNativeLookupValidator.validate(
            capture = capture,
            runtimeEvidence = runtime,
            procMapsText = maps,
        )

        assertTrue(validation.blockers.isEmpty())
        val observation = validation.observations.single()
        assertEquals(
            RuntimeEvidenceObservationKind.JNI_DLSYM_OBSERVED,
            observation.kind,
        )
        assertEquals(
            RuntimeEvidenceObservationStrength.CONFIRMED,
            observation.strength,
        )
        assertTrue(observation.independentlyConfirmed)
        assertNull(observation.proofLevel)
        assertTrue(
            observation.supportingFacts.any {
                it == "symbol=ResolveTarget"
            },
        )
    }

    @Test
    fun registerNativesKeepsExactClassMethodAndSignatureAsObservedFacts() {
        val maps = mapsText()
        val capture = RuntimeNativeTraceParser.capture(
            artifactSha256 = SHA,
            processIdentity = PACKAGE,
            processIdentityConfirmed = true,
            pid = PID,
            source = RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            text =
                "JNI_REGISTER_NATIVE\tlibsample.so\tcom/example/Game\tnativeTick\t(I)V\t0x70020120",
        )

        val parsed = RuntimeNativeTraceParser.parse(capture)

        assertTrue(parsed.blockers.isEmpty())
        val event = parsed.events.single()
        assertEquals(RuntimeNativeLookupKind.JNI_REGISTER_NATIVE, event.kind)
        assertEquals("com/example/Game", event.jniClassName)
        assertEquals("nativeTick", event.jniMethodName)
        assertEquals("(I)V", event.jniSignature)
    }

    @Test
    fun processIdentityMismatchBlocksEntireTrace() {
        val maps = mapsText()
        val capture = RuntimeNativeTraceParser.capture(
            artifactSha256 = SHA,
            processIdentity = "com.other.app",
            processIdentityConfirmed = true,
            pid = PID,
            source = RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            text = "DLSYM\tlibsample.so\tResolveTarget\t0x70020100",
        )

        val validation = RuntimeNativeLookupValidator.validate(
            capture = capture,
            runtimeEvidence = runtimeEvidence(maps),
            procMapsText = maps,
        )

        assertTrue(validation.observations.isEmpty())
        assertTrue(
            validation.blockers.any {
                "process identity" in it
            },
        )
    }

    @Test
    fun executableAddressWithoutConfirmedModuleMappingStaysObserved() {
        val maps = mapsText()
        val runtime = runtimeEvidence(maps).copy(
            moduleMappings = emptyList(),
        )
        val capture = RuntimeNativeTraceParser.capture(
            artifactSha256 = SHA,
            processIdentity = PACKAGE,
            processIdentityConfirmed = true,
            pid = PID,
            source = RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            text = "DLSYM\tlibsample.so\tResolveTarget\t0x70020100",
        )

        val validation = RuntimeNativeLookupValidator.validate(
            capture = capture,
            runtimeEvidence = runtime,
            procMapsText = maps,
        )

        val observation = validation.observations.single()
        assertEquals(
            RuntimeEvidenceObservationStrength.OBSERVED,
            observation.strength,
        )
        assertFalse(observation.independentlyConfirmed)
        assertTrue(
            observation.blockers.any {
                "confirmed ELF mapping" in it
            },
        )
    }

    @Test
    fun resolvedAddressOutsideExecutableModuleMappingIsNotConfirmed() {
        val maps = mapsText()
        val capture = RuntimeNativeTraceParser.capture(
            artifactSha256 = SHA,
            processIdentity = PACKAGE,
            processIdentityConfirmed = true,
            pid = PID,
            source = RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            text = "DLSYM\tlibsample.so\tResolveTarget\t0x70010100",
        )

        val validation = RuntimeNativeLookupValidator.validate(
            capture = capture,
            runtimeEvidence = runtimeEvidence(maps),
            procMapsText = maps,
        )

        val observation = validation.observations.single()
        assertFalse(observation.independentlyConfirmed)
        assertTrue(
            observation.blockers.any {
                "outside an executable mapping" in it
            },
        )
    }

    @Test
    fun traceCaptureExecutorsRemainUnavailableUntilActuallyRegistered() {
        assertTrue(
            RuntimeNativeTraceCapability.TRACE_PARSER in
                RuntimeNativeTraceCapabilityRegistry.registered,
        )
        assertTrue(
            RuntimeNativeTraceCapability.EXECUTABLE_ADDRESS_VALIDATOR in
                RuntimeNativeTraceCapabilityRegistry.registered,
        )
        assertTrue(
            RuntimeNativeTraceCapability.REPACKED_TARGETED_DLSYM_PROBE in
                RuntimeNativeTraceCapabilityRegistry.registered,
        )
        assertTrue(
            RuntimeNativeTraceCapabilityRegistry.targetedDlsymProbeAvailable(
                RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            ),
        )
        assertTrue(
            RuntimeNativeTraceCapability.REPACKED_PASSIVE_DLSYM_CAPTURE in
                RuntimeNativeTraceCapabilityRegistry.registered,
        )
        assertTrue(
            RuntimeNativeTraceCapabilityRegistry.passiveDlsymCaptureAvailable(
                RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            ),
        )
        assertFalse(
            RuntimeNativeTraceCapabilityRegistry.captureAvailable(
                RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            ),
        )
        assertFalse(
            RuntimeNativeTraceCapabilityRegistry.captureAvailable(
                RuntimeNativeTraceSource.NON_ROOT_RUNTIME,
            ),
        )
        assertFalse(
            RuntimeNativeTraceCapabilityRegistry.captureAvailable(
                RuntimeNativeTraceSource.ROOT_RUNTIME,
            ),
        )
    }

    private fun runtimeEvidence(
        maps: String,
    ) = RuntimeEvidenceBundle(
        artifactSha256 = SHA,
        procMapsSha256 = sha256(maps.toByteArray()),
        moduleMappings = listOf(
            RuntimeModuleMappingEvidence(
                moduleName = "libsample.so",
                mappedPath = "/data/app/pkg/lib/arm64/libsample.so",
                loadBias = 0x70000000,
                elfImageBaseVirtualAddress = 0,
                pageSize = 4096,
                matchedLoadSegments = 2,
                matchedExecutableSegments = 1,
                zeroOffsetMappingMatched = true,
                confirmed = true,
                blockers = emptyList(),
            ),
        ),
        addressConfirmations = emptyList(),
        blockers = emptyList(),
        captureSource = ProcMapsCaptureSource.NON_ROOT_PROCESS,
        capturePid = PID,
        capturedAtEpochMs = 1234,
        processIdentity = PACKAGE,
        processIdentityConfirmed = true,
    )

    private fun mapsText() = """
        70000000-70010000 r--p 00000000 103:02 42 /data/app/pkg/lib/arm64/libsample.so
        70020000-70040000 r-xp 00020000 103:02 42 /data/app/pkg/lib/arm64/libsample.so
    """.trimIndent()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val PID = 456
        private const val PACKAGE = "com.example.target"
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
