package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.ConfirmationRequest
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.WorkspaceSource
import io.github.ffenuss.modkit.domain.ProofLevel
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedTestRuntimeTest {
    @Test
    fun defaultPlanIsFailClosedUntilConcreteExecutorsAreRegistered() {
        val plan = RepackedTestRuntimePlanner.plan(runtimeRequiredResult())

        assertTrue(plan.required)
        assertFalse(plan.readyToBuildTestCopy)
        assertEquals(
            RuntimeEscalationStage.NON_ROOT_RUNTIME,
            plan.fallbackStage,
        )
        assertTrue(
            RepackedRuntimeCapability.SOURCE_COPY in
                plan.registeredCapabilities,
        )
        assertTrue(
            RepackedRuntimeCapability.MANIFEST_IDENTITY_INSPECTION in
                plan.registeredCapabilities,
        )
        assertTrue(
            RepackedRuntimeCapability.OLD_SIGNATURE_REMOVAL in
                plan.registeredCapabilities,
        )
        assertTrue(
            RepackedRuntimeCapability.APK_ALIGNMENT in
                plan.registeredCapabilities,
        )
        assertTrue(
            RepackedRuntimeCapability.APK_SIGNING in
                plan.registeredCapabilities,
        )
        assertTrue(
            RepackedRuntimeCapability.PACKAGE_VERIFY in
                plan.registeredCapabilities,
        )
        assertTrue(
            RepackedRuntimeCapability.BINARY_MANIFEST_REWRITE in
                plan.registeredCapabilities,
        )
        assertTrue(
            RepackedRuntimeCapability.PROBE_PAYLOAD_INJECTION in
                plan.registeredCapabilities,
        )
        assertFalse(
            plan.blockers.any {
                it.code ==
                    "BINARY_MANIFEST_REWRITE_NOT_REGISTERED"
            },
        )
        assertFalse(
            plan.blockers.any {
                it.code ==
                    "PROBE_PAYLOAD_INJECTION_NOT_REGISTERED"
            },
        )
        assertTrue(
            plan.blockers.any {
                it.code ==
                    "RUNTIME_EVIDENCE_CAPTURE_NOT_REGISTERED"
            },
        )
    }

    @Test
    fun planBecomesBuildReadyOnlyWhenEveryRequiredExecutorIsRegistered() {
        val plan = RepackedTestRuntimePlanner.plan(
            result = runtimeRequiredResult(),
            registeredCapabilities =
                RepackedTestRuntimePlanner.requiredCapabilities,
        )

        assertTrue(plan.required)
        assertTrue(plan.readyToBuildTestCopy)
        assertTrue(plan.blockers.isEmpty())
        assertTrue(plan.manifestContract.sourceApkMustRemainUnchanged)
        assertTrue(plan.cleanupRequired)
        assertTrue(
            RuntimeEvidenceObservationKind.RUNTIME_ADDRESS_CONFIRMED in
                plan.evidenceKinds,
        )
    }

    @Test
    fun planDoesNotInventRepackedWorkWhenRuntimeIsNotRequired() {
        val result = FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = ARTIFACT_SHA,
                sources = listOf(
                    ArtifactSource("base.apk", 1, SOURCE_SHA),
                ),
                entries = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
        )

        val plan = RepackedTestRuntimePlanner.plan(
            result = result,
            registeredCapabilities =
                RepackedTestRuntimePlanner.requiredCapabilities,
        )

        assertFalse(plan.required)
        assertFalse(plan.readyToBuildTestCopy)
        assertTrue(
            plan.blockers.any {
                it.code == "REPACKED_RUNTIME_NOT_REQUIRED"
            },
        )
    }

    @Test
    fun sourcePreparationCopiesAndVerifiesWithoutMutatingOriginal() {
        val root = Files.createTempDirectory("modkit-repacked-runtime-").toFile()
        try {
            val source = File(root, "original.apk").apply {
                writeBytes("immutable-apk-source".toByteArray())
            }
            val sourceBytes = source.readBytes()
            val sourceSha = sha256(sourceBytes)
            val workspace = AnalysisWorkspace(
                index = ArtifactIndex(
                    artifactSha256 = ARTIFACT_SHA,
                    sources = listOf(
                        ArtifactSource(
                            displayName = "base.apk",
                            size = source.length(),
                            sha256 = sourceSha,
                        ),
                    ),
                    entries = emptyList(),
                ),
                sources = listOf(
                    WorkspaceSource(
                        descriptor = ArtifactSource(
                            displayName = "base.apk",
                            size = source.length(),
                            sha256 = sourceSha,
                        ),
                        file = source,
                    ),
                ),
            )
            val outputRoot = File(root, "output")

            val prepared = RepackedRuntimeWorkspacePreparer.prepare(
                workspace = workspace,
                outputDir = outputRoot,
                cancellation = AtomicCancellationSignal(),
            )

            assertEquals(sourceBytes.toList(), source.readBytes().toList())
            val copied = File(prepared.sources.single().copiedFilePath)
            assertTrue(copied.isFile)
            assertEquals(sourceSha, prepared.sources.single().sourceSha256)
            assertEquals(sourceSha, prepared.sources.single().copiedSha256)
            assertEquals(sourceBytes.toList(), copied.readBytes().toList())

            assertTrue(
                RepackedRuntimeWorkspacePreparer.cleanup(
                    prepared = prepared,
                    allowedOutputRoot = outputRoot,
                ),
            )
            assertFalse(copied.exists())
            assertTrue(source.isFile)
            assertEquals(sourceBytes.toList(), source.readBytes().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sourcePreparationRejectsDescriptorShaMismatchAndRemovesPartialCopies() {
        val root = Files.createTempDirectory("modkit-repacked-runtime-bad-").toFile()
        try {
            val source = File(root, "original.apk").apply {
                writeText("changed")
            }
            val descriptor = ArtifactSource(
                displayName = "base.apk",
                size = source.length(),
                sha256 = SOURCE_SHA,
            )
            val workspace = AnalysisWorkspace(
                index = ArtifactIndex(
                    artifactSha256 = ARTIFACT_SHA,
                    sources = listOf(descriptor),
                    entries = emptyList(),
                ),
                sources = listOf(
                    WorkspaceSource(descriptor, source),
                ),
            )
            val outputRoot = File(root, "output")

            val failed = runCatching {
                RepackedRuntimeWorkspacePreparer.prepare(
                    workspace = workspace,
                    outputDir = outputRoot,
                    cancellation = AtomicCancellationSignal(),
                )
            }.isFailure

            assertTrue(failed)
            val preparedRoot = File(
                outputRoot,
                ARTIFACT_SHA + "/repacked-test/source-copy",
            )
            assertFalse(preparedRoot.exists())
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runtimeRequiredResult(): FastAnalysisResult =
        FastAnalysisResult(
            index = ArtifactIndex(
                artifactSha256 = ARTIFACT_SHA,
                sources = listOf(
                    ArtifactSource("base.apk", 1, SOURCE_SHA),
                ),
                entries = emptyList(),
            ),
            routingPlan = EngineRoutingPlan(emptyList(), emptyList()),
            elapsedMs = 1,
            confirmationQueue = listOf(
                ConfirmationRequest(
                    targetId = "target",
                    engineId = "runtime.il2cpp-confirm",
                    requiredProofLevel = ProofLevel.RUNTIME_CONFIRMED,
                    reason = "Static binding needs runtime confirmation.",
                    availableNow = false,
                ),
            ),
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SOURCE_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
