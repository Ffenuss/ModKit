package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.build.ApkSignatureVerification
import io.github.ffenuss.modkit.build.InstallabilityVerification
import io.github.ffenuss.modkit.build.ZipAlignmentVerification
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeNativeLookupCaptureTest {
    @Test
    fun exactSignedPidBoundLookupBecomesConfirmedObservation() {
        val maps = mapsText()
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            reply = reply(address = 0x70020100),
        )

        val result = RepackedRuntimeNativeLookupCapture.capture(
            build = buildResult(),
            runtimeEvidence = runtimeEvidence(maps),
            procMapsText = maps,
            moduleName = MODULE,
            symbolName = SYMBOL,
            transport = transport,
            cancellation = AtomicCancellationSignal(),
        )

        assertTrue(result.resolved)
        assertTrue(result.capture != null)
        assertEquals(1, result.validation.acceptedObservationCount)
        val observation = result.validation.observations.single()
        assertEquals(
            RuntimeEvidenceObservationStrength.CONFIRMED,
            observation.strength,
        )
        assertEquals(
            RuntimeEvidenceObservationKind.JNI_DLSYM_OBSERVED,
            observation.kind,
        )
        assertEquals(1, transport.resolveCalls)
    }

    @Test
    fun zeroAddressIsUnresolvedWithoutNegativeProof() {
        val maps = mapsText()
        val result = RepackedRuntimeNativeLookupCapture.capture(
            build = buildResult(),
            runtimeEvidence = runtimeEvidence(maps),
            procMapsText = maps,
            moduleName = MODULE,
            symbolName = SYMBOL,
            transport = FakeTransport(
                installed = installedProbe(),
                query = query(),
                reply = reply(address = 0),
            ),
            cancellation = AtomicCancellationSignal(),
        )

        assertFalse(result.resolved)
        assertNull(result.capture)
        assertTrue(result.validation.observations.isEmpty())
        assertTrue(
            result.validation.blockers.any {
                "no negative proof" in it
            },
        )
    }

    @Test
    fun signerMismatchBlocksBeforeQueryAndLookup() {
        val maps = mapsText()
        val transport = FakeTransport(
            installed = installedProbe(signer = OTHER_SIGNER),
            query = query(),
            reply = reply(address = 0x70020100),
        )

        val failure = runCatching {
            RepackedRuntimeNativeLookupCapture.capture(
                build = buildResult(),
                runtimeEvidence = runtimeEvidence(maps),
                procMapsText = maps,
                moduleName = MODULE,
                symbolName = SYMBOL,
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
        assertEquals(0, transport.resolveCalls)
    }

    @Test
    fun pidRaceBlocksBeforeNativeLookup() {
        val maps = mapsText()
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(pid = PID + 1),
            reply = reply(address = 0x70020100),
        )

        val failure = runCatching {
            RepackedRuntimeNativeLookupCapture.capture(
                build = buildResult(),
                runtimeEvidence = runtimeEvidence(maps),
                procMapsText = maps,
                moduleName = MODULE,
                symbolName = SYMBOL,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "no longer matches",
            ),
        )
        assertEquals(0, transport.resolveCalls)
    }

    @Test
    fun replyIdentityMismatchFailsClosed() {
        val maps = mapsText()
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            reply = reply(
                address = 0x70020100,
                symbol = "OtherSymbol",
            ),
        )

        val failure = runCatching {
            RepackedRuntimeNativeLookupCapture.capture(
                build = buildResult(),
                runtimeEvidence = runtimeEvidence(maps),
                procMapsText = maps,
                moduleName = MODULE,
                symbolName = SYMBOL,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "symbol identity mismatch",
            ),
        )
    }

    @Test
    fun executableAddressWithoutConfirmedElfMappingStaysObserved() {
        val maps = mapsText()
        val runtime = runtimeEvidence(maps).copy(
            moduleMappings = emptyList(),
        )

        val result = RepackedRuntimeNativeLookupCapture.capture(
            build = buildResult(),
            runtimeEvidence = runtime,
            procMapsText = maps,
            moduleName = MODULE,
            symbolName = SYMBOL,
            transport = FakeTransport(
                installed = installedProbe(),
                query = query(),
                reply = reply(address = 0x70020100),
            ),
            cancellation = AtomicCancellationSignal(),
        )

        assertFalse(result.resolved)
        assertEquals(
            RuntimeEvidenceObservationStrength.OBSERVED,
            result.validation.observations.single().strength,
        )
    }

    @Test
    fun malformedModuleAndSymbolAreRejectedBeforeTransport() {
        val maps = mapsText()
        val transport = FakeTransport(
            installed = installedProbe(),
            query = query(),
            reply = reply(address = 0x70020100),
        )

        val moduleFailure = runCatching {
            RepackedRuntimeNativeLookupCapture.capture(
                build = buildResult(),
                runtimeEvidence = runtimeEvidence(maps),
                procMapsText = maps,
                moduleName = "../libsample.so",
                symbolName = SYMBOL,
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()
        assertTrue(moduleFailure is IllegalArgumentException)

        val symbolFailure = runCatching {
            RepackedRuntimeNativeLookupCapture.capture(
                build = buildResult(),
                runtimeEvidence = runtimeEvidence(maps),
                procMapsText = maps,
                moduleName = MODULE,
                symbolName = "bad symbol",
                transport = transport,
                cancellation = AtomicCancellationSignal(),
            )
        }.exceptionOrNull()
        assertTrue(symbolFailure is IllegalArgumentException)
        assertEquals(0, transport.queryCalls)
        assertEquals(0, transport.resolveCalls)
    }

    private class FakeTransport(
        private val installed: RepackedRuntimeInstalledProbe?,
        private val query: RepackedRuntimeProbeQuery,
        private val reply: RepackedRuntimeNativeLookupReply,
    ) : RepackedRuntimeNativeLookupTransport {
        var queryCalls: Int = 0
            private set
        var resolveCalls: Int = 0
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

        override fun resolveLoadedSymbol(
            authority: String,
            moduleName: String,
            symbolName: String,
        ): RepackedRuntimeNativeLookupReply {
            resolveCalls++
            return reply
        }

        override fun readEvidence(
            authority: String,
            cancellation:
                io.github.ffenuss.modkit.analysis.CancellationSignal,
            maxBytes: Int,
        ): ByteArray =
            error("readEvidence is not used by targeted lookup")
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

    private fun reply(
        address: Long,
        module: String = MODULE,
        symbol: String = SYMBOL,
        pid: Int = PID,
    ) = RepackedRuntimeNativeLookupReply(
        schemaVersion =
            RuntimeEvidenceProviderContract.SCHEMA_VERSION,
        packageName = PACKAGE,
        pid = pid,
        moduleName = module,
        symbolName = symbol,
        resolvedRuntimeAddress = address,
    )

    private fun runtimeEvidence(
        maps: String,
    ) = RuntimeEvidenceBundle(
        artifactSha256 = ARTIFACT_SHA,
        procMapsSha256 = sha256(
            maps.toByteArray(Charsets.UTF_8),
        ),
        moduleMappings = listOf(
            RuntimeModuleMappingEvidence(
                moduleName = MODULE,
                mappedPath =
                    "/data/app/pkg/lib/arm64/$MODULE",
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
        captureSource =
            ProcMapsCaptureSource.REPACKED_TEST_RUNTIME,
        capturePid = PID,
        capturedAtEpochMs = 1234,
        processIdentity = PACKAGE,
        processIdentityConfirmed = true,
    )

    private fun mapsText() = """
        70000000-70010000 r--p 00000000 103:02 42 /data/app/pkg/lib/arm64/$MODULE
        70020000-70040000 r-xp 00020000 103:02 42 /data/app/pkg/lib/arm64/$MODULE
    """.trimIndent()

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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

    companion object {
        private const val PID = 456
        private const val PACKAGE = "com.example.target"
        private const val MODULE = "libsample.so"
        private const val SYMBOL = "ResolveTarget"
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SIGNER =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val OTHER_SIGNER =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
