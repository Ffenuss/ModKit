package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.io.Serializable

data class RepackedRuntimeManifestRecord(
    val sourceDisplayName: String,
    val copiedFilePath: String,
    val packageName: String,
    val splitName: String?,
    val applicationClassName: String?,
    val debuggable: Boolean?,
    val manifestSha256: String,
) : Serializable

data class RepackedRuntimeManifestInventory(
    val artifactSha256: String,
    val packageName: String?,
    val baseSourceDisplayName: String?,
    val records: List<RepackedRuntimeManifestRecord>,
    val blockers: List<String>,
) : Serializable {
    val verified: Boolean
        get() =
            blockers.isEmpty() &&
                !packageName.isNullOrBlank() &&
                !baseSourceDisplayName.isNullOrBlank() &&
                records.isNotEmpty()
}

/**
 * Inspects only prepared repacked-test copies. Original target APKs are never
 * opened by this layer after workspace preparation.
 */
object RepackedRuntimeManifestInventoryBuilder {
    fun inspect(
        prepared: RepackedRuntimePreparedWorkspace,
        cancellation: CancellationSignal,
    ): RepackedRuntimeManifestInventory {
        val records = mutableListOf<RepackedRuntimeManifestRecord>()
        val blockers = mutableListOf<String>()

        prepared.sources.forEach { source ->
            val file = File(source.copiedFilePath)
            val info = runCatching {
                BinaryAndroidManifestInspector.inspectApk(
                    apk = file,
                    cancellation = cancellation,
                )
            }.getOrElse { failure ->
                blockers += source.sourceDisplayName + ": " +
                    (failure.message ?: failure.javaClass.simpleName)
                return@forEach
            }

            records += RepackedRuntimeManifestRecord(
                sourceDisplayName = source.sourceDisplayName,
                copiedFilePath = source.copiedFilePath,
                packageName = info.packageName,
                splitName = info.splitName,
                applicationClassName = info.applicationClassName,
                debuggable = info.debuggable,
                manifestSha256 = info.manifestSha256,
            )
        }

        val packages = records.map { it.packageName }.distinct()
        if (records.size != prepared.sources.size) {
            blockers += "Not every prepared source has a validated binary AndroidManifest.xml."
        }
        if (packages.size != 1) {
            blockers += when {
                packages.isEmpty() ->
                    "No package name was resolved from prepared APK manifests."
                else ->
                    "Prepared APK-set manifests contain different package names."
            }
        }

        val bases = records.filter { it.splitName.isNullOrBlank() }
        if (records.isNotEmpty() && bases.size != 1) {
            blockers +=
                "Prepared APK-set must contain exactly one base manifest without split name."
        }

        val duplicateSplits = records
            .mapNotNull { it.splitName?.takeIf(String::isNotBlank) }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        if (duplicateSplits.isNotEmpty()) {
            blockers +=
                "Prepared APK-set contains duplicate split names: " +
                    duplicateSplits.joinToString()
        }

        val duplicateSources = records
            .groupingBy { it.sourceDisplayName }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .sorted()
        if (duplicateSources.isNotEmpty()) {
            blockers +=
                "Prepared APK-set contains duplicate source identities: " +
                    duplicateSources.joinToString()
        }

        return RepackedRuntimeManifestInventory(
            artifactSha256 = prepared.artifactSha256,
            packageName = packages.singleOrNull(),
            baseSourceDisplayName = bases.singleOrNull()?.sourceDisplayName,
            records = records,
            blockers = blockers.distinct(),
        )
    }
}
