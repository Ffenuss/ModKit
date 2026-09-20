package io.github.ffenuss.modkit.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ArtifactEntry
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.security.MessageDigest

data class RepackedRuntimeInstalledProbe(
    val packageName: String,
    val authority: String,
    val providerClassName: String,
    val exported: Boolean,
    val enabled: Boolean,
    val signerCertificateSha256: Set<String>,
)

data class RepackedRuntimeProbeQuery(
    val schemaVersion: Int,
    val packageName: String,
    val pid: Int,
)

data class RepackedRuntimeNativeLookupReply(
    val schemaVersion: Int,
    val packageName: String,
    val pid: Int,
    val moduleName: String,
    val symbolName: String,
    val resolvedRuntimeAddress: Long,
)

data class RepackedRuntimeNativeTraceStatus(
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
    val hookedSlotCount: Int,
    val producerIncomplete: Boolean,
    val producerRestoreFailed: Boolean,
)

data class RepackedRuntimeProbeCaptureResult(
    val packageName: String,
    val authority: String,
    val providerClassName: String,
    val signerCertificateSha256: Set<String>,
    val pid: Int,
    val processIdentity: String,
    val capturedAtEpochMs: Long,
    val rawEvidenceSha256: String,
    val maps: ProcMapsCapture,
)

fun RepackedRuntimeProbeCaptureResult.toEvidenceBundle(
    artifactSha256: String,
    artifactEntries: List<ArtifactEntry>,
): RuntimeEvidenceBundle {
    val regions = ProcMapsParser.parse(maps.text)
    return RuntimeEvidenceBundle(
        artifactSha256 = artifactSha256,
        procMapsSha256 = maps.sha256,
        moduleMappings = emptyList(),
        addressConfirmations = emptyList(),
        blockers = emptyList(),
        moduleInventory = RuntimeModuleInventoryBuilder.build(
            regions = regions,
            artifactEntries = artifactEntries,
        ),
        memoryMappingCandidates =
            RuntimeMemoryMappingDetector.candidates(regions),
        captureSource = ProcMapsCaptureSource.REPACKED_TEST_RUNTIME,
        capturePid = pid,
        capturedAtEpochMs = capturedAtEpochMs,
        processIdentity = processIdentity,
        processIdentityConfirmed = true,
    )
}

interface RepackedRuntimeProbeTransport {
    fun inspectInstalled(
        packageName: String,
        authority: String,
    ): RepackedRuntimeInstalledProbe?

    fun query(
        authority: String,
    ): RepackedRuntimeProbeQuery

    fun readEvidence(
        authority: String,
        cancellation: CancellationSignal,
        maxBytes: Int,
    ): ByteArray
}

interface RepackedRuntimeNativeLookupTransport :
    RepackedRuntimeProbeTransport {
    fun resolveLoadedSymbol(
        authority: String,
        moduleName: String,
        symbolName: String,
    ): RepackedRuntimeNativeLookupReply
}

interface RepackedRuntimePassiveTraceTransport :
    RepackedRuntimeNativeLookupTransport {
    fun startNativeTrace(
        authority: String,
    ): RepackedRuntimeNativeTraceStatus

    fun stopNativeTrace(
        authority: String,
    ): RepackedRuntimeNativeTraceStatus

    fun nativeTraceStatus(
        authority: String,
    ): RepackedRuntimeNativeTraceStatus

    fun readNativeTrace(
        authority: String,
        cancellation: CancellationSignal,
        maxBytes: Int =
            RepackedRuntimeNativeTraceExportProtocol
                .MAX_EXPORT_BYTES,
    ): ByteArray
}

object RepackedRuntimeProbeIdentityVerifier {
    fun verify(
        build: RepackedRuntimeBuildResult,
        installed: RepackedRuntimeInstalledProbe,
    ) {
        val authority =
            build.packageName +
                BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX
        require(installed.packageName == build.packageName) {
            "Installed probe package does not match test build."
        }
        require(
            installed.providerClassName ==
                BinaryAndroidManifestProbeInjector.PROVIDER_CLASS,
        ) {
            "Installed probe provider class does not match ModKit payload."
        }
        require(installed.authority == authority) {
            "Installed probe authority does not match test build."
        }
        require(installed.exported && installed.enabled) {
            "Installed probe provider is not exported/enabled."
        }

        val expectedSigners = build.signedApks
            .flatMap {
                it.signature.signerCertificateSha256
            }
            .map { it.lowercase() }
            .toSet()
        require(expectedSigners.isNotEmpty()) {
            "Test build has no verified signer certificate evidence."
        }
        require(
            installed.signerCertificateSha256
                .map { it.lowercase() }
                .toSet() == expectedSigners,
        ) {
            "Installed package signer does not match the built test APK."
        }
    }
}

class AndroidRepackedRuntimeProbeTransport(
    private val context: Context,
) : RepackedRuntimePassiveTraceTransport,
    RepackedRuntimePassiveJniTraceTransport {
    override fun inspectInstalled(
        packageName: String,
        authority: String,
    ): RepackedRuntimeInstalledProbe? {
        val packageManager = context.packageManager
        val provider = packageManager.resolveContentProvider(
            authority,
            PackageManager.GET_META_DATA,
        ) ?: return null
        if (provider.packageName != packageName) return null

        val signingFlag = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val packageInfo = packageManager.getPackageInfo(
            packageName,
            signingFlag,
        )
        val signatures = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            packageInfo.signingInfo
                ?.apkContentsSigners
                .orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        val signerHashes = signatures
            .map { sha256(it.toByteArray()) }
            .toSet()

        return RepackedRuntimeInstalledProbe(
            packageName = provider.packageName,
            authority = provider.authority ?: authority,
            providerClassName = provider.name,
            exported = provider.exported,
            enabled = provider.enabled,
            signerCertificateSha256 = signerHashes,
        )
    }

    override fun query(
        authority: String,
    ): RepackedRuntimeProbeQuery {
        val uri = Uri.parse(
            "content://$authority/${RuntimeEvidenceProviderContract.PATH_EVIDENCE}",
        )
        val cursor = context.contentResolver.query(
            uri,
            null,
            null,
            null,
            null,
        ) ?: error("Runtime probe query returned no cursor.")
        cursor.use {
            require(it.count == 1 && it.moveToFirst()) {
                "Runtime probe query must return exactly one row."
            }
            val schemaColumn = it.getColumnIndex("schemaVersion")
            val packageColumn = it.getColumnIndex("packageName")
            val pidColumn = it.getColumnIndex("pid")
            require(
                schemaColumn >= 0 &&
                    packageColumn >= 0 &&
                    pidColumn >= 0,
            ) {
                "Runtime probe query schema is incomplete."
            }
            return RepackedRuntimeProbeQuery(
                schemaVersion = it.getInt(schemaColumn),
                packageName = it.getString(packageColumn),
                pid = it.getInt(pidColumn),
            )
        }
    }

    override fun resolveLoadedSymbol(
        authority: String,
        moduleName: String,
        symbolName: String,
    ): RepackedRuntimeNativeLookupReply {
        val uri = Uri.parse(
            "content://" + authority + "/" +
                RuntimeEvidenceProviderContract.PATH_EVIDENCE,
        )
        val extras = Bundle().apply {
            putString("symbol", symbolName)
        }
        val result = requireNotNull(
            context.contentResolver.call(
                uri,
                "resolveLoadedSymbol",
                moduleName,
                extras,
            ),
        ) {
            "Runtime probe native lookup returned no result."
        }
        return RepackedRuntimeNativeLookupReply(
            schemaVersion = result.getInt("schemaVersion", -1),
            packageName =
                result.getString("packageName").orEmpty(),
            pid = result.getInt("pid", -1),
            moduleName =
                result.getString("moduleName").orEmpty(),
            symbolName =
                result.getString("symbolName").orEmpty(),
            resolvedRuntimeAddress =
                result.getLong("resolvedRuntimeAddress", 0L),
        )
    }

    override fun startNativeTrace(
        authority: String,
    ): RepackedRuntimeNativeTraceStatus =
        nativeTraceControl(
            authority = authority,
            method = "nativeTraceStart",
        )

    override fun stopNativeTrace(
        authority: String,
    ): RepackedRuntimeNativeTraceStatus =
        nativeTraceControl(
            authority = authority,
            method = "nativeTraceStop",
        )

    override fun nativeTraceStatus(
        authority: String,
    ): RepackedRuntimeNativeTraceStatus =
        nativeTraceControl(
            authority = authority,
            method = "nativeTraceStatus",
        )

    override fun readNativeTrace(
        authority: String,
        cancellation: CancellationSignal,
        maxBytes: Int,
    ): ByteArray {
        require(
            maxBytes in 1..
                RepackedRuntimeNativeTraceExportProtocol
                    .MAX_EXPORT_BYTES,
        ) {
            "Runtime native trace read limit is invalid."
        }
        val uri = Uri.parse(
            "content://" + authority + "/" +
                RuntimeEvidenceProviderContract
                    .PATH_NATIVE_TRACE,
        )
        val descriptor = requireNotNull(
            context.contentResolver
                .openFileDescriptor(uri, "r"),
        ) {
            "Runtime probe did not return a native trace descriptor."
        }
        return descriptor.use {
            readBounded(
                descriptor = it,
                cancellation = cancellation,
                maxBytes = maxBytes,
            )
        }
    }

    override fun startPassiveJniTrace(
        authority: String,
    ): RepackedRuntimePassiveJniTraceStatus =
        passiveJniTraceControl(
            authority = authority,
            method = "nativeJniTraceStart",
        )

    override fun stopPassiveJniTrace(
        authority: String,
    ): RepackedRuntimePassiveJniTraceStatus =
        passiveJniTraceControl(
            authority = authority,
            method = "nativeJniTraceStop",
        )

    override fun passiveJniTraceStatus(
        authority: String,
    ): RepackedRuntimePassiveJniTraceStatus =
        passiveJniTraceControl(
            authority = authority,
            method = "nativeJniTraceStatus",
        )

    override fun readPassiveJniTrace(
        authority: String,
        cancellation: CancellationSignal,
        maxBytes: Int,
    ): ByteArray {
        require(
            maxBytes in 1..
                RepackedRuntimePassiveJniTraceExportProtocol
                    .MAX_EXPORT_BYTES,
        ) {
            "Runtime passive JNI trace read limit is invalid."
        }
        val uri = Uri.parse(
            "content://" + authority + "/" +
                RuntimeEvidenceProviderContract
                    .PATH_NATIVE_JNI_TRACE,
        )
        val descriptor = requireNotNull(
            context.contentResolver
                .openFileDescriptor(uri, "r"),
        ) {
            "Runtime probe did not return a passive JNI trace descriptor."
        }
        return descriptor.use {
            readBounded(
                descriptor = it,
                cancellation = cancellation,
                maxBytes = maxBytes,
            )
        }
    }

    private fun nativeTraceControl(
        authority: String,
        method: String,
    ): RepackedRuntimeNativeTraceStatus {
        val uri = Uri.parse(
            "content://" + authority + "/" +
                RuntimeEvidenceProviderContract
                    .PATH_NATIVE_TRACE,
        )
        val result = requireNotNull(
            context.contentResolver.call(
                uri,
                method,
                null,
                null,
            ),
        ) {
            "Runtime probe native trace control returned no result."
        }
        return RepackedRuntimeNativeTraceStatus(
            schemaVersion =
                result.getInt("schemaVersion", -1),
            packageName =
                result.getString("packageName").orEmpty(),
            pid = result.getInt("pid", -1),
            sessionId =
                result.getString("sessionId").orEmpty(),
            active =
                result.getBoolean("active", false),
            truncated =
                result.getBoolean("truncated", false),
            eventCount =
                result.getInt("eventCount", -1),
            startedAtEpochMs =
                result.getLong(
                    "startedAtEpochMs",
                    -1L,
                ),
            stoppedAtEpochMs =
                result.getLong(
                    "stoppedAtEpochMs",
                    -1L,
                ),
            traceBytes =
                result.getInt("traceBytes", -1),
            producerKind =
                result.getString("producerKind").orEmpty(),
            producerReady =
                result.getBoolean("producerReady", false),
            producerActive =
                result.getBoolean("producerActive", false),
            hookedSlotCount =
                result.getInt("hookedSlotCount", -1),
            producerIncomplete =
                result.getBoolean(
                    "producerIncomplete",
                    true,
                ),
            producerRestoreFailed =
                result.getBoolean(
                    "producerRestoreFailed",
                    true,
                ),
        )
    }

    private fun passiveJniTraceControl(
        authority: String,
        method: String,
    ): RepackedRuntimePassiveJniTraceStatus {
        val uri = Uri.parse(
            "content://" + authority + "/" +
                RuntimeEvidenceProviderContract
                    .PATH_NATIVE_JNI_TRACE,
        )
        val result = requireNotNull(
            context.contentResolver.call(
                uri,
                method,
                null,
                null,
            ),
        ) {
            "Runtime probe passive JNI trace control returned no result."
        }
        return RepackedRuntimePassiveJniTraceStatus(
            schemaVersion =
                result.getInt("schemaVersion", -1),
            packageName =
                result.getString("packageName").orEmpty(),
            pid = result.getInt("pid", -1),
            sessionId =
                result.getString("sessionId").orEmpty(),
            active =
                result.getBoolean("active", false),
            truncated =
                result.getBoolean("truncated", false),
            eventCount =
                result.getInt("eventCount", -1),
            startedAtEpochMs =
                result.getLong(
                    "startedAtEpochMs",
                    -1L,
                ),
            stoppedAtEpochMs =
                result.getLong(
                    "stoppedAtEpochMs",
                    -1L,
                ),
            traceBytes =
                result.getInt("traceBytes", -1),
            producerKind =
                result.getString("producerKind").orEmpty(),
            producerReady =
                result.getBoolean("producerReady", false),
            producerActive =
                result.getBoolean("producerActive", false),
            jniOnLoadLookupHookedSlotCount =
                result.getInt(
                    "jniOnLoadLookupHookedSlotCount",
                    -1,
                ),
            jniOnLoadInvocationReady =
                result.getBoolean(
                    "jniOnLoadInvocationReady",
                    false,
                ),
            registerNativesHooked =
                result.getBoolean(
                    "registerNativesHooked",
                    false,
                ),
            producerIncomplete =
                result.getBoolean(
                    "producerIncomplete",
                    true,
                ),
            producerRestoreFailed =
                result.getBoolean(
                    "producerRestoreFailed",
                    true,
                ),
        )
    }

    override fun readEvidence(
        authority: String,
        cancellation: CancellationSignal,
        maxBytes: Int,
    ): ByteArray {
        require(maxBytes in 1..16 * 1024 * 1024) {
            "Runtime probe read limit is invalid."
        }
        val uri = Uri.parse(
            "content://$authority/${RuntimeEvidenceProviderContract.PATH_EVIDENCE}",
        )
        val descriptor = requireNotNull(
            context.contentResolver.openFileDescriptor(uri, "r"),
        ) {
            "Runtime probe did not return an evidence descriptor."
        }
        return descriptor.use {
            readBounded(it, cancellation, maxBytes)
        }
    }

    private fun readBounded(
        descriptor: ParcelFileDescriptor,
        cancellation: CancellationSignal,
        maxBytes: Int,
    ): ByteArray {
        FileInputStream(descriptor.fileDescriptor).use { input ->
            val output = ByteArrayOutputStream(
                minOf(maxBytes, 64 * 1024),
            )
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= maxBytes) {
                    "Runtime probe evidence exceeds bounded read limit."
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
}

object RuntimeEvidenceProviderContract {
    const val SCHEMA_VERSION = 1
    const val PATH_EVIDENCE = "evidence"
    const val PATH_NATIVE_TRACE = "native-trace"
    const val PATH_NATIVE_JNI_TRACE = "native-jni-trace"
    const val HEADER_MAGIC = "MODKIT_RUNTIME_EVIDENCE_V1"
    val MAPS_DELIMITER: ByteArray =
        "\n---MAPS---\n".toByteArray(Charsets.UTF_8)
}

data class ParsedRepackedRuntimeEvidence(
    val packageName: String,
    val pid: Int,
    val processIdentity: String,
    val capturedAtEpochMs: Long,
    val mapsSha256: String,
    val mapsBytes: Int,
    val mapsTruncated: Boolean,
    val maps: ByteArray,
)

object RepackedRuntimeEvidenceProtocol {
    fun parse(
        bytes: ByteArray,
    ): ParsedRepackedRuntimeEvidence {
        require(bytes.isNotEmpty()) {
            "Runtime probe evidence is empty."
        }
        val delimiter = RuntimeEvidenceProviderContract.MAPS_DELIMITER
        val delimiterOffset = indexOf(bytes, delimiter)
        require(delimiterOffset > 0) {
            "Runtime probe maps delimiter is missing."
        }

        val headerText = String(
            bytes,
            0,
            delimiterOffset,
            Charsets.UTF_8,
        )
        val lines = headerText.split('\n')
        require(
            lines.firstOrNull() ==
                RuntimeEvidenceProviderContract.HEADER_MAGIC,
        ) {
            "Runtime probe evidence header magic is invalid."
        }

        val fields = linkedMapOf<String, String>()
        lines.drop(1)
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('=')
                require(separator in 1 until line.lastIndex) {
                    "Runtime probe header field is malformed."
                }
                val key = line.substring(0, separator)
                val value = line.substring(separator + 1)
                require(fields.put(key, value) == null) {
                    "Runtime probe header contains duplicate field: $key"
                }
            }

        val required = setOf(
            "packageName",
            "pid",
            "processIdentity",
            "capturedAtEpochMs",
            "mapsSha256",
            "mapsBytes",
            "mapsTruncated",
        )
        require(fields.keys == required) {
            "Runtime probe header fields do not match schema."
        }

        val mapsStart = delimiterOffset + delimiter.size
        val maps = bytes.copyOfRange(mapsStart, bytes.size)
        val packageName = fields.getValue("packageName")
        val pid = fields.getValue("pid").toIntOrNull()
        val capturedAt = fields.getValue("capturedAtEpochMs")
            .toLongOrNull()
        val declaredMapsBytes = fields.getValue("mapsBytes")
            .toIntOrNull()
        val truncated = fields.getValue("mapsTruncated")
            .toBooleanStrictOrNull()

        require(packageName.isNotBlank()) {
            "Runtime probe package name is empty."
        }
        require(pid != null && pid > 0) {
            "Runtime probe PID is invalid."
        }
        require(capturedAt != null && capturedAt > 0L) {
            "Runtime probe capture timestamp is invalid."
        }
        require(
            declaredMapsBytes != null &&
                declaredMapsBytes == maps.size,
        ) {
            "Runtime probe maps byte count mismatch."
        }
        require(truncated != null) {
            "Runtime probe truncation flag is invalid."
        }

        val declaredSha = fields.getValue("mapsSha256")
        require(
            declaredSha.matches(Regex("[0-9a-fA-F]{64}")),
        ) {
            "Runtime probe maps SHA-256 format is invalid."
        }
        require(
            sha256(maps).equals(
                declaredSha,
                ignoreCase = true,
            ),
        ) {
            "Runtime probe maps SHA-256 mismatch."
        }

        return ParsedRepackedRuntimeEvidence(
            packageName = packageName,
            pid = pid,
            processIdentity =
                fields.getValue("processIdentity"),
            capturedAtEpochMs = capturedAt,
            mapsSha256 = declaredSha.lowercase(),
            mapsBytes = declaredMapsBytes,
            mapsTruncated = truncated,
            maps = maps,
        )
    }

    private fun indexOf(
        bytes: ByteArray,
        needle: ByteArray,
    ): Int {
        if (needle.isEmpty() || needle.size > bytes.size) return -1
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
}

object RepackedRuntimeEvidenceCapture {
    const val MAX_EVIDENCE_BYTES =
        ProcMapsCaptureReader.DEFAULT_MAX_BYTES + 64 * 1024

    fun capture(
        build: RepackedRuntimeBuildResult,
        transport: RepackedRuntimeProbeTransport,
        cancellation: CancellationSignal,
    ): RepackedRuntimeProbeCaptureResult {
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
            "Installed runtime probe schema version is unsupported."
        }
        require(query.packageName == build.packageName) {
            "Runtime probe query package identity mismatch."
        }
        require(query.pid > 0) {
            "Runtime probe query PID is invalid."
        }

        checkCancelled(cancellation)
        val raw = transport.readEvidence(
            authority = authority,
            cancellation = cancellation,
            maxBytes = MAX_EVIDENCE_BYTES,
        )
        val parsed = RepackedRuntimeEvidenceProtocol.parse(raw)
        require(parsed.packageName == query.packageName) {
            "Runtime probe stream package does not match query."
        }
        require(parsed.pid == query.pid) {
            "Runtime probe PID changed between query and evidence capture."
        }
        require(parsed.processIdentity == build.packageName) {
            "Runtime probe process identity is not the target main process."
        }
        require(!parsed.mapsTruncated) {
            "Runtime probe process maps capture is truncated."
        }

        val mapsText = parsed.maps.toString(Charsets.UTF_8)
        require(mapsText.isNotBlank()) {
            "Runtime probe process maps are empty."
        }

        return RepackedRuntimeProbeCaptureResult(
            packageName = build.packageName,
            authority = authority,
            providerClassName =
                installed.providerClassName,
            signerCertificateSha256 =
                installed.signerCertificateSha256,
            pid = parsed.pid,
            processIdentity = parsed.processIdentity,
            capturedAtEpochMs = parsed.capturedAtEpochMs,
            rawEvidenceSha256 = sha256(raw),
            maps = ProcMapsCapture(
                source =
                    ProcMapsCaptureSource.REPACKED_TEST_RUNTIME,
                pid = parsed.pid,
                capturedAtEpochMs =
                    parsed.capturedAtEpochMs,
                text = mapsText,
                sha256 = parsed.mapsSha256,
                truncated = false,
            ),
        )
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
}
