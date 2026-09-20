package io.github.ffenuss.modkit.runtime

import java.security.MessageDigest

data class RepackedRuntimeNativeTraceExport(
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
    val hookedSlotCount: Int,
    val producerIncomplete: Boolean,
    val producerRestoreFailed: Boolean,
    val text: String,
)

object RepackedRuntimeNativeTraceExportProtocol {
    const val HEADER_MAGIC = "MODKIT_NATIVE_TRACE_V1"
    val TRACE_DELIMITER: ByteArray =
        "\n---TRACE---\n".toByteArray(Charsets.UTF_8)
    const val MAX_EXPORT_BYTES =
        RuntimeNativeTraceParser.MAX_TRACE_BYTES + 64 * 1024

    fun parse(
        bytes: ByteArray,
    ): RepackedRuntimeNativeTraceExport {
        require(bytes.isNotEmpty()) {
            "Runtime native trace export is empty."
        }
        require(bytes.size <= MAX_EXPORT_BYTES) {
            "Runtime native trace export exceeds bounded size."
        }

        val delimiterOffset = indexOf(
            bytes,
            TRACE_DELIMITER,
        )
        require(delimiterOffset > 0) {
            "Runtime native trace delimiter is missing."
        }
        val headerText = String(
            bytes,
            0,
            delimiterOffset,
            Charsets.UTF_8,
        )
        val lines = headerText.split('\n')
        require(lines.firstOrNull() == HEADER_MAGIC) {
            "Runtime native trace header magic is invalid."
        }

        val fields = linkedMapOf<String, String>()
        lines.drop(1)
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(
                    separator in 1 until line.lastIndex,
                ) {
                    "Runtime native trace header field is malformed."
                }
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                require(fields.put(key, value) == null) {
                    "Runtime native trace header contains duplicate field: $key"
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
            "hookedSlotCount",
            "producerIncomplete",
            "producerRestoreFailed",
        )
        require(fields.keys == required) {
            "Runtime native trace header fields do not match schema."
        }

        val packageName = fields.getValue("packageName")
        val pid = fields.getValue("pid").toIntOrNull()
        val processIdentity =
            fields.getValue("processIdentity")
        val sessionId = fields.getValue("sessionId")
        val startedAt = fields.getValue("startedAtEpochMs")
            .toLongOrNull()
        val stoppedAt = fields.getValue("stoppedAtEpochMs")
            .toLongOrNull()
        val eventCount = fields.getValue("eventCount")
            .toIntOrNull()
        val declaredBytes = fields.getValue("traceBytes")
            .toIntOrNull()
        val truncated = fields.getValue("truncated")
            .toBooleanStrictOrNull()
        val producerKind =
            fields.getValue("producerKind")
        val hookedSlotCount =
            fields.getValue("hookedSlotCount")
                .toIntOrNull()
        val producerIncomplete =
            fields.getValue("producerIncomplete")
                .toBooleanStrictOrNull()
        val producerRestoreFailed =
            fields.getValue("producerRestoreFailed")
                .toBooleanStrictOrNull()

        require(packageName.isNotBlank()) {
            "Runtime native trace package name is empty."
        }
        require(pid != null && pid > 0) {
            "Runtime native trace PID is invalid."
        }
        require(processIdentity.isNotBlank()) {
            "Runtime native trace process identity is empty."
        }
        require(
            sessionId.isNotBlank() &&
                sessionId.length <= 256 &&
                sessionId.none {
                    it.isWhitespace() || it.isISOControl()
                },
        ) {
            "Runtime native trace session identity is invalid."
        }
        require(startedAt != null && startedAt > 0L) {
            "Runtime native trace start time is invalid."
        }
        require(
            stoppedAt != null &&
                stoppedAt >= startedAt,
        ) {
            "Runtime native trace stop time is invalid."
        }
        require(
            eventCount != null &&
                eventCount in 0..RuntimeNativeTraceParser.MAX_EVENTS,
        ) {
            "Runtime native trace event count is invalid."
        }
        require(
            declaredBytes != null &&
                declaredBytes in 0..RuntimeNativeTraceParser.MAX_TRACE_BYTES,
        ) {
            "Runtime native trace byte count is invalid."
        }
        require(truncated != null) {
            "Runtime native trace truncation flag is invalid."
        }
        require(producerKind == "PLT_DLSYM_GOT") {
            "Runtime native trace producer kind is unsupported."
        }
        require(
            hookedSlotCount != null &&
                hookedSlotCount in 0..8192,
        ) {
            "Runtime native trace hooked-slot count is invalid."
        }
        require(producerIncomplete != null) {
            "Runtime native trace producer-incomplete flag is invalid."
        }
        require(producerRestoreFailed != null) {
            "Runtime native trace restore-failed flag is invalid."
        }

        val traceStart =
            delimiterOffset + TRACE_DELIMITER.size
        val trace = bytes.copyOfRange(
            traceStart,
            bytes.size,
        )
        require(trace.size == declaredBytes) {
            "Runtime native trace byte count mismatch."
        }
        val declaredSha = fields.getValue("traceSha256")
        require(
            declaredSha.matches(
                Regex("[0-9a-fA-F]{64}"),
            ),
        ) {
            "Runtime native trace SHA-256 format is invalid."
        }
        require(
            sha256(trace).equals(
                declaredSha,
                ignoreCase = true,
            ),
        ) {
            "Runtime native trace SHA-256 mismatch."
        }

        val text = trace.toString(Charsets.UTF_8)
        val lineCount = text.lineSequence()
            .count { it.isNotBlank() }
        require(lineCount == eventCount) {
            "Runtime native trace event count does not match trace lines."
        }

        return RepackedRuntimeNativeTraceExport(
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
            hookedSlotCount = hookedSlotCount,
            producerIncomplete = producerIncomplete,
            producerRestoreFailed =
                producerRestoreFailed,
            text = text,
        )
    }

    fun toCapture(
        export: RepackedRuntimeNativeTraceExport,
        artifactSha256: String,
        source: RuntimeNativeTraceSource,
    ): RuntimeNativeTraceCapture =
        RuntimeNativeTraceCapture(
            artifactSha256 = artifactSha256,
            processIdentity = export.processIdentity,
            processIdentityConfirmed = true,
            pid = export.pid,
            source = source,
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
        if (
            needle.isEmpty() ||
            needle.size > bytes.size
        ) {
            return -1
        }
        outer@ for (
            start in 0..bytes.size - needle.size
        ) {
            for (index in needle.indices) {
                if (
                    bytes[start + index] !=
                    needle[index]
                ) {
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
