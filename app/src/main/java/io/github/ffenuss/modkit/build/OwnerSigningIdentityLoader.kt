package io.github.ffenuss.modkit.build

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

data class ImportedSigningIdentity(
    val identity: ApkSigningIdentity,
    val certificateSha256: String,
    val format: String,
)

/**
 * Loads an owner-provided signing identity only in memory.
 *
 * The keystore bytes and password are never persisted by ModKit.
 */
object OwnerSigningIdentityLoader {
    private const val MAX_KEYSTORE_BYTES =
        8 * 1024 * 1024

    fun load(
        context: Context,
        uri: Uri,
        password: CharArray,
        aliasHint: String?,
    ): ImportedSigningIdentity {
        val bytes =
            context.contentResolver
                .openInputStream(uri)
                ?.use { input ->
                    val data = input.readBytes()
                    require(
                        data.isNotEmpty() &&
                            data.size <= MAX_KEYSTORE_BYTES,
                    ) {
                        "Файл ключа пуст или превышает лимит 8 MiB."
                    }
                    data
                }
                ?: error(
                    "Не удалось открыть выбранный файл ключа.",
                )

        var lastFailure: Throwable? = null
        for (format in listOf("PKCS12", "JKS")) {
            try {
                val store =
                    KeyStore.getInstance(format)
                ByteArrayInputStream(bytes).use {
                    store.load(it, password)
                }

                val aliases =
                    buildList {
                        val enumeration =
                            store.aliases()
                        while (
                            enumeration.hasMoreElements()
                        ) {
                            val alias =
                                enumeration.nextElement()
                            if (store.isKeyEntry(alias)) {
                                add(alias)
                            }
                        }
                    }
                require(aliases.isNotEmpty()) {
                    "В хранилище нет приватного ключа."
                }

                val requested =
                    aliasHint
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                val alias =
                    when {
                        requested != null &&
                            requested in aliases ->
                            requested
                        requested != null ->
                            error(
                                "Alias «" +
                                    requested +
                                    "» не найден. Доступно: " +
                                    aliases.joinToString(),
                            )
                        aliases.size == 1 ->
                            aliases.single()
                        else ->
                            error(
                                "В хранилище несколько ключей. " +
                                    "Укажите alias: " +
                                    aliases.joinToString(),
                            )
                    }

                val privateKey =
                    store.getKey(
                        alias,
                        password,
                    ) as? PrivateKey
                        ?: error(
                            "Alias не содержит приватный ключ.",
                        )
                val certificates =
                    store.getCertificateChain(alias)
                        ?.mapNotNull {
                            it as? X509Certificate
                        }
                        .orEmpty()
                require(certificates.isNotEmpty()) {
                    "Для ключа не найден сертификат."
                }

                val identity =
                    ApkSigningIdentity(
                        alias = alias,
                        privateKey = privateKey,
                        certificates = certificates,
                    )
                return ImportedSigningIdentity(
                    identity = identity,
                    certificateSha256 =
                        certificateSha256(
                            certificates.first(),
                        ),
                    format = format,
                )
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }

        throw IllegalArgumentException(
            lastFailure?.message
                ?: "Не удалось прочитать PKCS12/JKS ключ.",
            lastFailure,
        )
    }

    fun certificateSha256(
        certificate: X509Certificate,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") {
                "%02x".format(
                    it.toInt() and 0xff,
                )
            }
}
