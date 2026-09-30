package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.patch.DexRuntimeSelection
import io.github.ffenuss.modkit.patch.DexRuntimeSwitchRewriter
import io.github.ffenuss.modkit.patch.DexInstallerCompatibilityRewriter
import io.github.ffenuss.modkit.patch.InstallSourceCheckDetector
import io.github.ffenuss.modkit.patch.InstallCompatibilitySurfaceDetector
import io.github.ffenuss.modkit.patch.InstallCompatibilitySurfaceKind
import io.github.ffenuss.modkit.patch.EngineResourceChange
import io.github.ffenuss.modkit.patch.EngineResourceMods
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class InstallerCompatibilityPolicy {
    /** Simple/default path: a proven local source gate must have observed source provenance. */
    STRICT,
    /** Expert/test path: report a known unresolved gate but do not fabricate installer provenance. */
    DIAGNOSTIC_ONLY,
}

data class RuntimeDexApkSource(
    val sourceDisplayName: String,
    val inputPath: String,
    val inputSha256: String,
    val outputPath: String,
    val outputSha256: String,
    val rewrittenDexSha256: Map<String, String>,
    val rewrittenResourceSha256: Map<String, String> = emptyMap(),
    val installerRedirectedCalls: Int = 0,
    val installerConfirmedChecks: Int = 0,
    val installerQueryOnlyChecks: Int = 0,
    val signingApiReferences: Int = 0,
    val playAttestationReferences: Int = 0,
    val reflectionDynamicReferences: Int = 0,
    val nativeMethodSurfaces: Int = 0,
)

/** Separate provenance stage; a changed DEX must not masquerade as an unchanged native injection. */
data class RepackedRuntimeDexSwitchInjection(
    val nativeInjection: RepackedRuntimeNativeProbeInjectionResult,
    val sources: List<RuntimeDexApkSource>,
    val switchIds: Set<String>,
    val instrumentedMethods: Int,
    val resourceChanges: List<EngineResourceChange> = emptyList(),
    val originalInstaller: OriginalInstallerRecord? = null,
) {
    val installerRedirectedCalls: Int get() = sources.sumOf { it.installerRedirectedCalls }
    val installerConfirmedChecks: Int get() = sources.sumOf { it.installerConfirmedChecks }
    val installerQueryOnlyChecks: Int get() = sources.sumOf { it.installerQueryOnlyChecks }
    val signingApiReferences: Int get() = sources.sumOf { it.signingApiReferences }
    val playAttestationReferences: Int get() = sources.sumOf { it.playAttestationReferences }
    val reflectionDynamicReferences: Int get() = sources.sumOf { it.reflectionDynamicReferences }
    val nativeMethodSurfaces: Int get() = sources.sumOf { it.nativeMethodSurfaces }
}

object RepackedRuntimeDexSwitchInjector {
    const val CATALOG = "assets/modkit-dex-switches.txt"
    private const val MAX_DEX = 96 * 1024 * 1024

    fun inject(
        nativeInjection: RepackedRuntimeNativeProbeInjectionResult,
        sourceNames: List<String>,
        selections: List<DexRuntimeSelection>,
        outputRoot: File,
        cancellation: CancellationSignal,
        resourceChanges: List<EngineResourceChange> = emptyList(),
        originalInstaller: OriginalInstallerRecord? = null,
        installerCompatibilityPolicy: InstallerCompatibilityPolicy = InstallerCompatibilityPolicy.STRICT,
    ): RepackedRuntimeDexSwitchInjection {
        require(selections.map { it.method.id }.distinct().size == selections.size)
        require(nativeInjection.sources.map { it.sourceDisplayName } == sourceNames) { "APK-set order changed before DEX instrumentation." }
        require(selections.all { it.method.apkIndex in sourceNames.indices }) { "DEX source APK is missing." }
        require(resourceChanges.all { it.artifactSha256 == nativeInjection.artifactSha256 &&
            it.apkIndex in sourceNames.indices && sourceNames[it.apkIndex] == it.sourceName }) { "Resource belongs to a different APK-set." }
        if (originalInstaller != null) {
            require(originalInstaller.artifactSha256 == nativeInjection.artifactSha256) {
                "Installer observation belongs to a different APK-set."
            }
            require(originalInstaller.packageName == nativeInjection.packageName) {
                "Installer observation belongs to a different package."
            }
        }
        val compatibilityPreflight = nativeInjection.sources.map { source ->
            val input = File(source.outputPath)
            require(hash(input.inputStream(), cancellation) == source.outputSha256) {
                "Native injection changed before installer compatibility preflight."
            }
            scanCompatibility(input, cancellation)
        }
        val confirmedInstallerChecks = compatibilityPreflight.sumOf { it.installerConfirmedChecks }
        require(
            confirmedInstallerChecks == 0 ||
                originalInstaller != null ||
                installerCompatibilityPolicy == InstallerCompatibilityPolicy.DIAGNOSTIC_ONLY,
        ) {
            "Найдена локальная проверка источника установки, но исходный установщик не подтверждён. " +
                "Анализируйте установленную SHA-проверенную версию или используйте экспертный диагностический режим."
        }
        val effectiveInstaller = originalInstaller?.takeIf { confirmedInstallerChecks > 0 }

        val switchIds = selections.map { it.switchId }.toSet()
        require(switchIds.size <= 24 && switchIds.all { it.matches(Regex("dex:[0-9a-f]{32}")) })
        val catalog = (switchIds.sorted().joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        val root = File(outputRoot, nativeInjection.artifactSha256 + "/repacked-test/dex-switch-injection").apply { mkdirs() }
        try {
            val sources = nativeInjection.sources.mapIndexed { index, source ->
                checkCancelled(cancellation)
                val input = File(source.outputPath)
                require(hash(input.inputStream(), cancellation) == source.outputSha256) { "Native injection changed before DEX instrumentation." }
                val byEntry = selections.filter { it.method.apkIndex == index }.groupBy { it.method.dexEntry }
                val isBase = source.sourceDisplayName == nativeInjection.baseSourceDisplayName
                val replacement = linkedMapOf<String, ByteArray>()
                val resourceHashes = linkedMapOf<String, String>()
                var installerRedirected = 0
                val compatibility = compatibilityPreflight[index]
                val installerConfirmed = compatibility.installerConfirmedChecks
                val installerQueryOnly = compatibility.installerQueryOnlyChecks
                ZipFile(input).use { zip ->
                    require(zip.getEntry(CATALOG) == null) { "APK already contains a DEX switch catalog. Use the original APK." }
                    byEntry.forEach { (name, chosen) ->
                        require(name.matches(Regex("classes(?:[0-9]+)?[.]dex")))
                        val entry = requireNotNull(zip.getEntry(name)) { "Selected DEX entry is missing: $name" }
                        require(entry.size in 1..MAX_DEX.toLong())
                        val bytes = zip.getInputStream(entry).use { stream ->
                            val out = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                checkCancelled(cancellation)
                                val count = stream.read(buffer)
                                if (count < 0) break
                                require(out.size().toLong() + count <= MAX_DEX) { "DEX expansion limit exceeded." }
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                        require(bytes.size.toLong() == entry.size && bytes.size <= MAX_DEX)
                        val rewritten = DexRuntimeSwitchRewriter.rewrite(bytes, index, name, chosen,
                            File(root, "$index-$name"), cancellation)
                        require(rewritten.appliedIds == chosen.map { it.method.id }.toSet())
                        replacement[name] = rewritten.file.readBytes()
                        rewritten.file.delete()
                    }
                    resourceChanges.filter { it.apkIndex == index }.groupBy { it.entry }.forEach { (name, changes) ->
                        require(zip.entries().asSequence().count { it.name == name } == 1) { "Resource entry is missing or duplicated: $name" }
                        val entry = requireNotNull(zip.getEntry(name))
                        require(entry.size == changes.first().sourceSize.toLong()) { "Resource size changed." }
                        val original = zip.getInputStream(entry).use { EngineResourceMods.read(it, cancellation) }
                        val rewritten = EngineResourceMods.rewrite(original, changes, cancellation)
                        replacement[name] = rewritten
                        resourceHashes[name] = hash(rewritten)
                    }

                    if (effectiveInstaller != null) {
                        require(zip.getEntry(OriginalInstallerRecord.ENTRY) == null) {
                            "APK already contains ModKit installer metadata. Use the original APK."
                        }
                        val dexEntries = zip.entries().asSequence()
                            .filter { it.name.matches(Regex("classes(?:[0-9]+)?[.]dex")) }
                            .toList()
                        for (entry in dexEntries) {
                            checkCancelled(cancellation)
                            require(entry.size in 1..MAX_DEX.toLong())
                            val current = replacement[entry.name] ?: zip.getInputStream(entry).use { stream ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    checkCancelled(cancellation)
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    require(out.size().toLong() + count <= MAX_DEX) { "DEX expansion limit exceeded." }
                                    out.write(buffer, 0, count)
                                }
                                out.toByteArray()
                            }
                            val adapted = DexInstallerCompatibilityRewriter.rewrite(current, cancellation)
                            installerRedirected += adapted.redirectedCalls
                            if (adapted.redirectedCalls > 0) replacement[entry.name] = adapted.bytes
                        }
                    }
                }
                val dexHashes = replacement
                    .filterKeys { it.matches(Regex("classes(?:[0-9]+)?[.]dex")) }
                    .mapValues { hash(it.value) }
                val addedEntries = linkedSetOf<String>()
                if (isBase && switchIds.isNotEmpty()) {
                    replacement[CATALOG] = catalog
                    addedEntries += CATALOG
                }
                if (isBase && effectiveInstaller != null) {
                    replacement[OriginalInstallerRecord.ENTRY] = effectiveInstaller.encode()
                    addedEntries += OriginalInstallerRecord.ENTRY
                }
                val output = if (replacement.isEmpty()) input else File(root, "$index.apk").also {
                    rewriteApk(input, it, replacement, addedEntries, cancellation)
                }
                RuntimeDexApkSource(
                    source.sourceDisplayName,
                    input.absolutePath,
                    source.outputSha256,
                    output.absolutePath,
                    hash(output.inputStream(), cancellation),
                    dexHashes,
                    resourceHashes,
                    installerRedirected,
                    installerConfirmed,
                    installerQueryOnly,
                    compatibility.signingApiReferences,
                    compatibility.playAttestationReferences,
                    compatibility.reflectionDynamicReferences,
                    compatibility.nativeMethodSurfaces,
                )
            }
            return RepackedRuntimeDexSwitchInjection(
                nativeInjection,
                sources,
                switchIds,
                selections.size,
                resourceChanges,
                effectiveInstaller,
            ).also {
                require(it.installerConfirmedChecks == confirmedInstallerChecks)
                require(it.installerRedirectedCalls > 0 || confirmedInstallerChecks == 0) {
                    "Confirmed installer-source checks were not adapted."
                }
                verify(it, cancellation)
            }
        } catch (failure: Throwable) {
            root.deleteRecursively()
            throw failure
        }
    }

    private data class CompatibilityPreflight(
        val installerConfirmedChecks: Int,
        val installerQueryOnlyChecks: Int,
        val signingApiReferences: Int,
        val playAttestationReferences: Int,
        val reflectionDynamicReferences: Int,
        val nativeMethodSurfaces: Int,
    )

    private fun scanCompatibility(file: File, cancellation: CancellationSignal): CompatibilityPreflight {
        var confirmed = 0
        var queryOnly = 0
        var signing = 0
        var play = 0
        var reflection = 0
        var native = 0
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                checkCancelled(cancellation)
                if (!entry.name.matches(Regex("classes(?:[0-9]+)?[.]dex"))) continue
                require(entry.size in 1..MAX_DEX.toLong())
                val bytes = zip.getInputStream(entry).use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        checkCancelled(cancellation)
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(out.size().toLong() + count <= MAX_DEX) {
                            "DEX expansion limit exceeded."
                        }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                val installer = InstallSourceCheckDetector.scan(bytes, cancellation)
                val surfaces = InstallCompatibilitySurfaceDetector.scan(bytes, cancellation)
                confirmed += installer.confirmedCount
                queryOnly += installer.queryOnlyCount
                signing += surfaces.count(InstallCompatibilitySurfaceKind.SIGNING_API_REFERENCE)
                play += surfaces.count(InstallCompatibilitySurfaceKind.PLAY_ATTESTATION_REFERENCE)
                reflection += surfaces.count(InstallCompatibilitySurfaceKind.REFLECTION_OR_DYNAMIC_CODE)
                native += surfaces.count(InstallCompatibilitySurfaceKind.NATIVE_METHOD)
            }
        }
        return CompatibilityPreflight(confirmed, queryOnly, signing, play, reflection, native)
    }

    fun verify(result: RepackedRuntimeDexSwitchInjection, cancellation: CancellationSignal) {
        require(result.sources.map { it.sourceDisplayName } == result.nativeInjection.sources.map { it.sourceDisplayName })
        for ((index, source) in result.sources.withIndex()) {
            val prior = result.nativeInjection.sources[index]
            require(source.inputPath == prior.outputPath && source.inputSha256 == prior.outputSha256)
            require(hash(File(source.inputPath).inputStream(), cancellation) == source.inputSha256) { "Previous runtime stage changed." }
            require(hash(File(source.outputPath).inputStream(), cancellation) == source.outputSha256) { "DEX-switch APK changed before signing." }
            if (result.originalInstaller != null) {
                ZipFile(source.outputPath).use { zip ->
                    val observed = zip.getEntry(OriginalInstallerRecord.ENTRY)
                    if (source.sourceDisplayName == result.nativeInjection.baseSourceDisplayName) {
                        requireNotNull(observed) { "Installer observation is missing from the repacked base APK." }
                        require(zip.getInputStream(observed).readBytes().contentEquals(result.originalInstaller.encode())) {
                            "Installer observation changed before signing."
                        }
                    } else {
                        require(observed == null) { "Installer observation must exist only in the base APK." }
                    }
                }
            }
        }
    }

    private fun rewriteApk(
        input: File,
        output: File,
        replacements: Map<String, ByteArray>,
        addedEntries: Set<String>,
        signal: CancellationSignal,
    ) {
        val expected = linkedMapOf<String, String>()
        ZipFile(input).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    checkCancelled(signal)
                    require(entry.name !in expected) { "Duplicate APK ZIP entry: ${entry.name}" }
                    val bytes = replacements[entry.name]
                    val target = ZipEntry(entry.name).apply {
                        method = entry.method
                        if (entry.time >= 0) time = entry.time
                        if (method == ZipEntry.STORED) {
                            size = bytes?.size?.toLong() ?: entry.size
                            compressedSize = size
                            crc = bytes?.let { CRC32().apply { update(it) }.value } ?: entry.crc
                        }
                    }
                    out.putNextEntry(target)
                    expected[entry.name] = if (bytes != null) {
                        out.write(bytes); hash(bytes)
                    } else hash(zip.getInputStream(entry), signal, out)
                    out.closeEntry()
                }
                require(replacements.keys.all { it in expected || it in addedEntries }) {
                    "Missing selected APK entry."
                }
                for (addedEntry in addedEntries.sorted()) {
                    require(addedEntry !in expected)
                    val bytes = replacements.getValue(addedEntry)
                    out.putNextEntry(ZipEntry(addedEntry))
                    out.write(bytes)
                    out.closeEntry()
                    expected[addedEntry] = hash(bytes)
                }
            }
        }
        ZipFile(output).use { zip ->
            val entries = zip.entries().toList()
            require(entries.map { it.name }.toSet() == expected.keys && entries.size == expected.size)
            for (entry in entries) require(hash(zip.getInputStream(entry), signal) == expected.getValue(entry.name)) {
                "DEX instrumentation changed an unexpected APK entry: ${entry.name}"
            }
        }
    }

    private fun hash(input: InputStream, signal: CancellationSignal, output: OutputStream? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use {
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(signal)
                val n = it.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                output?.write(buffer, 0, n)
            }
        }
        return digest.digest().hex()
    }
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun checkCancelled(signal: CancellationSignal) { if (signal.isCancelled()) throw AnalysisCancelledException() }
}
