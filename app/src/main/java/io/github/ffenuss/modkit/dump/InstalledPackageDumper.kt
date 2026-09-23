package io.github.ffenuss.modkit.dump

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.ffenuss.modkit.data.InstalledAppTarget
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledPackageDumpResult(
    val fileName: String,
    val location: String,
    val apkCount: Int,
    val totalBytes: Long,
)

object InstalledPackageDumper {
    suspend fun dump(
        context: Context,
        target: InstalledAppTarget,
    ): InstalledPackageDumpResult =
        withContext(Dispatchers.IO) {
            require(target.apkFiles.isNotEmpty()) {
                "У выбранного пакета не найдены APK-файлы."
            }

            val tempDir =
                File(context.cacheDir, "package-dumps")
                    .apply { mkdirs() }
            val fileName = buildDumpFileName(target)
            val temp = File(tempDir, fileName)
            if (temp.exists()) temp.delete()

            val copied =
                ArrayList<CopiedApk>(target.apkFiles.size)
            try {
                ZipOutputStream(
                    FileOutputStream(temp).buffered(),
                ).use { zip ->
                    target.apkFiles.forEachIndexed {
                            index,
                            source,
                        ->
                        check(source.isFile && source.canRead()) {
                            "APK недоступен для чтения: " +
                                source.name
                        }
                        val archiveName =
                            if (index == 0) {
                                "apk/base.apk"
                            } else {
                                "apk/split-" +
                                    index +
                                    "-" +
                                    safeName(source.name)
                            }
                        val digest =
                            MessageDigest.getInstance("SHA-256")
                        zip.putNextEntry(
                            ZipEntry(archiveName).apply {
                                time = source.lastModified()
                            },
                        )
                        DigestInputStream(
                            source.inputStream().buffered(),
                            digest,
                        ).use { input ->
                            input.copyTo(
                                zip,
                                DEFAULT_BUFFER_SIZE,
                            )
                        }
                        zip.closeEntry()
                        copied +=
                            CopiedApk(
                                archiveName = archiveName,
                                sourceName = source.name,
                                bytes = source.length(),
                                sha256 =
                                    digest.digest()
                                        .joinToString("") {
                                            "%02x".format(it)
                                        },
                            )
                    }

                    writePackageInfo(
                        zip = zip,
                        target = target,
                        copied = copied,
                    )
                    writeInventory(
                        zip = zip,
                        target = target,
                    )
                }

                val location =
                    publishToDevice(
                        context = context,
                        source = temp,
                        fileName = fileName,
                    )

                InstalledPackageDumpResult(
                    fileName = fileName,
                    location = location,
                    apkCount = copied.size,
                    totalBytes = copied.sumOf { it.bytes },
                )
            } finally {
                temp.delete()
            }
        }

    private fun writePackageInfo(
        zip: ZipOutputStream,
        target: InstalledAppTarget,
        copied: List<CopiedApk>,
    ) {
        val body = buildString {
            appendLine("ModKit installed package dump")
            appendLine("label=" + target.label)
            appendLine("package=" + target.packageName)
            appendLine(
                "kind=" +
                    if (target.isGame) {
                        "game"
                    } else {
                        "application"
                    },
            )
            appendLine(
                "versionName=" +
                    (target.versionName ?: ""),
            )
            appendLine(
                "versionCode=" + target.versionCode,
            )
            appendLine(
                "system=" + target.isSystemApp,
            )
            appendLine(
                "launcher=" +
                    target.hasLauncherActivity,
            )
            appendLine("apkCount=" + copied.size)
            appendLine("createdUtc=" + utcNow())
            appendLine()
            appendLine(
                "archive_path\tsource_name\tbytes\tsha256",
            )
            copied.forEach { apk ->
                append(apk.archiveName)
                append('\t')
                append(apk.sourceName)
                append('\t')
                append(apk.bytes)
                append('\t')
                appendLine(apk.sha256)
            }
        }
        zip.putNextEntry(
            ZipEntry("package-info.txt"),
        )
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun writeInventory(
        zip: ZipOutputStream,
        target: InstalledAppTarget,
    ) {
        zip.putNextEntry(
            ZipEntry("inventory/apk-entries.tsv"),
        )
        zip.write(
            (
                "apk\tentry\tbytes\t" +
                    "compressed_bytes\tcrc\n"
            ).toByteArray(Charsets.UTF_8),
        )
        target.apkFiles.forEachIndexed { index, apk ->
            runCatching {
                ZipFile(apk).use { sourceZip ->
                    val entries = sourceZip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val apkName =
                            if (index == 0) {
                                "base.apk"
                            } else {
                                "split-" +
                                    index +
                                    "-" +
                                    safeName(apk.name)
                            }
                        val line =
                            buildString {
                                append(apkName)
                                append('\t')
                                append(
                                    entry.name
                                        .replace('\t', ' ')
                                        .replace('\n', ' '),
                                )
                                append('\t')
                                append(entry.size)
                                append('\t')
                                append(
                                    entry.compressedSize,
                                )
                                append('\t')
                                append(entry.crc)
                                append('\n')
                            }
                        zip.write(
                            line.toByteArray(
                                Charsets.UTF_8,
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                val line =
                    "inventory-error\t" +
                        safeName(apk.name) +
                        "\t" +
                        (
                            error.message
                                ?: error.javaClass.simpleName
                        ) +
                        "\n"
                zip.write(
                    line.toByteArray(Charsets.UTF_8),
                )
            }
        }
        zip.closeEntry()
    }

    private fun publishToDevice(
        context: Context,
        source: File,
        fileName: String,
    ): String {
        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
        ) {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(
                        MediaStore.Downloads.DISPLAY_NAME,
                        fileName,
                    )
                    put(
                        MediaStore.Downloads.MIME_TYPE,
                        "application/zip",
                    )
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS +
                            "/ModKit",
                    )
                    put(
                        MediaStore.Downloads.IS_PENDING,
                        1,
                    )
                }
            val uri =
                checkNotNull(
                    resolver.insert(
                        MediaStore.Downloads
                            .EXTERNAL_CONTENT_URI,
                        values,
                    ),
                ) {
                    "Android не создал файл дампа " +
                        "в Downloads."
                }
            try {
                val output =
                    checkNotNull(
                        resolver.openOutputStream(
                            uri,
                            "w",
                        ),
                    ) {
                        "Не удалось открыть файл " +
                            "дампа для записи."
                    }
                output.buffered().use { target ->
                    source.inputStream()
                        .buffered()
                        .use { input ->
                            input.copyTo(target)
                        }
                }
                val ready =
                    ContentValues().apply {
                        put(
                            MediaStore.Downloads
                                .IS_PENDING,
                            0,
                        )
                    }
                resolver.update(
                    uri,
                    ready,
                    null,
                    null,
                )
                return "Downloads/ModKit/" + fileName
            } catch (failure: Throwable) {
                resolver.delete(uri, null, null)
                throw failure
            }
        }

        val root =
            context.getExternalFilesDir(
                Environment.DIRECTORY_DOWNLOADS,
            ) ?: context.filesDir
        val dir =
            File(root, "ModKit")
                .apply { mkdirs() }
        val output = uniqueFile(dir, fileName)
        source.copyTo(
            output,
            overwrite = false,
        )
        return output.absolutePath
    }

    private fun uniqueFile(
        dir: File,
        desiredName: String,
    ): File {
        val direct = File(dir, desiredName)
        if (!direct.exists()) return direct
        val stem = desiredName.removeSuffix(".zip")
        var suffix = 2
        while (true) {
            val candidate =
                File(
                    dir,
                    stem + "-" + suffix + ".zip",
                )
            if (!candidate.exists()) {
                return candidate
            }
            suffix += 1
        }
    }

    private fun buildDumpFileName(
        target: InstalledAppTarget,
    ): String {
        val timestamp =
            SimpleDateFormat(
                "yyyyMMdd-HHmmss",
                Locale.US,
            ).apply {
                timeZone =
                    TimeZone.getTimeZone("UTC")
            }.format(Date())
        val label =
            safeName(target.label)
                .ifBlank { "package" }
                .take(40)
        val packageName =
            safeName(target.packageName)
                .take(80)
        return "ModKit-" +
            label +
            "-" +
            packageName +
            "-" +
            timestamp +
            ".zip"
    }

    private fun utcNow(): String =
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            Locale.US,
        ).apply {
            timeZone =
                TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun safeName(
        value: String,
    ): String =
        value.trim()
            .replace(
                Regex("[^A-Za-z0-9._-]+"),
                "_",
            )
            .trim('_', '.', '-')

    private data class CopiedApk(
        val archiveName: String,
        val sourceName: String,
        val bytes: Long,
        val sha256: String,
    )
}
