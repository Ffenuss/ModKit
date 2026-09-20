package io.github.ffenuss.modkit.patch

import java.nio.file.Files

import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.analysis.WorkspaceSource
import io.github.ffenuss.modkit.domain.ProofLevel
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveMutationApplierTest {
    @Test
    fun nativePatchRewritesOnlyRequestedRangeAndStripsOldV1Signature() {
        val root = Files.createTempDirectory("modkit-archive-apply-").toFile()
        try {
            val source = File(root, "base.apk")
            val library = ByteArray(16) { it.toByte() }
            createZip(
                source,
                mapOf(
                    "lib/arm64-v8a/libil2cpp.so" to library,
                    "assets/keep.txt" to "keep".toByteArray(),
                    "META-INF/MANIFEST.MF" to "manifest".toByteArray(),
                    "META-INF/CERT.SF" to "sig".toByteArray(),
                ),
            )

            val replacementBytes = byteArrayOf(9, 9, 9, 9)
            val replacement = File(root, "replacement.bin").apply {
                writeBytes(replacementBytes)
            }
            val target = evidenceTarget(fileOffset = 4)
            val preparation = preparation(target)
            val request = MutationRequest(
                id = "native-1",
                artifactSha256 = ARTIFACT_SHA,
                targetId = target.id,
                kind = MutationKind.NATIVE_IN_PLACE_BYTES,
                expectedOriginalSha256 = sha256(library.copyOfRange(4, 8)),
                expectedOriginalSize = 4,
                replacement = MutationPayloadRef(
                    sha256 = sha256(replacementBytes),
                    size = replacementBytes.size.toLong(),
                    storagePath = replacement.absolutePath,
                ),
            )
            val preflight = MutationPreflightEngine.validate(
                preparation,
                listOf(request),
            )
            assertTrue(preflight.readyForApply)

            val workspace = AnalysisWorkspace(
                index = ArtifactIndex(
                    artifactSha256 = ARTIFACT_SHA,
                    sources = listOf(
                        ArtifactSource(
                            displayName = "base.apk",
                            size = source.length(),
                            sha256 = "b".repeat(64),
                        ),
                    ),
                    entries = emptyList(),
                ),
                sources = listOf(
                    WorkspaceSource(
                        descriptor = ArtifactSource(
                            displayName = "base.apk",
                            size = source.length(),
                            sha256 = "b".repeat(64),
                        ),
                        file = source,
                    ),
                ),
            )

            val result = ArchiveMutationApplier.apply(
                workspace = workspace,
                preflight = preflight,
                outputDir = File(root, "out"),
                cancellation = NeverCancelled,
                progress = NoProgress,
            )

            val output = result.outputFiles.single()
            assertTrue(output.isFile)
            ZipFile(output).use { zip ->
                val patched = zip.getInputStream(
                    zip.getEntry("lib/arm64-v8a/libil2cpp.so"),
                ).readBytes()
                val expected = library.copyOf().also {
                    replacementBytes.copyInto(it, destinationOffset = 4)
                }
                assertArrayEquals(expected, patched)

                val kept = zip.getInputStream(
                    zip.getEntry("assets/keep.txt"),
                ).readBytes()
                assertArrayEquals("keep".toByteArray(), kept)

                assertEquals(null, zip.getEntry("META-INF/MANIFEST.MF"))
                assertEquals(null, zip.getEntry("META-INF/CERT.SF"))
            }

            ZipFile(source).use { zip ->
                val original = zip.getInputStream(
                    zip.getEntry("lib/arm64-v8a/libil2cpp.so"),
                ).readBytes()
                assertArrayEquals(library, original)
            }

            assertEquals(1, result.diffs.size)
            assertEquals(4L, result.diffs.single().fileOffset)
            assertEquals(4L, result.diffs.single().length)
            assertTrue(
                result.strippedSignatureEntries.any {
                    it.endsWith("META-INF/CERT.SF")
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun apkSetRewritesUnmodifiedSplitAndStripsOldSignatureBeforeResign() {
        val root = Files.createTempDirectory("modkit-archive-splits-").toFile()
        try {
            val base = File(root, "base.apk")
            val split = File(root, "split_config.arm64_v8a.apk")
            val library = ByteArray(16) { it.toByte() }
            createZip(
                base,
                mapOf(
                    "AndroidManifest.xml" to byteArrayOf(1),
                    "assets/base.txt" to "base-unchanged".toByteArray(),
                    "META-INF/CERT.SF" to "base-signature".toByteArray(),
                ),
            )
            createZip(
                split,
                mapOf(
                    "AndroidManifest.xml" to byteArrayOf(2),
                    "lib/arm64-v8a/libil2cpp.so" to library,
                    "assets/split.txt" to "unchanged".toByteArray(),
                    "META-INF/CERT.SF" to "split-signature".toByteArray(),
                ),
            )

            val replacementBytes = byteArrayOf(7, 7, 7, 7)
            val replacement = File(root, "replacement.bin").apply {
                writeBytes(replacementBytes)
            }
            val target = evidenceTarget(
                fileOffset = 4,
                artifact =
                    "split_config.arm64_v8a.apk:" +
                        "lib/arm64-v8a/libil2cpp.so",
            )
            val request = MutationRequest(
                id = "native-split-test",
                artifactSha256 = ARTIFACT_SHA,
                targetId = target.id,
                kind = MutationKind.NATIVE_IN_PLACE_BYTES,
                expectedOriginalSha256 = sha256(library.copyOfRange(4, 8)),
                expectedOriginalSize = 4,
                replacement = MutationPayloadRef(
                    sha256 = sha256(replacementBytes),
                    size = 4,
                    storagePath = replacement.absolutePath,
                ),
            )
            val preflight = MutationPreflightEngine.validate(
                preparation(target),
                listOf(request),
            )
            assertTrue(preflight.readyForApply)

            val baseDescriptor = ArtifactSource("base.apk", base.length(), "b".repeat(64))
            val splitDescriptor = ArtifactSource(
                "split_config.arm64_v8a.apk",
                split.length(),
                "c".repeat(64),
            )
            val workspace = AnalysisWorkspace(
                index = ArtifactIndex(
                    artifactSha256 = ARTIFACT_SHA,
                    sources = listOf(baseDescriptor, splitDescriptor),
                    entries = emptyList(),
                ),
                sources = listOf(
                    WorkspaceSource(baseDescriptor, base),
                    WorkspaceSource(splitDescriptor, split),
                ),
            )

            val result = ArchiveMutationApplier.apply(
                workspace = workspace,
                preflight = preflight,
                outputDir = File(root, "out"),
                cancellation = NeverCancelled,
                progress = NoProgress,
            )

            assertEquals(2, result.outputFiles.size)
            val outputSplit = result.outputFiles.single {
                it.name == "split_config.arm64_v8a.apk"
            }
            ZipFile(outputSplit).use { zip ->
                assertEquals(null, zip.getEntry("META-INF/CERT.SF"))
                assertArrayEquals(
                    "unchanged".toByteArray(),
                    zip.getInputStream(zip.getEntry("assets/split.txt")).readBytes(),
                )
                val patched = zip.getInputStream(
                    zip.getEntry("lib/arm64-v8a/libil2cpp.so"),
                ).readBytes()
                val expected = library.copyOf().also {
                    replacementBytes.copyInto(it, destinationOffset = 4)
                }
                assertArrayEquals(expected, patched)
            }
            val outputBase = result.outputFiles.single {
                it.name == "base.apk"
            }
            ZipFile(outputBase).use { zip ->
                assertEquals(null, zip.getEntry("META-INF/CERT.SF"))
                assertArrayEquals(
                    "base-unchanged".toByteArray(),
                    zip.getInputStream(zip.getEntry("assets/base.txt")).readBytes(),
                )
                assertEquals(
                    null,
                    zip.getEntry("lib/arm64-v8a/libil2cpp.so"),
                )
            }
            assertEquals(
                "split_config.arm64_v8a.apk",
                result.diffs.single().container,
            )
            assertEquals(
                "lib/arm64-v8a/libil2cpp.so",
                result.diffs.single().entryPath,
            )
            assertTrue(
                result.strippedSignatureEntries.any {
                    it == "split_config.arm64_v8a.apk:META-INF/CERT.SF"
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun changedOriginalRangeAbortsBeforeProducingStagingApk() {
        val root = Files.createTempDirectory("modkit-archive-block-").toFile()
        try {
            val source = File(root, "base.apk")
            val library = ByteArray(12) { (it + 1).toByte() }
            createZip(
                source,
                mapOf("lib/arm64-v8a/libil2cpp.so" to library),
            )
            val replacement = File(root, "replacement.bin").apply {
                writeBytes(byteArrayOf(1, 1, 1, 1))
            }
            val target = evidenceTarget(fileOffset = 2)
            val preparation = preparation(target)
            val request = MutationRequest(
                id = "native-1",
                artifactSha256 = ARTIFACT_SHA,
                targetId = target.id,
                kind = MutationKind.NATIVE_IN_PLACE_BYTES,
                expectedOriginalSha256 = "f".repeat(64),
                expectedOriginalSize = 4,
                replacement = MutationPayloadRef(
                    sha256 = sha256(replacement.readBytes()),
                    size = 4,
                    storagePath = replacement.absolutePath,
                ),
            )
            val preflight = MutationPreflightEngine.validate(
                preparation,
                listOf(request),
            )
            assertTrue(preflight.readyForApply)

            val descriptor = ArtifactSource(
                displayName = "base.apk",
                size = source.length(),
                sha256 = "c".repeat(64),
            )
            val workspace = AnalysisWorkspace(
                index = ArtifactIndex(
                    artifactSha256 = ARTIFACT_SHA,
                    sources = listOf(descriptor),
                    entries = emptyList(),
                ),
                sources = listOf(WorkspaceSource(descriptor, source)),
            )
            val outputDir = File(root, "out")

            val failure = runCatching {
                ArchiveMutationApplier.apply(
                    workspace = workspace,
                    preflight = preflight,
                    outputDir = outputDir,
                    cancellation = NeverCancelled,
                    progress = NoProgress,
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertFalse(File(outputDir, "base.apk").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun evidenceTarget(
        fileOffset: Long,
        artifact: String = "base.apk:lib/arm64-v8a/libil2cpp.so",
    ) = EvidenceTarget(
        id = "target-1",
        runtimeId = "unity_il2cpp",
        kind = EvidenceTargetKind.METHOD,
        displayName = "Game.Player.Hit",
        artifact = artifact,
        abi = "arm64-v8a",
        declaringType = "Game.Player",
        memberName = "Hit",
        metadataToken = 0x06000001,
        rva = 0x1000,
        binaryVirtualAddress = 0x70001000,
        runtimeVirtualAddress = null,
        fileOffset = fileOffset,
        proofLevel = ProofLevel.EXACT_BINARY,
        userStatus = UserFindingStatus.CONFIRMED,
        blockers = emptyList(),
        facts = emptyList(),
    )

    private fun preparation(target: EvidenceTarget) = PatchPreparationPlan(
        artifactSha256 = ARTIFACT_SHA,
        sourceShaVerified = true,
        preparedAtEpochMs = 1,
        targets = listOf(
            PreparedTarget(
                target = target,
                status = PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE,
                blockers = emptyList(),
            ),
        ),
        globalBlockers = emptyList(),
    )

    private fun createZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private object NeverCancelled : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }

    private object NoProgress : ProgressSink {
        override fun publish(progress: io.github.ffenuss.modkit.domain.EngineProgress) = Unit
    }

    companion object {
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
