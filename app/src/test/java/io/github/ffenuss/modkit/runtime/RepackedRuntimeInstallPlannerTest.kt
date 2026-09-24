package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.build.BuiltApkFile
import io.github.ffenuss.modkit.build.MutationDiffVerification
import io.github.ffenuss.modkit.build.VerifiedBuildResult
import io.github.ffenuss.modkit.build.ApkSignatureVerification
import io.github.ffenuss.modkit.build.InstallabilityVerification
import io.github.ffenuss.modkit.build.ZipAlignmentVerification
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeInstallPlannerTest {
    @Test
    fun verifiedSignedApkSetProducesReadyInstallPlan() {
        val root = Files.createTempDirectory("modkit-install-plan-").toFile()
        try {
            val base = File(root, "base.apk").apply {
                writeText("signed-base")
            }
            val split = File(root, "split.apk").apply {
                writeText("signed-split")
            }

            val plan = RepackedRuntimeInstallPlanner.plan(
                build = buildResult(
                    listOf(
                        built("base.apk", base, SIGNER),
                        built("split.apk", split, SIGNER),
                    ),
                ),
                cancellation = AtomicCancellationSignal(),
            )

            assertTrue(plan.ready)
            assertEquals(PACKAGE, plan.packageName)
            assertEquals(setOf(SIGNER), plan.signerCertificateSha256)
            assertEquals(2, plan.apks.size)
            assertEquals(base.length() + split.length(), plan.totalBytes)
            assertTrue(plan.blockers.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fileChangedAfterBuildVerificationIsBlocked() {
        val root = Files.createTempDirectory("modkit-install-stale-").toFile()
        try {
            val base = File(root, "base.apk").apply {
                writeText("signed-base")
            }
            val built = built("base.apk", base, SIGNER)
            base.appendText("-changed")

            val plan = RepackedRuntimeInstallPlanner.plan(
                build = buildResult(listOf(built)),
                cancellation = AtomicCancellationSignal(),
            )

            assertFalse(plan.ready)
            assertTrue(
                plan.blockers.any {
                    "changed after build verification" in it
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mixedSignerApkSetFailsClosed() {
        val root = Files.createTempDirectory("modkit-install-signers-").toFile()
        try {
            val base = File(root, "base.apk").apply {
                writeText("signed-base")
            }
            val split = File(root, "split.apk").apply {
                writeText("signed-split")
            }

            val plan = RepackedRuntimeInstallPlanner.plan(
                build = buildResult(
                    listOf(
                        built("base.apk", base, SIGNER),
                        built("split.apk", split, OTHER_SIGNER),
                    ),
                ),
                cancellation = AtomicCancellationSignal(),
            )

            assertFalse(plan.ready)
            assertTrue(
                plan.blockers.any {
                    "signer set differs" in it
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun verifiedPatchedApkSetUsesTheSameSecureInstallFlow() {
        val root = Files.createTempDirectory("modkit-verified-install-").toFile()
        try {
            val base = File(root, "base.apk").apply {
                writeText("modkit-signed-base")
            }
            val split = File(root, "split.apk").apply {
                writeText("modkit-signed-split")
            }
            val files = listOf(base, split).map { file ->
                BuiltApkFile(
                    file = file,
                    sha256 = sha256(file.readBytes()),
                    alignment = ZipAlignmentVerification(
                        verified = true,
                        records = emptyList(),
                        blockers = emptyList(),
                    ),
                    signature = ApkSignatureVerification(
                        verified = true,
                        v1 = true,
                        v2 = true,
                        v3 = false,
                        v31 = false,
                        signerCertificateSha256 = listOf(SIGNER),
                        warnings = emptyList(),
                        errors = emptyList(),
                    ),
                )
            }
            val result = verifiedBuild(files, root)
            val plan = RepackedRuntimeInstallPlanner.plan(
                build = result,
                cancellation = AtomicCancellationSignal(),
            )
            assertTrue(plan.ready)
            assertEquals(2, plan.apks.size)
            assertEquals(PACKAGE, plan.packageName)
            assertEquals(base.length() + split.length(), plan.totalBytes)

            split.appendText("-tampered")
            val stale = RepackedRuntimeInstallPlanner.plan(
                build = result,
                cancellation = AtomicCancellationSignal(),
            )
            assertFalse(stale.ready)
            assertTrue(stale.blockers.any {
                "changed since build" in it
            })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun verifiedBuild(
        files: List<BuiltApkFile>,
        root: File,
    ) = VerifiedBuildResult(
        artifactSha256 = ARTIFACT_SHA,
        files = files,
        mutationDiffs = emptyList(),
        mutationDiffVerification = MutationDiffVerification(
            verified = true, blockers = emptyList(),
        ),
        installability = InstallabilityVerification(
            verified = true,
            packageName = PACKAGE,
            files = emptyList(),
            blockers = emptyList(),
        ),
        signerAlias = "MODKIT",
        signerCertificateSha256 = listOf(SIGNER),
        postBuildAnalysis = FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = ARTIFACT_SHA,
                sources = listOf(
                    ArtifactSource("base.apk", 1, ARTIFACT_SHA),
                ),
                entries = emptyList(),
                detectedAbis = emptySet(),
                runtimeProfiles = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(
                emptyList(), emptyList(),
            ),
            elapsedMs = 0,
        ),
        reportFile = File(root, "report.txt"),
        builtAtEpochMs = 1,
    )

    private fun buildResult(
        apks: List<RepackedRuntimeBuiltApk>,
    ) = RepackedRuntimeBuildResult(
        artifactSha256 = ARTIFACT_SHA,
        packageName = PACKAGE,
        signedApks = apks,
        installability = InstallabilityVerification(
            verified = true,
            packageName = PACKAGE,
            files = emptyList(),
            blockers = emptyList(),
        ),
        reportPath = "/tmp/report.txt",
        signerAlias = "MODKIT",
        completedAtEpochMs = 1,
    )

    private fun built(
        displayName: String,
        file: File,
        signer: String,
    ): RepackedRuntimeBuiltApk {
        val sha = sha256(file.readBytes())
        return RepackedRuntimeBuiltApk(
            sourceDisplayName = displayName,
            sanitizedSha256 = sha,
            alignedPath = file.absolutePath,
            signedPath = file.absolutePath,
            signedSha256 = sha,
            alignment = ZipAlignmentVerification(
                verified = true,
                records = emptyList(),
                blockers = emptyList(),
            ),
            signature = ApkSignatureVerification(
                verified = true,
                v1 = true,
                v2 = true,
                v3 = false,
                v31 = false,
                signerCertificateSha256 = listOf(signer),
                warnings = emptyList(),
                errors = emptyList(),
            ),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

    companion object {
        private const val PACKAGE = "com.example.target"
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SIGNER =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val OTHER_SIGNER =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
