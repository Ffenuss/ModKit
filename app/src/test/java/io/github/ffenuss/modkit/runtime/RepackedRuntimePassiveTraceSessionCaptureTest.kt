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

class RepackedRuntimePassiveTraceSessionCaptureTest {
    @Test
    fun exactSignedPidBoundSessionStopsAndExportsPassiveCapture() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus = startStatus(),
            stopStatus = stopStatus(),
            traceBytes = exportBytes(),
        )

        val session =
            RepackedRuntimePassiveTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        val result =
            RepackedRuntimePassiveTraceSessionCapture.stopAndRead(
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
        assertEquals(1, result.export.eventCount)
        assertEquals(1, transport.startCalls)
        assertEquals(1, transport.stopCalls)
        assertEquals(1, transport.readCalls)
    }

    @Test
    fun signerMismatchBlocksBeforeSessionStart() {
        val transport = FakeTransport(
            installed = installedProbe(OTHER_SIGNER),
            query = query(),
            startStatus = startStatus(),
            stopStatus = stopStatus(),
            traceBytes = exportBytes(),
        )

        val failure = runCatching {
            RepackedRuntimePassiveTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty()
                .contains("signer", ignoreCase = true),
        )
        assertEquals(0, transport.queryCalls)
        assertEquals(0, transport.startCalls)
    }

    @Test
    fun pidRestartBeforeStopFailsClosedWithoutExport() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus = startStatus(),
            stopStatus = stopStatus(),
            traceBytes = exportBytes(),
        )
        val session =
            RepackedRuntimePassiveTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        transport.queryReply = query(PID + 1)

        val failure = runCatching {
            RepackedRuntimePassiveTraceSessionCapture.stopAndRead(
                build = buildResult(),
                session = session,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "PID changed",
            ),
        )
        assertEquals(0, transport.stopCalls)
        assertEquals(0, transport.readCalls)
    }

    @Test
    fun sessionIdMismatchBlocksBeforeTraceRead() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus = startStatus(),
            stopStatus = stopStatus(
                sessionId = "other-session",
            ),
            traceBytes = exportBytes(),
        )
        val session =
            RepackedRuntimePassiveTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )

        val failure = runCatching {
            RepackedRuntimePassiveTraceSessionCapture.stopAndRead(
                build = buildResult(),
                session = session,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "session ID changed",
            ),
        )
        assertEquals(0, transport.readCalls)
    }

    @Test
    fun exportCounterMismatchFailsClosed() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus = startStatus(),
            stopStatus = stopStatus(
                eventCount = 1,
                traceBytes = TRACE_BYTES,
            ),
            traceBytes = exportBytes(
                eventCount = 0,
                trace = "",
            ),
        )
        val session =
            RepackedRuntimePassiveTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )

        val failure = runCatching {
            RepackedRuntimePassiveTraceSessionCapture.stopAndRead(
                build = buildResult(),
                session = session,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "counters",
                ignoreCase = true,
            ),
        )
    }

    @Test
    fun truncatedSessionReturnsTruncatedCaptureAndParserRejectsProof() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            startStatus = startStatus(),
            stopStatus = stopStatus(
                truncated = true,
            ),
            traceBytes = exportBytes(
                truncated = true,
            ),
        )
        val session =
            RepackedRuntimePassiveTraceSessionCapture.start(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        val result =
            RepackedRuntimePassiveTraceSessionCapture.stopAndRead(
                build = buildResult(),
                session = session,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )

        assertTrue(result.capture.truncated)
        val parsed =
            RuntimeNativeTraceParser.parse(result.capture)
        assertTrue(parsed.events.isEmpty())
        assertTrue(
            parsed.blockers.any {
                "bounded capture limit" in it
            },
        )
    }

    private class FakeTransport(
        private val installed: RepackedRuntimeInstalledProbe?,
        query: RepackedRuntimeProbeQuery,
        private val startStatus: RepackedRuntimeNativeTraceStatus,
        private val stopStatus: RepackedRuntimeNativeTraceStatus,
        private val traceBytes: ByteArray,
    ) : RepackedRuntimePassiveTraceTransport {
        var queryReply: RepackedRuntimeProbeQuery = query
        var queryCalls = 0
        var startCalls = 0
        var stopCalls = 0
        var readCalls = 0

        override fun inspectInstalled(
            packageName: String,
            authority: String,
        ): RepackedRuntimeInstalledProbe? =
            installed

        override fun query(
            authority: String,
        ): RepackedRuntimeProbeQuery {
            queryCalls++
            return queryReply
        }

        override fun startNativeTrace(
            authority: String,
        ): RepackedRuntimeNativeTraceStatus {
            startCalls++
            return startStatus
        }

        override fun stopNativeTrace(
            authority: String,
        ): RepackedRuntimeNativeTraceStatus {
            stopCalls++
            return stopStatus
        }

        override fun nativeTraceStatus(
            authority: String,
        ): RepackedRuntimeNativeTraceStatus =
            if (stopCalls > 0) {
                stopStatus
            } else {
                startStatus
            }

        override fun readNativeTrace(
            authority: String,
            cancellation:
                io.github.ffenuss.modkit.analysis.CancellationSignal,
            maxBytes: Int,
        ): ByteArray {
            readCalls++
            require(traceBytes.size <= maxBytes)
            return traceBytes
        }

        override fun resolveLoadedSymbol(
            authority: String,
            moduleName: String,
            symbolName: String,
        ): RepackedRuntimeNativeLookupReply =
            error("targeted lookup is not used")

        override fun readEvidence(
            authority: String,
            cancellation:
                io.github.ffenuss.modkit.analysis.CancellationSignal,
            maxBytes: Int,
        ): ByteArray =
            error("maps evidence is not used")
    }

    private fun installedProbe(
        signer: String = SIGNER,
    ) = RepackedRuntimeInstalledProbe(
        packageName = PACKAGE,
        authority =
            PACKAGE +
                BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX,
        providerClassName =
            BinaryAndroidManifestProbeInjector.PROVIDER_CLASS,
        exported = true,
        enabled = true,
        signerCertificateSha256 = setOf(signer),
    )

    private fun query(
        pid: Int = PID,
    ) = RepackedRuntimeProbeQuery(
        schemaVersion =
            RuntimeEvidenceProviderContract.SCHEMA_VERSION,
        packageName = PACKAGE,
        pid = pid,
    )

    private fun startStatus() =
        RepackedRuntimeNativeTraceStatus(
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
            producerKind = "PLT_DLSYM_GOT",
            producerReady = true,
            producerActive = true,
            hookedSlotCount = 0,
            producerIncomplete = false,
            producerRestoreFailed = false,
        )

    private fun stopStatus(
        sessionId: String = SESSION_ID,
        eventCount: Int = 1,
        traceBytes: Int = TRACE_BYTES,
        truncated: Boolean = false,
        producerReady: Boolean = true,
        producerIncomplete: Boolean = false,
        producerRestoreFailed: Boolean = false,
    ) = RepackedRuntimeNativeTraceStatus(
        schemaVersion =
            RuntimeEvidenceProviderContract.SCHEMA_VERSION,
        packageName = PACKAGE,
        pid = PID,
        sessionId = sessionId,
        active = false,
        truncated = truncated,
        eventCount = eventCount,
        startedAtEpochMs = STARTED,
        stoppedAtEpochMs = STOPPED,
        traceBytes = traceBytes,
    )

    private fun exportBytes(
        eventCount: Int = 1,
        trace: String = TRACE,
        truncated: Boolean = false,
    ): ByteArray {
        val traceBytes =
            trace.toByteArray(Charsets.UTF_8)
        val header = buildString {
            appendLine(
                RepackedRuntimeNativeTraceExportProtocol
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
                "traceSha256=" +
                    sha256(traceBytes),
            )
            appendLine(
                "traceBytes=" + traceBytes.size,
            )
            append("truncated=$truncated")
        }.toByteArray(Charsets.UTF_8)
        return header +
            RepackedRuntimeNativeTraceExportProtocol
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
        private const val SESSION_ID = "session-1"
        private const val STARTED = 1000L
        private const val STOPPED = 2000L
        private const val TRACE =
            "DLSYM\tlibsample.so\tResolveTarget\t0x70020100\n"
        private val TRACE_BYTES =
            TRACE.toByteArray(Charsets.UTF_8).size
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SIGNER =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val OTHER_SIGNER =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
