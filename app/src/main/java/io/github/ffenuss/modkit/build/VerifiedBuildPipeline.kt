package io.github.ffenuss.modkit.build

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import io.github.ffenuss.modkit.patch.MutationApplyOutcome
import io.github.ffenuss.modkit.patch.MutationDiff
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BuiltApkFile(
    val file: File,
    val sha256: String,
    val alignment: ZipAlignmentVerification,
    val signature: ApkSignatureVerification,
)

data class VerifiedBuildResult(
    val artifactSha256: String,
    val files: List<BuiltApkFile>,
    val mutationDiffs: List<MutationDiff>,
    val mutationDiffVerification: MutationDiffVerification,
    val signerAlias: String,
    val signerCertificateSha256: List<String>,
    val postBuildAnalysis: FastAnalysisResult,
    val reportFile: File,
    val builtAtEpochMs: Long,
)

/**
 * Canonical v0.0.1 build path:
 *
 * verified staging -> align -> sign -> signature verify -> alignment re-check
 * -> mutation diff re-check -> signed APK/APK-set.
 */
object VerifiedBuildPipeline {
    suspend fun build(
        context: Context,
        stagingOutcome: MutationApplyOutcome,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): VerifiedBuildResult {
        require(stagingOutcome.applied) {
            "Verified staging mutation is required before Build APK."
        }
        val staging = requireNotNull(stagingOutcome.staging)
        val artifactSha = stagingOutcome.preflight.artifactSha256

        val identity = withContext(Dispatchers.IO) {
            ModKitSigningIdentityProvider.getOrCreateDevelopmentIdentity()
        }
        val root = File(
            context.filesDir,
            "patch-build/" + artifactSha,
        )
        val alignedDir = File(root, "aligned").apply { mkdirs() }
        val signedDir = File(root, "signed").apply { mkdirs() }

        val built = mutableListOf<BuiltApkFile>()
        try {
            staging.outputFiles.forEachIndexed { index, unsigned ->
                checkCancelled(cancellation)
                val aligned = File(alignedDir, unsigned.name)
                val signed = File(signedDir, unsigned.name)

                progress.publish(
                    EngineProgress(
                        engineId = "build.pipeline",
                        scheduleClass = EngineScheduleClass.CONFIRMATION,
                        state = RunState.RUNNING,
                        currentTask = "Align APK",
                        currentArtifact = unsigned.name,
                        processed = index.toLong(),
                        total = staging.outputFiles.size.toLong(),
                        lastHeartbeatEpochMs = System.currentTimeMillis(),
                    ),
                )
                val alignResult = withContext(Dispatchers.IO) {
                    ApkZipAligner.align(
                        input = unsigned,
                        output = aligned,
                        cancellation = cancellation,
                        progress = progress,
                    )
                }

                checkCancelled(cancellation)
                progress.publish(
                    EngineProgress(
                        engineId = "build.pipeline",
                        scheduleClass = EngineScheduleClass.CONFIRMATION,
                        state = RunState.RUNNING,
                        currentTask = "Sign APK",
                        currentArtifact = unsigned.name,
                        processed = index.toLong(),
                        total = staging.outputFiles.size.toLong(),
                        lastHeartbeatEpochMs = System.currentTimeMillis(),
                    ),
                )
                val signing = withContext(Dispatchers.IO) {
                    ApkSigningStage.signAndVerify(
                        input = alignResult.outputFile,
                        output = signed,
                        identity = identity,
                    )
                }

                val signedAlignment = withContext(Dispatchers.IO) {
                    ZipAlignmentVerifier.verify(signing.outputFile)
                }
                require(signedAlignment.verified) {
                    "Signed APK lost required alignment: " +
                        (signedAlignment.blockers.firstOrNull() ?: unsigned.name)
                }

                val sha = withContext(Dispatchers.IO) {
                    sha256(signing.outputFile, cancellation)
                }
                built += BuiltApkFile(
                    file = signing.outputFile,
                    sha256 = sha,
                    alignment = signedAlignment,
                    signature = signing.verification,
                )
            }

            val diffVerification = withContext(Dispatchers.IO) {
                MutationDiffVerifier.verify(
                    files = built.map { it.file },
                    diffs = staging.diffs,
                    cancellation = cancellation,
                )
            }
            require(diffVerification.verified) {
                "Signed APK mutation diff verification failed: " +
                    (diffVerification.blockers.firstOrNull() ?: "unknown diff error")
            }

            val signerFingerprints = built
                .flatMap { it.signature.signerCertificateSha256 }
                .distinct()
            require(signerFingerprints.isNotEmpty()) {
                "Verified APKs did not expose a signer certificate."
            }
            require(
                built.all {
                    it.signature.signerCertificateSha256.toSet() ==
                        signerFingerprints.toSet()
                },
            ) {
                "APK-set outputs were not signed with the same identity."
            }

            progress.publish(
                EngineProgress(
                    engineId = "build.post-analysis",
                    scheduleClass = EngineScheduleClass.CONFIRMATION,
                    state = RunState.RUNNING,
                    currentTask = "Повторный анализ собранного APK",
                    processed = 0,
                    total = built.size.toLong(),
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )
            val postBuildAnalysis = PostBuildReanalyzer.analyze(
                context = context,
                files = built,
                cancellation = cancellation,
                progress = progress,
            )

            progress.publish(
                EngineProgress(
                    engineId = "build.pipeline",
                    scheduleClass = EngineScheduleClass.CONFIRMATION,
                    state = RunState.COMPLETED,
                    currentTask = "APK build verified",
                    processed = built.size.toLong(),
                    total = built.size.toLong(),
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                ),
            )

            val builtAt = System.currentTimeMillis()
            val reportFile = withContext(Dispatchers.IO) {
                BuildReportWriter.write(
                    outputDir = File(root, "report"),
                    sourceArtifactSha256 = artifactSha,
                    files = built,
                    mutationDiffs = staging.diffs,
                    diffVerification = diffVerification,
                    signerAlias = identity.alias,
                    signerCertificateSha256 = signerFingerprints,
                    postBuildAnalysis = postBuildAnalysis,
                    builtAtEpochMs = builtAt,
                )
            }

            return VerifiedBuildResult(
                artifactSha256 = artifactSha,
                files = built,
                mutationDiffs = staging.diffs,
                mutationDiffVerification = diffVerification,
                signerAlias = identity.alias,
                signerCertificateSha256 = signerFingerprints,
                postBuildAnalysis = postBuildAnalysis,
                reportFile = reportFile,
                builtAtEpochMs = builtAt,
            )
        } catch (failure: Throwable) {
            built.forEach { it.file.delete() }
            throw failure
        }
    }

    private fun sha256(
        file: File,
        cancellation: CancellationSignal,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }
}
