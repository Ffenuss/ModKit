package io.github.ffenuss.modkit.runtime

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.io.Serializable

data class RepackedRuntimeInstrumentationResult(
    val artifactSha256: String,
    val packageName: String,
    val prepared: RepackedRuntimePreparedWorkspace,
    val manifestInventory: RepackedRuntimeManifestInventory,
    val manifestRewrite: RepackedRuntimeManifestRewriteResult,
    val probeInjection: RepackedRuntimeProbeInjectionResult,
) : Serializable

/**
 * Connects the already-verified repacked-test stages into one deterministic
 * instrumentation flow. It stops before signing/build finalization so callers
 * can inspect the staged mutation before invoking the common build tail.
 */
object RepackedRuntimeInstrumentationCoordinator {
    fun instrument(
        context: Context,
        workspace: AnalysisWorkspace,
        outputRoot: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimeInstrumentationResult {
        val payload = RuntimeProbePayloadSource.load(
            context = context,
            cancellation = cancellation,
        )
        return instrumentWithPayload(
            workspace = workspace,
            payload = payload,
            outputRoot = outputRoot,
            cancellation = cancellation,
        )
    }

    fun instrumentWithPayload(
        workspace: AnalysisWorkspace,
        payload: RuntimeProbePayload,
        outputRoot: File,
        cancellation: CancellationSignal,
    ): RepackedRuntimeInstrumentationResult {
        val prepared = RepackedRuntimeWorkspacePreparer.prepare(
            workspace = workspace,
            outputDir = outputRoot,
            cancellation = cancellation,
        )

        try {
            val manifestInventory =
                RepackedRuntimeManifestInventoryBuilder.inspect(
                    prepared = prepared,
                    cancellation = cancellation,
                )
            require(manifestInventory.verified) {
                manifestInventory.blockers.firstOrNull()
                    ?: "Repacked manifest inventory is not verified."
            }

            val manifestRewrite =
                RepackedRuntimeManifestRewriter.rewrite(
                    prepared = prepared,
                    manifestInventory = manifestInventory,
                    outputRoot = outputRoot,
                    cancellation = cancellation,
                )

            val probeInjection =
                RepackedRuntimeProbePayloadInjector.inject(
                    manifestRewrite = manifestRewrite,
                    payload = payload,
                    outputRoot = outputRoot,
                    cancellation = cancellation,
                )

            val preflight =
                RepackedRuntimeBuildPreflight.validateProbeInjection(
                    manifestInventory = manifestInventory,
                    injection = probeInjection,
                )
            require(preflight.ready) {
                preflight.blockers.firstOrNull()
                    ?: "Repacked instrumentation build preflight failed."
            }

            return RepackedRuntimeInstrumentationResult(
                artifactSha256 = workspace.index.artifactSha256,
                packageName = requireNotNull(manifestInventory.packageName),
                prepared = prepared,
                manifestInventory = manifestInventory,
                manifestRewrite = manifestRewrite,
                probeInjection = probeInjection,
            )
        } catch (failure: Throwable) {
            cleanupOwnedStages(
                artifactSha256 = workspace.index.artifactSha256,
                outputRoot = outputRoot,
            )
            throw failure
        }
    }

    fun cleanup(
        result: RepackedRuntimeInstrumentationResult,
        outputRoot: File,
    ): Boolean =
        cleanupOwnedStages(
            artifactSha256 = result.artifactSha256,
            outputRoot = outputRoot,
        )

    private fun cleanupOwnedStages(
        artifactSha256: String,
        outputRoot: File,
    ): Boolean {
        val allowed = outputRoot.canonicalFile
        val testRoot = File(
            allowed,
            artifactSha256 + "/repacked-test",
        ).canonicalFile
        require(testRoot.toPath().startsWith(allowed.toPath())) {
            "Refusing repacked runtime cleanup outside output root."
        }

        val owned = listOf(
            File(testRoot, "source-copy"),
            File(testRoot, "manifest-rewrite"),
            File(testRoot, "probe-injection"),
        )
        var success = true
        owned.forEach { directory ->
            if (directory.exists() && !directory.deleteRecursively()) {
                success = false
            }
        }
        return success
    }
}
