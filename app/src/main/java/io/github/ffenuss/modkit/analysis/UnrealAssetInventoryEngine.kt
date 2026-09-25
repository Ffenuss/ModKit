package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.FileInputStream
import java.io.InputStream
import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile

data class UnrealAssetRecord(
    val container: String,
    val path: String,
    val size: Long,
    val kind: String,
    val status: String,
    val pakVersion: Int? = null,
    val pakIndexOffset: Long? = null,
    val pakIndexSize: Long? = null,
    val pakIndexSha1Verified: Boolean? = null,
    val encryptedIndex: Boolean? = null,
    val note: String? = null,
) : Serializable

data class UnrealAssetInventoryResult(
    val records: List<UnrealAssetRecord>,
    val warnings: List<String>,
) : Serializable {
    val verifiedPakCount: Int get() = records.count { it.status == "PAK_INDEX_VERIFIED" }
    val packagedContentCount: Int get() = records.count { it.kind in setOf("uasset", "umap") }
}

/**
 * Genuine, bounded Unreal container/asset inventory, not a Blueprint/UE gameplay
 * editor. Handles UE Pak footer versions 1-11 with optional SHA1 checking for
 * small unencrypted indexes, asset package magic, and IoStore container pairs.
 * Large or encrypted Paks are explicitly reported as not decoded.
 */
object UnrealAssetInventoryEngine {
    const val ID = "unreal.package-inventory"
    const val VERSION = "1"
    private const val MAX_ENTRIES = 20_000
    private const val MAX_PAK_READ_BYTES = 128L * 1024L * 1024L
    private const val MAX_INDEX_SHA1_BYTES = 16L * 1024L * 1024L
    private const val TAIL_BYTES = 512
    private const val PAK_MAGIC = 0x5A6F12E1
    private val UASSET_MAGIC = byteArrayOf(0xC1.toByte(), 0x83.toByte(), 0x2A, 0x9E.toByte())
    private val UTOC_MAGIC = "-==--==--==--==-".toByteArray(Charsets.US_ASCII)

    internal data class PakFooter(
        val version: Int,
        val indexOffset: Long,
        val indexSize: Long,
        val indexHash: ByteArray,
        val encryptedIndex: Boolean?,
    )

    /**
     * Pak footer is version-dependent and its Magic is not necessarily at
     * EOF-44. Search backward in a bounded tail, then validate index bounds.
     */
    internal fun parsePakFooter(tail: ByteArray, archiveSize: Long): PakFooter? {
        if (tail.size < 44 || archiveSize < tail.size) return null
        val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        for (pos in (tail.size - 44) downTo 0) {
            if (buffer.getInt(pos) != PAK_MAGIC) continue
            val version = buffer.getInt(pos + 4)
            if (version !in 1..11) continue
            val offset = buffer.getLong(pos + 8)
            val size = buffer.getLong(pos + 16)
            val footerStart = archiveSize - tail.size + pos
            if (offset < 0L || size <= 0L || offset > footerStart ||
                size > footerStart - offset
            ) continue
            if (version >= 4 && pos < 1) continue
            if (version >= 7 && pos < 17) continue
            if (version >= 8 && pos + 44 + 160 > tail.size) continue
            val hash = tail.copyOfRange(pos + 24, pos + 44)
            val encrypted = if (version >= 4) tail[pos - 1] != 0.toByte() else false
            return PakFooter(version, offset, size, hash, encrypted)
        }
        return null
    }

    fun analyze(
        workspace: AnalysisWorkspace,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): UnrealAssetInventoryResult {
        val candidates = workspace.index.entries.filter { entry ->
            "unreal_container_candidate" in entry.tags ||
                entry.path.endsWith(".uasset", ignoreCase = true) ||
                entry.path.endsWith(".umap", ignoreCase = true)
        }
        val selected = candidates.take(MAX_ENTRIES)
        val out = ArrayList<UnrealAssetRecord>(selected.size)
        val warnings = mutableListOf<String>()
        if (candidates.isEmpty()) warnings +=
            "Unreal runtime detected, but no PAK, IoStore or loose cooked assets " +
                "are present in the selected APK/splits. Check external game downloads."
        if (candidates.size > selected.size) {
            warnings += "Unreal inventory: first $MAX_ENTRIES entries only; " +
                (candidates.size - selected.size) + " entries deferred."
        }
        val allPaths = candidates.mapTo(HashSet()) {
            it.container + ":" + it.path.substringBeforeLast('.').lowercase()
        }
        val sources = workspace.sources.associateBy { it.descriptor.displayName }
        var examined = 0L
        var lastHeartbeat = 0L

        for ((container, entries) in selected.groupBy { it.container }) {
            val source = sources[container]
            if (source == null) {
                for (entry in entries) {
                    out += record(entry, "SOURCE_MISSING", "Исходный APK/split недоступен.")
                }
                warnings += "$container: source not present"
                continue
            }
            val standalone = entries.all {
                it.path == source.file.name && it.container == source.descriptor.displayName
            }
            val zip = if (standalone) null else runCatching { ZipFile(source.file) }
                .getOrElse {
                    warnings += "$container: unable to open source ZIP: " +
                        (it.message ?: it.javaClass.simpleName)
                    null
                }
            try {
                for (entry in entries) {
                    if (cancellation.isCancelled()) throw AnalysisCancelledException()
                    val low = entry.path.lowercase()
                    val kind = low.substringAfterLast('.', "")
                    val found = when (kind) {
                        "pak" -> {
                            when {
                                entry.size <= 0L -> record(entry, "INVALID_SIZE", "Размер PAK неизвестен.")
                                entry.size > MAX_PAK_READ_BYTES ->
                                    record(entry, "PAK_LARGE", "PAK >128 MiB: индекс не извлекался; файл обнаружен.")
                                else -> runCatching {
                                    inspectPak(source.file.absolutePath, zip, entry, cancellation)
                                }.getOrElse {
                                    if (it is AnalysisCancelledException) throw it
                                    record(entry, "PAK_INSPECTION_FAILED", it.message)
                                }
                            }
                        }
                        "uasset", "umap" -> runCatching {
                            withEntry(source.file.absolutePath, zip, entry) { input ->
                                val probe = ByteArray(4)
                                val n = input.read(probe)
                                if (n == 4 && probe.contentEquals(UASSET_MAGIC)) {
                                    record(entry, "UASSET_HEADER_VALID", "Заголовок пакета подтверждён.")
                                } else {
                                    record(entry, "UASSET_HEADER_UNKNOWN", "Неизвестная структура пакета.")
                                }
                            }
                        }.getOrElse { record(entry, "ASSET_INSPECTION_FAILED", it.message) }
                        "utoc" -> runCatching {
                            withEntry(source.file.absolutePath, zip, entry) { input ->
                                val probe = ByteArray(UTOC_MAGIC.size)
                                val n = input.read(probe)
                                val companion = container + ":" +
                                    entry.path.substringBeforeLast('.').lowercase()
                                val linked = companion in allPaths &&
                                    candidates.any {
                                        it.container == container &&
                                            it.path.equals(
                                                entry.path.substringBeforeLast('.') + ".ucas",
                                                ignoreCase = true,
                                            )
                                    }
                                record(
                                    entry,
                                    if (n == probe.size && probe.contentEquals(UTOC_MAGIC))
                                        "UTOC_MAGIC_VALID" else "UTOC_MAGIC_UNKNOWN",
                                    "IoStore container: UCAS " +
                                        (if (linked) "present" else "not included in APK input") +
                                        ". Deep UE5 TOC decoder is not yet supported.",
                                )
                            }
                        }.getOrElse { record(entry, "UTOC_INSPECTION_FAILED", it.message) }
                        "ucas" -> record(
                            entry, "UCAS_INDEX_REQUIRED",
                            "IoStore data requires a matching UTOC (possibly downloaded separately).",
                        )
                        else -> record(entry, "UNREAL_ASSET_SIGNAL", null)
                    }
                    out += found
                    examined++
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat >= 1_000L || examined == selected.size.toLong()) {
                        lastHeartbeat = now
                        progress.publish(
                            EngineProgress(
                                engineId = ID,
                                scheduleClass = EngineScheduleClass.TARGETED,
                                state = RunState.RUNNING,
                                currentTask = "Unreal: инвентаризация PAK/IoStore и ассетов",
                                currentArtifact = entry.container + ":" + entry.path,
                                processed = examined,
                                total = selected.size.toLong(),
                                lastHeartbeatEpochMs = now,
                            ),
                        )
                    }
                }
            } finally {
                zip?.close()
            }
        }
        progress.publish(
            EngineProgress(
                engineId = ID,
                scheduleClass = EngineScheduleClass.TARGETED,
                state = RunState.COMPLETED,
                currentTask = "Unreal: инвентаризация завершена; Blueprint/UE patch backend не готов",
                processed = out.size.toLong(),
                total = selected.size.toLong(),
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )
        return UnrealAssetInventoryResult(out, warnings)
    }

    private fun record(
        entry: ArtifactEntry, status: String, note: String?,
    ): UnrealAssetRecord = UnrealAssetRecord(
        entry.container, entry.path, entry.size,
        entry.path.substringAfterLast('.', "unknown").lowercase(),
        status, note = note,
    )

    private fun inspectPak(
        source: String,
        zip: ZipFile?,
        entry: ArtifactEntry,
        cancellation: CancellationSignal,
    ): UnrealAssetRecord {
        val tail = ByteArray(TAIL_BYTES)
        var readTotal = 0L
        withEntry(source, zip, entry) { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (cancellation.isCancelled()) throw AnalysisCancelledException()
                val n = input.read(buffer)
                if (n < 0) break
                require(readTotal + n <= MAX_PAK_READ_BYTES &&
                    readTotal + n <= entry.size
                ) { "PAK exceeds declared/bounded size." }
                for (i in 0 until n) {
                    tail[((readTotal + i) % TAIL_BYTES).toInt()] = buffer[i]
                }
                readTotal += n
            }
        }
        require(readTotal == entry.size) { "PAK is truncated or differs from ZIP size." }
        val tailLength = minOf(readTotal, TAIL_BYTES.toLong()).toInt()
        val orderedTail = ByteArray(tailLength) { i ->
            tail[((readTotal - tailLength + i) % TAIL_BYTES).toInt()]
        }
        val footer = parsePakFooter(orderedTail, readTotal)
            ?: return record(entry, "PAK_FOOTER_UNKNOWN", "Нет подтверждённого footer версии 1–11.")
        if (footer.encryptedIndex == true) return UnrealAssetRecord(
            entry.container, entry.path, entry.size, "pak", "PAK_INDEX_ENCRYPTED",
            footer.version, footer.indexOffset, footer.indexSize, null, true,
            "Индекс зашифрован; игра или внешний источник ключей не исследовались.",
        )
        val verified = if (footer.indexSize <= MAX_INDEX_SHA1_BYTES) {
            withEntry(source, zip, entry) { input ->
                skipExactly(input, footer.indexOffset, cancellation)
                val hash = MessageDigest.getInstance("SHA-1")
                val buffer = ByteArray(128 * 1024)
                var remaining = footer.indexSize
                while (remaining > 0L) {
                    if (cancellation.isCancelled()) throw AnalysisCancelledException()
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    require(n > 0) { "Pak primary index is truncated." }
                    hash.update(buffer, 0, n)
                    remaining -= n
                }
                hash.digest().contentEquals(footer.indexHash)
            }
        } else null
        val status = when (verified) {
            true -> "PAK_INDEX_VERIFIED"
            false -> "PAK_INDEX_HASH_MISMATCH"
            null -> "PAK_FOOTER_VALID_INDEX_TOO_LARGE"
        }
        return UnrealAssetRecord(
            entry.container, entry.path, entry.size, "pak", status,
            footer.version, footer.indexOffset, footer.indexSize, verified,
            footer.encryptedIndex,
            if (verified == true) "Index SHA-1 verified; UE package object decoder pending."
            else "PAK content not confirmed; no editable game modifications inferred.",
        )
    }

    private fun skipExactly(input: InputStream, amount: Long, signal: CancellationSignal) {
        var remaining = amount
        val buffer = ByteArray(128 * 1024)
        while (remaining > 0L) {
            if (signal.isCancelled()) throw AnalysisCancelledException()
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(n > 0) { "Truncated PAK before index." }
            remaining -= n
        }
    }

    private inline fun <T> withEntry(
        source: String, zip: ZipFile?, entry: ArtifactEntry, action: (InputStream) -> T,
    ): T {
        if (zip == null) {
            require(entry.path == java.io.File(source).name) { "Source archive unavailable." }
            return FileInputStream(source).use(action)
        }
        val item = zip.getEntry(entry.path) ?: error("ZIP entry missing: " + entry.path)
        return zip.getInputStream(item).use(action)
    }
}
