package io.github.ffenuss.modkit.build

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

data class ApkSigningIdentity(
    val alias: String,
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
)

/**
 * App-private local signing identity for development/test APK outputs.
 *
 * The private key is generated inside AndroidKeyStore and is never exported.
 * Release/user-provided signing identities are a separate later layer.
 */
object ModKitSigningIdentityProvider {
    const val DEVELOPMENT_ALIAS = "modkit-local-output-v1"

    fun getOrCreateDevelopmentIdentity(
        alias: String = DEVELOPMENT_ALIAS,
    ): ApkSigningIdentity {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

        if (!store.containsAlias(alias)) {
            generate(alias)
        }

        val privateKey = store.getKey(alias, null) as? PrivateKey
            ?: error("AndroidKeyStore signing key is unavailable.")
        val certificates = store.getCertificateChain(alias)
            ?.mapNotNull { it as? X509Certificate }
            .orEmpty()
        require(certificates.isNotEmpty()) {
            "AndroidKeyStore signing certificate is unavailable."
        }

        return ApkSigningIdentity(
            alias = alias,
            privateKey = privateKey,
            certificates = certificates,
        )
    }

    private fun generate(alias: String) {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - ONE_DAY_MS)
        val notAfter = Date(now + CERT_VALIDITY_MS)
        val serial = BigInteger.valueOf(now.coerceAtLeast(1L))

        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setKeySize(3072)
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA512,
            )
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(
                X500Principal(
                    "CN=ModKit Local Output Signer,O=ModKit",
                ),
            )
            .setCertificateSerialNumber(serial)
            .setCertificateNotBefore(notBefore)
            .setCertificateNotAfter(notAfter)
            .build()

        KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEYSTORE,
        ).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
    private const val CERT_VALIDITY_MS = 20L * 365L * ONE_DAY_MS
}
