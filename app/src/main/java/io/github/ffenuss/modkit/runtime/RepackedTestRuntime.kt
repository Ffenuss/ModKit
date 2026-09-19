package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.Serializable
import java.security.MessageDigest

enum class RepackedRuntimeCapability {
    SOURCE_COPY,
    MANIFEST_IDENTITY_INSPECTION,
    BINARY_MANIFEST_REWRITE,
    PROBE_PAYLOAD_INJECTION,
    OLD_SIGNATURE_REMOVAL,
    APK_ALIGNMENT,
    APK_SIGNING,
    PACKAGE_VERIFY,
    TEST_LAUNCH,
    RUNTIME_EVIDENCE_CAPTURE,
    REPORT_WRITE,
    CLEANUP,
}

data class RepackedRuntimeBlocker(
    val code: String,
    val message: String,
    val requiredCapability: RepackedRuntimeCapability?,
) : Serializable

data class RepackedRuntimeManifestContract(
    val preserveOriginalPackageName: Boolean = true,
    val requiresParsedTargetPackage: Boolean = true,
    val requiresInstrumentationDeclaration: Boolean = true,
    val requiresProbeEntryPoint: Boolean = true,
    val sourceApkMustRemainUnchanged: Boolean = true,
) : Serializable

data class RepackedTestRuntimePlan(
    val artifactSha256: String,
    val targetIds: List<String>,
    val sourceRelationships: List<String>,
    val manifestContract: RepackedRuntimeManifestContract,
    val requiredCapabilities: Set<RepackedRuntimeCapability>,
    val registeredCapabilities: Set<RepackedRuntimeCapability>,
    val evidenceKinds: Set<RuntimeEvidenceObservationKind>,
    val fallbackStage: RuntimeEscalationStage,
    val cleanupRequired: Boolean,
    val blockers: List<RepackedRuntimeBlocker>,
) : Serializable {
    val required: Boolean
        get() = targetIds.isNotEmpty()

    val readyToBuildTestCopy: Boolean
        get() = required && blockers.isEmpty()
}

/**
 * Architecture gate for the transparent repacked-test runtime.
 *
 * This planner never claims availability from design intent. A capability is
 * usable only when its concrete executor is supplied by the caller/registry.
 * Registered capabilities below correspond to concrete executors already
 * present in the clean repository. Instrumentation/launch/capture remain
 * unavailable until their executors exist.
 */
object RepackedTestRuntimePlanner {
    val requiredCapabilities: Set<RepackedRuntimeCapability> = setOf(
        RepackedRuntimeCapability.SOURCE_COPY,
        RepackedRuntimeCapability.MANIFEST_IDENTITY_INSPECTION,
        RepackedRuntimeCapability.BINARY_MANIFEST_REWRITE,
        RepackedRuntimeCapability.PROBE_PAYLOAD_INJECTION,
        RepackedRuntimeCapability.OLD_SIGNATURE_REMOVAL,
        RepackedRuntimeCapability.APK_ALIGNMENT,
        RepackedRuntimeCapability.APK_SIGNING,
        RepackedRuntimeCapability.PACKAGE_VERIFY,
        RepackedRuntimeCapability.TEST_LAUNCH,
        RepackedRuntimeCapability.RUNTIME_EVIDENCE_CAPTURE,
        RepackedRuntimeCapability.REPORT_WRITE,
        RepackedRuntimeCapability.CLEANUP,
    )

    val currentlyRegisteredCapabilities: Set<RepackedRuntimeCapability> =
        setOf(
            RepackedRuntimeCapability.SOURCE_COPY,
            RepackedRuntimeCapability.MANIFEST_IDENTITY_INSPECTION,
            RepackedRuntimeCapability.BINARY_MANIFEST_REWRITE,
            RepackedRuntimeCapability.PROBE_PAYLOAD_INJECTION,
            RepackedRuntimeCapability.OLD_SIGNATURE_REMOVAL,
            RepackedRuntimeCapability.APK_ALIGNMENT,
            RepackedRuntimeCapability.APK_SIGNING,
            RepackedRuntimeCapability.PACKAGE_VERIFY,
            RepackedRuntimeCapability.REPORT_WRITE,
            RepackedRuntimeCapability.CLEANUP,
        )

    fun plan(
        result: FastAnalysisResult,
        registeredCapabilities: Set<RepackedRuntimeCapability> =
            currentlyRegisteredCapabilities,
    ): RepackedTestRuntimePlan {
        val escalation = RuntimeEscalationPlanner.plan(result)
        val needs = escalation.needs.filter {
            it.firstStage == RuntimeEscalationStage.REPACKED_TEST_RUNTIME
        }
        val targetIds = needs.map { it.targetId }.distinct()

        val blockers = mutableListOf<RepackedRuntimeBlocker>()
        if (targetIds.isEmpty()) {
            blockers += RepackedRuntimeBlocker(
                code = "REPACKED_RUNTIME_NOT_REQUIRED",
                message = "No unresolved confirmation currently requires repacked test runtime.",
                requiredCapability = null,
            )
        }
        (requiredCapabilities - registeredCapabilities)
            .sortedBy { it.ordinal }
            .forEach { missing ->
                blockers += RepackedRuntimeBlocker(
                    code = missing.name + "_NOT_REGISTERED",
                    message =
                        "Concrete executor is not registered for " +
                            missing.name.lowercase().replace('_', ' ') + ".",
                    requiredCapability = missing,
                )
            }

        return RepackedTestRuntimePlan(
            artifactSha256 = result.index.artifactSha256,
            targetIds = targetIds,
            sourceRelationships = result.index.sources.map { source ->
                source.displayName + "@" + source.sha256
            },
            manifestContract = RepackedRuntimeManifestContract(),
            requiredCapabilities = requiredCapabilities,
            registeredCapabilities = registeredCapabilities,
            evidenceKinds = setOf(
                RuntimeEvidenceObservationKind.PROCESS_OBSERVED,
                RuntimeEvidenceObservationKind.MODULE_OBSERVED,
                RuntimeEvidenceObservationKind.MODULE_MAPPING_CONFIRMED,
                RuntimeEvidenceObservationKind.RUNTIME_ADDRESS_CONFIRMED,
            ),
            fallbackStage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
            cleanupRequired = true,
            blockers = blockers,
        )
    }
}

data class RepackedRuntimePreparedSource(
    val sourceDisplayName: String,
    val sourceSha256: String,
    val copiedFilePath: String,
    val copiedSha256: String,
) : Serializable

data class RepackedRuntimePreparedWorkspace(
    val artifactSha256: String,
    val rootPath: String,
    val sources: List<RepackedRuntimePreparedSource>,
    val preparedAtEpochMs: Long,
) : Serializable

/**
 * First concrete executor for the repacked-test path: make verified test copies.
 *
 * Source files are opened read-only. Every descriptor SHA is checked while
 * copying and every completed copy is independently re-hashed before it is
 * returned. No manifest/signature mutation happens here.
 */
object RepackedRuntimeWorkspacePreparer {
    private const val BUFFER_BYTES = 128 * 1024

    fun prepare(
        workspace: AnalysisWorkspace,
        outputDir: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimePreparedWorkspace {
        require(workspace.sources.isNotEmpty()) {
            "Repacked runtime requires at least one APK/APK-set source."
        }
        val root = File(
            outputDir,
            workspace.index.artifactSha256 + "/repacked-test/source-copy",
        )
        root.mkdirs()

        val prepared = mutableListOf<RepackedRuntimePreparedSource>()
        try {
            workspace.sources.forEachIndexed { index, source ->
                checkCancelled(cancellation)
                require(source.file.isFile && source.file.canRead()) {
                    "Source is unavailable: " + source.descriptor.displayName
                }

                val safeName = source.descriptor.displayName
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "source.apk" }
                val copy = File(root, index.toString().padStart(3, '0') + "-" + safeName)
                require(
                    copy.canonicalFile != source.file.canonicalFile,
                ) {
                    "Repacked runtime source copy must not alias the original APK."
                }
                val temp = File(copy.parentFile, copy.name + ".tmp")
                temp.delete()
                copy.delete()

                val sourceDigest = MessageDigest.getInstance("SHA-256")
                BufferedInputStream(
                    FileInputStream(source.file),
                    BUFFER_BYTES,
                ).use { input ->
                    BufferedOutputStream(
                        FileOutputStream(temp),
                        BUFFER_BYTES,
                    ).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            checkCancelled(cancellation)
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            sourceDigest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }

                val actualSourceSha = sourceDigest.digest().toHex()
                require(
                    actualSourceSha.equals(
                        source.descriptor.sha256,
                        ignoreCase = true,
                    ),
                ) {
                    temp.delete()
                    "Source SHA changed before repacked runtime preparation: " +
                        source.descriptor.displayName
                }
                check(temp.renameTo(copy)) {
                    temp.delete()
                    "Could not finalize repacked runtime source copy."
                }

                val copySha = sha256(copy, cancellation)
                require(copySha.equals(actualSourceSha, ignoreCase = true)) {
                    copy.delete()
                    "Repacked runtime source copy failed SHA verification."
                }

                prepared += RepackedRuntimePreparedSource(
                    sourceDisplayName = source.descriptor.displayName,
                    sourceSha256 = actualSourceSha,
                    copiedFilePath = copy.absolutePath,
                    copiedSha256 = copySha,
                )
            }

            return RepackedRuntimePreparedWorkspace(
                artifactSha256 = workspace.index.artifactSha256,
                rootPath = root.absolutePath,
                sources = prepared,
                preparedAtEpochMs = System.currentTimeMillis(),
            )
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    fun cleanup(
        prepared: RepackedRuntimePreparedWorkspace,
        allowedOutputRoot: File,
    ): Boolean {
        val target = File(prepared.rootPath).canonicalFile
        val allowed = allowedOutputRoot.canonicalFile
        require(target.toPath().startsWith(allowed.toPath())) {
            "Refusing cleanup outside the caller-provided repacked runtime root."
        }
        return !target.exists() || target.deleteRecursively()
    }

    private fun sha256(
        file: File,
        cancellation: CancellationSignal,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(
            FileInputStream(file),
            BUFFER_BYTES,
        ).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
