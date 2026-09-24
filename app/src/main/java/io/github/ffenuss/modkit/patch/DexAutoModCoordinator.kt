package io.github.ffenuss.modkit.patch

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.EvidenceFact
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.ProofLevel
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge from pure DEX method rewriting to the existing verified
 * FILE_REPLACE preflight/staging/alignment/signing/export pipeline.
 */
object DexAutoModCoordinator {
    private const val MAX_DEX_BYTES = 96L * 1024L * 1024L

    suspend fun scan(
        context: Context,
        target: AnalysisTargetDescriptor,
        analysis: FastAnalysisResult,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): DexLocalScan = withContext(Dispatchers.IO) {
        val cache = EngineResultCache(
            File(context.filesDir, "analysis-cache"),
        )
        PatchWorkspaceProvider.open(
            context = context,
            target = target,
            expected = analysis,
            cache = cache,
            cancellation = cancellation,
            progress = progress,
        ).use { snapshot ->
            DexLocalPatchEngine.scanApks(
                apkFiles = snapshot.workspace.sources.map { it.file },
                developerTestMode = true,
                cancellation = cancellation,
            )
        }
    }

    suspend fun prepareAndApply(
        context: Context,
        target: AnalysisTargetDescriptor,
        analysis: FastAnalysisResult,
        selected: List<DexLocalOpportunity>,
        developerTestMode: Boolean,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): MutationApplyOutcome = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty()) { "Выберите хотя бы одно изменение DEX." }
        require(selected.distinctBy { it.id }.size == selected.size) {
            "Выбранные DEX-методы повторяются."
        }
        if (!developerTestMode) {
            require(selected.none {
                it.category == DexLocalCategory.FULL_VERSION
            }) {
                "Full/Premium — только для тестирования собственной игры."
            }
        }

        val sha = analysis.index.artifactSha256
        val cache = EngineResultCache(
            File(context.filesDir, "analysis-cache"),
        )
        val stageRoot = File(
            context.filesDir,
            "patch-staging/dex/" + sha.take(24) + "/" +
                System.currentTimeMillis().toString(),
        ).apply { mkdirs() }
        val preparedTargets = ArrayList<PreparedTarget>()
        val requests = ArrayList<MutationRequest>()

        try {
            PatchWorkspaceProvider.open(
                context = context,
                target = target,
                expected = analysis,
                cache = cache,
                cancellation = cancellation,
                progress = progress,
            ).use { snapshot ->
                val sources = snapshot.workspace.sources
                val indexed = selected.groupBy { it.apkIndex to it.dexEntry }
                indexed.forEach { (key, opportunities) ->
                    if (cancellation.isCancelled()) {
                        throw io.github.ffenuss.modkit.analysis.AnalysisCancelledException()
                    }
                    val source = sources.getOrNull(key.first)
                        ?: error("Исходный APK-set изменился: нет контейнера #" + key.first)
                    val entryName = key.second
                    val bytes = ZipFile(source.file).use { zip ->
                        val entry = zip.getEntry(entryName)
                            ?: error("DEX отсутствует: " + entryName)
                        require(!entry.isDirectory &&
                            entry.size in 1..MAX_DEX_BYTES
                        ) { "DEX entry size is outside supported limits." }
                        zip.getInputStream(entry).use { input ->
                            readBounded(input)
                        }
                    }

                    progress.publish(
                        EngineProgress(
                            engineId = "dex.rewrite",
                            scheduleClass = EngineScheduleClass.CONFIRMATION,
                            state = RunState.RUNNING,
                            currentTask = "Пересборка выбранных DEX-методов",
                            currentArtifact = source.descriptor.displayName +
                                ":" + entryName,
                            processed = requests.size.toLong(),
                            total = indexed.size.toLong(),
                            lastHeartbeatEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    val replacement = File(
                        stageRoot,
                        key.first.toString() + "-" +
                            entryName.substringAfterLast('/') + ".patched.dex",
                    )
                    val rewritten = DexLocalPatchEngine.rewriteDex(
                        bytes = bytes,
                        apkIndex = key.first,
                        dexEntry = entryName,
                        selected = opportunities,
                        destination = replacement,
                        developerTestMode = developerTestMode,
                        cancellation = cancellation,
                    )
                    require(
                        rewritten.appliedIds == opportunities.map { it.id }.toSet()
                    ) {
                        "Not every selected method was applied."
                    }
                    val targetId = "dexfile:" + key.first + ":" + entryName
                    val evidence = EvidenceTarget(
                        id = targetId,
                        runtimeId = "android_dex",
                        kind = EvidenceTargetKind.RESOURCE,
                        displayName = "Verified rewritten " + entryName,
                        artifact = source.descriptor.displayName + ":" + entryName,
                        abi = null,
                        declaringType = null,
                        memberName = null,
                        metadataToken = null,
                        rva = null,
                        binaryVirtualAddress = null,
                        runtimeVirtualAddress = null,
                        fileOffset = null,
                        proofLevel = ProofLevel.CHANGE_READY,
                        userStatus = UserFindingStatus.READY,
                        blockers = emptyList(),
                        facts = listOf(
                            EvidenceFact(
                                engineId = "dex.rewrite",
                                kind = "exact-method-rewrite",
                                summary = rewritten.appliedIds.size.toString() +
                                    " selected methods rewritten and parsed again.",
                            ),
                        ),
                    )
                    preparedTargets += PreparedTarget(
                        target = evidence,
                        status = PreparationTargetStatus.READY,
                        blockers = emptyList(),
                    )
                    requests += MutationRequest(
                        id = "dexreplace:" + targetId,
                        artifactSha256 = sha,
                        targetId = targetId,
                        kind = MutationKind.FILE_REPLACE,
                        expectedOriginalSha256 = rewritten.sourceSha256,
                        expectedOriginalSize = bytes.size.toLong(),
                        replacement = MutationPayloadRef(
                            sha256 = rewritten.resultSha256,
                            size = rewritten.file.length(),
                            storagePath = rewritten.file.absolutePath,
                        ),
                    )
                }
            }

            val preparation = PatchPreparationPlan(
                artifactSha256 = sha,
                sourceShaVerified = true,
                preparedAtEpochMs = System.currentTimeMillis(),
                targets = preparedTargets,
                globalBlockers = emptyList(),
            )
            val preflight = MutationPreflightEngine.validate(
                preparation = preparation,
                requests = requests,
            )
            require(preflight.readyForApply) {
                (preflight.globalBlockers +
                    preflight.blockedItems.flatMap { it.blockers })
                    .distinct().joinToString("; ")
            }

            MutationApplyCoordinator.apply(
                context = context,
                target = target,
                analysis = analysis,
                preparation = preparation,
                requests = requests,
                cancellation = cancellation,
                progress = progress,
            )
        } catch (failure: Throwable) {
            stageRoot.deleteRecursively()
            throw failure
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(128 * 1024)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= MAX_DEX_BYTES) {
                "DEX decompressed entry exceeds 96 MiB limit."
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
