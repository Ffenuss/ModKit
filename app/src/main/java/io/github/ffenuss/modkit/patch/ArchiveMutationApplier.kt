package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.WorkspaceSource
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class MutationDiff(
    val mutationId: String,
    val kind: MutationKind,
    val container: String,
    val entryPath: String,
    val fileOffset: Long?,
    val length: Long,
    val beforeSha256: String,
    val afterSha256: String,
)

data class MutationApplyResult(
    val outputFiles: List<File>,
    val diffs: List<MutationDiff>,
    val strippedSignatureEntries: List<String>,
)

/**
 * Applies only preflight-approved archive mutations to staging APK/APK-set files.
 *
 * Original inputs are never modified. Rewriting an APK intentionally removes
 * the old APK Signing Block; v1 META-INF signature files are also stripped.
 * Alignment and signing are separate later build stages.
 */
object ArchiveMutationApplier {
    private const val BUFFER_BYTES = 128 * 1024
    private const val HEARTBEAT_MS = 1_500L

    private data class ResolvedMutation(
        val item: MutationPreflightItem,
        val source: WorkspaceSource,
        val entryPath: String,
        val replacement: File,
    )

    fun apply(
        workspace: AnalysisWorkspace,
        preflight: MutationPreflightResult,
        outputDir: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): MutationApplyResult {
        require(preflight.readyForApply) { "Mutation preflight is not ready for apply." }
        require(preflight.artifactSha256 == workspace.index.artifactSha256) {
            "Mutation preflight belongs to a different artifact SHA."
        }

        val resolved = preflight.readyItems.map { item ->
            resolve(workspace, item, cancellation, progress)
        }
        validateOriginals(resolved, cancellation, progress)

        outputDir.mkdirs()
        val byContainer = resolved.groupBy { it.source.descriptor.displayName }
        val outputFiles = mutableListOf<File>()
        val diffs = mutableListOf<MutationDiff>()
        val stripped = mutableListOf<String>()

        workspace.sources.forEachIndexed { index, source ->
            checkCancelled(cancellation)
            val output = File(outputDir, source.descriptor.displayName)
            output.parentFile?.mkdirs()
            output.delete()

            val mutations = byContainer[source.descriptor.displayName].orEmpty()
            if (mutations.isEmpty()) {
                copyFile(
                    source = source.file,
                    target = output,
                    cancellation = cancellation,
                    progress = progress,
                    label = source.descriptor.displayName,
                    sourceIndex = index,
                    sourceCount = workspace.sources.size,
                )
            } else {
                rewriteArchive(
                    source = source,
                    target = output,
                    mutations = mutations,
                    cancellation = cancellation,
                    progress = progress,
                    strippedSignatures = stripped,
                    diffs = diffs,
                )
            }
            outputFiles += output
        }

        return MutationApplyResult(
            outputFiles = outputFiles,
            diffs = diffs.sortedWith(
                compareBy<MutationDiff> { it.container }
                    .thenBy { it.entryPath }
                    .thenBy { it.fileOffset ?: -1L },
            ),
            strippedSignatureEntries = stripped.distinct().sorted(),
        )
    }

    private fun resolve(
        workspace: AnalysisWorkspace,
        item: MutationPreflightItem,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): ResolvedMutation {
        require(item.status == MutationPreflightStatus.READY) {
            "Mutation is not preflight-ready: " + item.request.id
        }
        require(MutationExecutorRegistry.isImplemented(item.request.kind)) {
            "Mutation executor is not implemented: " + item.request.kind
        }

        val target = requireNotNull(item.target) { "Mutation target disappeared." }
        val artifact = requireNotNull(target.artifact) { "Mutation target has no artifact." }
        val separator = artifact.indexOf(':')
        require(separator > 0 && separator < artifact.lastIndex) {
            "Mutation artifact must identify APK container and entry: " + artifact
        }

        val container = artifact.substring(0, separator)
        val entryPath = artifact.substring(separator + 1)
        val source = workspace.sources.singleOrNull {
            it.descriptor.displayName == container
        } ?: error("Source container is unavailable or ambiguous: " + container)

        val payload = requireNotNull(item.request.replacement) {
            "Mutation replacement payload is missing."
        }
        val storagePath = requireNotNull(payload.storagePath) {
            "Mutation replacement payload is not staged."
        }
        val replacement = File(storagePath)
        require(replacement.isFile && replacement.canRead()) {
            "Mutation replacement payload is unavailable."
        }
        require(replacement.length() == payload.size) {
            "Mutation replacement size changed after preflight."
        }
        val actualPayloadSha = sha256(replacement, cancellation, progress)
        require(actualPayloadSha.equals(payload.sha256, ignoreCase = true)) {
            "Mutation replacement SHA changed after preflight."
        }

        return ResolvedMutation(
            item = item,
            source = source,
            entryPath = entryPath,
            replacement = replacement,
        )
    }

    private fun validateOriginals(
        mutations: List<ResolvedMutation>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ) {
        mutations.groupBy { it.source.file.absolutePath }.forEach { (_, rows) ->
            val source = rows.first().source
            ZipFile(source.file).use { zip ->
                rows.forEachIndexed { index, row ->
                    checkCancelled(cancellation)
                    val entry = zip.getEntry(row.entryPath)
                        ?: error("Archive entry disappeared after preflight: " + row.entryPath)
                    when (row.item.request.kind) {
                        MutationKind.NATIVE_IN_PLACE_BYTES -> {
                            val offset = requireNotNull(row.item.target?.fileOffset)
                            val length = requireNotNull(row.item.request.expectedOriginalSize)
                            val actual = zip.getInputStream(entry).use { input ->
                                hashRange(input, offset, length, cancellation)
                            }
                            require(
                                actual.equals(
                                    row.item.request.expectedOriginalSha256,
                                    ignoreCase = true,
                                ),
                            ) {
                                "Original byte range changed before apply: " +
                                    row.entryPath + "+0x" + offset.toString(16)
                            }
                        }

                        MutationKind.FILE_REPLACE,
                        MutationKind.RESOURCE_REPLACE -> {
                            val expectedSize = requireNotNull(
                                row.item.request.expectedOriginalSize,
                            )
                            require(entry.size == expectedSize) {
                                "Original entry size changed before apply: " + row.entryPath
                            }
                            val actual = zip.getInputStream(entry).use { input ->
                                hashStream(input, cancellation)
                            }
                            require(
                                actual.equals(
                                    row.item.request.expectedOriginalSha256,
                                    ignoreCase = true,
                                ),
                            ) {
                                "Original entry changed before apply: " + row.entryPath
                            }
                        }

                        else -> error(
                            "Mutation executor is not implemented for " +
                                row.item.request.kind,
                        )
                    }

                    publish(
                        progress = progress,
                        task = "Проверка исходных данных изменения",
                        artifact = source.descriptor.displayName + ":" + row.entryPath,
                        processed = (index + 1).toLong(),
                        total = rows.size.toLong(),
                    )
                }
            }
        }
    }

    private fun rewriteArchive(
        source: WorkspaceSource,
        target: File,
        mutations: List<ResolvedMutation>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        strippedSignatures: MutableList<String>,
        diffs: MutableList<MutationDiff>,
    ) {
        val byEntry = mutations.groupBy { it.entryPath }
        val workDir = File(target.parentFile, ".mutation-work-" + target.name).apply {
            mkdirs()
        }
        val patchedFiles = mutableMapOf<String, File>()

        ZipFile(source.file).use { zip ->
            try {
                byEntry.forEach { (entryPath, rows) ->
                    checkCancelled(cancellation)
                    val entry = zip.getEntry(entryPath)
                        ?: error("Archive entry disappeared before rewrite: " + entryPath)

                    val wholeReplacement = rows.singleOrNull()?.takeIf {
                        it.item.request.kind == MutationKind.FILE_REPLACE ||
                            it.item.request.kind == MutationKind.RESOURCE_REPLACE
                    }

                    if (wholeReplacement != null) {
                        patchedFiles[entryPath] = wholeReplacement.replacement
                    } else {
                        require(rows.all {
                            it.item.request.kind == MutationKind.NATIVE_IN_PLACE_BYTES
                        }) {
                            "Mixed whole-entry/native mutation bundle is not allowed."
                        }
                        val temp = File(
                            workDir,
                            Integer.toHexString(entryPath.hashCode()) + ".patched",
                        )
                        zip.getInputStream(entry).use { input ->
                            BufferedOutputStream(
                                FileOutputStream(temp),
                                BUFFER_BYTES,
                            ).use { output ->
                                patchNativeRanges(
                                    input = input,
                                    output = output,
                                    rows = rows.sortedBy {
                                        requireNotNull(it.item.target?.fileOffset)
                                    },
                                    cancellation = cancellation,
                                )
                            }
                        }
                        patchedFiles[entryPath] = temp
                    }
                }

                ZipOutputStream(
                    BufferedOutputStream(FileOutputStream(target), BUFFER_BYTES),
                ).use { output ->
                    val entries = zip.entries()
                    var processed = 0L
                    while (entries.hasMoreElements()) {
                        checkCancelled(cancellation)
                        val entry = entries.nextElement()
                        if (isV1SignatureEntry(entry.name)) {
                            strippedSignatures += source.descriptor.displayName + ":" + entry.name
                            continue
                        }

                        val replacement = patchedFiles[entry.name]
                        output.putNextEntry(cloneEntry(entry, replacement))
                        if (!entry.isDirectory) {
                            if (replacement != null) {
                                FileInputStream(replacement).use { input ->
                                    copyStream(input, output, cancellation)
                                }
                            } else {
                                zip.getInputStream(entry).use { input ->
                                    copyStream(input, output, cancellation)
                                }
                            }
                        }
                        output.closeEntry()
                        processed++

                        publish(
                            progress = progress,
                            task = "Применение изменений к staging APK",
                            artifact = source.descriptor.displayName + ":" + entry.name,
                            processed = processed,
                            total = null,
                        )
                    }
                }

                mutations.forEach { row ->
                    val request = row.item.request
                    val evidenceTarget = requireNotNull(row.item.target)
                    val replacement = requireNotNull(request.replacement)
                    diffs += MutationDiff(
                        mutationId = request.id,
                        kind = request.kind,
                        container = source.descriptor.displayName,
                        entryPath = row.entryPath,
                        fileOffset = evidenceTarget.fileOffset,
                        length = if (request.kind == MutationKind.NATIVE_IN_PLACE_BYTES) {
                            requireNotNull(request.expectedOriginalSize)
                        } else {
                            replacement.size
                        },
                        beforeSha256 = requireNotNull(request.expectedOriginalSha256),
                        afterSha256 = replacement.sha256,
                    )
                }
            } finally {
                patchedFiles.values
                    .filter { it.parentFile == workDir }
                    .forEach(File::delete)
                workDir.delete()
            }
        }
    }

    private fun patchNativeRanges(
        input: InputStream,
        output: OutputStream,
        rows: List<ResolvedMutation>,
        cancellation: CancellationSignal,
    ) {
        var position = 0L
        rows.forEach { row ->
            val offset = requireNotNull(row.item.target?.fileOffset)
            val length = requireNotNull(row.item.request.expectedOriginalSize)
            require(offset >= position) { "Native patch ranges overlap." }

            copyExactly(
                input = input,
                output = output,
                bytes = offset - position,
                cancellation = cancellation,
            )
            discardExactly(input, length, cancellation)
            FileInputStream(row.replacement).use { replacement ->
                copyExactly(
                    input = replacement,
                    output = output,
                    bytes = length,
                    cancellation = cancellation,
                )
                require(replacement.read() < 0) {
                    "Replacement payload grew after preflight."
                }
            }
            position = offset + length
        }
        copyStream(input, output, cancellation)
    }

    private fun cloneEntry(
        source: ZipEntry,
        replacement: File?,
    ): ZipEntry {
        val target = ZipEntry(source.name)
        target.comment = source.comment
        target.extra = source.extra
        if (source.time >= 0L) target.time = source.time

        val method = if (source.method == ZipEntry.STORED) {
            ZipEntry.STORED
        } else {
            ZipEntry.DEFLATED
        }
        target.method = method

        if (method == ZipEntry.STORED) {
            if (replacement == null) {
                target.size = source.size
                target.compressedSize = source.size
                target.crc = source.crc
            } else {
                target.size = replacement.length()
                target.compressedSize = replacement.length()
                target.crc = crc32(replacement)
            }
        }
        return target
    }

    private fun copyFile(
        source: File,
        target: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        label: String,
        sourceIndex: Int,
        sourceCount: Int,
    ) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                var copied = 0L
                var lastHeartbeat = 0L
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    checkCancelled(cancellation)
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read

                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat >= HEARTBEAT_MS) {
                        lastHeartbeat = now
                        publish(
                            progress = progress,
                            task = "Копирование неизменённого APK-set файла",
                            artifact = label + " · " +
                                (sourceIndex + 1) + "/" + sourceCount,
                            processed = copied,
                            total = source.length(),
                        )
                    }
                }
            }
        }
    }

    private fun sha256(
        file: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var processed = 0L
        var lastHeartbeat = 0L
        while (true) {
            checkCancelled(cancellation)
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            processed += read
            val now = System.currentTimeMillis()
            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = now
                publish(
                    progress = progress,
                    task = "Проверка payload",
                    artifact = file.name,
                    processed = processed,
                    total = file.length(),
                )
            }
        }
        digest.digest().toHex()
    }

    private fun hashRange(
        input: InputStream,
        offset: Long,
        length: Long,
        cancellation: CancellationSignal,
    ): String {
        discardExactly(input, offset, cancellation)
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = length
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0L) {
            checkCancelled(cancellation)
            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
            require(read > 0) { "Archive entry ended before expected patch range." }
            digest.update(buffer, 0, read)
            remaining -= read
        }
        return digest.digest().toHex()
    }

    private fun hashStream(
        input: InputStream,
        cancellation: CancellationSignal,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            checkCancelled(cancellation)
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun crc32(file: File): Long {
        val crc = CRC32()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        cancellation: CancellationSignal,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            checkCancelled(cancellation)
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun copyExactly(
        input: InputStream,
        output: OutputStream,
        bytes: Long,
        cancellation: CancellationSignal,
    ) {
        var remaining = bytes
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0L) {
            checkCancelled(cancellation)
            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
            require(read > 0) { "Archive entry ended before expected offset." }
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun discardExactly(
        input: InputStream,
        bytes: Long,
        cancellation: CancellationSignal,
    ) {
        var remaining = bytes
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0L) {
            checkCancelled(cancellation)
            val read = input.read(
                buffer,
                0,
                minOf(buffer.size.toLong(), remaining).toInt(),
            )
            require(read > 0) { "Archive entry ended before expected range." }
            remaining -= read
        }
    }

    private fun isV1SignatureEntry(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        val leaf = upper.substringAfterLast('/')
        return leaf == "MANIFEST.MF" ||
            leaf.endsWith(".SF") ||
            leaf.endsWith(".RSA") ||
            leaf.endsWith(".DSA") ||
            leaf.endsWith(".EC")
    }

    private fun publish(
        progress: ProgressSink,
        task: String,
        artifact: String,
        processed: Long?,
        total: Long?,
    ) {
        progress.publish(
            EngineProgress(
                engineId = "patch.archive-apply",
                scheduleClass = EngineScheduleClass.CONFIRMATION,
                state = RunState.RUNNING,
                currentTask = task,
                currentArtifact = artifact,
                processed = processed,
                total = total,
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
