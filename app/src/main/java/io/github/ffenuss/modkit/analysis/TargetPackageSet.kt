package io.github.ffenuss.modkit.analysis

import android.content.Context
import android.net.Uri
import io.github.ffenuss.modkit.data.InstalledAppRepository
import java.io.File

class OpenedTarget(val files: List<File>, val knownSha256: Map<String, String> = emptyMap(),
                   private val cleanup: () -> Unit = {}) : AutoCloseable {
    override fun close() = cleanup()
}

/** Same source expansion for initial analysis, recipe discovery and SHA revalidation. */
object TargetPackageSet {
    fun open(context: Context, target: AnalysisTargetDescriptor, cancellation: CancellationSignal,
             progress: ProgressSink): OpenedTarget = when (target) {
        is AnalysisTargetDescriptor.InstalledPackage -> OpenedTarget(
            InstalledAppRepository(context).find(target.packageName)?.apkFiles
                ?: error("Установленное приложение больше недоступно: ${target.packageName}"))
        is AnalysisTargetDescriptor.FileUri -> {
            val materialized = TargetMaterializer.fromUri(context, Uri.parse(target.uri), cancellation, progress)
            try {
                val loaded = ArtifactPackageLoader.open(materialized.file, File(context.cacheDir, "apk-set"), cancellation, progress)
                OpenedTarget(loaded.files, if (loaded.files.singleOrNull() == materialized.file)
                    mapOf(materialized.file.absolutePath to materialized.sha256) else emptyMap()) {
                    loaded.close(); materialized.file.parentFile?.deleteRecursively()
                }
            } catch (error: Throwable) { materialized.file.parentFile?.deleteRecursively(); throw error }
        }
    }
}
