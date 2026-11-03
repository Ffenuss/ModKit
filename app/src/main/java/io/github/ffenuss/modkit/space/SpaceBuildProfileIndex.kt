package io.github.ffenuss.modkit.space

import android.content.Context
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import java.io.File

/**
 * A non-virtualized, read-only index of verified ModKit build records.
 *
 * The older Space host has its own guest/menu contract. This catalog does not
 * claim to implement that host, or turn static APK patches into live switches.
 * It is the stable per-package entry point for a future authenticated handoff.
 */
object SpaceBuildProfileIndex {
    private val sha = Regex("[0-9a-fA-F]{64}")
    private val pkg = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** One latest usable build per Android package; never mix APKs from games. */
    fun select(records: List<AutoModBuildRecord>): List<AutoModBuildRecord> =
        records.asSequence()
            .filter { record ->
                val plan = record.plan
                plan.ready && sha.matches(plan.artifactSha256) &&
                    pkg.matches(plan.packageName) &&
                    plan.apks.size in 1..64 &&
                    plan.apks.all { sha.matches(it.expectedSha256) && it.size > 0L } &&
                    plan.signerCertificateSha256.isNotEmpty() &&
                    plan.signerCertificateSha256.all(sha::matches)
            }
            .groupBy { it.plan.packageName }
            .values
            .mapNotNull { versions ->
                versions.maxWithOrNull(
                    compareBy<AutoModBuildRecord> { it.builtAt }
                        .thenBy { it.plan.artifactSha256 },
                )
            }
            .sortedWith(compareByDescending<AutoModBuildRecord> { it.builtAt }
                .thenBy { it.plan.packageName })
            .toList()

    /**
     * Load only bounded, canonical build-history files. Records are parsed by
     * the existing validator, which rejects missing/untrusted output paths.
     * A deleted build is not advertised as launch-ready.
     */
    fun load(context: Context): List<AutoModBuildRecord> {
        val root = File(context.filesDir, "build-history").canonicalFile
        val files = root.listFiles().orEmpty()
            .filter { candidate ->
                candidate.isFile &&
                    candidate.length() in 1L..1_048_576L &&
                    candidate.name.endsWith(".json") &&
                    sha.matches(candidate.name.removeSuffix(".json")) &&
                    candidate.canonicalFile.parentFile == root
            }
            .sortedByDescending(File::lastModified)
            .take(512)
        return select(files.mapNotNull {
            AutoModBuildRecord.load(context, it.name.removeSuffix(".json"))
        })
    }
}
