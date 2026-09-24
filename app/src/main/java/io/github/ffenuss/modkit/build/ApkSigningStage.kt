package io.github.ffenuss.modkit.build

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.security.Signature

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
    /**
     * ModKit targets the phone it runs on (minSdk 26). Repacked test APKs
     * use v2/v3 from Android 7 onward. The older v1/JAR signer is unnecessary
     * and can fail with an AndroidKeyStore non-exportable RSA private key.
     * Do not claim compatibility with Android 6 or older for test outputs.
     */
    internal const val MIN_TEST_APK_API = 24
    internal const val ENABLE_V1 = false

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
            requireSigningIdentity(identity)
            val signerConfig = ApkSigner.SignerConfig.Builder(
                "MODKIT",
                identity.privateKey,
                identity.certificates,
            ).build()

            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(temp)
                .setMinSdkVersion(MIN_TEST_APK_API)
                .setV1SigningEnabled(ENABLE_V1)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setCreatedBy("ModKit")
                .build()
                .sign()

            val verification = verify(
                file = temp,
                minimumCheckedApi = MIN_TEST_APK_API,
            )
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
        } catch (failure: Exception) {
            temp.delete()
            output.delete()
            throw IllegalStateException(
                BuildFailureDetails.describe(
                    stage = "Подпись APK v2/v3",
                    artifactName = input.name,
                    failure = failure,
                ),
                failure,
            )
        }
    }

    fun verify(
        file: File,
        minimumCheckedApi: Int? = null,
    ): ApkSignatureVerification {
        require(file.isFile && file.canRead()) { "APK is unavailable." }
        val builder = ApkVerifier.Builder(file)
        minimumCheckedApi?.let(builder::setMinCheckedPlatformVersion)
        val result = builder.build().verify()
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

    /**
     * Check the key/certificate pair on a 32-byte message before processing a
     * multi-hundred-megabyte asset split. Reveals a JCA provider/key failure
     * at the start rather than after the APK has been fully repackaged.
     */
    private fun requireSigningIdentity(identity: ApkSigningIdentity) {
        val certificate =
            identity.certificates.firstOrNull()
                ?: error("У ключа ModKit отсутствует сертификат.")
        try {
            val payload = "ModKit signer preflight".toByteArray(Charsets.UTF_8)
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(identity.privateKey)
            signature.update(payload)
            val bytes = signature.sign()

            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(certificate.publicKey)
            verifier.update(payload)
            require(verifier.verify(bytes)) {
                "Ключ и сертификат ModKit не совпадают."
            }
        } catch (failure: Exception) {
            throw IllegalStateException(
                BuildFailureDetails.describe(
                    stage = "Самопроверка ключа ModKit",
                    artifactName = null,
                    failure = failure,
                ),
                failure,
            )
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
