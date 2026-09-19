package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal

data class RepackedRuntimePassiveTraceSession(
    val packageName: String,
    val authority: String,
    val pid: Int,
    val sessionId: String,
    val startedAtEpochMs: Long,
)

data class RepackedRuntimePassiveTraceResult(
    val session: RepackedRuntimePassiveTraceSession,
    val status: RepackedRuntimeNativeTraceStatus,
    val export: RepackedRuntimeNativeTraceExport,
    val capture: RuntimeNativeTraceCapture,
)

/**
 * Binds a future passive native-trace producer to an exact installed repacked
 * test process. This owns only session/control/export integrity; it does not
 * claim that a producer is currently registered.
 */
object RepackedRuntimePassiveTraceSessionCapture {
    fun start(
        build: RepackedRuntimeBuildResult,
        transport: RepackedRuntimePassiveTraceTransport,
        cancellation: CancellationSignal,
    ): RepackedRuntimePassiveTraceSession {
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
        requireBaseIdentity(
            build = build,
            schemaVersion = query.schemaVersion,
            packageName = query.packageName,
            pid = query.pid,
        )

        checkCancelled(cancellation)
        val status =
            transport.startNativeTrace(authority)
        requireStatusIdentity(
            build = build,
            expectedPid = query.pid,
            status = status,
        )
        require(status.active) {
            "Native trace session did not become active."
        }
        require(!status.truncated) {
            "Native trace session was truncated at start."
        }
        require(status.sessionId.isNotBlank()) {
            "Native trace session ID is missing."
        }
        require(status.startedAtEpochMs > 0L) {
            "Native trace session start time is invalid."
        }
        require(status.stoppedAtEpochMs == 0L) {
            "Fresh native trace session already has a stop time."
        }
        require(
            status.eventCount == 0 &&
                status.traceBytes == 0,
        ) {
            "Fresh native trace session is not empty."
        }

        return RepackedRuntimePassiveTraceSession(
            packageName = build.packageName,
            authority = authority,
            pid = query.pid,
            sessionId = status.sessionId,
            startedAtEpochMs =
                status.startedAtEpochMs,
        )
    }

    fun stopAndRead(
        build: RepackedRuntimeBuildResult,
        session: RepackedRuntimePassiveTraceSession,
        transport: RepackedRuntimePassiveTraceTransport,
        cancellation: CancellationSignal,
    ): RepackedRuntimePassiveTraceResult {
        require(session.packageName == build.packageName) {
            "Native trace session package does not match test build."
        }
        val expectedAuthority =
            build.packageName +
                BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX
        require(session.authority == expectedAuthority) {
            "Native trace session authority does not match test build."
        }

        val installed = requireNotNull(
            transport.inspectInstalled(
                packageName = build.packageName,
                authority = expectedAuthority,
            ),
        ) {
            "Installed repacked test probe provider was not found."
        }
        RepackedRuntimeProbeIdentityVerifier.verify(
            build = build,
            installed = installed,
        )

        checkCancelled(cancellation)
        val query =
            transport.query(expectedAuthority)
        requireBaseIdentity(
            build = build,
            schemaVersion = query.schemaVersion,
            packageName = query.packageName,
            pid = query.pid,
        )
        require(query.pid == session.pid) {
            "Native trace process PID changed before stop."
        }

        checkCancelled(cancellation)
        val status =
            transport.stopNativeTrace(
                expectedAuthority,
            )
        requireStatusIdentity(
            build = build,
            expectedPid = session.pid,
            status = status,
        )
        require(!status.active) {
            "Native trace session remained active after stop."
        }
        require(status.sessionId == session.sessionId) {
            "Native trace session ID changed before export."
        }
        require(
            status.startedAtEpochMs ==
                session.startedAtEpochMs,
        ) {
            "Native trace session start time changed."
        }
        require(
            status.stoppedAtEpochMs >=
                status.startedAtEpochMs,
        ) {
            "Native trace session stop time is invalid."
        }
        require(
            status.eventCount in
                0..RuntimeNativeTraceParser.MAX_EVENTS,
        ) {
            "Native trace session event count is invalid."
        }
        require(
            status.traceBytes in
                0..RuntimeNativeTraceParser.MAX_TRACE_BYTES,
        ) {
            "Native trace session byte count is invalid."
        }

        checkCancelled(cancellation)
        val raw = transport.readNativeTrace(
            authority = expectedAuthority,
            cancellation = cancellation,
        )
        val export =
            RepackedRuntimeNativeTraceExportProtocol
                .parse(raw)
        require(export.packageName == build.packageName) {
            "Native trace export package identity mismatch."
        }
        require(export.processIdentity == build.packageName) {
            "Native trace export process identity mismatch."
        }
        require(export.pid == session.pid) {
            "Native trace export PID mismatch."
        }
        require(export.sessionId == session.sessionId) {
            "Native trace export session ID mismatch."
        }
        require(
            export.startedAtEpochMs ==
                session.startedAtEpochMs &&
                export.stoppedAtEpochMs ==
                status.stoppedAtEpochMs,
        ) {
            "Native trace export timestamps do not match session status."
        }
        require(
            export.eventCount == status.eventCount &&
                export.traceBytes == status.traceBytes &&
                export.truncated == status.truncated,
        ) {
            "Native trace export counters do not match session status."
        }

        return RepackedRuntimePassiveTraceResult(
            session = session,
            status = status,
            export = export,
            capture =
                RepackedRuntimeNativeTraceExportProtocol
                    .toCapture(
                        export = export,
                        artifactSha256 =
                            build.artifactSha256,
                        source =
                            RuntimeNativeTraceSource
                                .REPACKED_TEST_RUNTIME,
                    ),
        )
    }

    private fun requireBaseIdentity(
        build: RepackedRuntimeBuildResult,
        schemaVersion: Int,
        packageName: String,
        pid: Int,
    ) {
        require(
            schemaVersion ==
                RuntimeEvidenceProviderContract
                    .SCHEMA_VERSION,
        ) {
            "Native trace provider schema version is unsupported."
        }
        require(packageName == build.packageName) {
            "Native trace provider package identity mismatch."
        }
        require(pid > 0) {
            "Native trace provider PID is invalid."
        }
    }

    private fun requireStatusIdentity(
        build: RepackedRuntimeBuildResult,
        expectedPid: Int,
        status: RepackedRuntimeNativeTraceStatus,
    ) {
        requireBaseIdentity(
            build = build,
            schemaVersion = status.schemaVersion,
            packageName = status.packageName,
            pid = status.pid,
        )
        require(status.pid == expectedPid) {
            "Native trace status PID changed."
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
