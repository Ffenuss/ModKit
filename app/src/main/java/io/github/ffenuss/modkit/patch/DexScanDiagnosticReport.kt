package io.github.ffenuss.modkit.patch

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.ffenuss.modkit.BuildConfig
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import java.io.File

/**
 * Human-readable scan evidence for a failed or empty Android DEX search.
 * No original APK, signing keys, gameplay saves or executable code is exported.
 */
object DexScanDiagnosticReport {
    fun write(
        context: Context,
        targetName: String,
        analysis: FastAnalysisResult,
        scan: DexLocalScan,
    ): File {
        val root = File(
            context.filesDir,
            "expert-lab-export",
        )
        require(root.isDirectory || root.mkdirs()) {
            "Не удалось создать папку диагностических отчётов."
        }
        val report = File(
            root,
            "ModKit-DEX-diagnostics-" +
                System.currentTimeMillis() + ".txt",
        )
        val content = buildString {
            appendLine("ModKit Android DEX scan diagnostics")
            appendLine("Target: " + targetName)
            appendLine("Artifact SHA-256: " + analysis.index.artifactSha256)
            appendLine()
            appendLine("ANALYSIS")
            appendLine("ABI: " + analysis.index.detectedAbis.joinToString())
            appendLine(
                "Runtime fingerprints: " +
                    analysis.index.runtimeProfiles
                        .joinToString {
                            it.runtimeId + "=" + it.status.name
                        },
            )
            appendLine(
                "Indexed DEX entries: " +
                    analysis.index.entries.count {
                        it.format ==
                            io.github.ffenuss.modkit.analysis.BinaryFormat.DEX
                    },
            )
            appendLine()
            appendLine("DEX SCAN")
            appendLine("DEX files read: " + scan.dexFilesExamined)
            appendLine("Native libraries observed: " + scan.nativeLibrariesObserved)
            appendLine("Classes read: " + scan.classesInspected)
            appendLine("Classes excluded: " + scan.classesExcluded)
            appendLine("Methods read: " + scan.methodsExamined)
            appendLine("Methods with executable code: " + scan.methodsWithCode)
            appendLine("Methods without parameters: " + scan.noArgumentMethods)
            appendLine("Zero-argument scalar getters: " +
                scan.scalarNoArgumentMethods)
            appendLine("Gameplay name signals: " + scan.semanticNamesMatched)
            appendLine("Parameter/return type mismatches: " +
                scan.rejectedReturnTypes)
            appendLine("Actionable candidates: " + scan.opportunities.size)
            appendLine("Conclusion: " + scan.explanation)
            appendLine()
            appendLine("FOUND METHODS")
            scan.opportunities.forEach {
                appendLine("- " + it.category.label)
                appendLine("  method: " + it.displayName)
                appendLine("  return patch: " + it.action.label)
                appendLine("  selectable: " + it.selectable)
                appendLine("  reason: " + it.reason)
            }
            if (scan.diagnostics.isNotEmpty()) {
                appendLine()
                appendLine("NON-ACTIONABLE GAMEPLAY NAME SIGNALS (capped)")
                scan.diagnostics.take(30).forEach {
                    appendLine("- " + it)
                }
            }
            if (scan.warnings.isNotEmpty()) {
                appendLine()
                appendLine("WARNINGS")
                scan.warnings.forEach { appendLine("- " + it) }
            }
            appendLine()
            appendLine(
                "Note: a matched name is not proof of a functioning mod. " +
                    "Server-controlled state and compiled native code require " +
                    "different analysis. No unverified patch was applied.",
            )
        }
        val temp = File(root, report.name + ".tmp")
        temp.writeText(content, Charsets.UTF_8)
        require(temp.renameTo(report)) {
            temp.delete()
            "Не удалось сохранить диагностический отчёт."
        }
        return report
    }

    fun share(
        context: Context,
        report: File,
    ) {
        require(report.isFile) { "Диагностический отчёт недоступен." }
        val uri = FileProvider.getUriForFile(
            context,
            BuildConfig.APPLICATION_ID + ".files",
            report,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "ModKit DEX diagnostic report",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                intent,
                "Экспортировать диагностику ModKit",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
