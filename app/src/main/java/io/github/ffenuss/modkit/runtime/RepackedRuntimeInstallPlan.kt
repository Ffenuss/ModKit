package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.build.VerifiedBuildResult
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.Serializable
import java.security.MessageDigest

data class RepackedRuntimeInstallApk(
    val sourceDisplayName: String,
    val signedPath: String,
    val expectedSha256: String,
    val size: Long,
) : Serializable

data class RepackedRuntimeInstallPlan(
    val artifactSha256: String,
    val packageName: String,
    val signerCertificateSha256: Set<String>,
    val apks: List<RepackedRuntimeInstallApk>,
    val totalBytes: Long,
    val blockers: List<String>,
) : Serializable {
    val ready: Boolean
        get() =
            blockers.isEmpty() &&
                packageName.isNotBlank() &&
                signerCertificateSha256.isNotEmpty() &&
                apks.isNotEmpty() &&
                totalBytes > 0L
}

/**
 * Pure install preflight for the signed repacked runtime output.
 *
 * PackageInstaller is allowed to see only APKs that already passed the build
 * tail. Every file is re-hashed immediately before session creation so stale
 * or replaced staging files cannot be installed under an older build result.
 */
object RepackedRuntimeInstallPlanner {
    fun plan(
        build: RepackedRuntimeBuildResult,
        cancellation: CancellationSignal,
    ): RepackedRuntimeInstallPlan {
        val blockers = mutableListOf<String>()
        if (build.artifactSha256.isBlank()) {
            blockers += "Repacked runtime build artifact SHA is missing."
        }
        if (!build.installability.verified) {
            blockers += build.installability.blockers.ifEmpty {
                listOf("Repacked runtime build did not pass package verification.")
            }
        }
        if (build.installability.packageName != build.packageName) {
            blockers +=
                "Verified package name does not match repacked runtime build."
        }
        if (build.signedApks.isEmpty()) {
            blockers += "Repacked runtime build contains no signed APK files."
        }

        val signers = build.signedApks
            .flatMap { it.signature.signerCertificateSha256 }
            .map { it.lowercase() }
            .toSet()
        if (signers.isEmpty()) {
            blockers += "Repacked runtime build has no verified signer certificate."
        }
        build.signedApks.forEach { apk ->
            if (!apk.signature.verified) {
                blockers +=
                    apk.sourceDisplayName +
                        ": APK signature verification is not green."
            }
            if (!apk.alignment.verified) {
                blockers +=
                    apk.sourceDisplayName +
                        ": APK alignment verification is not green."
            }
            val apkSigners = apk.signature.signerCertificateSha256
                .map { it.lowercase() }
                .toSet()
            if (apkSigners != signers) {
                blockers +=
                    apk.sourceDisplayName +
                        ": signer set differs from the APK-set signer identity."
            }
        }

        val duplicateSources = build.signedApks
            .groupingBy { it.sourceDisplayName }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateSources.isNotEmpty()) {
            blockers +=
                "Repacked runtime APK-set contains duplicate source identities: " +
                    duplicateSources.sorted().joinToString()
        }

        val apks = mutableListOf<RepackedRuntimeInstallApk>()
        build.signedApks.forEach { apk ->
            checkCancelled(cancellation)
            val file = File(apk.signedPath)
            if (!file.isFile || !file.canRead() || file.length() <= 0L) {
                blockers +=
                    apk.sourceDisplayName +
                        ": signed APK is unavailable for installation."
                return@forEach
            }
            val actualSha = sha256(file, cancellation)
            if (!actualSha.equals(apk.signedSha256, ignoreCase = true)) {
                blockers +=
                    apk.sourceDisplayName +
                        ": signed APK changed after build verification."
                return@forEach
            }
            apks += RepackedRuntimeInstallApk(
                sourceDisplayName = apk.sourceDisplayName,
                signedPath = file.absolutePath,
                expectedSha256 = actualSha,
                size = file.length(),
            )
        }

        val totalBytes = runCatching {
            apks.fold(0L) { total, apk ->
                Math.addExact(total, apk.size)
            }
        }.getOrElse {
            blockers += "Repacked runtime APK-set size overflow."
            0L
        }

        return RepackedRuntimeInstallPlan(
            artifactSha256 = build.artifactSha256,
            packageName = build.packageName,
            signerCertificateSha256 = signers,
            apks = apks,
            totalBytes = totalBytes,
            blockers = blockers.distinct(),
        )
    }

    /**
     * The same authenticated Android PackageInstaller flow also installs
     * regular verified ModKit patch outputs, including base + split APKs.
     */
    fun plan(
        build: VerifiedBuildResult,
        cancellation: CancellationSignal,
    ): RepackedRuntimeInstallPlan {
        val blockers = mutableListOf<String>()
        if (!build.installability.verified) {
            blockers += build.installability.blockers.ifEmpty {
                listOf("Собранный APK не прошёл проверку устанавливаемости.")
            }
        }
        val packageName = build.installability.packageName.orEmpty()
        if (packageName.isBlank()) blockers += "Package name is unavailable."
        if (build.files.isEmpty()) blockers += "No signed APK files are available."
        val signers = build.files
            .flatMap { it.signature.signerCertificateSha256 }
            .map(String::lowercase)
            .toSet()
        if (signers.isEmpty()) blockers += "Verified signer certificate is missing."
        val duplicateNames = build.files.groupingBy { it.file.name }
            .eachCount().filterValues { it > 1 }.keys
        if (duplicateNames.isNotEmpty()) {
            blockers += "The built APK-set contains duplicate file names."
        }
        val apks = mutableListOf<RepackedRuntimeInstallApk>()
        build.files.forEach { built ->
            checkCancelled(cancellation)
            if (!built.signature.verified || !built.alignment.verified) {
                blockers += built.file.name + ": build verification is incomplete."
                return@forEach
            }
            if (built.signature.signerCertificateSha256
                    .map(String::lowercase).toSet() != signers
            ) {
                blockers += built.file.name + ": signer mismatch within the set."
                return@forEach
            }
            val file = built.file
            if (!file.isFile || !file.canRead() || file.length() <= 0L) {
                blockers += file.name + ": signed output is unavailable."
                return@forEach
            }
            val currentSha = sha256(file, cancellation)
            if (!currentSha.equals(built.sha256, ignoreCase = true)) {
                blockers += file.name + ": signed APK changed since build."
                return@forEach
            }
            apks += RepackedRuntimeInstallApk(
                sourceDisplayName = file.name,
                signedPath = file.absolutePath,
                expectedSha256 = currentSha,
                size = file.length(),
            )
        }
        val total = runCatching {
            apks.fold(0L) { sum, apk ->
                Math.addExact(sum, apk.size)
            }
        }.getOrElse {
            blockers += "APK-set total size overflow."
            0L
        }
        return RepackedRuntimeInstallPlan(
            artifactSha256 = build.artifactSha256,
            packageName = packageName,
            signerCertificateSha256 = signers,
            apks = apks,
            totalBytes = total,
            blockers = blockers.distinct(),
        )
    }

    private fun sha256(
        file: File,
        cancellation: CancellationSignal,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(
            FileInputStream(file),
            128 * 1024,
        ).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
