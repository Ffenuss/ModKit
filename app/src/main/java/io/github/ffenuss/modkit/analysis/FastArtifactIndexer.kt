package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

object FastArtifactIndexer {
    data class Limits(
        val maxEntries: Int = 100_000,
        val probeBytes: Int = 64,
    )

    fun index(
        files: List<File>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        limits: Limits = Limits(),
    ): FastAnalysisResult {
        require(files.isNotEmpty()) { "No target files" }
        val started = System.currentTimeMillis()
        val sources = ArrayList<ArtifactSource>(files.size)
        val entries = ArrayList<ArtifactEntry>()
        val warnings = mutableListOf<String>()
        val abis = linkedSetOf<String>()
        var truncated = false

        progress.publish(
            EngineProgress(
                engineId = "artifact.fast-index",
                scheduleClass = EngineScheduleClass.FAST,
                state = RunState.RUNNING,
                currentTask = "SHA-256 и инвентаризация входа",
                processed = 0,
                total = files.size.toLong(),
                lastHeartbeatEpochMs = System.currentTimeMillis(),
            ),
        )

        for ((fileIndex, file) in files.withIndex()) {
            checkCancelled(cancellation)
            require(file.isFile) { "Target file does not exist: ${file.absolutePath}" }
            val sha = sha256(file, cancellation)
            sources += ArtifactSource(file.name, file.length(), sha)

            val indexed = runCatching {
                ZipFile(file).use { zip ->
                    val iterator = zip.entries()
                    var count = 0
                    while (iterator.hasMoreElements()) {
                        checkCancelled(cancellation)
                        if (count >= limits.maxEntries) {
                            truncated = true
                            warnings += "${file.name}: archive entry limit ${limits.maxEntries} reached"
                            break
                        }
                        val entry = iterator.nextElement()
                        if (entry.isDirectory) continue
                        val probe = runCatching {
                            zip.getInputStream(entry).use { input ->
                                val bytes = ByteArray(limits.probeBytes)
                                val read = input.read(bytes)
                                if (read <= 0) ByteArray(0) else bytes.copyOf(read)
                            }
                        }.getOrDefault(ByteArray(0))
                        val classification = classify(entry.name, probe)
                        classification.abi?.let(abis::add)
                        entries += ArtifactEntry(
                            container = file.name,
                            path = entry.name,
                            size = entry.size.coerceAtLeast(0),
                            compressedSize = entry.compressedSize.takeIf { it >= 0 },
                            crc32 = entry.crc.takeIf { it >= 0 },
                            format = classification.format,
                            abi = classification.abi,
                            tags = classification.tags,
                        )
                        count++
                        if (count % 128 == 0) {
                            progress.publish(
                                EngineProgress(
                                    engineId = "artifact.fast-index",
                                    scheduleClass = EngineScheduleClass.FAST,
                                    state = RunState.RUNNING,
                                    currentTask = "Индексирование archive entries",
                                    currentArtifact = "${file.name}: ${entry.name}",
                                    processed = count.toLong(),
                                    total = null,
                                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
                true
            }.getOrElse { error ->
                if (error is ZipException) {
                    val probe = FileInputStream(file).use { input ->
                        val bytes = ByteArray(limits.probeBytes)
                        val read = input.read(bytes)
                        if (read <= 0) ByteArray(0) else bytes.copyOf(read)
                    }
                    val classification = classify(file.name, probe)
                    classification.abi?.let(abis::add)
                    entries += ArtifactEntry(
                        container = file.name,
                        path = file.name,
                        size = file.length(),
                        format = classification.format,
                        abi = classification.abi,
                        tags = classification.tags,
                    )
                    true
                } else {
                    warnings += "${file.name}: ${error.message ?: error.javaClass.simpleName}"
                    false
                }
            }
            if (!indexed) warnings += "${file.name}: index incomplete"

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

        val setSha = if (sources.size == 1) {
            sources.single().sha256
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            sources.sortedBy { it.displayName }.forEach { source ->
                digest.update(source.displayName.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(source.sha256.toByteArray(Charsets.US_ASCII))
                digest.update(0.toByte())
                digest.update(source.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(0.toByte())
            }
            digest.digest().toHex()
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
        return FastAnalysisResult(index, System.currentTimeMillis() - started)
    }

    private data class Classification(
        val format: BinaryFormat,
        val abi: String?,
        val tags: Set<String>,
    )

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

    private fun sha256(file: File, cancellation: CancellationSignal): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun abiFromEvidence(value: String): String? =
        KNOWN_ABIS.firstOrNull { "/$it/" in value.lowercase() }

    private val KNOWN_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
}

class AnalysisCancelledException : RuntimeException("Analysis cancelled")
