package io.github.ffenuss.modkit.build

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.data.InstalledAppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OwnerTargetAuthorization(
    val supported: Boolean,
    val verified: Boolean,
    val packageName: String?,
    val sourceSignerSha256: List<String>,
    val ownerSignerSha256: String?,
    val message: String,
)

object OwnerTargetAuthorizationVerifier {
    suspend fun verify(
        context: Context,
        target: AnalysisTargetDescriptor,
        imported: ImportedSigningIdentity,
    ): OwnerTargetAuthorization =
        withContext(Dispatchers.IO) {
            val installed =
                target as?
                    AnalysisTargetDescriptor
                        .InstalledPackage
                    ?: return@withContext
                        OwnerTargetAuthorization(
                            supported = false,
                            verified = false,
                            packageName = null,
                            sourceSignerSha256 =
                                emptyList(),
                            ownerSignerSha256 =
                                imported
                                    .certificateSha256,
                            message =
                                "Режим владельца пока проверяет " +
                                    "только установленное приложение. " +
                                    "Выберите игру из списка установленных.",
                        )

            val app =
                InstalledAppRepository(
                    context,
                ).find(
                    installed.packageName,
                )
                    ?: return@withContext
                        OwnerTargetAuthorization(
                            supported = true,
                            verified = false,
                            packageName =
                                installed.packageName,
                            sourceSignerSha256 =
                                emptyList(),
                            ownerSignerSha256 =
                                imported
                                    .certificateSha256,
                            message =
                                "Выбранное приложение больше " +
                                    "не доступно на устройстве.",
                        )

            val baseApk =
                app.apkFiles.firstOrNull()
                    ?: return@withContext
                        OwnerTargetAuthorization(
                            supported = true,
                            verified = false,
                            packageName =
                                installed.packageName,
                            sourceSignerSha256 =
                                emptyList(),
                            ownerSignerSha256 =
                                imported
                                    .certificateSha256,
                            message =
                                "У приложения не найден base APK.",
                        )

            val source =
                runCatching {
                    ApkSigningStage.verify(baseApk)
                }.getOrElse {
                    failure ->
                    return@withContext
                        OwnerTargetAuthorization(
                            supported = true,
                            verified = false,
                            packageName =
                                installed.packageName,
                            sourceSignerSha256 =
                                emptyList(),
                            ownerSignerSha256 =
                                imported
                                    .certificateSha256,
                            message =
                                "Не удалось проверить подпись " +
                                    "исходного APK: " +
                                    (
                                        failure.message
                                            ?: failure
                                                .javaClass
                                                .simpleName
                                    ),
                        )
                }

            if (!source.verified) {
                return@withContext
                    OwnerTargetAuthorization(
                        supported = true,
                        verified = false,
                        packageName =
                            installed.packageName,
                        sourceSignerSha256 =
                            source
                                .signerCertificateSha256,
                        ownerSignerSha256 =
                            imported
                                .certificateSha256,
                        message =
                            "Исходная подпись APK не прошла " +
                                "проверку apksig.",
                    )
            }

            val match =
                source.signerCertificateSha256
                    .any {
                        it.equals(
                            imported
                                .certificateSha256,
                            ignoreCase = true,
                        )
                    }

            OwnerTargetAuthorization(
                supported = true,
                verified = match,
                packageName =
                    installed.packageName,
                sourceSignerSha256 =
                    source
                        .signerCertificateSha256,
                ownerSignerSha256 =
                    imported
                        .certificateSha256,
                message =
                    if (match) {
                        "Ключ владельца совпадает с подписью " +
                            "исходного APK. Entitlement test " +
                            "patches и подпись этим ключом разрешены."
                    } else {
                        "Сертификат выбранного ключа не совпадает " +
                            "с подписью исходного APK. " +
                            "Entitlement patch заблокирован."
                    },
            )
        }
}
