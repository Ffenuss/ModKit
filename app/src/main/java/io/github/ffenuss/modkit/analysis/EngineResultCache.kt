package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.runtime.RuntimeEvidenceBundle
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceIntegrator
import io.github.ffenuss.modkit.runtime.RuntimeStageAttemptLedger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * App-private, content-addressed cache for completed engine outputs.
 *
 * Key shape follows the canonical spec:
 *   artifactSHA + engineID + engineVersion
 *
 * Cache corruption or version mismatch is treated as a miss. The cache is an
 * optimization only and can never create evidence that the engine did not
 * produce itself.
 */
class EngineResultCache(
    private val root: File,
) {
    private data class Envelope(
        val magic: String,
        val artifactSha256: String,
        val engineId: String,
        val engineVersion: String,
        val payload: Serializable,
    ) : Serializable

    fun loadArtifactIndex(artifactSha256: String): ArtifactIndex? =
        load(
            artifactSha256 = artifactSha256,
            engineId = ARTIFACT_INDEX_ENGINE_ID,
            engineVersion = ARTIFACT_INDEX_ENGINE_VERSION,
            type = ArtifactIndex::class.java,
        )

    fun hasRestorablePartial(artifactSha256: String): Boolean =
        entryFile(
            artifactSha256,
            ARTIFACT_INDEX_ENGINE_ID,
            ARTIFACT_INDEX_ENGINE_VERSION,
        ).let { it.isFile && it.length() in 1..MAX_ENTRY_BYTES }

    fun restorePartialResult(artifactSha256: String): FastAnalysisResult? {
        val index = loadArtifactIndex(artifactSha256) ?: return null
        val dexInventory = loadDexInventory(artifactSha256)
        val elfInventory = loadUniversalElfInventory(artifactSha256)
        val dump = loadIl2CppFastDump(artifactSha256)
        val binding = loadIl2CppBinaryBinding(artifactSha256)
        val runtimeEvidence = loadRuntimeEvidence(artifactSha256)
        val runtimeAttempts = loadRuntimeStageAttempts(artifactSha256)
        val cacheHits = buildSet {
            add(ARTIFACT_INDEX_ENGINE_ID)
            if (dexInventory != null) add(DEX_INVENTORY_ENGINE_ID)
            if (elfInventory != null) add(UNIVERSAL_ELF_INVENTORY_ENGINE_ID)
            if (dump != null) add(IL2CPP_FAST_DUMP_ENGINE_ID)
            if (binding != null) add(IL2CPP_BINARY_BINDING_ENGINE_ID)
            if (runtimeEvidence != null) add(RUNTIME_EVIDENCE_ENGINE_ID)
            if (runtimeAttempts != null) add(RUNTIME_STAGE_ATTEMPTS_ENGINE_ID)
        }

        var result = FastAnalysisResult(
            index = index,
            routingPlan = EngineRouter.plan(index),
            elapsedMs = 0L,
            dexInventory = dexInventory,
            elfInventory = elfInventory,
            il2cppFastDump = dump,
            il2cppBinaryBinding = binding,
            runtimeStageAttempts = runtimeAttempts?.attempts.orEmpty(),
            engineCacheHits = cacheHits,
        )
        if (dump != null) {
            result = result.copy(
                il2cppEvidence = EvidenceGate.evaluate(
                    artifactSha256 = artifactSha256,
                    metadataIdentityExact = dump.metadata.magicValid &&
                        dump.metadata.structuredSupported &&
                        !dump.metadata.truncated,
                    binaryIdentityExact = binding?.exactBindingAvailable == true,
                    runtimeConfirmed = false,
                    mutationValidated = false,
                    requestedChangeReady = false,
                    suppliedBlockers = if (binding == null || binding.exactBindingAvailable) {
                        emptyList()
                    } else {
                        binding.toEvidenceBlockers()
                    },
                ),
            ).withEvidenceGraph()
        }
        if (runtimeEvidence != null) {
            result = RuntimeEvidenceIntegrator.restorePersistedSnapshot(
                result = result,
                evidence = runtimeEvidence,
            )
        }
        return result
    }

    fun saveArtifactIndex(
        artifactSha256: String,
        index: ArtifactIndex,
    ): Boolean = save(
        artifactSha256 = artifactSha256,
        engineId = ARTIFACT_INDEX_ENGINE_ID,
        engineVersion = ARTIFACT_INDEX_ENGINE_VERSION,
        payload = index,
    )

    fun loadDexInventory(
        artifactSha256: String,
    ): DexInventoryResult? =
        load(
            artifactSha256 = artifactSha256,
            engineId = DEX_INVENTORY_ENGINE_ID,
            engineVersion = DEX_INVENTORY_ENGINE_VERSION,
            type = DexInventoryResult::class.java,
        )

    fun saveDexInventory(
        artifactSha256: String,
        result: DexInventoryResult,
    ): Boolean = save(
        artifactSha256 = artifactSha256,
        engineId = DEX_INVENTORY_ENGINE_ID,
        engineVersion = DEX_INVENTORY_ENGINE_VERSION,
        payload = result,
    )

    fun loadUniversalElfInventory(
        artifactSha256: String,
    ): UniversalElfInventoryResult? =
        load(
            artifactSha256 = artifactSha256,
            engineId = UNIVERSAL_ELF_INVENTORY_ENGINE_ID,
            engineVersion = UNIVERSAL_ELF_INVENTORY_ENGINE_VERSION,
            type = UniversalElfInventoryResult::class.java,
        )

    fun saveUniversalElfInventory(
        artifactSha256: String,
        result: UniversalElfInventoryResult,
    ): Boolean = save(
        artifactSha256 = artifactSha256,
        engineId = UNIVERSAL_ELF_INVENTORY_ENGINE_ID,
        engineVersion = UNIVERSAL_ELF_INVENTORY_ENGINE_VERSION,
        payload = result,
    )

    fun loadIl2CppFastDump(artifactSha256: String): Il2CppFastDumpResult? {
        val value = load(
            artifactSha256 = artifactSha256,
            engineId = IL2CPP_FAST_DUMP_ENGINE_ID,
            engineVersion = IL2CPP_FAST_DUMP_ENGINE_VERSION,
            type = Il2CppFastDumpResult::class.java,
        ) ?: return null

        if (!File(value.dumpFilePath).isFile) {
            invalidate(
                artifactSha256,
                IL2CPP_FAST_DUMP_ENGINE_ID,
                IL2CPP_FAST_DUMP_ENGINE_VERSION,
            )
            return null
        }
        return value
    }

    fun saveIl2CppFastDump(
        artifactSha256: String,
        result: Il2CppFastDumpResult,
    ): Boolean = save(
        artifactSha256 = artifactSha256,
        engineId = IL2CPP_FAST_DUMP_ENGINE_ID,
        engineVersion = IL2CPP_FAST_DUMP_ENGINE_VERSION,
        payload = result,
    )

    fun loadIl2CppBinaryBinding(artifactSha256: String): Il2CppBinaryBindingResult? =
        load(
            artifactSha256 = artifactSha256,
            engineId = IL2CPP_BINARY_BINDING_ENGINE_ID,
            engineVersion = IL2CPP_BINARY_BINDING_ENGINE_VERSION,
            type = Il2CppBinaryBindingResult::class.java,
        )

    fun saveIl2CppBinaryBinding(
        artifactSha256: String,
        result: Il2CppBinaryBindingResult,
    ): Boolean = save(
        artifactSha256 = artifactSha256,
        engineId = IL2CPP_BINARY_BINDING_ENGINE_ID,
        engineVersion = IL2CPP_BINARY_BINDING_ENGINE_VERSION,
        payload = result,
    )

    fun loadRuntimeEvidence(
        artifactSha256: String,
    ): RuntimeEvidenceBundle? =
        load(
            artifactSha256 = artifactSha256,
            engineId = RUNTIME_EVIDENCE_ENGINE_ID,
            engineVersion = RUNTIME_EVIDENCE_ENGINE_VERSION,
            type = RuntimeEvidenceBundle::class.java,
        )

    fun saveRuntimeEvidence(
        artifactSha256: String,
        result: RuntimeEvidenceBundle,
    ): Boolean {
        if (!result.artifactSha256.equals(artifactSha256, ignoreCase = true)) {
            return false
        }
        return save(
            artifactSha256 = artifactSha256,
            engineId = RUNTIME_EVIDENCE_ENGINE_ID,
            engineVersion = RUNTIME_EVIDENCE_ENGINE_VERSION,
            payload = result,
        )
    }

    fun loadRuntimeStageAttempts(
        artifactSha256: String,
    ): RuntimeStageAttemptLedger? =
        load(
            artifactSha256 = artifactSha256,
            engineId = RUNTIME_STAGE_ATTEMPTS_ENGINE_ID,
            engineVersion = RUNTIME_STAGE_ATTEMPTS_ENGINE_VERSION,
            type = RuntimeStageAttemptLedger::class.java,
        )?.takeIf {
            it.artifactSha256.equals(artifactSha256, ignoreCase = true)
        }

    fun saveRuntimeStageAttempts(
        artifactSha256: String,
        ledger: RuntimeStageAttemptLedger,
    ): Boolean {
        if (!ledger.artifactSha256.equals(artifactSha256, ignoreCase = true)) {
            return false
        }
        return save(
            artifactSha256 = artifactSha256,
            engineId = RUNTIME_STAGE_ATTEMPTS_ENGINE_ID,
            engineVersion = RUNTIME_STAGE_ATTEMPTS_ENGINE_VERSION,
            payload = ledger,
        )
    }

    private fun <T : Serializable> load(
        artifactSha256: String,
        engineId: String,
        engineVersion: String,
        type: Class<T>,
    ): T? {
        val file = entryFile(artifactSha256, engineId, engineVersion)
        if (!file.isFile || file.length() !in 1..MAX_ENTRY_BYTES) return null

        return runCatching {
            val envelope = ObjectInputStream(
                BufferedInputStream(FileInputStream(file), BUFFER_BYTES),
            ).use { input ->
                input.readObject() as? Envelope
            } ?: error("Invalid cache envelope")

            require(envelope.magic == MAGIC)
            require(envelope.artifactSha256 == artifactSha256)
            require(envelope.engineId == engineId)
            require(envelope.engineVersion == engineVersion)
            require(type.isInstance(envelope.payload))
            type.cast(envelope.payload)
        }.getOrElse {
            file.delete()
            null
        }
    }

    private fun save(
        artifactSha256: String,
        engineId: String,
        engineVersion: String,
        payload: Serializable,
    ): Boolean {
        val file = entryFile(artifactSha256, engineId, engineVersion)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.parentFile?.mkdirs()
        tmp.delete()

        return runCatching {
            ObjectOutputStream(
                BufferedOutputStream(FileOutputStream(tmp), BUFFER_BYTES),
            ).use { output ->
                output.writeObject(
                    Envelope(
                        magic = MAGIC,
                        artifactSha256 = artifactSha256,
                        engineId = engineId,
                        engineVersion = engineVersion,
                        payload = payload,
                    ),
                )
                output.flush()
            }

            if (tmp.length() !in 1..MAX_ENTRY_BYTES) {
                tmp.delete()
                return false
            }

            if (file.exists() && !file.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return false
            }
            true
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    private fun invalidate(
        artifactSha256: String,
        engineId: String,
        engineVersion: String,
    ) {
        entryFile(artifactSha256, engineId, engineVersion).delete()
    }

    private fun entryFile(
        artifactSha256: String,
        engineId: String,
        engineVersion: String,
    ): File {
        val safeSha = artifactSha256.lowercase().replace(NON_SAFE, "_")
        val safeEngine = engineId.replace(NON_SAFE, "_")
        val safeVersion = engineVersion.replace(NON_SAFE, "_")
        return File(
            File(File(File(root, safeSha), safeEngine), safeVersion),
            "result.bin",
        )
    }

    companion object {
        private const val MAGIC = "MODKIT_ENGINE_CACHE_V1"
        private const val BUFFER_BYTES = 128 * 1024
        private const val MAX_ENTRY_BYTES = 192L * 1024L * 1024L
        private val NON_SAFE = Regex("[^A-Za-z0-9._-]")

        const val ARTIFACT_INDEX_ENGINE_ID = "artifact.fast-index"
        const val ARTIFACT_INDEX_ENGINE_VERSION = "2"

        const val DEX_INVENTORY_ENGINE_ID = "dex.inventory"
        const val DEX_INVENTORY_ENGINE_VERSION = "1"

        const val UNIVERSAL_ELF_INVENTORY_ENGINE_ID = "elf.universal-inventory"
        const val UNIVERSAL_ELF_INVENTORY_ENGINE_VERSION = "2"

        const val IL2CPP_FAST_DUMP_ENGINE_ID = "il2cpp.fast-dump"
        const val IL2CPP_FAST_DUMP_ENGINE_VERSION = "3"

        const val IL2CPP_BINARY_BINDING_ENGINE_ID = "il2cpp.codegen-bind"
        const val IL2CPP_BINARY_BINDING_ENGINE_VERSION = "4"

        const val RUNTIME_EVIDENCE_ENGINE_ID = "runtime.evidence"
        const val RUNTIME_EVIDENCE_ENGINE_VERSION = "3"

        const val RUNTIME_STAGE_ATTEMPTS_ENGINE_ID = "runtime.stage-attempts"
        const val RUNTIME_STAGE_ATTEMPTS_ENGINE_VERSION = "1"
    }
}
