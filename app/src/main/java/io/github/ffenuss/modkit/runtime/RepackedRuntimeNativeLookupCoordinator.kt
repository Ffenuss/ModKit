package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.ArtifactEntry
import io.github.ffenuss.modkit.analysis.BinaryFormat
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.WorkspaceSource
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class RepackedRuntimeNativeLookupExecution(
    val mapsCapture: RepackedRuntimeProbeCaptureResult,
    val moduleArtifact: ArtifactEntry,
    val moduleEvidence: RuntimeEvidenceBundle,
    val lookup: RepackedRuntimeNativeLookupResult,
    val attachedEvidence: RuntimeEvidenceBundle,
)

/**
 * End-to-end targeted native lookup coordinator.
 *
 * It captures fresh self-process maps from the installed ModKit-signed test
 * build, selects exactly one static ELF matching the mapped module/ABI, proves
 * the ELF mapping, then performs a PID-bound RTLD_NOLOAD dlsym observation.
 */
object RepackedRuntimeNativeLookupCoordinator {
    private const val MAX_MODULE_BYTES =
        512L * 1024L * 1024L
    private const val BUFFER_BYTES = 128 * 1024

    fun execute(
        build: RepackedRuntimeBuildResult,
        workspace: AnalysisWorkspace,
        moduleName: String,
        symbolName: String,
        transport: RepackedRuntimeNativeLookupTransport,
        tempRoot: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimeNativeLookupExecution {
        require(
            build.artifactSha256.equals(
                workspace.index.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "Native lookup build artifact SHA does not match active workspace."
        }

        val mapsCapture = RepackedRuntimeEvidenceCapture.capture(
            build = build,
            transport = transport,
            cancellation = cancellation,
        )
        val descriptive = mapsCapture.toEvidenceBundle(
            artifactSha256 = workspace.index.artifactSha256,
            artifactEntries = workspace.index.entries,
        )
        val selected = selectStaticModule(
            moduleName = moduleName,
            inventory = descriptive.moduleInventory,
            artifactEntries = workspace.index.entries,
        )
        val source = workspace.sources.singleOrNull {
            it.descriptor.displayName == selected.container
        } ?: error(
            "Static ELF source container is unavailable: " +
                selected.container,
        )

        val materialized = materializeModule(
            source = source,
            entry = selected,
            tempRoot = tempRoot,
            cancellation = cancellation,
        )
        try {
            val moduleEvidence = RuntimeModuleEvidenceCollector.collect(
                artifactSha256 = workspace.index.artifactSha256,
                moduleFile = materialized.file,
                moduleName = moduleName,
                capture = mapsCapture.maps,
                cancellation = cancellation,
                artifactEntries = workspace.index.entries,
            ).copy(
                processIdentity = mapsCapture.processIdentity,
                processIdentityConfirmed = true,
            )

            val lookup = RepackedRuntimeNativeLookupCapture.capture(
                build = build,
                runtimeEvidence = moduleEvidence,
                procMapsText = mapsCapture.maps.text,
                moduleName = moduleName,
                symbolName = symbolName,
                transport = transport,
                cancellation = cancellation,
            )
            val attached =
                RepackedRuntimeNativeLookupCapture.attach(
                    runtimeEvidence = moduleEvidence,
                    result = lookup,
                )
            return RepackedRuntimeNativeLookupExecution(
                mapsCapture = mapsCapture,
                moduleArtifact = selected,
                moduleEvidence = moduleEvidence,
                lookup = lookup,
                attachedEvidence = attached,
            )
        } finally {
            if (materialized.temporary) {
                materialized.file.delete()
                materialized.file.parentFile?.delete()
            }
        }
    }

    internal fun selectStaticModule(
        moduleName: String,
        inventory: List<RuntimeMappedModule>,
        artifactEntries: List<ArtifactEntry>,
    ): ArtifactEntry {
        val mapped = inventory.filter {
            it.fileName == moduleName &&
                it.executableRegionCount > 0 &&
                it.staticArtifactMatches.isNotEmpty()
        }
        require(mapped.size == 1) {
            if (mapped.isEmpty()) {
                "Mapped module has no unique static artifact match: $moduleName"
            } else {
                "Mapped module resolves to multiple runtime file identities: $moduleName"
            }
        }

        val runtimeAbi = abiFromMappedPath(mapped.single().path)
        val matches = mapped.single().staticArtifactMatches.toSet()
        val candidates = artifactEntries.filter { entry ->
            entry.path.substringAfterLast('/') == moduleName &&
                (entry.format == BinaryFormat.ELF ||
                    entry.path.lowercase().endsWith(".so")) &&
                entry.container + ":" + entry.path in matches
        }
        val narrowed = if (runtimeAbi == null) {
            candidates
        } else {
            candidates.filter { it.abi == runtimeAbi }
        }
        require(narrowed.size == 1) {
            "Static ELF identity is ambiguous for mapped module $moduleName" +
                (runtimeAbi?.let { " ABI=$it" } ?: "")
        }
        return narrowed.single()
    }

    private data class MaterializedModule(
        val file: File,
        val temporary: Boolean,
    )

    private fun materializeModule(
        source: WorkspaceSource,
        entry: ArtifactEntry,
        tempRoot: File,
        cancellation: CancellationSignal,
    ): MaterializedModule {
        verifySourceIdentity(source, cancellation)

        if (
            entry.container == source.descriptor.displayName &&
            entry.path == source.file.name
        ) {
            require(source.file.length() in 1..MAX_MODULE_BYTES) {
                "Direct ELF module size is outside runtime lookup limit."
            }
            return MaterializedModule(
                file = source.file,
                temporary = false,
            )
        }

        require(entry.size in 1..MAX_MODULE_BYTES) {
            "Static ELF module size is outside runtime lookup limit."
        }
        val root = File(
            tempRoot,
            "native-lookup-module",
        ).apply { mkdirs() }
        val output = File(
            root,
            Integer.toHexString(
                (entry.container + ":" + entry.path).hashCode(),
            ) + ".so",
        )
        output.delete()

        ZipFile(source.file).use { zip ->
            val zipEntry = requireNotNull(
                zip.getEntry(entry.path),
            ) {
                "Static ELF entry disappeared from source APK."
            }
            require(!zipEntry.isDirectory) {
                "Static ELF entry is a directory."
            }
            require(zipEntry.size == entry.size) {
                "Static ELF entry size changed since indexing."
            }
            entry.crc32?.let { expected ->
                require(zipEntry.crc == expected) {
                    "Static ELF entry CRC changed since indexing."
                }
            }

            zip.getInputStream(zipEntry).use { input ->
                FileOutputStream(output).use { target ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        checkCancelled(cancellation)
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total = Math.addExact(total, read.toLong())
                        require(total <= MAX_MODULE_BYTES) {
                            "Static ELF extraction exceeded runtime lookup limit."
                        }
                        target.write(buffer, 0, read)
                    }
                    require(total == entry.size) {
                        "Static ELF extraction size mismatch."
                    }
                }
            }
        }
        return MaterializedModule(
            file = output,
            temporary = true,
        )
    }

    private fun verifySourceIdentity(
        source: WorkspaceSource,
        cancellation: CancellationSignal,
    ) {
        require(source.file.isFile && source.file.canRead()) {
            "Runtime lookup source container is unavailable."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(
            FileInputStream(source.file),
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
        val actual = digest.digest()
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        require(
            actual.equals(
                source.descriptor.sha256,
                ignoreCase = true,
            ),
        ) {
            "Runtime lookup source container changed since artifact indexing."
        }
    }

    private fun abiFromMappedPath(
        path: String,
    ): String? {
        val normalized = path.lowercase()
        return when {
            "/arm64-v8a/" in normalized ||
                "/lib/arm64/" in normalized ->
                "arm64-v8a"
            "/armeabi-v7a/" in normalized ||
                "/lib/arm/" in normalized ->
                "armeabi-v7a"
            "/x86_64/" in normalized ||
                "/lib/x86_64/" in normalized ->
                "x86_64"
            "/x86/" in normalized ||
                "/lib/x86/" in normalized ->
                "x86"
            else -> null
        }
    }

    private fun checkCancelled(
        cancellation: CancellationSignal,
    ) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
