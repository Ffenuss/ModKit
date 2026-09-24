package io.github.ffenuss.modkit.build

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.content.Intent
import android.net.Uri
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import io.github.ffenuss.modkit.BuildConfig
import java.util.ArrayList

data class SavedBuildArtifact(
    val fileName: String,
    val uri: Uri,
    val bytesWritten: Long,
)

data class SavedApkFiles(
    val destinationDirectory: String,
    val files: List<SavedBuildArtifact>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytesWritten }
}

/**
 * One APK means a single installable APK. A split application is stored as
 * several real APK files rather than disguising a ZIP as a universal APK.
 */
object BuildArtifactExporter {
    private const val BUFFER_BYTES = 128 * 1024

    fun proposedFileName(result: VerifiedBuildResult): String {
        require(result.files.isNotEmpty()) { "No built APK to save." }
        val packageName = result.installability.packageName
            .orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "application" }
        val extension = if (result.files.size == 1) ".apk" else "-apk-set.zip"
        return "ModKit-" + packageName + "-" +
            result.builtAtEpochMs + extension
    }

    /**
     * The default user-facing export: .apk files in Downloads, never an
     * unexpected ZIP. Multi-split sets go into a dedicated directory and
     * remain installable together from ModKit's PackageInstaller screen.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveApkFilesToDownloads(
        context: Context,
        result: VerifiedBuildResult,
    ): SavedApkFiles {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Android 8/9: сохраните комплект через системный выбор папки."
        }
        validateBuild(result)
        if (result.files.size == 1) {
            return SavedApkFiles(
                destinationDirectory = "Загрузки/ModKit",
                files = listOf(saveToDownloads(context, result)),
            )
        }

        val plan = ApkSetExportPlanner.plan(
            packageName = result.installability.packageName,
            builtAtEpochMs = result.builtAtEpochMs,
            signedFileNames = result.files.map { it.file.name },
        )
        val resolver = context.contentResolver
        val created = mutableListOf<Uri>()
        val saved = mutableListOf<SavedBuildArtifact>()
        try {
            result.files.zip(plan.fileNames).forEach { (built, filename) ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        "application/vnd.android.package-archive",
                    )
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" +
                            plan.relativeDirectory + "/",
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values,
                ) ?: error("Android не создал файл: $filename")
                created += uri
                val bytes = resolver.openOutputStream(uri, "w")?.use {
                    output ->
                    val counting = CountingOutputStream(output)
                    copyVerified(built, counting)
                    counting.flush()
                    counting.total
                } ?: error("Не удалось записать APK: $filename")
                saved += SavedBuildArtifact(filename, uri, bytes)
            }
            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            created.forEach { uri ->
                require(resolver.update(uri, ready, null, null) == 1) {
                    "Android не опубликовал один из split APK."
                }
            }
            return SavedApkFiles(plan.displayDirectory, saved)
        } catch (failure: Throwable) {
            // No incomplete APK should appear as a finished file. Retain
            // the verified internal build so the export can be retried.
            created.forEach { uri ->
                runCatching { resolver.delete(uri, null, null) }
            }
            throw failure
        }
    }

    /**
     * Store the signed output directly on the device, without a share intent.
     * API 29+ uses scoped MediaStore Downloads/ModKit. Older devices should
     * use ACTION_CREATE_DOCUMENT and writeToUri().
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(
        context: Context,
        result: VerifiedBuildResult,
    ): SavedBuildArtifact {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Android 8/9: choose a location with the system file picker."
        }
        validateBuild(result)
        val name = proposedFileName(result)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                if (result.files.size == 1) {
                    "application/vnd.android.package-archive"
                } else {
                    "application/zip"
                },
            )
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/ModKit/",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: error("Downloads rejected the APK export.")
        return try {
            val bytes = resolver.openOutputStream(uri, "w")?.use { output ->
                writeArtifact(result, output)
            } ?: error("Cannot open Downloads for writing.")
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            require(resolver.update(uri, published, null, null) == 1) {
                "Could not publish the saved APK."
            }
            SavedBuildArtifact(name, uri, bytes)
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    /**
     * SAF destination for Android 8/9. Also useful when the user wants to
     * choose another folder. Never persists any signing key or password.
     */
    fun writeToUri(
        context: Context,
        result: VerifiedBuildResult,
        destination: Uri,
    ): SavedBuildArtifact {
        validateBuild(result)
        val bytes = context.contentResolver
            .openOutputStream(destination, "w")
            ?.use { writeArtifact(result, it) }
            ?: error("The chosen destination is not writable.")
        return SavedBuildArtifact(
            fileName = proposedFileName(result),
            uri = destination,
            bytesWritten = bytes,
        )
    }

    private fun validateBuild(result: VerifiedBuildResult) {
        require(
            result.files.isNotEmpty() &&
                result.installability.verified &&
                result.mutationDiffVerification.verified &&
                result.files.all {
                    it.file.isFile &&
                        it.signature.verified &&
                        it.alignment.verified
                },
        ) {
            "Only verified, signed build outputs can be saved."
        }
    }

    private fun writeArtifact(
        result: VerifiedBuildResult,
        output: OutputStream,
    ): Long {
        val counting = CountingOutputStream(output)
        if (result.files.size == 1) {
            copyVerified(
                result.files.single(),
                counting,
            )
        } else {
            ZipOutputStream(
                BufferedOutputStream(counting, BUFFER_BYTES),
            ).use { zip ->
                // APK files are already compressed and signed. ZIP level 0
                // avoids wasting phone CPU recompressing game assets.
                zip.setLevel(0)
                result.files.forEach { built ->
                    val entryName = built.file.name
                    require(entryName == entryName.substringAfterLast('/')) {
                        "Unexpected archive member name."
                    }
                    zip.putNextEntry(ZipEntry(entryName))
                    copyVerified(built, zip)
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("SHA256SUMS.txt"))
                zip.write(
                    result.files.joinToString("\n", postfix = "\n") {
                        it.sha256 + "  " + it.file.name
                    }.toByteArray(Charsets.UTF_8),
                )
                zip.closeEntry()
                if (result.reportFile.isFile) {
                    zip.putNextEntry(ZipEntry("ModKit-build-report.txt"))
                    FileInputStream(result.reportFile).use { input ->
                        input.copyTo(zip, BUFFER_BYTES)
                    }
                    zip.closeEntry()
                }
            }
        }
        return counting.total
    }

    private fun copyVerified(
        built: BuiltApkFile,
        destination: OutputStream,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(built.file).buffered(BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                destination.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        require(actual.equals(built.sha256, ignoreCase = true)) {
            "APK file changed after verification; export cancelled."
        }
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        var total: Long = 0
            private set

        override fun write(value: Int) {
            delegate.write(value)
            total++
        }

        override fun write(data: ByteArray, offset: Int, length: Int) {
            delegate.write(data, offset, length)
            total += length
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
    fun shareReport(
        context: Context,
        result: VerifiedBuildResult,
    ) {
        require(result.reportFile.isFile) { "Build report is unavailable for export." }
        val authority = BuildConfig.APPLICATION_ID + ".files"
        val uri = FileProvider.getUriForFile(
            context,
            authority,
            result.reportFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ModKit verified build report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Экспорт отчёта сборки")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun share(
        context: Context,
        result: VerifiedBuildResult,
    ) {
        require(result.files.isNotEmpty()) { "No built APK files are available for export." }
        val authority = BuildConfig.APPLICATION_ID + ".files"
        val uris = result.files.map { built ->
            FileProvider.getUriForFile(
                context,
                authority,
                built.file,
            )
        }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uris.single())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/vnd.android.package-archive"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList<Uri>(uris),
                )
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "ModKit verified APK output",
            )
        }

        context.startActivity(
            Intent.createChooser(intent, "Экспорт готового APK")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
