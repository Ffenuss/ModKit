package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data-only schema 2. Catalog entries contain build-time identities, NOT
 * assertions that the APK is currently installed or functionally modifiable.
 * The separately signed Companion re-checks all bytes and signers on use.
 */
internal data class SpaceCatalogEntry(
    val packageName: String,
    val artifactSha256: String,
    val signers: List<String>,
    val sources: List<SpaceLegacySource>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("artifactSha256", artifactSha256)
        .put("signers", JSONArray(signers))
        .put("sources", JSONArray(sources.map { source ->
            JSONObject().put("sha256", source.sha256).put("size", source.size)
        }))
}

internal object SpaceCatalogContract {
    const val SCHEMA = 2
    const val MAX_PROFILES = 32

    fun entries(records: List<AutoModBuildRecord>): List<SpaceCatalogEntry> =
        SpaceBuildProfileIndex.select(records).asSequence()
            .mapNotNull { record ->
                val menu = SpaceLegacyProfileContract.from(record) ?: return@mapNotNull null
                val signers = record.plan.signerCertificateSha256
                    .map { it.lowercase(Locale.ROOT) }.distinct().sorted()
                if (signers.size !in 1..4 ||
                    signers.size != record.plan.signerCertificateSha256.size) {
                    return@mapNotNull null
                }
                SpaceCatalogEntry(
                    menu.packageName, menu.artifactSha256, signers, menu.sources,
                )
            }
            .take(MAX_PROFILES)
            .toList()

    fun encode(records: List<AutoModBuildRecord>): ByteArray =
        JSONObject()
            .put("schema", SCHEMA)
            .put("backend", "none")
            .put("profiles", JSONArray(entries(records).map { it.toJson() }))
            .toString().toByteArray(Charsets.UTF_8)
}
