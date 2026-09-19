package io.github.ffenuss.modkit.runtime

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.build.ApkSignatureVerification
import io.github.ffenuss.modkit.build.ApkSigningStage
import io.github.ffenuss.modkit.build.ApkZipAligner
import io.github.ffenuss.modkit.build.BuiltPackageVerifier
import io.github.ffenuss.modkit.build.InstallabilityVerification
import io.github.ffenuss.modkit.build.ModKitSigningIdentityProvider
import io.github.ffenuss.modkit.build.ZipAlignmentVerification
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.Serializable
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class RepackedRuntimeSanitizedApk(
    val sourcePath: String,
    val outputPath: String,
    val sourceSha256: String,
    val outputSha256: String,
    val strippedSignatureEntries: List<String>,
) : Serializable

data class RepackedRuntimeBuiltApk(
    val sourceDisplayName: String,
    val sanitizedSha256: String,
    val alignedPath: String,
    val signedPath: String,
    val signedSha256: String,
    val alignment: ZipAlignmentVerification,
    val signature: ApkSignatureVerification,
) : Serializable

data class RepackedRuntimeBuildResult(
    val artifactSha256: String,
    val packageName: String,
    val signedApks: List<RepackedRuntimeBuiltApk>,
    val installability: InstallabilityVerification,
    val reportPath: String,
    val signerAlias: String,
    val completedAtEpochMs: Long,
) : Serializable

/**
 * Rewrites an APK without legacy JAR-signature entries.
 *
 * This is intentionally archive-only. No manifest, DEX or native payload is
 * changed here. The source file is read-only and a separate output is created.
 */
object RepackedRuntimeSignatureStripper {
    private const val BUFFER_BYTES = 128 * 1024

    fun strip(
        input: File,
        output: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimeSanitizedApk {
        require(input.isFile && input.canRead()) {
            "Repacked runtime APK source is unavailable."
        }
        require(input.canonicalFile != output.canonicalFile) {
            "Signature stripping must not overwrite the source APK."
        }

        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.delete()
        output.delete()

        val sourceSha = sha256(input, cancellation)
        val stripped = mutableListOf<String>()

        try {
            ZipFile(input).use { zip ->
                ZipOutputStream(
                    BufferedOutputStream(
                        FileOutputStream(temp),
                        BUFFER_BYTES,
                    ),
                ).use { out ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        checkCancelled(cancellation)
                        val entry = entries.nextElement()
                        if (isLegacySignatureEntry(entry.name)) {
                            stripped += entry.name
                            continue
                        }

                        val cloned = cloneEntry(entry)
                        out.putNextEntry(cloned)
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { source ->
                                copy(source, out, cancellation)
                            }
                        }
                        out.closeEntry()
                    }
                }
            }

            check(temp.renameTo(output)) {
                "Could not finalize signature-stripped APK."
            }
            val outputSha = sha256(output, cancellation)
            require(sourceSha != outputSha || stripped.isEmpty()) {
                "Legacy signature removal produced no observable archive change."
            }

            return RepackedRuntimeSanitizedApk(
                sourcePath = input.absolutePath,
                outputPath = output.absolutePath,
                sourceSha256 = sourceSha,
                outputSha256 = outputSha,
                strippedSignatureEntries = stripped.distinct().sorted(),
            )
        } catch (failure: Throwable) {
            temp.delete()
            output.delete()
            throw failure
        }
    }

    private fun cloneEntry(source: ZipEntry): ZipEntry {
        val target = ZipEntry(source.name)
        target.comment = source.comment
        target.extra = source.extra
        if (source.time >= 0L) target.time = source.time
        target.method = source.method

        if (source.method == ZipEntry.STORED) {
            require(source.size >= 0L && source.crc >= 0L) {
                "Stored APK entry lacks size/CRC: " + source.name
            }
            target.size = source.size
            target.compressedSize = source.size
            target.crc = source.crc
        }
        return target
    }

    private fun isLegacySignatureEntry(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        val leaf = upper.substringAfterLast('/')
        return leaf == "MANIFEST.MF" ||
            leaf.endsWith(".SF") ||
            leaf.endsWith(".RSA") ||
            leaf.endsWith(".DSA") ||
            leaf.endsWith(".EC")
    }

    private fun copy(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        cancellation: CancellationSignal,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            checkCancelled(cancellation)
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) output.write(buffer, 0, read)
        }
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
        return digest.digest()
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }
}

/**
 * Finalizes an already instrumented repacked-test APK/APK-set.
 *
 * Important: this coordinator does not instrument the APK. It is usable only
 * after a separate manifest/payload executor has created modified test copies.
 * It provides the already-real build tail: stale-signature removal, alignment,
 * development signing, package verification and report generation.
 */
object RepackedRuntimeBuildCoordinator {
    fun build(
        context: Context,
        artifactSha256: String,
        expectedPackageName: String,
        instrumentedApks: List<Pair<String, File>>,
        outputRoot: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): RepackedRuntimeBuildResult {
        require(artifactSha256.isNotBlank()) {
            "Artifact SHA is required for repacked runtime build."
        }
        require(expectedPackageName.isNotBlank()) {
            "Parsed target package is required for repacked runtime build."
        }
        require(instrumentedApks.isNotEmpty()) {
            "No instrumented test APK is available."
        }

        val root = File(
            outputRoot,
            artifactSha256 + "/repacked-test/finalized",
        ).apply { mkdirs() }
        val signedFiles = mutableListOf<File>()
        val built = mutableListOf<RepackedRuntimeBuiltApk>()

        try {
            val identity =
                ModKitSigningIdentityProvider.getOrCreateDevelopmentIdentity()

            instrumentedApks.forEachIndexed { index, (displayName, source) ->
                checkCancelled(cancellation)
                require(source.isFile && source.canRead()) {
                    "Instrumented APK is unavailable: $displayName"
                }

                val safe = displayName
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "base.apk" }
                val prefix = index.toString().padStart(3, '0') + "-" + safe
                val sanitized = File(root, prefix + ".unsigned.apk")
                val aligned = File(root, prefix + ".aligned.apk")
                val signed = File(root, prefix + ".signed.apk")

                val sanitizedResult = RepackedRuntimeSignatureStripper.strip(
                    input = source,
                    output = sanitized,
                    cancellation = cancellation,
                )
                val alignedResult = ApkZipAligner.align(
                    input = sanitized,
                    output = aligned,
                    cancellation = cancellation,
                    progress = progress,
                )
                val signedResult = ApkSigningStage.signAndVerify(
                    input = alignedResult.outputFile,
                    output = signed,
                    identity = identity,
                )
                val signedSha = sha256(signed, cancellation)

                built += RepackedRuntimeBuiltApk(
                    sourceDisplayName = displayName,
                    sanitizedSha256 = sanitizedResult.outputSha256,
                    alignedPath = aligned.absolutePath,
                    signedPath = signed.absolutePath,
                    signedSha256 = signedSha,
                    alignment = alignedResult.verification,
                    signature = signedResult.verification,
                )
                signedFiles += signed

                sanitized.delete()
                aligned.delete()
            }

            val installability = BuiltPackageVerifier.verify(
                context = context,
                files = signedFiles,
            )
            require(installability.verified) {
                installability.blockers.firstOrNull()
                    ?: "Repacked runtime package verification failed."
            }
            require(installability.packageName == expectedPackageName) {
                "Repacked runtime package name changed: expected " +
                    expectedPackageName + ", got " +
                    (installability.packageName ?: "not_resolved")
            }

            val completedAt = System.currentTimeMillis()
            val report = RepackedRuntimeBuildReportWriter.write(
                outputDir = root,
                artifactSha256 = artifactSha256,
                packageName = expectedPackageName,
                signerAlias = identity.alias,
                built = built,
                installability = installability,
                completedAtEpochMs = completedAt,
            )

            return RepackedRuntimeBuildResult(
                artifactSha256 = artifactSha256,
                packageName = expectedPackageName,
                signedApks = built,
                installability = installability,
                reportPath = report.absolutePath,
                signerAlias = identity.alias,
                completedAtEpochMs = completedAt,
            )
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    private fun sha256(
        file: File,
        cancellation: CancellationSignal,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file), 128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }
}

object RepackedRuntimeBuildReportWriter {
    fun write(
        outputDir: File,
        artifactSha256: String,
        packageName: String,
        signerAlias: String,
        built: List<RepackedRuntimeBuiltApk>,
        installability: InstallabilityVerification,
        completedAtEpochMs: Long,
    ): File {
        outputDir.mkdirs()
        val report = File(outputDir, "repacked-runtime-build-report.txt")
        val temp = File(outputDir, report.name + ".tmp")
        temp.delete()

        temp.writeText(
            buildString {
                appendLine("ModKit repacked test runtime build report")
                appendLine("schemaVersion: 1")
                appendLine("engineVersion: repacked-runtime-build/1")
                appendLine("artifactSha256: " + artifactSha256)
                appendLine("packageName: " + packageName)
                appendLine("signerAlias: " + signerAlias)
                appendLine("completedAtEpochMs: " + completedAtEpochMs)
                appendLine("sourcePolicy: COPY_ONLY")
                appendLine("installabilityVerified: " + installability.verified)
                built.forEach { apk ->
                    appendLine("- source: " + apk.sourceDisplayName)
                    appendLine("  sanitizedSha256: " + apk.sanitizedSha256)
                    appendLine("  signedSha256: " + apk.signedSha256)
                    appendLine("  alignmentVerified: " + apk.alignment.verified)
                    appendLine("  signatureVerified: " + apk.signature.verified)
                    appendLine(
                        "  signerCertSha256: " +
                            apk.signature.signerCertificateSha256.joinToString()
                                .ifBlank { "not_resolved" },
                    )
                }
            },
            Charsets.UTF_8,
        )

        if (report.exists()) report.delete()
        check(temp.renameTo(report)) {
            temp.delete()
            "Could not finalize repacked runtime build report."
        }
        return report
    }
}
