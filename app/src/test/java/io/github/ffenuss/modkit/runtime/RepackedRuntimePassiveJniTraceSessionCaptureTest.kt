package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.build.ApkSignatureVerification
import io.github.ffenuss.modkit.build.InstallabilityVerification
import io.github.ffenuss.modkit.build.ZipAlignmentVerification
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimePassiveJniTraceSessionCaptureTest {
    @Test
    fun signedPidBoundSessionExportsLookupInvocationAndRegistrationEvents() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus = startStatus(),
            stopStatus = stopStatus(),
            traceBytes = exportBytes(),
        )

        val session =
            RepackedRuntimePassiveJniTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        val result =
            RepackedRuntimePassiveJniTraceSessionCapture.stopAndRead(
                build = buildResult(),
                session = session,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )

        assertEquals(SESSION_ID, session.sessionId)
        assertEquals(PID, result.capture.pid)
        assertEquals(
            RuntimeNativeAcquisitionMode.PASSIVE_TRACE,
            result.capture.acquisitionMode,
        )
        assertFalse(result.capture.truncated)
        val parsed = RuntimeNativeTraceParser.parse(result.capture)
        assertTrue(parsed.blockers.isEmpty())
        assertEquals(3, parsed.events.size)
        assertEquals(
            RuntimeNativeLookupKind.DLSYM,
            parsed.events[0].kind,
        )
        assertEquals("JNI_OnLoad", parsed.events[0].symbolName)
        assertEquals(
            RuntimeNativeLookupKind.JNI_ON_LOAD,
            parsed.events[1].kind,
        )
        assertEquals(
            RuntimeNativeLookupKind.JNI_REGISTER_NATIVE,
            parsed.events[2].kind,
        )
    }

    @Test
    fun missingRegisterNativesHookFailsClosedAtStart() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus =
                startStatus().copy(
                    registerNativesHooked = false,
                ),
            stopStatus = stopStatus(),
            traceBytes = exportBytes(),
        )

        val failure = runCatching {
            RepackedRuntimePassiveJniTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "RegisterNatives",
            ),
        )
    }

    @Test
    fun missingArtLookupHookKeepsRegistrationCaptureAvailableButIncomplete() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus =
                startStatus().copy(
                    jniOnLoadLookupHookedSlotCount = 0,
                    jniOnLoadInvocationReady = false,
                    producerIncomplete = true,
                ),
            stopStatus =
                stopStatus(
                    eventCount = 1,
                    traceBytes = REGISTRATION_ONLY_TRACE_BYTES,
                ).copy(
                    jniOnLoadLookupHookedSlotCount = 0,
                    jniOnLoadInvocationReady = false,
                    producerIncomplete = true,
                ),
            traceBytes =
                exportBytes(
                    trace = REGISTRATION_ONLY_TRACE,
                    eventCount = 1,
                    producerIncomplete = true,
                    jniOnLoadLookupHookedSlotCount = 0,
                    jniOnLoadInvocationReady = false,
                ),
        )

        val session =
            RepackedRuntimePassiveJniTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        val result =
            RepackedRuntimePassiveJniTraceSessionCapture.stopAndRead(
                build = buildResult(),
                session = session,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )

        assertEquals(0, result.status.jniOnLoadLookupHookedSlotCount)
        assertFalse(result.status.jniOnLoadInvocationReady)
        assertTrue(result.status.producerIncomplete)
    }

    @Test
    fun invocationEventWithoutInvocationReadyProvenanceFailsClosed() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus =
                startStatus().copy(
                    jniOnLoadInvocationReady = false,
                    producerIncomplete = true,
                ),
            stopStatus =
                stopStatus().copy(
                    jniOnLoadInvocationReady = false,
                    producerIncomplete = true,
                ),
            traceBytes =
                exportBytes(
                    jniOnLoadInvocationReady = false,
                    producerIncomplete = true,
                ),
        )
        val session =
            RepackedRuntimePassiveJniTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )

        val failure = runCatching {
            RepackedRuntimePassiveJniTraceSessionCapture.stopAndRead(
                build = buildResult(),
                session = session,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "invocation-ready",
                ignoreCase = true,
            ),
        )
    }

    @Test
    fun restoreFailureBlocksExport() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus = startStatus(),
            stopStatus =
                stopStatus().copy(
                    producerReady = false,
                    producerRestoreFailed = true,
                ),
            traceBytes = exportBytes(),
        )
        val session =
            RepackedRuntimePassiveJniTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )

        val failure = runCatching {
            RepackedRuntimePassiveJniTraceSessionCapture.stopAndRead(
                build = buildResult(),
                session = session,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, transport.readCalls)
    }

    @Test
    fun producerIncompleteIsDiagnosticAndNotTruncation() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus =
                startStatus().copy(
                    producerIncomplete = true,
                ),
            stopStatus =
                stopStatus().copy(
                    producerIncomplete = true,
                ),
            traceBytes =
                exportBytes(
                    producerIncomplete = true,
                ),
        )

        val session =
            RepackedRuntimePassiveJniTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        val result =
            RepackedRuntimePassiveJniTraceSessionCapture.stopAndRead(
                build = buildResult(),
                session = session,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )

        assertTrue(result.status.producerIncomplete)
        assertFalse(result.capture.truncated)
    }

    private class FakeTransport(
        private val installed: RepackedRuntimeInstalledProbe?,
        private val query: RepackedRuntimeProbeQuery,
        private val startStatus: RepackedRuntimePassiveJniTraceStatus,
        private val stopStatus: RepackedRuntimePassiveJniTraceStatus,
        private val traceBytes: ByteArray,
    ) : RepackedRuntimePassiveJniTraceTransport {
        var readCalls = 0

        override fun inspectInstalled(
            packageName: String,
            authority: String,
        ): RepackedRuntimeInstalledProbe? = installed

        override fun query(
            authority: String,
        ): RepackedRuntimeProbeQuery = query

        override fun startPassiveJniTrace(
            authority: String,
        ): RepackedRuntimePassiveJniTraceStatus = startStatus

        override fun stopPassiveJniTrace(
            authority: String,
        ): RepackedRuntimePassiveJniTraceStatus = stopStatus

        override fun passiveJniTraceStatus(
            authority: String,
        ): RepackedRuntimePassiveJniTraceStatus = stopStatus

        override fun readPassiveJniTrace(
            authority: String,
            cancellation:
                io.github.ffenuss.modkit.analysis.CancellationSignal,
            maxBytes: Int,
        ): ByteArray {
            readCalls++
            require(traceBytes.size <= maxBytes)
            return traceBytes
        }

        override fun readEvidence(
            authority: String,
            cancellation:
                io.github.ffenuss.modkit.analysis.CancellationSignal,
            maxBytes: Int,
        ): ByteArray = error("maps evidence is not used")
    }

    private fun installedProbe() =
        RepackedRuntimeInstalledProbe(
            packageName = PACKAGE,
            authority =
                PACKAGE +
                    BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX,
            providerClassName =
                BinaryAndroidManifestProbeInjector.PROVIDER_CLASS,
            exported = true,
            enabled = true,
            signerCertificateSha256 = setOf(SIGNER),
        )

    private fun query() =
        RepackedRuntimeProbeQuery(
            schemaVersion =
                RuntimeEvidenceProviderContract.SCHEMA_VERSION,
            packageName = PACKAGE,
            pid = PID,
        )

    private fun startStatus() =
        RepackedRuntimePassiveJniTraceStatus(
            schemaVersion =
                RuntimeEvidenceProviderContract.SCHEMA_VERSION,
            packageName = PACKAGE,
            pid = PID,
            sessionId = SESSION_ID,
            active = true,
            truncated = false,
            eventCount = 0,
            startedAtEpochMs = STARTED,
            stoppedAtEpochMs = 0,
            traceBytes = 0,
            producerKind = "ART_JNI_ONLOAD_WRAPPER_JNI_TABLE",
            producerReady = true,
            producerActive = true,
            jniOnLoadLookupHookedSlotCount = 1,
            jniOnLoadInvocationReady = true,
            registerNativesHooked = true,
            producerIncomplete = false,
            producerRestoreFailed = false,
        )

    private fun stopStatus(
        eventCount: Int = 3,
        traceBytes: Int = TRACE_BYTES,
    ) =
        RepackedRuntimePassiveJniTraceStatus(
            schemaVersion =
                RuntimeEvidenceProviderContract.SCHEMA_VERSION,
            packageName = PACKAGE,
            pid = PID,
            sessionId = SESSION_ID,
            active = false,
            truncated = false,
            eventCount = eventCount,
            startedAtEpochMs = STARTED,
            stoppedAtEpochMs = STOPPED,
            traceBytes = traceBytes,
            producerKind = "ART_JNI_ONLOAD_WRAPPER_JNI_TABLE",
            producerReady = true,
            producerActive = false,
            jniOnLoadLookupHookedSlotCount = 1,
            jniOnLoadInvocationReady = true,
            registerNativesHooked = true,
            producerIncomplete = false,
            producerRestoreFailed = false,
        )

    private fun exportBytes(
        trace: String = TRACE,
        eventCount: Int = 3,
        producerIncomplete: Boolean = false,
        jniOnLoadLookupHookedSlotCount: Int = 1,
        jniOnLoadInvocationReady: Boolean = true,
    ): ByteArray {
        val traceBytes = trace.toByteArray(Charsets.UTF_8)
        val header = buildString {
            appendLine(
                RepackedRuntimePassiveJniTraceExportProtocol
                    .HEADER_MAGIC,
            )
            appendLine("packageName=$PACKAGE")
            appendLine("pid=$PID")
            appendLine("processIdentity=$PACKAGE")
            appendLine("sessionId=$SESSION_ID")
            appendLine("startedAtEpochMs=$STARTED")
            appendLine("stoppedAtEpochMs=$STOPPED")
            appendLine("eventCount=$eventCount")
            appendLine(
                "traceSha256=" + sha256(traceBytes),
            )
            appendLine("traceBytes=" + traceBytes.size)
            appendLine("truncated=false")
            appendLine("producerKind=ART_JNI_ONLOAD_WRAPPER_JNI_TABLE")
            appendLine(
                "jniOnLoadLookupHookedSlotCount=" +
                    jniOnLoadLookupHookedSlotCount,
            )
            appendLine(
                "jniOnLoadInvocationReady=" +
                    jniOnLoadInvocationReady,
            )
            appendLine("registerNativesHooked=true")
            appendLine(
                "producerIncomplete=$producerIncomplete",
            )
            append("producerRestoreFailed=false")
        }.toByteArray(Charsets.UTF_8)
        return header +
            RepackedRuntimePassiveJniTraceExportProtocol
                .TRACE_DELIMITER +
            traceBytes
    }

    private fun buildResult() =
        RepackedRuntimeBuildResult(
            artifactSha256 = ARTIFACT_SHA,
            packageName = PACKAGE,
            signedApks = listOf(
                RepackedRuntimeBuiltApk(
                    sourceDisplayName = "base.apk",
                    sanitizedSha256 = ARTIFACT_SHA,
                    alignedPath = "/tmp/aligned.apk",
                    signedPath = "/tmp/signed.apk",
                    signedSha256 = ARTIFACT_SHA,
                    alignment = ZipAlignmentVerification(
                        verified = true,
                        records = emptyList(),
                        blockers = emptyList(),
                    ),
                    signature = ApkSignatureVerification(
                        verified = true,
                        v1 = true,
                        v2 = true,
                        v3 = false,
                        v31 = false,
                        signerCertificateSha256 =
                            listOf(SIGNER),
                        warnings = emptyList(),
                        errors = emptyList(),
                    ),
                ),
            ),
            installability = InstallabilityVerification(
                verified = true,
                packageName = PACKAGE,
                files = emptyList(),
                blockers = emptyList(),
            ),
            reportPath = "/tmp/report.txt",
            signerAlias = "MODKIT",
            completedAtEpochMs = 1,
        )

    private fun sha256(
        bytes: ByteArray,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

    companion object {
        private const val PID = 456
        private const val PACKAGE = "com.example.target"
        private const val SESSION_ID = "jni-session-1"
        private const val STARTED = 1000L
        private const val STOPPED = 2000L
        private const val REGISTRATION_ONLY_TRACE =
            "JNI_REGISTER_NATIVE\tlibsample.so\tcom/example/Foo\tbar\t(I)V\t0x70020300\n"
        private const val TRACE =
            "DLSYM\tlibsample.so\tJNI_OnLoad\t0x70020200\n" +
                "JNI_ON_LOAD\tlibsample.so\tJNI_OnLoad\t0x70020200\n" +
                REGISTRATION_ONLY_TRACE
        private val TRACE_BYTES =
            TRACE.toByteArray(Charsets.UTF_8).size
        private val REGISTRATION_ONLY_TRACE_BYTES =
            REGISTRATION_ONLY_TRACE.toByteArray(Charsets.UTF_8).size
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SIGNER =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
