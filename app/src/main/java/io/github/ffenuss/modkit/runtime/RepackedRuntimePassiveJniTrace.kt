package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.security.MessageDigest

data class RepackedRuntimePassiveJniTraceStatus(
    val schemaVersion: Int,
    val packageName: String,
    val pid: Int,
    val sessionId: String,
    val active: Boolean,
    val truncated: Boolean,
    val eventCount: Int,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val traceBytes: Int,
    val producerKind: String,
    val producerReady: Boolean,
    val producerActive: Boolean,
    val jniOnLoadLookupHookedSlotCount: Int,
    val registerNativesHooked: Boolean,
    val producerIncomplete: Boolean,
    val producerRestoreFailed: Boolean,
)

data class RepackedRuntimePassiveJniTraceSession(
    val packageName: String,
    val authority: String,
    val pid: Int,
    val sessionId: String,
    val startedAtEpochMs: Long,
)

data class RepackedRuntimePassiveJniTraceExport(
    val packageName: String,
    val pid: Int,
    val processIdentity: String,
    val sessionId: String,
    val startedAtEpochMs: Long,
    val stoppedAtEpochMs: Long,
    val eventCount: Int,
    val traceSha256: String,
    val traceBytes: Int,
    val truncated: Boolean,
    val producerKind: String,
    val jniOnLoadLookupHookedSlotCount: Int,
    val registerNativesHooked: Boolean,
    val producerIncomplete: Boolean,
    val producerRestoreFailed: Boolean,
    val text: String,
)

data class RepackedRuntimePassiveJniTraceResult(
    val session: RepackedRuntimePassiveJniTraceSession,
    val status: RepackedRuntimePassiveJniTraceStatus,
    val export: RepackedRuntimePassiveJniTraceExport,
    val capture: RuntimeNativeTraceCapture,
)

data class RepackedRuntimePassiveJniTraceValidationExecution(
    val trace: RepackedRuntimePassiveJniTraceResult,
    val mapsCapture: RepackedRuntimeProbeCaptureResult,
    val runtimeEvidence: RuntimeEvidenceBundle,
    val validation: RuntimeNativeTraceValidationResult,
    val attachedEvidence: RuntimeEvidenceBundle,
)

interface RepackedRuntimePassiveJniTraceTransport :
    RepackedRuntimeProbeTransport {
    fun startPassiveJniTrace(
        authority: String,
    ): RepackedRuntimePassiveJniTraceStatus

    fun stopPassiveJniTrace(
        authority: String,
    ): RepackedRuntimePassiveJniTraceStatus

    fun passiveJniTraceStatus(
        authority: String,
    ): RepackedRuntimePassiveJniTraceStatus

    fun readPassiveJniTrace(
        authority: String,
        cancellation: CancellationSignal,
        maxBytes: Int =
            RepackedRuntimePassiveJniTraceExportProtocol.MAX_EXPORT_BYTES,
    ): ByteArray
}

object RepackedRuntimePassiveJniTraceExportProtocol {
    const val HEADER_MAGIC = "MODKIT_NATIVE_JNI_TRACE_V1"
    val TRACE_DELIMITER: ByteArray =
        "\n---TRACE---\n".toByteArray(Charsets.UTF_8)
    const val MAX_EXPORT_BYTES =
        RuntimeNativeTraceParser.MAX_TRACE_BYTES + 64 * 1024

    fun parse(
        bytes: ByteArray,
    ): RepackedRuntimePassiveJniTraceExport {
        require(bytes.isNotEmpty()) {
            "Runtime passive JNI trace export is empty."
        }
        require(bytes.size <= MAX_EXPORT_BYTES) {
            "Runtime passive JNI trace export exceeds bounded size."
        }
        val delimiterOffset = indexOf(bytes, TRACE_DELIMITER)
        require(delimiterOffset > 0) {
            "Runtime passive JNI trace delimiter is missing."
        }
        val headerText = String(
            bytes,
            0,
            delimiterOffset,
            Charsets.UTF_8,
        )
        val lines = headerText.split('\n')
        require(lines.firstOrNull() == HEADER_MAGIC) {
            "Runtime passive JNI trace header magic is invalid."
        }

        val fields = linkedMapOf<String, String>()
        lines.drop(1)
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator in 1 until line.lastIndex) {
                    "Runtime passive JNI trace header field is malformed."
                }
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                require(fields.put(key, value) == null) {
                    "Runtime passive JNI trace header contains duplicate field: $key"
                }
            }

        val required = setOf(
            "packageName",
            "pid",
            "processIdentity",
            "sessionId",
            "startedAtEpochMs",
            "stoppedAtEpochMs",
            "eventCount",
            "traceSha256",
            "traceBytes",
            "truncated",
            "producerKind",
            "jniOnLoadLookupHookedSlotCount",
            "registerNativesHooked",
            "producerIncomplete",
            "producerRestoreFailed",
        )
        require(fields.keys == required) {
            "Runtime passive JNI trace header fields do not match schema."
        }

        val packageName = fields.getValue("packageName")
        val pid = fields.getValue("pid").toIntOrNull()
        val processIdentity = fields.getValue("processIdentity")
        val sessionId = fields.getValue("sessionId")
        val startedAt =
            fields.getValue("startedAtEpochMs").toLongOrNull()
        val stoppedAt =
            fields.getValue("stoppedAtEpochMs").toLongOrNull()
        val eventCount =
            fields.getValue("eventCount").toIntOrNull()
        val declaredBytes =
            fields.getValue("traceBytes").toIntOrNull()
        val truncated =
            fields.getValue("truncated").toBooleanStrictOrNull()
        val producerKind = fields.getValue("producerKind")
        val jniOnLoadLookupHookedSlotCount =
            fields.getValue("jniOnLoadLookupHookedSlotCount")
                .toIntOrNull()
        val registerNativesHooked =
            fields.getValue("registerNativesHooked")
                .toBooleanStrictOrNull()
        val producerIncomplete =
            fields.getValue("producerIncomplete")
                .toBooleanStrictOrNull()
        val producerRestoreFailed =
            fields.getValue("producerRestoreFailed")
                .toBooleanStrictOrNull()

        require(packageName.isNotBlank()) {
            "Runtime passive JNI trace package name is empty."
        }
        require(pid != null && pid > 0) {
            "Runtime passive JNI trace PID is invalid."
        }
        require(processIdentity.isNotBlank()) {
            "Runtime passive JNI trace process identity is empty."
        }
        require(
            sessionId.isNotBlank() &&
                sessionId.length <= 256 &&
                sessionId.none {
                    it.isWhitespace() || it.isISOControl()
                },
        ) {
            "Runtime passive JNI trace session identity is invalid."
        }
        require(startedAt != null && startedAt > 0L) {
            "Runtime passive JNI trace start time is invalid."
        }
        require(
            stoppedAt != null &&
                stoppedAt >= startedAt,
        ) {
            "Runtime passive JNI trace stop time is invalid."
        }
        require(
            eventCount != null &&
                eventCount in 0..RuntimeNativeTraceParser.MAX_EVENTS,
        ) {
            "Runtime passive JNI trace event count is invalid."
        }
        require(
            declaredBytes != null &&
                declaredBytes in
                0..RuntimeNativeTraceParser.MAX_TRACE_BYTES,
        ) {
            "Runtime passive JNI trace byte count is invalid."
        }
        require(truncated != null) {
            "Runtime passive JNI trace truncation flag is invalid."
        }
        require(producerKind == "ART_JNI_ONLOAD_LOOKUP_JNI_TABLE") {
            "Runtime passive JNI trace producer kind is unsupported."
        }
        require(
            jniOnLoadLookupHookedSlotCount != null &&
                jniOnLoadLookupHookedSlotCount in 1..16,
        ) {
            "Runtime passive JNI trace JNI_OnLoad hook count is invalid."
        }
        require(registerNativesHooked == true) {
            "Runtime passive JNI trace RegisterNatives hook is missing."
        }
        require(producerIncomplete != null) {
            "Runtime passive JNI trace producer-incomplete flag is invalid."
        }
        require(producerRestoreFailed != null) {
            "Runtime passive JNI trace restore-failed flag is invalid."
        }

        val traceStart = delimiterOffset + TRACE_DELIMITER.size
        val trace = bytes.copyOfRange(traceStart, bytes.size)
        require(trace.size == declaredBytes) {
            "Runtime passive JNI trace byte count mismatch."
        }
        val declaredSha = fields.getValue("traceSha256")
        require(declaredSha.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Runtime passive JNI trace SHA-256 format is invalid."
        }
        require(
            sha256(trace).equals(
                declaredSha,
                ignoreCase = true,
            ),
        ) {
            "Runtime passive JNI trace SHA-256 mismatch."
        }
        val text = trace.toString(Charsets.UTF_8)
        val lineCount = text.lineSequence()
            .count { it.isNotBlank() }
        require(lineCount == eventCount) {
            "Runtime passive JNI trace event count does not match trace lines."
        }

        return RepackedRuntimePassiveJniTraceExport(
            packageName = packageName,
            pid = pid,
            processIdentity = processIdentity,
            sessionId = sessionId,
            startedAtEpochMs = startedAt,
            stoppedAtEpochMs = stoppedAt,
            eventCount = eventCount,
            traceSha256 = declaredSha.lowercase(),
            traceBytes = declaredBytes,
            truncated = truncated,
            producerKind = producerKind,
            jniOnLoadLookupHookedSlotCount =
                jniOnLoadLookupHookedSlotCount,
            registerNativesHooked = registerNativesHooked,
            producerIncomplete = producerIncomplete,
            producerRestoreFailed = producerRestoreFailed,
            text = text,
        )
    }

    fun toCapture(
        export: RepackedRuntimePassiveJniTraceExport,
        artifactSha256: String,
    ): RuntimeNativeTraceCapture =
        RuntimeNativeTraceCapture(
            artifactSha256 = artifactSha256,
            processIdentity = export.processIdentity,
            processIdentityConfirmed = true,
            pid = export.pid,
            source =
                RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            capturedAtEpochMs = export.stoppedAtEpochMs,
            text = export.text,
            sha256 = export.traceSha256,
            truncated = export.truncated,
            acquisitionMode =
                RuntimeNativeAcquisitionMode.PASSIVE_TRACE,
        )

    private fun indexOf(
        bytes: ByteArray,
        needle: ByteArray,
    ): Int {
        if (needle.isEmpty() || needle.size > bytes.size) {
            return -1
        }
        outer@ for (start in 0..bytes.size - needle.size) {
            for (index in needle.indices) {
                if (bytes[start + index] != needle[index]) {
                    continue@outer
                }
            }
            return start
        }
        return -1
    }

    private fun sha256(
        bytes: ByteArray,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
}

object RepackedRuntimePassiveJniTraceSessionCapture {
    fun start(
        build: RepackedRuntimeBuildResult,
        transport: RepackedRuntimePassiveJniTraceTransport,
        cancellation: CancellationSignal,
    ): RepackedRuntimePassiveJniTraceSession {
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
        requireBaseIdentity(build, query)

        checkCancelled(cancellation)
        val status = transport.startPassiveJniTrace(authority)
        requireStatusIdentity(build, query.pid, status)
        require(status.producerKind == "ART_JNI_ONLOAD_LOOKUP_JNI_TABLE") {
            "Passive JNI producer kind is unsupported."
        }
        require(status.producerReady) {
            "Passive JNI producer failed to start."
        }
        require(status.producerActive) {
            "Passive JNI producer is not active."
        }
        require(status.jniOnLoadLookupHookedSlotCount > 0) {
            "Passive JNI producer did not hook ART JNI_OnLoad lookup."
        }
        require(status.registerNativesHooked) {
            "Passive JNI producer did not hook RegisterNatives."
        }
        require(!status.producerRestoreFailed) {
            "Passive JNI producer reports a prior restore failure."
        }
        require(status.active) {
            "Passive JNI trace session did not become active."
        }
        require(!status.truncated) {
            "Passive JNI trace session was truncated at start."
        }
        require(status.sessionId.isNotBlank()) {
            "Passive JNI trace session ID is missing."
        }
        require(status.startedAtEpochMs > 0L) {
            "Passive JNI trace session start time is invalid."
        }
        require(status.stoppedAtEpochMs == 0L) {
            "Fresh passive JNI trace session already has a stop time."
        }
        require(
            status.eventCount == 0 &&
                status.traceBytes == 0,
        ) {
            "Fresh passive JNI trace session is not empty."
        }

        return RepackedRuntimePassiveJniTraceSession(
            packageName = build.packageName,
            authority = authority,
            pid = query.pid,
            sessionId = status.sessionId,
            startedAtEpochMs = status.startedAtEpochMs,
        )
    }

    fun stopAndRead(
        build: RepackedRuntimeBuildResult,
        session: RepackedRuntimePassiveJniTraceSession,
        transport: RepackedRuntimePassiveJniTraceTransport,
        cancellation: CancellationSignal,
    ): RepackedRuntimePassiveJniTraceResult {
        require(session.packageName == build.packageName) {
            "Passive JNI trace session package does not match test build."
        }
        val expectedAuthority =
            build.packageName +
                BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX
        require(session.authority == expectedAuthority) {
            "Passive JNI trace session authority does not match test build."
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
        val query = transport.query(expectedAuthority)
        requireBaseIdentity(build, query)
        require(query.pid == session.pid) {
            "Passive JNI trace process PID changed before stop."
        }

        checkCancelled(cancellation)
        val status =
            transport.stopPassiveJniTrace(expectedAuthority)
        requireStatusIdentity(build, session.pid, status)
        require(status.producerKind == "ART_JNI_ONLOAD_LOOKUP_JNI_TABLE") {
            "Passive JNI producer kind changed before export."
        }
        require(status.producerReady) {
            "Passive JNI producer did not stop cleanly."
        }
        require(!status.producerActive) {
            "Passive JNI producer remained active after stop."
        }
        require(status.jniOnLoadLookupHookedSlotCount > 0) {
            "Passive JNI producer lost JNI_OnLoad hook provenance."
        }
        require(status.registerNativesHooked) {
            "Passive JNI producer lost RegisterNatives hook provenance."
        }
        require(!status.producerRestoreFailed) {
            "Passive JNI producer failed to restore hooks."
        }
        require(!status.active) {
            "Passive JNI trace session remained active after stop."
        }
        require(status.sessionId == session.sessionId) {
            "Passive JNI trace session ID changed before export."
        }
        require(status.startedAtEpochMs == session.startedAtEpochMs) {
            "Passive JNI trace session start time changed."
        }
        require(status.stoppedAtEpochMs >= status.startedAtEpochMs) {
            "Passive JNI trace session stop time is invalid."
        }
        require(
            status.eventCount in
                0..RuntimeNativeTraceParser.MAX_EVENTS,
        ) {
            "Passive JNI trace session event count is invalid."
        }
        require(
            status.traceBytes in
                0..RuntimeNativeTraceParser.MAX_TRACE_BYTES,
        ) {
            "Passive JNI trace session byte count is invalid."
        }

        checkCancelled(cancellation)
        val raw = transport.readPassiveJniTrace(
            authority = expectedAuthority,
            cancellation = cancellation,
        )
        val export =
            RepackedRuntimePassiveJniTraceExportProtocol.parse(raw)
        require(export.packageName == build.packageName) {
            "Passive JNI trace export package identity mismatch."
        }
        require(export.processIdentity == build.packageName) {
            "Passive JNI trace export process identity mismatch."
        }
        require(export.pid == session.pid) {
            "Passive JNI trace export PID mismatch."
        }
        require(export.sessionId == session.sessionId) {
            "Passive JNI trace export session ID mismatch."
        }
        require(
            export.startedAtEpochMs == session.startedAtEpochMs &&
                export.stoppedAtEpochMs ==
                status.stoppedAtEpochMs,
        ) {
            "Passive JNI trace export timestamps do not match session status."
        }
        require(
            export.eventCount == status.eventCount &&
                export.traceBytes == status.traceBytes &&
                export.truncated == status.truncated,
        ) {
            "Passive JNI trace export counters do not match session status."
        }
        require(
            export.producerKind == status.producerKind &&
                export.jniOnLoadLookupHookedSlotCount ==
                status.jniOnLoadLookupHookedSlotCount &&
                export.registerNativesHooked ==
                status.registerNativesHooked &&
                export.producerIncomplete ==
                status.producerIncomplete &&
                export.producerRestoreFailed ==
                status.producerRestoreFailed,
        ) {
            "Passive JNI trace export producer provenance does not match session status."
        }

        return RepackedRuntimePassiveJniTraceResult(
            session = session,
            status = status,
            export = export,
            capture =
                RepackedRuntimePassiveJniTraceExportProtocol
                    .toCapture(
                        export = export,
                        artifactSha256 = build.artifactSha256,
                    ),
        )
    }

    private fun requireBaseIdentity(
        build: RepackedRuntimeBuildResult,
        query: RepackedRuntimeProbeQuery,
    ) {
        require(
            query.schemaVersion ==
                RuntimeEvidenceProviderContract.SCHEMA_VERSION,
        ) {
            "Passive JNI trace provider schema version is unsupported."
        }
        require(query.packageName == build.packageName) {
            "Passive JNI trace provider package identity mismatch."
        }
        require(query.pid > 0) {
            "Passive JNI trace provider PID is invalid."
        }
    }

    private fun requireStatusIdentity(
        build: RepackedRuntimeBuildResult,
        expectedPid: Int,
        status: RepackedRuntimePassiveJniTraceStatus,
    ) {
        require(
            status.schemaVersion ==
                RuntimeEvidenceProviderContract.SCHEMA_VERSION,
        ) {
            "Passive JNI trace status schema version is unsupported."
        }
        require(status.packageName == build.packageName) {
            "Passive JNI trace status package identity mismatch."
        }
        require(status.pid == expectedPid && status.pid > 0) {
            "Passive JNI trace status PID changed."
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

object RepackedRuntimePassiveJniTraceCoordinator {
    fun stopAndValidate(
        build: RepackedRuntimeBuildResult,
        session: RepackedRuntimePassiveJniTraceSession,
        workspace: AnalysisWorkspace,
        transport: RepackedRuntimePassiveJniTraceTransport,
        tempRoot: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimePassiveJniTraceValidationExecution {
        require(
            build.artifactSha256.equals(
                workspace.index.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "Passive JNI trace build artifact SHA does not match active workspace."
        }

        val trace =
            RepackedRuntimePassiveJniTraceSessionCapture
                .stopAndRead(
                    build = build,
                    session = session,
                    transport = transport,
                    cancellation = cancellation,
                )

        checkCancelled(cancellation)
        val mapsCapture = RepackedRuntimeEvidenceCapture.capture(
            build = build,
            transport = transport,
            cancellation = cancellation,
        )
        require(mapsCapture.pid == session.pid) {
            "Passive JNI trace PID changed before bound maps validation."
        }
        require(mapsCapture.processIdentity == build.packageName) {
            "Passive JNI trace maps process identity mismatch."
        }

        val parsed =
            RuntimeNativeTraceParser.parse(trace.capture)
        val descriptive = mapsCapture.toEvidenceBundle(
            artifactSha256 = workspace.index.artifactSha256,
            artifactEntries = workspace.index.entries,
        )

        val mappings =
            mutableListOf<RuntimeModuleMappingEvidence>()
        val mappingBlockers = mutableListOf<String>()
        parsed.events
            .map { it.moduleName }
            .distinct()
            .forEach { moduleName ->
                checkCancelled(cancellation)
                try {
                    val resolved =
                        RepackedRuntimeNativeLookupCoordinator
                            .collectModuleEvidence(
                                workspace = workspace,
                                mapsCapture = mapsCapture,
                                moduleName = moduleName,
                                tempRoot = tempRoot,
                                cancellation = cancellation,
                            )
                    mappings +=
                        resolved.evidence.moduleMappings
                } catch (failure: AnalysisCancelledException) {
                    throw failure
                } catch (failure: Throwable) {
                    mappingBlockers +=
                        "module=$moduleName: " +
                            (
                                failure.message
                                    ?: failure.javaClass.simpleName
                                )
                }
            }

        val producerBlockers = buildList {
            if (trace.status.producerIncomplete) {
                add(
                    "Passive JNI producer reported incomplete hook coverage; " +
                        "no negative proof may be inferred from missing events.",
                )
            }
        }
        val evidence = descriptive.copy(
            moduleMappings =
                mappings.distinctBy {
                    it.moduleName to it.mappedPath
                },
            blockers = (
                descriptive.blockers +
                    mappingBlockers +
                    producerBlockers
                ).distinct(),
            processIdentity = mapsCapture.processIdentity,
            processIdentityConfirmed = true,
        )

        val validation =
            RuntimeNativeLookupValidator.validate(
                capture = trace.capture,
                runtimeEvidence = evidence,
                procMapsText = mapsCapture.maps.text,
            )
        val attached =
            RuntimeNativeLookupValidator.attach(
                runtimeEvidence = evidence,
                validation = validation,
            )

        return RepackedRuntimePassiveJniTraceValidationExecution(
            trace = trace,
            mapsCapture = mapsCapture,
            runtimeEvidence = evidence,
            validation = validation,
            attachedEvidence = attached,
        )
    }

    private fun checkCancelled(
        cancellation: CancellationSignal,
    ) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
