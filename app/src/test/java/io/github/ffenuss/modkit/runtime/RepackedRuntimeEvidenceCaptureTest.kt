package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.build.ApkSignatureVerification
import io.github.ffenuss.modkit.build.InstallabilityVerification
import io.github.ffenuss.modkit.build.ZipAlignmentVerification
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeEvidenceCaptureTest {
    @Test
    fun signedInstalledProbeActivatesAndReturnsBoundRuntimeMaps() {
        val evidence = evidenceBytes()
        val transport = FakeTransport(
            installed = installedProbe(),
            query = RepackedRuntimeProbeQuery(
                schemaVersion = 1,
                packageName = PACKAGE,
                pid = PID,
            ),
            evidence = evidence,
        )

        val result = RepackedRuntimeEvidenceCapture.capture(
            build = buildResult(),
            transport = transport,
            cancellation = AtomicCancellationSignal(),
        )

        assertEquals(PACKAGE, result.packageName)
        assertEquals(PID, result.pid)
        assertEquals(PACKAGE, result.processIdentity)
        assertEquals(
            ProcMapsCaptureSource.REPACKED_TEST_RUNTIME,
            result.maps.source,
        )
        assertEquals(PID, result.maps.pid)
        assertFalse(result.maps.truncated)
        assertEquals(MAPS, result.maps.text)
        assertEquals(1, transport.queryCalls)
        assertEquals(1, transport.readCalls)
    }

    @Test
    fun signerMismatchBlocksBeforeProviderActivation() {
        val transport = FakeTransport(
            installed = installedProbe(
                signer = OTHER_SIGNER,
            ),
            query = RepackedRuntimeProbeQuery(
                schemaVersion = 1,
                packageName = PACKAGE,
                pid = PID,
            ),
            evidence = evidenceBytes(),
        )

        val failure = runCatching {
            RepackedRuntimeEvidenceCapture.capture(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "signer",
                ignoreCase = true,
            ),
        )
        assertEquals(0, transport.queryCalls)
        assertEquals(0, transport.readCalls)
    }

    @Test
    fun pidRaceBetweenQueryAndStreamFailsClosed() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = RepackedRuntimeProbeQuery(
                schemaVersion = 1,
                packageName = PACKAGE,
                pid = PID + 1,
            ),
            evidence = evidenceBytes(),
        )

        val failure = runCatching {
            RepackedRuntimeEvidenceCapture.capture(
                build = buildResult(),
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
    }

    @Test
    fun differentProcessIdentityFailsClosed() {
        val transport = FakeTransport(
            installed = installedProbe(),
            query = RepackedRuntimeProbeQuery(
                schemaVersion = 1,
                packageName = PACKAGE,
                pid = PID,
            ),
            evidence = evidenceBytes(
                processIdentity = "$PACKAGE:remote",
            ),
        )

        val failure = runCatching {
            RepackedRuntimeEvidenceCapture.capture(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "main process",
            ),
        )
    }

    @Test
    fun protocolRejectsMapsShaMismatch() {
        val bytes = evidenceBytes()
        bytes[bytes.lastIndex] =
            (bytes.last().toInt() xor 1).toByte()

        val failure = runCatching {
            RepackedRuntimeEvidenceProtocol.parse(bytes)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "SHA-256 mismatch",
            ),
        )
    }

    @Test
    fun protocolRejectsTruncatedFlagOnlyAtCaptureGate() {
        val parsed = RepackedRuntimeEvidenceProtocol.parse(
            evidenceBytes(truncated = true),
        )
        assertTrue(parsed.mapsTruncated)

        val transport = FakeTransport(
            installed = installedProbe(),
            query = RepackedRuntimeProbeQuery(
                schemaVersion = 1,
                packageName = PACKAGE,
                pid = PID,
            ),
            evidence = evidenceBytes(truncated = true),
        )
        val failure = runCatching {
            RepackedRuntimeEvidenceCapture.capture(
                build = buildResult(),
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "truncated",
                ignoreCase = true,
            ),
        )
    }

    private class FakeTransport(
        private val installed: RepackedRuntimeInstalledProbe?,
        private val query: RepackedRuntimeProbeQuery,
        private val evidence: ByteArray,
    ) : RepackedRuntimeProbeTransport {
        var queryCalls: Int = 0
            private set
        var readCalls: Int = 0
            private set

        override fun inspectInstalled(
            packageName: String,
            authority: String,
        ): RepackedRuntimeInstalledProbe? =
            installed

        override fun query(
            authority: String,
        ): RepackedRuntimeProbeQuery {
            queryCalls++
            return query
        }

        override fun readEvidence(
            authority: String,
            cancellation: io.github.ffenuss.modkit.analysis.CancellationSignal,
            maxBytes: Int,
        ): ByteArray {
            readCalls++
            require(evidence.size <= maxBytes)
            return evidence
        }
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

    private fun evidenceBytes(
        processIdentity: String = PACKAGE,
        truncated: Boolean = false,
    ): ByteArray {
        val maps = MAPS.toByteArray(Charsets.UTF_8)
        val sha = sha256(maps)
        val header = buildString {
            appendLine(
                RuntimeEvidenceProviderContract.HEADER_MAGIC,
            )
            appendLine("packageName=$PACKAGE")
            appendLine("pid=$PID")
            appendLine("processIdentity=$processIdentity")
            appendLine("capturedAtEpochMs=1234")
            appendLine("mapsSha256=$sha")
            appendLine("mapsBytes=" + maps.size)
            append("mapsTruncated=$truncated")
        }.toByteArray(Charsets.UTF_8)
        return header +
            RuntimeEvidenceProviderContract.MAPS_DELIMITER +
            maps
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

    companion object {
        private const val PID = 4242
        private const val PACKAGE = "com.example.target"
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SIGNER =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val OTHER_SIGNER =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val MAPS =
            "70000000-70010000 r--p 00000000 103:02 42 /data/app/libsample.so\n" +
            "70020000-70040000 r-xp 00020000 103:02 42 /data/app/libsample.so\n"
    }
}
