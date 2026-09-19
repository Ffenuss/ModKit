package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.Serializable

data class RepackedRuntimeNativeLookupResult(
    val moduleName: String,
    val symbolName: String,
    val reply: RepackedRuntimeNativeLookupReply,
    val capture: RuntimeNativeTraceCapture?,
    val validation: RuntimeNativeTraceValidationResult,
) : Serializable {
    val resolved: Boolean
        get() =
            capture != null &&
                validation.acceptedObservationCount > 0
}

/**
 * Performs one targeted self-process dlsym observation against an installed,
 * ModKit-signed repacked test build.
 *
 * The executor is deliberately non-invasive:
 * - provider/package/signer identity is revalidated before the call;
 * - PID/package from query, runtime evidence and lookup reply must match;
 * - the probe-side native helper uses RTLD_NOLOAD, so it cannot load a target
 *   library merely to make the lookup succeed;
 * - an address of zero is treated as unresolved observation, never as negative
 *   proof that the symbol does not exist;
 * - RuntimeNativeLookupValidator still owns executable-map/module validation.
 */
object RepackedRuntimeNativeLookupCapture {
    private const val MAX_MODULE_CHARS = 255
    private const val MAX_SYMBOL_CHARS = 1024

    fun capture(
        build: RepackedRuntimeBuildResult,
        runtimeEvidence: RuntimeEvidenceBundle,
        procMapsText: String,
        moduleName: String,
        symbolName: String,
        transport: RepackedRuntimeNativeLookupTransport,
        cancellation: CancellationSignal,
    ): RepackedRuntimeNativeLookupResult {
        validateRequest(moduleName, symbolName)
        require(
            build.artifactSha256.equals(
                runtimeEvidence.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "Native lookup build artifact SHA does not match runtime evidence."
        }
        require(
            runtimeEvidence.captureSource ==
                ProcMapsCaptureSource.REPACKED_TEST_RUNTIME,
        ) {
            "Native lookup requires repacked test runtime evidence."
        }
        require(runtimeEvidence.processIdentityConfirmed) {
            "Native lookup requires independently confirmed process identity."
        }
        val processIdentity =
            requireNotNull(runtimeEvidence.processIdentity) {
                "Native lookup runtime process identity is missing."
            }
        require(processIdentity == build.packageName) {
            "Native lookup process identity does not match test build package."
        }
        val expectedPid = requireNotNull(runtimeEvidence.capturePid) {
            "Native lookup runtime PID is missing."
        }
        require(expectedPid > 0) {
            "Native lookup runtime PID is invalid."
        }

        val authority =
            build.packageName +
                BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX
        val installed = requireNotNull(
            transport.inspectInstalled(
                packageName = build.packageName,
                authority = authority,
            ),
        ) {
            "Installed repacked test probe provider was not found."
        }
        RepackedRuntimeProbeIdentityVerifier.verify(
            build = build,
            installed = installed,
        )

        checkCancelled(cancellation)
        val query = transport.query(authority)
        require(
            query.schemaVersion ==
                RuntimeEvidenceProviderContract.SCHEMA_VERSION,
        ) {
            "Runtime native lookup query schema version is unsupported."
        }
        require(query.packageName == build.packageName) {
            "Runtime native lookup query package identity mismatch."
        }
        require(query.pid == expectedPid) {
            "Runtime native lookup PID no longer matches captured runtime evidence."
        }

        checkCancelled(cancellation)
        val reply = transport.resolveLoadedSymbol(
            authority = authority,
            moduleName = moduleName,
            symbolName = symbolName,
        )
        require(
            reply.schemaVersion ==
                RuntimeEvidenceProviderContract.SCHEMA_VERSION,
        ) {
            "Runtime native lookup reply schema version is unsupported."
        }
        require(reply.packageName == query.packageName) {
            "Runtime native lookup reply package identity mismatch."
        }
        require(reply.pid == query.pid) {
            "Runtime native lookup PID changed during the lookup."
        }
        require(reply.moduleName == moduleName) {
            "Runtime native lookup reply module identity mismatch."
        }
        require(reply.symbolName == symbolName) {
            "Runtime native lookup reply symbol identity mismatch."
        }

        if (reply.resolvedRuntimeAddress <= 0L) {
            return RepackedRuntimeNativeLookupResult(
                moduleName = moduleName,
                symbolName = symbolName,
                reply = reply,
                capture = null,
                validation = RuntimeNativeTraceValidationResult(
                    observations = emptyList(),
                    rejectedEvents = 0,
                    blockers = listOf(
                        "Loaded-module dlsym did not resolve the requested symbol; no negative proof is inferred.",
                    ),
                ),
            )
        }

        val trace = RuntimeNativeTraceParser.capture(
            artifactSha256 = runtimeEvidence.artifactSha256,
            processIdentity = processIdentity,
            processIdentityConfirmed = true,
            pid = expectedPid,
            source = RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            text = buildString {
                append("DLSYM")
                append('\t')
                append(moduleName)
                append('\t')
                append(symbolName)
                append('\t')
                append("0x")
                append(reply.resolvedRuntimeAddress.toString(16))
            },
        )
        val validation = RuntimeNativeLookupValidator.validate(
            capture = trace,
            runtimeEvidence = runtimeEvidence,
            procMapsText = procMapsText,
        )
        return RepackedRuntimeNativeLookupResult(
            moduleName = moduleName,
            symbolName = symbolName,
            reply = reply,
            capture = trace,
            validation = validation,
        )
    }

    fun attach(
        runtimeEvidence: RuntimeEvidenceBundle,
        result: RepackedRuntimeNativeLookupResult,
    ): RuntimeEvidenceBundle =
        RuntimeNativeLookupValidator.attach(
            runtimeEvidence = runtimeEvidence,
            validation = result.validation,
        )

    private fun validateRequest(
        moduleName: String,
        symbolName: String,
    ) {
        require(
            moduleName.isNotBlank() &&
                moduleName.length <= MAX_MODULE_CHARS &&
                moduleName.endsWith(".so") &&
                '/' !in moduleName &&
                '\\' !in moduleName &&
                ".." !in moduleName,
        ) {
            "Runtime native lookup module name is invalid."
        }
        require(
            symbolName.isNotBlank() &&
                symbolName.length <= MAX_SYMBOL_CHARS &&
                symbolName.none {
                    it.isWhitespace() || it.isISOControl()
                },
        ) {
            "Runtime native lookup symbol name is invalid."
        }
    }

    private fun checkCancelled(
        cancellation: CancellationSignal,
    ) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
