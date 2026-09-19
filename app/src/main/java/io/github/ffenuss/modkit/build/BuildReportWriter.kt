package io.github.ffenuss.modkit.build

import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.patch.MutationDiff
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object BuildReportWriter {
    fun write(
        outputDir: File,
        sourceArtifactSha256: String,
        files: List<BuiltApkFile>,
        mutationDiffs: List<MutationDiff>,
        diffVerification: MutationDiffVerification,
        signerAlias: String,
        signerCertificateSha256: List<String>,
        postBuildAnalysis: FastAnalysisResult,
        builtAtEpochMs: Long,
    ): File {
        outputDir.mkdirs()
        val file = File(outputDir, "ModKit-build-report.txt")
        val formatter = SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.US,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val graph = postBuildAnalysis.evidenceGraph
        val summary = graph?.summary
        val content = buildString {
            appendLine("ModKit verified build report")
            appendLine("Format: 1")
            appendLine("Built at: " + formatter.format(Date(builtAtEpochMs)))
            appendLine("Source artifact SHA-256: " + sourceArtifactSha256)
            appendLine("Signer alias: " + signerAlias)
            signerCertificateSha256.forEach {
                appendLine("Signer certificate SHA-256: " + it)
            }
            appendLine()
            appendLine("OUTPUT FILES")
            files.forEach { built ->
                appendLine("- " + built.file.name)
                appendLine("  size: " + built.file.length())
                appendLine("  SHA-256: " + built.sha256)
                appendLine(
                    "  signature: verified=" + built.signature.verified +
                        ", v1=" + built.signature.v1 +
                        ", v2=" + built.signature.v2 +
                        ", v3=" + built.signature.v3 +
                        ", v3.1=" + built.signature.v31,
                )
                appendLine(
                    "  alignment: verified=" + built.alignment.verified +
                        ", checked entries=" + built.alignment.records.size,
                )
            }
            appendLine()
            appendLine("MUTATION DIFF")
            appendLine("Verified: " + diffVerification.verified)
            mutationDiffs.forEach { diff ->
                appendLine("- " + diff.mutationId)
                appendLine("  type: " + diff.kind.name)
                appendLine("  target: " + diff.container + ":" + diff.entryPath)
                diff.fileOffset?.let {
                    appendLine("  file offset: 0x" + it.toString(16))
                }
                appendLine("  length: " + diff.length)
                appendLine("  before SHA-256: " + diff.beforeSha256)
                appendLine("  after SHA-256: " + diff.afterSha256)
            }
            if (diffVerification.blockers.isNotEmpty()) {
                appendLine("Diff blockers:")
                diffVerification.blockers.forEach { appendLine("- " + it) }
            }
            appendLine()
            appendLine("POST-BUILD RE-ANALYSIS")
            appendLine(
                "Built artifact SHA-256: " +
                    postBuildAnalysis.index.artifactSha256,
            )
            appendLine(
                "Detected ABIs: " +
                    postBuildAnalysis.index.detectedAbis.sorted().joinToString()
                        .ifBlank { "none" },
            )
            appendLine("Runtimes:")
            postBuildAnalysis.index.runtimeProfiles.forEach { runtime ->
                appendLine(
                    "- " + runtime.runtimeId +
                        " [" + runtime.status.name + "/" +
                        runtime.confidence.name + "]",
                )
            }
            appendLine(
                "Evidence targets: " +
                    (graph?.targets?.size ?: 0),
            )
            if (summary != null) {
                appendLine(
                    "Evidence summary: found=" + summary.found +
                        ", confirming=" + summary.confirming +
                        ", confirmed=" + summary.confirmed +
                        ", ready=" + summary.ready +
                        ", runtimeRequired=" + summary.runtimeRequired +
                        ", couldNotConfirm=" + summary.couldNotConfirm,
                )
            }
            if (postBuildAnalysis.engineWarnings.isNotEmpty()) {
                appendLine("Analysis warnings:")
                postBuildAnalysis.engineWarnings.forEach {
                    appendLine("- " + it)
                }
            }
        }

        val temp = File(outputDir, file.name + ".tmp")
        temp.writeText(content, Charsets.UTF_8)
        if (file.exists()) file.delete()
        check(temp.renameTo(file)) {
            temp.delete()
            "Could not finalize build report."
        }
        return file
    }
}
