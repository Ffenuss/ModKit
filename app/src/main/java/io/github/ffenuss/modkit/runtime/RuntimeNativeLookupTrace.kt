package io.github.ffenuss.modkit.runtime

import java.io.Serializable
import java.security.MessageDigest

enum class RuntimeNativeLookupKind {
    DLSYM,
    JNI_REGISTER_NATIVE,
    JNI_ON_LOAD,
}

enum class RuntimeNativeTraceSource {
    REPACKED_TEST_RUNTIME,
    NON_ROOT_RUNTIME,
    ROOT_RUNTIME,
}

enum class RuntimeNativeAcquisitionMode {
    PASSIVE_TRACE,
    TARGETED_PROBE,
}

data class RuntimeNativeLookupEvent(
    val index: Int,
    val kind: RuntimeNativeLookupKind,
    val moduleName: String,
    val symbolName: String?,
    val jniClassName: String?,
    val jniMethodName: String?,
    val jniSignature: String?,
    val resolvedRuntimeAddress: Long,
) : Serializable {
    val displayIdentity: String
        get() =
            when (kind) {
                RuntimeNativeLookupKind.DLSYM ->
                    symbolName ?: "<unnamed dlsym>"
                RuntimeNativeLookupKind.JNI_ON_LOAD ->
                    symbolName ?: "JNI_OnLoad"
                RuntimeNativeLookupKind.JNI_REGISTER_NATIVE ->
                    listOfNotNull(
                        jniClassName,
                        jniMethodName,
                        jniSignature,
                    ).joinToString(" ")
                        .ifBlank { "<unnamed RegisterNatives>" }
            }
}

data class RuntimeNativeTraceCapture(
    val artifactSha256: String,
    val processIdentity: String,
    val processIdentityConfirmed: Boolean,
    val pid: Int,
    val source: RuntimeNativeTraceSource,
    val capturedAtEpochMs: Long,
    val text: String,
    val sha256: String,
    val truncated: Boolean,
    val acquisitionMode: RuntimeNativeAcquisitionMode =
        RuntimeNativeAcquisitionMode.PASSIVE_TRACE,
) : Serializable

data class RuntimeNativeTraceParseResult(
    val events: List<RuntimeNativeLookupEvent>,
    val rejectedLines: Int,
    val blockers: List<String>,
)

data class RuntimeNativeTraceValidationResult(
    val observations: List<RuntimeEvidenceObservation>,
    val rejectedEvents: Int,
    val blockers: List<String>,
) {
    val acceptedObservationCount: Int
        get() = observations.count { it.independentlyConfirmed }
}

/**
 * Stable line protocol for runtime-native observations produced by a future
 * repacked/non-root/root trace executor.
 *
 * Format (tab-separated):
 *   DLSYM <module> <symbol> <runtime-address>
 *   JNI_ON_LOAD <module> <symbol> <runtime-address>
 *   JNI_REGISTER_NATIVE <module> <class> <method> <signature> <runtime-address>
 *
 * Parsing a line does not make it trusted. Trust is assigned only later after
 * artifact/process/maps/module/address validation.
 */
object RuntimeNativeTraceParser {
    const val MAX_TRACE_BYTES = 4 * 1024 * 1024
    const val MAX_EVENTS = 100_000
    private const val MAX_FIELD_CHARS = 4096

    fun capture(
        artifactSha256: String,
        processIdentity: String,
        processIdentityConfirmed: Boolean,
        pid: Int,
        source: RuntimeNativeTraceSource,
        text: String,
        capturedAtEpochMs: Long = System.currentTimeMillis(),
        acquisitionMode: RuntimeNativeAcquisitionMode =
            RuntimeNativeAcquisitionMode.PASSIVE_TRACE,
    ): RuntimeNativeTraceCapture {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val truncated = bytes.size > MAX_TRACE_BYTES
        val retained = if (truncated) {
            bytes.copyOf(MAX_TRACE_BYTES)
        } else {
            bytes
        }
        return RuntimeNativeTraceCapture(
            artifactSha256 = artifactSha256,
            processIdentity = processIdentity,
            processIdentityConfirmed = processIdentityConfirmed,
            pid = pid,
            source = source,
            capturedAtEpochMs = capturedAtEpochMs,
            text = retained.toString(Charsets.UTF_8),
            sha256 = sha256(retained),
            truncated = truncated,
            acquisitionMode = acquisitionMode,
        )
    }

    fun parse(capture: RuntimeNativeTraceCapture): RuntimeNativeTraceParseResult {
        if (capture.truncated) {
            return RuntimeNativeTraceParseResult(
                events = emptyList(),
                rejectedLines = 0,
                blockers = listOf(
                    "Runtime native trace exceeded the bounded capture limit.",
                ),
            )
        }
        if (
            !sha256(capture.text.toByteArray(Charsets.UTF_8))
                .equals(capture.sha256, ignoreCase = true)
        ) {
            return RuntimeNativeTraceParseResult(
                events = emptyList(),
                rejectedLines = 0,
                blockers = listOf("Runtime native trace SHA-256 mismatch."),
            )
        }

        val events = mutableListOf<RuntimeNativeLookupEvent>()
        var rejected = 0

        for (line in capture.text.lineSequence()) {
            if (line.isBlank()) continue
            if (events.size >= MAX_EVENTS) {
                return RuntimeNativeTraceParseResult(
                    events = events,
                    rejectedLines = rejected,
                    blockers = listOf(
                        "Runtime native event count exceeded the parser limit.",
                    ),
                )
            }

            val fields = line.split('	')
            val event = parseLine(events.size, fields)
            if (event == null) {
                rejected++
            } else {
                events += event
            }
        }

        return RuntimeNativeTraceParseResult(
            events = events,
            rejectedLines = rejected,
            blockers = emptyList(),
        )
    }

    private fun parseLine(
        index: Int,
        fields: List<String>,
    ): RuntimeNativeLookupEvent? {
        if (
            fields.any {
                it.isBlank() || it.length > MAX_FIELD_CHARS
            }
        ) {
            return null
        }

        return when (fields.firstOrNull()) {
            "DLSYM" -> {
                if (fields.size != 4) return null
                RuntimeNativeLookupEvent(
                    index = index,
                    kind = RuntimeNativeLookupKind.DLSYM,
                    moduleName = fields[1],
                    symbolName = fields[2],
                    jniClassName = null,
                    jniMethodName = null,
                    jniSignature = null,
                    resolvedRuntimeAddress =
                        parseAddress(fields[3]) ?: return null,
                )
            }
            "JNI_ON_LOAD" -> {
                if (fields.size != 4) return null
                RuntimeNativeLookupEvent(
                    index = index,
                    kind = RuntimeNativeLookupKind.JNI_ON_LOAD,
                    moduleName = fields[1],
                    symbolName = fields[2],
                    jniClassName = null,
                    jniMethodName = null,
                    jniSignature = null,
                    resolvedRuntimeAddress =
                        parseAddress(fields[3]) ?: return null,
                )
            }
            "JNI_REGISTER_NATIVE" -> {
                if (fields.size != 6) return null
                RuntimeNativeLookupEvent(
                    index = index,
                    kind = RuntimeNativeLookupKind.JNI_REGISTER_NATIVE,
                    moduleName = fields[1],
                    symbolName = null,
                    jniClassName = fields[2],
                    jniMethodName = fields[3],
                    jniSignature = fields[4],
                    resolvedRuntimeAddress =
                        parseAddress(fields[5]) ?: return null,
                )
            }
            else -> null
        }
    }

    private fun parseAddress(value: String): Long? {
        val normalized = value.removePrefix("0x").removePrefix("0X")
        if (normalized.isBlank()) return null
        val parsed = normalized.toLongOrNull(16) ?: return null
        return parsed.takeIf { it > 0L }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Validates a native lookup trace against already-confirmed runtime context.
 *
 * A parsed event becomes CONFIRMED only when:
 * - trace artifact SHA equals runtime artifact SHA;
 * - both trace and RuntimeEvidenceBundle independently confirm the same process;
 * - PID is identical;
 * - process-maps SHA matches the supplied snapshot;
 * - resolved address lies in an executable mapping of the named module;
 * - that module has its own confirmed ELF mapping evidence.
 *
 * JNI/dlsym observations never grant method execution proof and never promote
 * an EvidenceTarget to CHANGE_READY.
 */
object RuntimeNativeLookupValidator {
    fun validate(
        capture: RuntimeNativeTraceCapture,
        runtimeEvidence: RuntimeEvidenceBundle,
        procMapsText: String,
    ): RuntimeNativeTraceValidationResult {
        val topLevelBlockers = mutableListOf<String>()

        if (
            !capture.artifactSha256.equals(
                runtimeEvidence.artifactSha256,
                ignoreCase = true,
            )
        ) {
            topLevelBlockers += "Native trace artifact SHA does not match runtime evidence."
        }
        if (
            !runtimeEvidence.procMapsSha256.equals(
                sha256(procMapsText.toByteArray(Charsets.UTF_8)),
                ignoreCase = true,
            )
        ) {
            topLevelBlockers += "Process-maps snapshot SHA does not match runtime evidence."
        }
        if (
            !capture.processIdentityConfirmed ||
            !runtimeEvidence.processIdentityConfirmed ||
            capture.processIdentity.isBlank() ||
            runtimeEvidence.processIdentity.isNullOrBlank() ||
            capture.processIdentity != runtimeEvidence.processIdentity
        ) {
            topLevelBlockers += "Native trace process identity is not independently confirmed."
        }
        if (
            capture.pid <= 0 ||
            runtimeEvidence.capturePid == null ||
            capture.pid != runtimeEvidence.capturePid
        ) {
            topLevelBlockers += "Native trace PID does not match runtime evidence."
        }

        val parsed = RuntimeNativeTraceParser.parse(capture)
        topLevelBlockers += parsed.blockers
        if (topLevelBlockers.isNotEmpty()) {
            return RuntimeNativeTraceValidationResult(
                observations = emptyList(),
                rejectedEvents = parsed.events.size + parsed.rejectedLines,
                blockers = topLevelBlockers.distinct(),
            )
        }

        val regions = ProcMapsParser.parse(procMapsText)
        val observations = parsed.events.map { event ->
            val mappedRegions = ProcMapsParser.matchingModule(
                regions = regions,
                moduleName = event.moduleName,
            )
            val executableContains = mappedRegions.any { region ->
                region.executable &&
                    event.resolvedRuntimeAddress >= region.start &&
                    event.resolvedRuntimeAddress < region.endExclusive
            }
            val mappingConfirmed = runtimeEvidence.moduleMappings.any {
                it.confirmed && it.moduleName == event.moduleName
            }

            val blockers = buildList {
                if (mappedRegions.isEmpty()) {
                    add("Named module is absent from the bound process-maps snapshot.")
                }
                if (!executableContains) {
                    add("Resolved runtime address is outside an executable mapping of the named module.")
                }
                if (!mappingConfirmed) {
                    add("Named module does not have independent confirmed ELF mapping evidence.")
                }
            }

            RuntimeEvidenceObservation(
                id = "runtime:native-lookup:" +
                    capture.sha256.take(16) + ":" + event.index,
                kind = when (event.kind) {
                    RuntimeNativeLookupKind.DLSYM ->
                        RuntimeEvidenceObservationKind.JNI_DLSYM_OBSERVED
                    RuntimeNativeLookupKind.JNI_REGISTER_NATIVE ->
                        RuntimeEvidenceObservationKind
                            .JNI_REGISTER_NATIVE_OBSERVED
                    RuntimeNativeLookupKind.JNI_ON_LOAD ->
                        RuntimeEvidenceObservationKind
                            .JNI_ON_LOAD_INVOCATION_OBSERVED
                },
                strength = if (blockers.isEmpty()) {
                    RuntimeEvidenceObservationStrength.CONFIRMED
                } else {
                    RuntimeEvidenceObservationStrength.OBSERVED
                },
                subjectId = event.displayIdentity,
                artifactSha256 = runtimeEvidence.artifactSha256,
                captureSha256 = capture.sha256,
                captureSource = runtimeEvidence.captureSource,
                capturedAtEpochMs = capture.capturedAtEpochMs,
                summary = capture.acquisitionMode.name +
                    " " + event.kind.name +
                    " observed for " + event.displayIdentity +
                    " in " + event.moduleName +
                    " at 0x" + event.resolvedRuntimeAddress.toString(16),
                supportingFacts = buildList {
                    add("pid=" + capture.pid)
                    add("processIdentity=" + capture.processIdentity)
                    add("traceSource=" + capture.source.name)
                    add(
                        "acquisitionMode=" +
                            capture.acquisitionMode.name,
                    )
                    add("module=" + event.moduleName)
                    add(
                        "runtimeAddress=0x" +
                            event.resolvedRuntimeAddress.toString(16),
                    )
                    event.symbolName?.let { add("symbol=$it") }
                    event.jniClassName?.let { add("jniClass=$it") }
                    event.jniMethodName?.let { add("jniMethod=$it") }
                    event.jniSignature?.let { add("jniSignature=$it") }
                },
                blockers = blockers,
                proofLevel = null,
            )
        }

        return RuntimeNativeTraceValidationResult(
            observations = observations,
            rejectedEvents = parsed.rejectedLines +
                observations.count { !it.independentlyConfirmed },
            blockers = emptyList(),
        )
    }

    fun attach(
        runtimeEvidence: RuntimeEvidenceBundle,
        validation: RuntimeNativeTraceValidationResult,
    ): RuntimeEvidenceBundle =
        runtimeEvidence.copy(
            additionalObservations = (
                runtimeEvidence.additionalObservations +
                    validation.observations
                ).distinctBy { it.id },
            blockers = (
                runtimeEvidence.blockers +
                    validation.blockers
                ).distinct(),
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

enum class RuntimeNativeTraceCapability {
    TRACE_PARSER,
    EXECUTABLE_ADDRESS_VALIDATOR,
    REPACKED_TRACE_CAPTURE,
    NON_ROOT_TRACE_CAPTURE,
    ROOT_TRACE_CAPTURE,
    REPACKED_TARGETED_DLSYM_PROBE,
    REPACKED_PASSIVE_DLSYM_CAPTURE,
    REPACKED_PASSIVE_JNI_REGISTRATION_CAPTURE,
    REPACKED_PASSIVE_JNI_ONLOAD_INVOCATION_CAPTURE,
}

object RuntimeNativeTraceCapabilityRegistry {
    private val repackedTraceRequirements =
        setOf(
            RuntimeNativeTraceCapability.TRACE_PARSER,
            RuntimeNativeTraceCapability.EXECUTABLE_ADDRESS_VALIDATOR,
            RuntimeNativeTraceCapability.REPACKED_PASSIVE_DLSYM_CAPTURE,
            RuntimeNativeTraceCapability
                .REPACKED_PASSIVE_JNI_REGISTRATION_CAPTURE,
            RuntimeNativeTraceCapability
                .REPACKED_PASSIVE_JNI_ONLOAD_INVOCATION_CAPTURE,
        )

    val registered: Set<RuntimeNativeTraceCapability> =
        setOf(
            RuntimeNativeTraceCapability.TRACE_PARSER,
            RuntimeNativeTraceCapability.EXECUTABLE_ADDRESS_VALIDATOR,
            RuntimeNativeTraceCapability.REPACKED_TARGETED_DLSYM_PROBE,
            RuntimeNativeTraceCapability.REPACKED_PASSIVE_DLSYM_CAPTURE,
            RuntimeNativeTraceCapability.REPACKED_PASSIVE_JNI_REGISTRATION_CAPTURE,
            RuntimeNativeTraceCapability
                .REPACKED_PASSIVE_JNI_ONLOAD_INVOCATION_CAPTURE,
            RuntimeNativeTraceCapability.REPACKED_TRACE_CAPTURE,
        )

    fun captureAvailable(
        source: RuntimeNativeTraceSource,
    ): Boolean =
        when (source) {
            RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME ->
                RuntimeNativeTraceCapability.REPACKED_TRACE_CAPTURE in
                    registered &&
                    repackedTraceRequirements.all { it in registered }
            RuntimeNativeTraceSource.NON_ROOT_RUNTIME ->
                RuntimeNativeTraceCapability.NON_ROOT_TRACE_CAPTURE in registered
            RuntimeNativeTraceSource.ROOT_RUNTIME ->
                RuntimeNativeTraceCapability.ROOT_TRACE_CAPTURE in registered
        }

    fun targetedDlsymProbeAvailable(
        source: RuntimeNativeTraceSource,
    ): Boolean =
        source == RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME &&
            RuntimeNativeTraceCapability.REPACKED_TARGETED_DLSYM_PROBE in
            registered


    fun passiveDlsymCaptureAvailable(
        source: RuntimeNativeTraceSource,
    ): Boolean =
        source == RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME &&
            RuntimeNativeTraceCapability.REPACKED_PASSIVE_DLSYM_CAPTURE in
            registered

    fun passiveJniRegistrationCaptureAvailable(
        source: RuntimeNativeTraceSource,
    ): Boolean =
        source == RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME &&
            RuntimeNativeTraceCapability
                .REPACKED_PASSIVE_JNI_REGISTRATION_CAPTURE in
            registered

    fun passiveJniOnLoadInvocationCaptureAvailable(
        source: RuntimeNativeTraceSource,
    ): Boolean =
        source == RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME &&
            RuntimeNativeTraceCapability
                .REPACKED_PASSIVE_JNI_ONLOAD_INVOCATION_CAPTURE in
            registered
}
