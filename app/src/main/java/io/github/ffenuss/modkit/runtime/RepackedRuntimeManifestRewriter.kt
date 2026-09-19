package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.Serializable
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class RepackedRuntimeManifestRewriteSource(
    val sourceDisplayName: String,
    val preparedInputPath: String,
    val preparedInputSha256: String,
    val outputPath: String,
    val outputSha256: String,
    val manifestChanged: Boolean,
    val manifestBeforeSha256: String?,
    val manifestAfterSha256: String?,
) : Serializable

data class RepackedRuntimeManifestRewriteResult(
    val artifactSha256: String,
    val packageName: String,
    val baseSourceDisplayName: String,
    val sources: List<RepackedRuntimeManifestRewriteSource>,
    val providerClassName: String,
    val providerAuthority: String,
    val outputRootPath: String,
) : Serializable

/**
 * Concrete binary-manifest rewrite executor for the repacked-test path.
 *
 * Only the verified base APK gets the test provider declaration. Every output
 * is separate from the prepared read-only source-copy workspace. Split APKs
 * are copied byte-for-byte at this stage so their manifest identities and
 * contents remain untouched until the common sign/align build tail.
 */
object RepackedRuntimeManifestRewriter {
    private const val BUFFER_BYTES = 128 * 1024
    private const val MANIFEST_ENTRY = "AndroidManifest.xml"

    fun rewrite(
        prepared: RepackedRuntimePreparedWorkspace,
        manifestInventory: RepackedRuntimeManifestInventory,
        outputRoot: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimeManifestRewriteResult {
        require(manifestInventory.verified) {
            manifestInventory.blockers.firstOrNull()
                ?: "Repacked manifest inventory is not verified."
        }
        require(
            manifestInventory.artifactSha256.equals(
                prepared.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "Manifest inventory artifact SHA does not match prepared workspace."
        }

        val packageName = requireNotNull(manifestInventory.packageName)
        val baseDisplayName = requireNotNull(
            manifestInventory.baseSourceDisplayName,
        )
        val preparedNames = prepared.sources
            .map { it.sourceDisplayName }
            .sorted()
        val inventoryNames = manifestInventory.records
            .map { it.sourceDisplayName }
            .sorted()
        require(preparedNames == inventoryNames) {
            "Prepared source relationships changed after manifest inspection."
        }

        val root = File(
            outputRoot,
            prepared.artifactSha256 + "/repacked-test/manifest-rewrite",
        ).apply { mkdirs() }
        val outputs = mutableListOf<RepackedRuntimeManifestRewriteSource>()
        var providerAuthority: String? = null

        try {
            prepared.sources.forEachIndexed { index, source ->
                checkCancelled(cancellation)
                val input = File(source.copiedFilePath)
                require(input.isFile && input.canRead()) {
                    "Prepared APK is unavailable: " + source.sourceDisplayName
                }
                val actualInputSha = sha256(input, cancellation)
                require(
                    actualInputSha.equals(
                        source.copiedSha256,
                        ignoreCase = true,
                    ),
                ) {
                    "Prepared APK changed after source-copy verification: " +
                        source.sourceDisplayName
                }

                val safeName = source.sourceDisplayName
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "base.apk" }
                val output = File(
                    root,
                    index.toString().padStart(3, '0') + "-" + safeName,
                )
                require(output.canonicalFile != input.canonicalFile) {
                    "Manifest rewrite must not overwrite prepared APK."
                }

                if (source.sourceDisplayName == baseDisplayName) {
                    val injection = rewriteBaseArchive(
                        input = input,
                        output = output,
                        expectedPackageName = packageName,
                        cancellation = cancellation,
                    )
                    providerAuthority = injection.authority
                    outputs += RepackedRuntimeManifestRewriteSource(
                        sourceDisplayName = source.sourceDisplayName,
                        preparedInputPath = input.absolutePath,
                        preparedInputSha256 = actualInputSha,
                        outputPath = output.absolutePath,
                        outputSha256 = sha256(output, cancellation),
                        manifestChanged = !injection.alreadyPresent,
                        manifestBeforeSha256 = injection.beforeSha256,
                        manifestAfterSha256 = injection.afterSha256,
                    )
                } else {
                    copyVerified(
                        input = input,
                        output = output,
                        expectedSha256 = actualInputSha,
                        cancellation = cancellation,
                    )
                    outputs += RepackedRuntimeManifestRewriteSource(
                        sourceDisplayName = source.sourceDisplayName,
                        preparedInputPath = input.absolutePath,
                        preparedInputSha256 = actualInputSha,
                        outputPath = output.absolutePath,
                        outputSha256 = actualInputSha,
                        manifestChanged = false,
                        manifestBeforeSha256 = null,
                        manifestAfterSha256 = null,
                    )
                }
            }

            require(outputs.size == prepared.sources.size) {
                "Manifest rewrite did not produce every APK-set member."
            }
            return RepackedRuntimeManifestRewriteResult(
                artifactSha256 = prepared.artifactSha256,
                packageName = packageName,
                baseSourceDisplayName = baseDisplayName,
                sources = outputs,
                providerClassName =
                    BinaryAndroidManifestProbeInjector.PROVIDER_CLASS,
                providerAuthority = requireNotNull(providerAuthority) {
                    "Base APK was not processed by manifest rewrite executor."
                },
                outputRootPath = root.absolutePath,
            )
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    private fun rewriteBaseArchive(
        input: File,
        output: File,
        expectedPackageName: String,
        cancellation: CancellationSignal,
    ): BinaryManifestProbeInjectionResult {
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.delete()
        output.delete()

        try {
            var manifestSeen = false
            var injection: BinaryManifestProbeInjectionResult? = null

            ZipFile(input).use { zip ->
                ZipOutputStream(
                    BufferedOutputStream(
                        FileOutputStream(temp),
                        BUFFER_BYTES,
                    ),
                ).use { out ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        checkCancelled(cancellation)
                        val entry = entries.nextElement()
                        if (entry.name == MANIFEST_ENTRY) {
                            require(!manifestSeen) {
                                "Base APK contains duplicate AndroidManifest.xml entries."
                            }
                            manifestSeen = true
                            val manifestBytes = zip.getInputStream(entry).use {
                                readBoundedManifest(it, cancellation)
                            }
                            val result =
                                BinaryAndroidManifestProbeInjector.inject(
                                    manifestBytes,
                                )
                            require(result.packageName == expectedPackageName) {
                                "Base manifest package changed after inventory verification."
                            }
                            injection = result
                            out.putNextEntry(
                                cloneEntry(
                                    source = entry,
                                    replacement = result.bytes,
                                ),
                            )
                            out.write(result.bytes)
                            out.closeEntry()
                        } else {
                            out.putNextEntry(cloneEntry(entry, null))
                            if (!entry.isDirectory) {
                                zip.getInputStream(entry).use { source ->
                                    copyStream(source, out, cancellation)
                                }
                            }
                            out.closeEntry()
                        }
                    }
                }
            }

            require(manifestSeen) {
                "Base APK has no AndroidManifest.xml."
            }
            val result = requireNotNull(injection)
            check(temp.renameTo(output)) {
                "Could not finalize manifest-rewritten test APK."
            }

            val verified = BinaryAndroidManifestInspector.inspectApk(
                apk = output,
                cancellation = cancellation,
            )
            require(verified.packageName == expectedPackageName) {
                "Rewritten base APK package verification failed."
            }

            return result
        } catch (failure: Throwable) {
            temp.delete()
            output.delete()
            throw failure
        }
    }

    private fun copyVerified(
        input: File,
        output: File,
        expectedSha256: String,
        cancellation: CancellationSignal,
    ) {
        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.delete()
        output.delete()

        try {
            BufferedInputStream(
                FileInputStream(input),
                BUFFER_BYTES,
            ).use { source ->
                BufferedOutputStream(
                    FileOutputStream(temp),
                    BUFFER_BYTES,
                ).use { target ->
                    copyStream(source, target, cancellation)
                }
            }
            check(temp.renameTo(output)) {
                "Could not finalize unchanged split APK copy."
            }
            val copiedSha = sha256(output, cancellation)
            require(copiedSha.equals(expectedSha256, ignoreCase = true)) {
                "Unchanged split APK copy failed SHA verification."
            }
        } catch (failure: Throwable) {
            temp.delete()
            output.delete()
            throw failure
        }
    }

    private fun cloneEntry(
        source: ZipEntry,
        replacement: ByteArray?,
    ): ZipEntry {
        val target = ZipEntry(source.name)
        target.comment = source.comment
        target.extra = source.extra
        if (source.time >= 0L) target.time = source.time
        target.method = source.method

        if (source.method == ZipEntry.STORED) {
            if (replacement == null) {
                require(source.size >= 0L && source.crc >= 0L) {
                    "Stored APK entry lacks size/CRC: " + source.name
                }
                target.size = source.size
                target.compressedSize = source.size
                target.crc = source.crc
            } else {
                val crc = CRC32().apply { update(replacement) }
                target.size = replacement.size.toLong()
                target.compressedSize = replacement.size.toLong()
                target.crc = crc.value
            }
        }
        return target
    }

    private fun readBoundedManifest(
        input: java.io.InputStream,
        cancellation: CancellationSignal,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            checkCancelled(cancellation)
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= 4 * 1024 * 1024) {
                "AndroidManifest.xml exceeds rewrite limit."
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        cancellation: CancellationSignal,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            checkCancelled(cancellation)
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) output.write(buffer, 0, read)
        }
    }

    private fun sha256(
        file: File,
        cancellation: CancellationSignal,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(
            FileInputStream(file),
            BUFFER_BYTES,
        ).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
