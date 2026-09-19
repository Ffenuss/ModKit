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

data class RepackedRuntimeNativeProbeInjectedSource(
    val sourceDisplayName: String,
    val inputPath: String,
    val inputSha256: String,
    val outputPath: String,
    val outputSha256: String,
    val nativePayloadAbis: Set<String>,
) : Serializable

data class RepackedRuntimeNativeProbeInjectionResult(
    val artifactSha256: String,
    val packageName: String,
    val baseSourceDisplayName: String,
    val selectedAbis: Set<String>,
    val payloadSha256ByAbi: Map<String, String>,
    val sources: List<RepackedRuntimeNativeProbeInjectedSource>,
    val outputRootPath: String,
) : Serializable

/**
 * Adds the self-process native lookup helper to the base repacked-test APK.
 *
 * The helper never replaces target native libraries. Existing target ABIs are
 * detected across the whole APK-set, while only the base test copy receives
 * lib/<abi>/libmodkit_runtime_probe.so. Pure-Java targets receive all supported
 * helper ABIs so Android can select the device ABI at install/runtime.
 */
object RepackedRuntimeNativeProbeInjector {
    private const val BUFFER_BYTES = 128 * 1024
    private val nativeEntry = Regex(
        """lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/[^/]+\.so""",
    )

    fun inject(
        dexInjection: RepackedRuntimeProbeInjectionResult,
        payloads: Map<String, RuntimeProbeNativePayload>,
        outputRoot: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimeNativeProbeInjectionResult {
        require(payloads.isNotEmpty()) {
            "No runtime probe native payloads are available."
        }
        payloads.forEach { (abi, payload) ->
            require(abi == payload.abi) {
                "Runtime probe native payload ABI key mismatch."
            }
            val validation =
                RuntimeProbeNativePayloadValidator.validate(
                    bytes = payload.bytes,
                    abi = abi,
                )
            require(validation.valid) {
                validation.blockers.firstOrNull()
                    ?: "Runtime probe native payload is invalid."
            }
            require(
                sha256(payload.bytes).equals(
                    payload.sha256,
                    ignoreCase = true,
                ),
            ) {
                "Runtime probe native payload SHA mismatch for $abi."
            }
        }

        val base = dexInjection.sources.singleOrNull {
            it.sourceDisplayName ==
                dexInjection.baseSourceDisplayName
        } ?: error("Probe DEX injection result has no unique base APK.")

        val targetAbis = detectTargetAbis(
            sources = dexInjection.sources,
            cancellation = cancellation,
        )
        val selectedAbis = if (targetAbis.isEmpty()) {
            RuntimeProbeNativePayloadSource.supportedAbis.toSet()
        } else {
            targetAbis
        }
        require(selectedAbis.all { it in payloads }) {
            "Runtime probe native payloads do not cover target ABI set: " +
                selectedAbis.sorted().joinToString()
        }

        val root = File(
            outputRoot,
            dexInjection.artifactSha256 +
                "/repacked-test/native-probe-injection",
        ).apply { mkdirs() }
        val outputs =
            mutableListOf<RepackedRuntimeNativeProbeInjectedSource>()

        try {
            dexInjection.sources.forEachIndexed { index, source ->
                checkCancelled(cancellation)
                val input = File(source.outputPath)
                require(input.isFile && input.canRead()) {
                    "Probe-injected APK is unavailable: " +
                        source.sourceDisplayName
                }
                val inputSha = sha256(input, cancellation)
                require(
                    inputSha.equals(
                        source.outputSha256,
                        ignoreCase = true,
                    ),
                ) {
                    "Probe-injected APK changed before native helper injection: " +
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
                    "Native helper injection must not overwrite its input APK."
                }

                if (source.sourceDisplayName == base.sourceDisplayName) {
                    val injectedAbis = injectBase(
                        input = input,
                        output = output,
                        selectedAbis = selectedAbis,
                        payloads = payloads,
                        cancellation = cancellation,
                    )
                    outputs += RepackedRuntimeNativeProbeInjectedSource(
                        sourceDisplayName = source.sourceDisplayName,
                        inputPath = input.absolutePath,
                        inputSha256 = inputSha,
                        outputPath = output.absolutePath,
                        outputSha256 = sha256(output, cancellation),
                        nativePayloadAbis = injectedAbis,
                    )
                } else {
                    copyVerified(
                        input = input,
                        output = output,
                        expectedSha256 = inputSha,
                        cancellation = cancellation,
                    )
                    outputs += RepackedRuntimeNativeProbeInjectedSource(
                        sourceDisplayName = source.sourceDisplayName,
                        inputPath = input.absolutePath,
                        inputSha256 = inputSha,
                        outputPath = output.absolutePath,
                        outputSha256 = inputSha,
                        nativePayloadAbis = emptySet(),
                    )
                }
            }

            require(outputs.size == dexInjection.sources.size) {
                "Native helper injection did not produce every APK-set member."
            }
            val baseOutput = outputs.single {
                it.sourceDisplayName ==
                    dexInjection.baseSourceDisplayName
            }
            require(baseOutput.nativePayloadAbis == selectedAbis) {
                "Base APK does not contain the full selected native helper ABI set."
            }

            return RepackedRuntimeNativeProbeInjectionResult(
                artifactSha256 = dexInjection.artifactSha256,
                packageName = dexInjection.packageName,
                baseSourceDisplayName =
                    dexInjection.baseSourceDisplayName,
                selectedAbis = selectedAbis,
                payloadSha256ByAbi = selectedAbis.associateWith {
                    requireNotNull(payloads[it]).sha256
                },
                sources = outputs,
                outputRootPath = root.absolutePath,
            )
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    private fun detectTargetAbis(
        sources: List<RepackedRuntimeProbeInjectedSource>,
        cancellation: CancellationSignal,
    ): Set<String> {
        val abis = linkedSetOf<String>()
        sources.forEach { source ->
            ZipFile(File(source.outputPath)).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    checkCancelled(cancellation)
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val match =
                        nativeEntry.matchEntire(entry.name)
                            ?: continue
                    if (
                        entry.name.endsWith(
                            "/" +
                                RuntimeProbeNativePayloadSource.LIBRARY_NAME,
                        )
                    ) {
                        continue
                    }
                    abis += match.groupValues[1]
                }
            }
        }
        return abis
    }

    private fun injectBase(
        input: File,
        output: File,
        selectedAbis: Set<String>,
        payloads: Map<String, RuntimeProbeNativePayload>,
        cancellation: CancellationSignal,
    ): Set<String> {
        val entryNames = linkedSetOf<String>()
        val existingExact = linkedSetOf<String>()

        ZipFile(input).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                checkCancelled(cancellation)
                val entry = entries.nextElement()
                require(entryNames.add(entry.name)) {
                    "APK contains duplicate ZIP entry: " + entry.name
                }
                val abi = selectedAbis.firstOrNull {
                    entry.name ==
                        "lib/$it/" +
                        RuntimeProbeNativePayloadSource.LIBRARY_NAME
                } ?: continue
                require(!entry.isDirectory) {
                    "Runtime probe native helper path is a directory."
                }
                val existingSha = zip.getInputStream(entry).use {
                    sha256(it.readBytes())
                }
                val expected = requireNotNull(payloads[abi])
                require(
                    existingSha.equals(
                        expected.sha256,
                        ignoreCase = true,
                    ),
                ) {
                    "APK already contains a different runtime probe native helper for $abi."
                }
                existingExact += abi
            }
        }

        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, output.name + ".tmp")
        temp.delete()
        output.delete()

        try {
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
                        out.putNextEntry(cloneEntry(entry))
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

                    selectedAbis
                        .filterNot { it in existingExact }
                        .sorted()
                        .forEach { abi ->
                            val payload = requireNotNull(payloads[abi])
                            val path =
                                "lib/$abi/" +
                                    RuntimeProbeNativePayloadSource.LIBRARY_NAME
                            require(path !in entryNames) {
                                "Runtime probe native helper ZIP path collision: $path"
                            }
                            val crc = CRC32().apply {
                                update(payload.bytes)
                            }
                            val entry = ZipEntry(path).apply {
                                method = ZipEntry.STORED
                                size = payload.bytes.size.toLong()
                                compressedSize = payload.bytes.size.toLong()
                                this.crc = crc.value
                            }
                            out.putNextEntry(entry)
                            out.write(payload.bytes)
                            out.closeEntry()
                        }
                }
            }

            check(temp.renameTo(output)) {
                "Could not finalize native-helper-injected base APK."
            }

            val verified = linkedSetOf<String>()
            ZipFile(output).use { zip ->
                selectedAbis.forEach { abi ->
                    val path =
                        "lib/$abi/" +
                            RuntimeProbeNativePayloadSource.LIBRARY_NAME
                    val entry = requireNotNull(zip.getEntry(path)) {
                        "Runtime probe native helper is missing after injection: $abi"
                    }
                    val bytes = zip.getInputStream(entry).use {
                        it.readBytes()
                    }
                    val payload = requireNotNull(payloads[abi])
                    require(
                        sha256(bytes).equals(
                            payload.sha256,
                            ignoreCase = true,
                        ),
                    ) {
                        "Runtime probe native helper SHA verification failed for $abi."
                    }
                    verified += abi
                }
            }
            return verified
        } catch (failure: Throwable) {
            temp.delete()
            output.delete()
            throw failure
        }
    }

    private fun cloneEntry(source: ZipEntry): ZipEntry {
        val target = ZipEntry(source.name)
        target.comment = source.comment
        target.extra = source.extra
        if (source.time >= 0L) target.time = source.time
        target.method = source.method
        if (source.method == ZipEntry.STORED) {
            require(source.size >= 0L && source.crc >= 0L) {
                "Stored APK entry lacks size/CRC: " + source.name
            }
            target.size = source.size
            target.compressedSize = source.size
            target.crc = source.crc
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
                "Could not finalize unchanged split APK copy."
            }
            require(
                sha256(output, cancellation).equals(
                    expectedSha256,
                    ignoreCase = true,
                ),
            ) {
                "Unchanged split APK copy failed SHA verification."
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
        joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}
