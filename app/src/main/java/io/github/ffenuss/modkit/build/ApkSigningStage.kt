package io.github.ffenuss.modkit.build

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

data class ApkSignatureVerification(
    val verified: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val v31: Boolean,
    val signerCertificateSha256: List<String>,
    val warnings: List<String>,
    val errors: List<String>,
)

data class ApkSigningResult(
    val outputFile: File,
    val identityAlias: String,
    val verification: ApkSignatureVerification,
)

/**
 * Signs an already-aligned APK using the official Android apksig library and
 * immediately verifies the produced signature before returning the file.
 */
object ApkSigningStage {
    fun signAndVerify(
        input: File,
        output: File,
        identity: ApkSigningIdentity,
    ): ApkSigningResult {
        require(input.isFile && input.canRead()) { "Aligned APK is unavailable." }
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.delete()
        output.delete()

        try {
            val signerConfig = ApkSigner.SignerConfig.Builder(
                "MODKIT",
                identity.privateKey,
                identity.certificates,
            ).build()

            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(temp)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setCreatedBy("ModKit 0.0.1")
                .build()
                .sign()

            val verification = verify(temp)
            require(verification.verified) {
                "Signed APK verification failed: " +
                    (verification.errors.firstOrNull() ?: "unknown signature error")
            }
            require(verification.v2 || verification.v3 || verification.v31) {
                "Signed APK lacks a verified modern APK Signature Scheme."
            }

            check(temp.renameTo(output)) {
                "Could not finalize signed APK."
            }
            return ApkSigningResult(
                outputFile = output,
                identityAlias = identity.alias,
                verification = verification,
            )
        } catch (failure: Throwable) {
            temp.delete()
            output.delete()
            throw failure
        }
    }

    fun verify(file: File): ApkSignatureVerification {
        require(file.isFile && file.canRead()) { "APK is unavailable." }
        val result = ApkVerifier.Builder(file).build().verify()
        return ApkSignatureVerification(
            verified = result.isVerified,
            v1 = result.isVerifiedUsingV1Scheme,
            v2 = result.isVerifiedUsingV2Scheme,
            v3 = result.isVerifiedUsingV3Scheme,
            v31 = result.isVerifiedUsingV31Scheme,
            signerCertificateSha256 = result.signerCertificates.map { certificate ->
                MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .toHex()
            }.distinct(),
            warnings = result.warnings.map { it.toString() },
            errors = result.errors.map { it.toString() },
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
