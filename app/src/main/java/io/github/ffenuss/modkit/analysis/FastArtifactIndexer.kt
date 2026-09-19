package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

object FastArtifactIndexer {
    private const val HEARTBEAT_INTERVAL_MS = 1_500L

    data class Limits(
        val maxEntries: Int = 100_000,
        val probeBytes: Int = 64,
    )

    fun index(
        files: List<File>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        knownSha256: Map<String, String> = emptyMap(),
        limits: Limits = Limits(),
        cache: EngineResultCache? = null,
    ): FastAnalysisResult {
        require(files.isNotEmpty()) { "No target files" }
        val started = System.currentTimeMillis()
        val sources = ArrayList<ArtifactSource>(files.size)

        progress.publish(
            EngineProgress(
                engineId = "artifact.fast-index",
                scheduleClass = EngineScheduleClass.FAST,
                state = RunState.RUNNING,
                currentTask = "SHA-привязка входа",
                processed = 0,
                total = files.size.toLong(),
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )

        for ((fileIndex, file) in files.withIndex()) {
            checkCancelled(cancellation)
            require(file.isFile) { "Target file does not exist: ${file.absolutePath}" }

            val sha = knownSha256[file.absolutePath]
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                ?.lowercase()
                ?: sha256(file, cancellation, progress)

            sources += ArtifactSource(
                displayName = file.name,
                size = file.length(),
                sha256 = sha,
            )

            progress.publish(
                EngineProgress(
                    engineId = "artifact.fast-index",
                    scheduleClass = EngineScheduleClass.FAST,
                    state = RunState.RUNNING,
                    currentTask = "SHA входа подтверждён",
                    currentArtifact = file.name,
                    processed = (fileIndex + 1).toLong(),
                    total = files.size.toLong(),
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )
        }

        val setSha = ArtifactIdentity.combine(sources)
        val cachedIndex = cache
            ?.loadArtifactIndex(setSha)
            ?.takeIf { cached -> sameSourceContent(cached.sources, sources) }

        if (cachedIndex != null) {
            progress.publish(
                EngineProgress(
                    engineId = "artifact.fast-index",
                    scheduleClass = EngineScheduleClass.FAST,
                    state = RunState.COMPLETED,
                    currentTask = "Быстрый индекс восстановлен из content-addressed cache",
                    currentArtifact = setSha.take(16),
                    processed = cachedIndex.entries.size.toLong(),
                    total = cachedIndex.entries.size.toLong(),
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )
            return FastAnalysisResult(
                index = cachedIndex,
                routingPlan = EngineRouter.plan(cachedIndex),
                elapsedMs = System.currentTimeMillis() - started,
                engineCacheHits = setOf(EngineResultCache.ARTIFACT_INDEX_ENGINE_ID),
            )
        }

        val entries = ArrayList<ArtifactEntry>()
        val warnings = mutableListOf<String>()
        val abis = linkedSetOf<String>()
        var truncated = false

        progress.publish(
            EngineProgress(
                engineId = "artifact.fast-index",
                scheduleClass = EngineScheduleClass.FAST,
                state = RunState.RUNNING,
                currentTask = "Инвентаризация archive entries",
                processed = 0,
                total = files.size.toLong(),
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )

        for ((fileIndex, file) in files.withIndex()) {
            checkCancelled(cancellation)
            val fileProbe = readProbe(file, limits.probeBytes)
            if (isZip(fileProbe)) {
                runCatching {
                    ZipFile(file).use { zip ->
                        val iterator = zip.entries()
                        var count = 0
                        var lastHeartbeat = System.currentTimeMillis()
                        while (iterator.hasMoreElements()) {
                            checkCancelled(cancellation)
                            if (count >= limits.maxEntries) {
                                truncated = true
                                warnings += "${file.name}: archive entry limit ${limits.maxEntries} reached"
                                break
                            }
                            val entry = iterator.nextElement()
                            if (entry.isDirectory) continue

                            val probe = if (shouldProbeEntry(entry.name, entry.size)) {
                                runCatching {
                                    zip.getInputStream(entry).use { input ->
                                        val bytes = ByteArray(limits.probeBytes)
                                        val read = input.read(bytes)
                                        if (read <= 0) ByteArray(0) else bytes.copyOf(read)
                                    }
                                }.getOrElse { error ->
                                    warnings += "${file.name}:${entry.name}: probe failed: ${error.message ?: error.javaClass.simpleName}"
                                    ByteArray(0)
                                }
                            } else {
                                ByteArray(0)
                            }

                            val classification = classify(entry.name, probe)
                            classification.abi?.let(abis::add)
                            entries += ArtifactEntry(
                                container = file.name,
                                path = entry.name,
                                size = entry.size.coerceAtLeast(0L),
                                compressedSize = entry.compressedSize.takeIf { it >= 0L },
                                crc32 = entry.crc.takeIf { it >= 0L },
                                format = classification.format,
                                abi = classification.abi,
                                tags = classification.tags,
                            )
                            count++

                            val now = System.currentTimeMillis()
                            if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                                lastHeartbeat = now
                                progress.publish(
                                    EngineProgress(
                                        engineId = "artifact.fast-index",
                                        scheduleClass = EngineScheduleClass.FAST,
                                        state = RunState.RUNNING,
                                        currentTask = "Индексирование archive entries",
                                        currentArtifact = "${file.name}: ${entry.name}",
                                        processed = count.toLong(),
                                        total = null,
                                        lastHeartbeatEpochMs = now,
                                    ),
                                )
                            }
                        }
                    }
                }.onFailure { error ->
                    warnings += "${file.name}: archive index failed: ${error.message ?: error.javaClass.simpleName}"
                }
            } else {
                val classification = classify(file.name, fileProbe)
                classification.abi?.let(abis::add)
                entries += ArtifactEntry(
                    container = file.name,
                    path = file.name,
                    size = file.length(),
                    format = classification.format,
                    abi = classification.abi,
                    tags = classification.tags,
                )
            }

            progress.publish(
                EngineProgress(
                    engineId = "artifact.fast-index",
                    scheduleClass = EngineScheduleClass.FAST,
                    state = RunState.RUNNING,
                    currentTask = "Вход проиндексирован",
                    currentArtifact = file.name,
                    processed = (fileIndex + 1).toLong(),
                    total = files.size.toLong(),
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )
        }

        val runtimeProfiles = RuntimeFingerprintProfiler.profile(entries)
        runtimeProfiles.flatMapTo(abis) { profile ->
            profile.evidence.mapNotNull(::abiFromEvidence)
        }

        val index = ArtifactIndex(
            artifactSha256 = setSha,
            sources = sources,
            entries = entries,
            detectedAbis = abis,
            runtimeProfiles = runtimeProfiles,
            truncated = truncated,
            warnings = warnings.distinct(),
        )

        cache?.saveArtifactIndex(setSha, index)

        progress.publish(
            EngineProgress(
                engineId = "artifact.fast-index",
                scheduleClass = EngineScheduleClass.FAST,
                state = RunState.COMPLETED,
                currentTask = "Быстрый индекс готов",
                processed = entries.size.toLong(),
                total = entries.size.toLong(),
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )
        return FastAnalysisResult(
            index = index,
            routingPlan = EngineRouter.plan(index),
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    private fun sameSourceContent(
        cached: List<ArtifactSource>,
        current: List<ArtifactSource>,
    ): Boolean {
        if (cached.size != current.size) return false
        fun identities(values: List<ArtifactSource>) =
            values.map { it.sha256.lowercase() to it.size }.sortedBy { it.first }
        return identities(cached) == identities(current)
    }

    private data class Classification(
        val format: BinaryFormat,
        val abi: String?,
        val tags: Set<String>,
    )

    private fun shouldProbeEntry(path: String, size: Long): Boolean {
        if (size <= 0L) return false
        val low = path.lowercase()
        val base = low.substringAfterLast('/')
        if (base.matches(Regex("classes(\\d*)\\.dex"))) return true
        if (low.endsWith(".so")) return true
        if (low.endsWith("global-metadata.dat")) return true
        if (low.endsWith(".wasm")) return true
        return '.' !in base && (low.startsWith("assets/") || low.startsWith("lib/"))
    }

    private fun classify(path: String, probe: ByteArray): Classification {
        val low = path.lowercase()
        val base = low.substringAfterLast('/')
        val tags = linkedSetOf<String>()
        val abiFromPath = low.split('/').let { parts ->
            if (parts.size >= 3 && parts[0] == "lib") parts[1].takeIf { it in KNOWN_ABIS } else null
        }

        val isDex = probe.size >= 8 &&
            probe[0] == 'd'.code.toByte() &&
            probe[1] == 'e'.code.toByte() &&
            probe[2] == 'x'.code.toByte() &&
            probe[3] == '\n'.code.toByte()
        val isElf = probe.size >= 20 &&
            (probe[0].toInt() and 0xff) == 0x7f &&
            probe[1] == 'E'.code.toByte() &&
            probe[2] == 'L'.code.toByte() &&
            probe[3] == 'F'.code.toByte()
        val isWasm = probe.size >= 4 &&
            probe[0] == 0.toByte() &&
            probe[1] == 0x61.toByte() &&
            probe[2] == 0x73.toByte() &&
            probe[3] == 0x6d.toByte()
        val isMetadata = probe.size >= 4 &&
            (probe[0].toInt() and 0xff) == 0xaf &&
            (probe[1].toInt() and 0xff) == 0x1b &&
            (probe[2].toInt() and 0xff) == 0xb1 &&
            (probe[3].toInt() and 0xff) == 0xfa

        val elfAbi = if (isElf) elfAbi(probe) else null
        val abi = elfAbi ?: abiFromPath

        if (base.matches(Regex("classes(\\d*)\\.dex"))) tags += "dex_candidate"
        if (isDex) tags += "dex_valid"
        if (low.endsWith(".so")) tags += "native_candidate"
        if (isElf) tags += "elf_valid"
        if (base == "libil2cpp.so") tags += "il2cpp_binary"
        if (low.endsWith("global-metadata.dat")) tags += "il2cpp_metadata"
        if (isMetadata) tags += "il2cpp_metadata_valid"
        if (base == "libunity.so") tags += "unity_native"
        if (base == "libflutter.so") tags += "flutter_engine"
        if (base == "libapp.so") tags += "flutter_app"
        if ("flutter_assets/" in low) tags += "flutter_asset"
        if (base.contains("hermes")) tags += "hermes_native"
        if (low.endsWith(".hbc") || low.endsWith(".hermes")) tags += "hermes_bytecode_candidate"
        if (base == "libjsc.so" || base == "libjscexecutor.so") tags += "jsc_native"
        if (low.endsWith(".dll")) tags += "managed_candidate"
        if ("/managed/" in low || low.startsWith("assemblies/")) tags += "managed_layout"
        if (base in setOf("libmonosgen-2.0.so", "libmonobdwgc-2.0.so", "libmonodroid.so")) tags += "mono_native"
        if (base in setOf("libue4.so", "libunreal.so", "libunrealengine.so")) tags += "unreal_native"
        if (low.endsWith(".pak") || low.endsWith(".utoc") || low.endsWith(".ucas")) tags += "unreal_container_candidate"
        if (base in setOf("libgodot_android.so", "libgodot.so")) tags += "godot_native"
        if (low.endsWith(".pck")) tags += "godot_pck_candidate"
        if (base == "libdmengine.so") tags += "defold_native"
        if (base.startsWith("libqt5") || base.startsWith("libqt6") || "/qml/" in low) tags += "qt_qml_signal"
        if (base in setOf("libcocos2dcpp.so", "libcocos.so", "libcocos2d.so")) tags += "cocos_native"
        if (low.endsWith(".lua") || low.endsWith(".luac") || base.contains("liblua")) tags += "lua_signal"
        if (low.endsWith("cordova.js")) tags += "cordova_signal"
        if ("capacitor.config" in low || "assets/public/" in low) tags += "capacitor_signal"
        if (low.endsWith(".wasm")) tags += "wasm_candidate"
        if (isWasm) tags += "wasm_valid"

        val format = when {
            isDex -> BinaryFormat.DEX
            isElf -> BinaryFormat.ELF
            isWasm -> BinaryFormat.WASM
            isMetadata -> BinaryFormat.IL2CPP_METADATA
            else -> BinaryFormat.UNKNOWN
        }
        return Classification(format, abi, tags)
    }

    private fun elfAbi(probe: ByteArray): String? {
        if (probe.size < 20) return null
        val machine = (probe[18].toInt() and 0xff) or ((probe[19].toInt() and 0xff) shl 8)
        return when (machine) {
            3 -> "x86"
            40 -> "armeabi-v7a"
            62 -> "x86_64"
            183 -> "arm64-v8a"
            else -> null
        }
    }

    private fun isZip(probe: ByteArray): Boolean =
        probe.size >= 4 &&
            probe[0] == 'P'.code.toByte() &&
            probe[1] == 'K'.code.toByte() &&
            ((probe[2] == 3.toByte() && probe[3] == 4.toByte()) ||
                (probe[2] == 5.toByte() && probe[3] == 6.toByte()) ||
                (probe[2] == 7.toByte() && probe[3] == 8.toByte()))

    private fun readProbe(file: File, maxBytes: Int): ByteArray =
        FileInputStream(file).use { input ->
            val bytes = ByteArray(maxBytes)
            val read = input.read(bytes)
            if (read <= 0) ByteArray(0) else bytes.copyOf(read)
        }

    private fun sha256(
        file: File,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var processed = 0L
        var lastHeartbeat = 0L
        FileInputStream(file).buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                processed += read
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeat = now
                    progress.publish(
                        EngineProgress(
                            engineId = "artifact.fast-index",
                            scheduleClass = EngineScheduleClass.FAST,
                            state = RunState.RUNNING,
                            currentTask = "SHA-256",
                            currentArtifact = file.name,
                            processed = processed,
                            total = file.length(),
                            lastHeartbeatEpochMs = now,
                        ),
                    )
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun abiFromEvidence(value: String): String? =
        KNOWN_ABIS.firstOrNull { "/$it/" in value.lowercase() }

    private val KNOWN_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
}

class AnalysisCancelledException : RuntimeException("Analysis cancelled")
