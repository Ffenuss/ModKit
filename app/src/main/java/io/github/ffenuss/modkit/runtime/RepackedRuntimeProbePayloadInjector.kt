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

data class RepackedRuntimeProbeInjectedSource(
    val sourceDisplayName: String,
    val inputPath: String,
    val inputSha256: String,
    val outputPath: String,
    val outputSha256: String,
    val payloadInjected: Boolean,
    val payloadDexEntry: String?,
    val payloadSha256: String?,
) : Serializable

data class RepackedRuntimeProbeInjectionResult(
    val artifactSha256: String,
    val packageName: String,
    val baseSourceDisplayName: String,
    val payloadSha256: String,
    val payloadDexVersion: String,
    val payloadDexEntry: String,
    val alreadyPresent: Boolean,
    val sources: List<RepackedRuntimeProbeInjectedSource>,
    val outputRootPath: String,
) : Serializable

/**
 * Injects the executable runtime-probe DEX into the already manifest-rewritten
 * base APK test copy.
 *
 * Existing APK DEX files are never modified or merged. The probe is added as
 * the first free top-level classesN.dex entry. Split APKs are copied byte-for-
 * byte. Idempotence is accepted only when an existing top-level DEX has the
 * exact probe SHA-256; merely finding the provider descriptor is treated as a
 * conflict, not proof that our payload is present.
 */
object RepackedRuntimeProbePayloadInjector {
    private const val BUFFER_BYTES = 128 * 1024
    private const val MAX_DEX_INDEX = 9999
    private val dexName = Regex("""classes(?:([2-9][0-9]*))?\.dex""")

    fun inject(
        manifestRewrite: RepackedRuntimeManifestRewriteResult,
        payload: RuntimeProbePayload,
        outputRoot: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimeProbeInjectionResult {
        val validation = RuntimeProbeDexValidator.validate(payload.bytes)
        require(validation.valid) {
            validation.blockers.firstOrNull()
                ?: "Runtime probe payload is invalid."
        }
        require(
            sha256(payload.bytes).equals(
                payload.sha256,
                ignoreCase = true,
            ),
        ) {
            "Runtime probe payload SHA-256 does not match its descriptor."
        }

        val baseSource = manifestRewrite.sources.singleOrNull {
            it.sourceDisplayName ==
                manifestRewrite.baseSourceDisplayName
        } ?: error("Manifest rewrite result has no unique base APK source.")

        val root = File(
            outputRoot,
            manifestRewrite.artifactSha256 +
                "/repacked-test/probe-injection",
        ).apply { mkdirs() }
        val outputs = mutableListOf<RepackedRuntimeProbeInjectedSource>()
        var selectedEntry: String? = null
        var alreadyPresent = false

        try {
            manifestRewrite.sources.forEachIndexed { index, source ->
                checkCancelled(cancellation)
                val input = File(source.outputPath)
                require(input.isFile && input.canRead()) {
                    "Manifest-rewritten APK is unavailable: " +
                        source.sourceDisplayName
                }
                val inputSha = sha256(input, cancellation)
                require(
                    inputSha.equals(
                        source.outputSha256,
                        ignoreCase = true,
                    ),
                ) {
                    "Manifest-rewritten APK changed before probe injection: " +
                        source.sourceDisplayName
                }

                val safeName = source.sourceDisplayName
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .ifBlank { "base.apk" }
                val output = File(
                    root,
                    index.toString().padStart(3, '0') +
                        "-" + safeName,
                )
                require(output.canonicalFile != input.canonicalFile) {
                    "Probe injection must not overwrite its input APK."
                }

                if (source.sourceDisplayName ==
                    baseSource.sourceDisplayName
                ) {
                    val archive = injectBase(
                        input = input,
                        output = output,
                        payload = payload,
                        expectedPackageName = manifestRewrite.packageName,
                        cancellation = cancellation,
                    )
                    selectedEntry = archive.dexEntry
                    alreadyPresent = archive.alreadyPresent
                    outputs += RepackedRuntimeProbeInjectedSource(
                        sourceDisplayName = source.sourceDisplayName,
                        inputPath = input.absolutePath,
                        inputSha256 = inputSha,
                        outputPath = output.absolutePath,
                        outputSha256 = sha256(output, cancellation),
                        payloadInjected = !archive.alreadyPresent,
                        payloadDexEntry = archive.dexEntry,
                        payloadSha256 = payload.sha256,
                    )
                } else {
                    copyVerified(
                        input = input,
                        output = output,
                        expectedSha256 = inputSha,
                        cancellation = cancellation,
                    )
                    outputs += RepackedRuntimeProbeInjectedSource(
                        sourceDisplayName = source.sourceDisplayName,
                        inputPath = input.absolutePath,
                        inputSha256 = inputSha,
                        outputPath = output.absolutePath,
                        outputSha256 = inputSha,
                        payloadInjected = false,
                        payloadDexEntry = null,
                        payloadSha256 = null,
                    )
                }
            }

            require(outputs.size == manifestRewrite.sources.size) {
                "Probe injection did not produce every APK-set member."
            }

            return RepackedRuntimeProbeInjectionResult(
                artifactSha256 = manifestRewrite.artifactSha256,
                packageName = manifestRewrite.packageName,
                baseSourceDisplayName =
                    manifestRewrite.baseSourceDisplayName,
                payloadSha256 = payload.sha256,
                payloadDexVersion = payload.dexVersion,
                payloadDexEntry = requireNotNull(selectedEntry) {
                    "Base APK was not processed by probe injector."
                },
                alreadyPresent = alreadyPresent,
                sources = outputs,
                outputRootPath = root.absolutePath,
            )
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    private data class BaseInjection(
        val dexEntry: String,
        val alreadyPresent: Boolean,
    )

    private data class DexScan(
        val entryName: String,
        val sha256: String,
        val providerDescriptorPresent: Boolean,
    )

    private fun injectBase(
        input: File,
        output: File,
        payload: RuntimeProbePayload,
        expectedPackageName: String,
        cancellation: CancellationSignal,
    ): BaseInjection {
        verifyProbeManifestDeclaration(
            input = input,
            expectedPackageName = expectedPackageName,
            cancellation = cancellation,
        )

        val scans = mutableListOf<DexScan>()
        val usedIndexes = linkedSetOf<Int>()
        val entryNames = linkedSetOf<String>()

        ZipFile(input).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                checkCancelled(cancellation)
                val entry = entries.nextElement()
                require(entryNames.add(entry.name)) {
                    "APK contains duplicate ZIP entry: " + entry.name
                }
                val dexIndex = dexIndex(entry.name) ?: continue
                require(usedIndexes.add(dexIndex)) {
                    "APK contains conflicting DEX slot: " + entry.name
                }
                require(!entry.isDirectory) {
                    "APK DEX entry is a directory: " + entry.name
                }
                scans += scanDexEntry(
                    zip = zip,
                    entry = entry,
                    cancellation = cancellation,
                )
            }
        }

        val exactPayload = scans.filter {
            it.sha256.equals(payload.sha256, ignoreCase = true)
        }
        require(exactPayload.size <= 1) {
            "APK contains multiple copies of the exact runtime probe DEX."
        }
        val providerConflicts = scans.filter {
            it.providerDescriptorPresent &&
                !it.sha256.equals(payload.sha256, ignoreCase = true)
        }
        require(providerConflicts.isEmpty()) {
            "APK already contains the ModKit runtime probe provider " +
                "descriptor in a different DEX payload."
        }

        val existing = exactPayload.singleOrNull()
        val selectedDex = existing?.entryName ?: firstFreeDex(usedIndexes)

        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.delete()
        output.delete()

        try {
            if (existing != null) {
                copyVerified(
                    input = input,
                    output = output,
                    expectedSha256 = sha256(input, cancellation),
                    cancellation = cancellation,
                )
                return BaseInjection(
                    dexEntry = selectedDex,
                    alreadyPresent = true,
                )
            }

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
                        out.putNextEntry(cloneEntry(entry, null))
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { source ->
                                copyStream(
                                    source,
                                    out,
                                    cancellation,
                                )
                            }
                        }
                        out.closeEntry()
                    }

                    val payloadEntry = ZipEntry(selectedDex).apply {
                        method = ZipEntry.STORED
                        size = payload.bytes.size.toLong()
                        compressedSize = payload.bytes.size.toLong()
                        crc = CRC32().apply {
                            update(payload.bytes)
                        }.value
                    }
                    out.putNextEntry(payloadEntry)
                    out.write(payload.bytes)
                    out.closeEntry()
                }
            }

            check(temp.renameTo(output)) {
                "Could not finalize probe-injected base APK."
            }

            val verifiedPayload = ZipFile(output).use { zip ->
                val entry = requireNotNull(zip.getEntry(selectedDex)) {
                    "Probe DEX entry is missing after archive injection."
                }
                zip.getInputStream(entry).use { source ->
                    val bytes = source.readBytes()
                    require(bytes.size == payload.bytes.size) {
                        "Injected probe DEX size changed."
                    }
                    bytes
                }
            }
            require(
                sha256(verifiedPayload).equals(
                    payload.sha256,
                    ignoreCase = true,
                ),
            ) {
                "Injected probe DEX SHA-256 verification failed."
            }
            require(RuntimeProbeDexValidator.validate(verifiedPayload).valid) {
                "Injected probe DEX failed post-write structural validation."
            }

            return BaseInjection(
                dexEntry = selectedDex,
                alreadyPresent = false,
            )
        } catch (failure: Throwable) {
            temp.delete()
            output.delete()
            throw failure
        }
    }

    private fun verifyProbeManifestDeclaration(
        input: File,
        expectedPackageName: String,
        cancellation: CancellationSignal,
    ) {
        val manifestBytes = ZipFile(input).use { zip ->
            val entry = requireNotNull(zip.getEntry("AndroidManifest.xml")) {
                "Manifest-rewritten base APK has no AndroidManifest.xml."
            }
            require(!entry.isDirectory) {
                "AndroidManifest.xml is a directory."
            }
            zip.getInputStream(entry).use { source ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    checkCancelled(cancellation)
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= 4 * 1024 * 1024) {
                        "AndroidManifest.xml exceeds probe verification limit."
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }

        val info = BinaryAndroidManifestInspector.inspect(manifestBytes)
        require(info.packageName == expectedPackageName) {
            "Manifest package does not match probe-injection target."
        }
        val reinjection =
            BinaryAndroidManifestProbeInjector.inject(manifestBytes)
        require(reinjection.alreadyPresent) {
            "Runtime probe manifest declaration is absent."
        }
        require(
            reinjection.authority ==
                expectedPackageName +
                BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX,
        ) {
            "Runtime probe manifest authority does not match target package."
        }
    }

    private fun scanDexEntry(
        zip: ZipFile,
        entry: ZipEntry,
        cancellation: CancellationSignal,
    ): DexScan {
        val digest = MessageDigest.getInstance("SHA-256")
        val marker = RuntimeProbePayloadSource.PROVIDER_DESCRIPTOR
            .toByteArray(Charsets.UTF_8)
        var matched = 0
        var markerPresent = false

        zip.getInputStream(entry).use { raw ->
            BufferedInputStream(raw, BUFFER_BYTES).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    checkCancelled(cancellation)
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    if (!markerPresent) {
                        for (index in 0 until read) {
                            if (buffer[index] == marker[matched]) {
                                matched++
                                if (matched == marker.size) {
                                    markerPresent = true
                                    break
                                }
                            } else {
                                matched =
                                    if (buffer[index] == marker[0]) 1 else 0
                            }
                        }
                    }
                }
            }
        }

        return DexScan(
            entryName = entry.name,
            sha256 = digest.digest().toHex(),
            providerDescriptorPresent = markerPresent,
        )
    }

    private fun firstFreeDex(
        usedIndexes: Set<Int>,
    ): String {
        for (index in 1..MAX_DEX_INDEX) {
            if (index !in usedIndexes) {
                return if (index == 1) {
                    "classes.dex"
                } else {
                    "classes$index.dex"
                }
            }
        }
        error("No free classesN.dex slot is available for runtime probe.")
    }

    private fun dexIndex(name: String): Int? {
        if (name == "classes.dex") return 1
        val match = dexName.matchEntire(name) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: return null
        require(index in 2..MAX_DEX_INDEX) {
            "APK DEX index exceeds supported range: $name"
        }
        return index
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
                    "Stored APK entry lacks size/CRC: " +
                        source.name
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
                "Could not finalize unchanged APK copy."
            }
            require(
                sha256(output, cancellation).equals(
                    expectedSha256,
                    ignoreCase = true,
                ),
            ) {
                "Unchanged APK copy failed SHA-256 verification."
            }
        } catch (failure: Throwable) {
            temp.delete()
            output.delete()
            throw failure
        }
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
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHex()

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
