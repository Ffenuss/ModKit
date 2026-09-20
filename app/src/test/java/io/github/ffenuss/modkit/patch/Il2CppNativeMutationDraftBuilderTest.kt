package io.github.ffenuss.modkit.patch

import java.nio.file.Files

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppNativeMutationDraftBuilderTest {
    @Test
    fun buildsDraftFromExactBinaryTargetAndExtractedLibrary() {
        val root = Files.createTempDirectory("modkit-native-draft-").toFile()
        try {
            val analysisRoot = File(root, "analysis-results")
            val nativeDir = File(
                analysisRoot,
                SHA + "/il2cpp/native",
            ).apply { mkdirs() }
            val library = File(nativeDir, "arm64-v8a-libil2cpp.so")
            val bytes = ByteArray(32) { it.toByte() }
            library.writeBytes(bytes)

            val result = analysisResult(
                proof = ProofLevel.EXACT_BINARY,
                status = UserFindingStatus.CONFIRMED,
            )
            val draft = Il2CppNativeMutationDraftBuilder.build(
                result = result,
                targetId = TARGET_ID,
                replacementHex = "AA BB CC DD",
                analysisResultsRoot = analysisRoot,
                stagingRoot = File(root, "staging"),
            )

            assertEquals("08 09 0A 0B", draft.originalHex)
            assertEquals("AA BB CC DD", draft.replacementHex)
            assertEquals(8L, result.evidenceGraph?.targets?.single()?.fileOffset)
            assertEquals(4L, draft.request.expectedOriginalSize)
            assertEquals(
                sha256(bytes.copyOfRange(8, 12)),
                draft.request.expectedOriginalSha256,
            )
            val payload = File(requireNotNull(draft.request.replacement?.storagePath))
            assertTrue(payload.isFile)
            assertEquals(4L, payload.length())
            assertEquals(
                sha256(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())),
                draft.request.replacement?.sha256,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readsBoundedNativeCodeWindow() {
        val root =
            Files.createTempDirectory(
                "modkit-native-window-",
            ).toFile()
        try {
            val analysisRoot =
                File(root, "analysis-results")
            val nativeDir =
                File(
                    analysisRoot,
                    SHA + "/il2cpp/native",
                ).apply { mkdirs() }
            val bytes =
                ByteArray(32) { it.toByte() }
            File(
                nativeDir,
                "arm64-v8a-libil2cpp.so",
            ).writeBytes(bytes)

            val window =
                Il2CppNativeMutationDraftBuilder
                    .readCodeWindow(
                        result =
                            analysisResult(
                                proof =
                                    ProofLevel.EXACT_BINARY,
                                status =
                                    UserFindingStatus.CONFIRMED,
                            ),
                        targetId = TARGET_ID,
                        analysisResultsRoot =
                            analysisRoot,
                        maxBytes = 16,
                    )

            assertEquals(8L, window.fileOffset)
            assertEquals(16, window.byteLength)
            assertEquals(
                "08 09 0A 0B 0C 0D 0E 0F " +
                    "10 11 12 13 14 15 16 17",
                window.originalHex,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesMetadataOnlyTarget() {
        val root = Files.createTempDirectory("modkit-native-draft-block-").toFile()
        try {
            val result = analysisResult(
                proof = ProofLevel.EXACT_METADATA,
                status = UserFindingStatus.CONFIRMING,
            )
            Il2CppNativeMutationDraftBuilder.build(
                result = result,
                targetId = TARGET_ID,
                replacementHex = "00",
                analysisResultsRoot = File(root, "analysis-results"),
                stagingRoot = File(root, "staging"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun hexParserAcceptsCommonSpacingAndPrefix() {
        val bytes = Il2CppNativeMutationDraftBuilder.parseHex(
            "0xAA, 0xbb  CC-DD",
        )
        assertEquals(
            listOf(0xAA, 0xBB, 0xCC, 0xDD),
            bytes.map { it.toInt() and 0xff },
        )
    }

    @Test
    fun refusesUnalignedArm64InstructionPayload() {
        val root =
            Files.createTempDirectory(
                "modkit-native-draft-align-",
            ).toFile()
        try {
            val analysisRoot =
                File(root, "analysis-results")
            val nativeDir =
                File(
                    analysisRoot,
                    SHA + "/il2cpp/native",
                ).apply { mkdirs() }
            File(
                nativeDir,
                "arm64-v8a-libil2cpp.so",
            ).writeBytes(ByteArray(32) { it.toByte() })

            val failure =
                runCatching {
                    Il2CppNativeMutationDraftBuilder.build(
                        result =
                            analysisResult(
                                proof =
                                    ProofLevel.EXACT_BINARY,
                                status =
                                    UserFindingStatus.CONFIRMED,
                            ),
                        targetId = TARGET_ID,
                        replacementHex = "AA BB",
                        analysisResultsRoot =
                            analysisRoot,
                        stagingRoot =
                            File(root, "staging"),
                    )
                }.exceptionOrNull()

            assertTrue(
                failure is IllegalArgumentException,
            )
            assertTrue(
                failure?.message.orEmpty().contains(
                    "4-байтовых",
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesSharedExecutableOffsetForSingleMetadataTarget() {
        val root =
            Files.createTempDirectory(
                "modkit-native-draft-shared-",
            ).toFile()
        try {
            val analysisRoot =
                File(root, "analysis-results")
            val nativeDir =
                File(
                    analysisRoot,
                    SHA + "/il2cpp/native",
                ).apply { mkdirs() }
            File(
                nativeDir,
                "arm64-v8a-libil2cpp.so",
            ).writeBytes(ByteArray(32) { it.toByte() })

            val failure =
                runCatching {
                    Il2CppNativeMutationDraftBuilder.build(
                        result =
                            analysisResult(
                                proof =
                                    ProofLevel.EXACT_BINARY,
                                status =
                                    UserFindingStatus.CONFIRMED,
                                includeSharedAlias = true,
                            ),
                        targetId = TARGET_ID,
                        replacementHex =
                            "C0 03 5F D6",
                        analysisResultsRoot =
                            analysisRoot,
                        stagingRoot =
                            File(root, "staging"),
                    )
                }.exceptionOrNull()

            assertTrue(
                failure is IllegalArgumentException,
            )
            assertTrue(
                failure?.message.orEmpty().contains(
                    "разделяется 2 IL2CPP-методами",
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesMultiInstructionPatchWithoutProvenNextBoundary() {
        val root =
            Files.createTempDirectory(
                "modkit-native-draft-boundary-",
            ).toFile()
        try {
            val analysisRoot =
                File(root, "analysis-results")
            val nativeDir =
                File(
                    analysisRoot,
                    SHA + "/il2cpp/native",
                ).apply { mkdirs() }
            File(
                nativeDir,
                "arm64-v8a-libil2cpp.so",
            ).writeBytes(
                ByteArray(64) {
                    it.toByte()
                },
            )

            val failure =
                runCatching {
                    Il2CppNativeMutationDraftBuilder
                        .build(
                            result =
                                analysisResult(
                                    proof =
                                        ProofLevel.EXACT_BINARY,
                                    status =
                                        UserFindingStatus.CONFIRMED,
                                ),
                            targetId = TARGET_ID,
                            replacementHex =
                                "20 00 80 D2 C0 03 5F D6",
                            analysisResultsRoot =
                                analysisRoot,
                            stagingRoot =
                                File(
                                    root,
                                    "staging",
                                ),
                        )
                }.exceptionOrNull()

            assertTrue(
                failure is IllegalArgumentException,
            )
            assertTrue(
                failure?.message.orEmpty()
                    .contains(
                        "Граница следующего метода",
                    ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun allowsMultiInstructionPatchWithinProvenNextBoundary() {
        val root =
            Files.createTempDirectory(
                "modkit-native-draft-boundary-ok-",
            ).toFile()
        try {
            val analysisRoot =
                File(root, "analysis-results")
            val nativeDir =
                File(
                    analysisRoot,
                    SHA + "/il2cpp/native",
                ).apply { mkdirs() }
            File(
                nativeDir,
                "arm64-v8a-libil2cpp.so",
            ).writeBytes(
                ByteArray(64) {
                    it.toByte()
                },
            )

            val draft =
                Il2CppNativeMutationDraftBuilder
                    .build(
                        result =
                            analysisResult(
                                proof =
                                    ProofLevel.EXACT_BINARY,
                                status =
                                    UserFindingStatus.CONFIRMED,
                                includeNextMethod = true,
                            ),
                        targetId = TARGET_ID,
                        replacementHex =
                            "20 00 80 D2 C0 03 5F D6",
                        analysisResultsRoot =
                            analysisRoot,
                        stagingRoot =
                            File(
                                root,
                                "staging",
                            ),
                    )

            assertEquals(
                8L,
                draft.request
                    .expectedOriginalSize,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesNoOpReplacement() {
        val root =
            Files.createTempDirectory(
                "modkit-native-draft-noop-",
            ).toFile()
        try {
            val analysisRoot =
                File(root, "analysis-results")
            val nativeDir =
                File(
                    analysisRoot,
                    SHA + "/il2cpp/native",
                ).apply { mkdirs() }
            val bytes = ByteArray(32) { it.toByte() }
            File(
                nativeDir,
                "arm64-v8a-libil2cpp.so",
            ).writeBytes(bytes)

            val failure =
                runCatching {
                    Il2CppNativeMutationDraftBuilder.build(
                        result =
                            analysisResult(
                                proof =
                                    ProofLevel.EXACT_BINARY,
                                status =
                                    UserFindingStatus.CONFIRMED,
                            ),
                        targetId = TARGET_ID,
                        replacementHex = "08 09 0A 0B",
                        analysisResultsRoot =
                            analysisRoot,
                        stagingRoot =
                            File(root, "staging"),
                    )
                }.exceptionOrNull()

            assertTrue(
                failure is IllegalArgumentException,
            )
            assertTrue(
                failure?.message.orEmpty().contains(
                    "изменение отсутствует",
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun analysisResult(
        proof: ProofLevel,
        status: UserFindingStatus,
        includeSharedAlias: Boolean = false,
        includeNextMethod: Boolean = false,
    ): FastAnalysisResult {
        val source = ArtifactSource("base.apk", 1, SHA)
        val index = ArtifactIndex(
            artifactSha256 = SHA,
            sources = listOf(source),
            entries = emptyList(),
        )
        val target = EvidenceTarget(
            id = TARGET_ID,
            runtimeId = "unity_il2cpp",
            kind = EvidenceTargetKind.METHOD,
            displayName = "Game.Player.Hit",
            artifact = "base.apk:lib/arm64-v8a/libil2cpp.so",
            abi = "arm64-v8a",
            declaringType = "Game.Player",
            memberName = "Hit",
            metadataToken = 0x06000001,
            rva = 0x1000,
            binaryVirtualAddress = 0x70001000,
            runtimeVirtualAddress = null,
            fileOffset = 8,
            proofLevel = proof,
            userStatus = status,
            blockers = emptyList(),
            facts = emptyList(),
        )
        val targets =
            when {
                includeSharedAlias ->
                    listOf(
                        target,
                        target.copy(
                            id = "target-2",
                            displayName =
                                "Game.Player.Shared",
                            memberName = "Shared",
                            metadataToken =
                                0x06000002,
                        ),
                    )
                includeNextMethod ->
                    listOf(
                        target,
                        target.copy(
                            id = "target-next",
                            displayName =
                                "Game.Player.Next",
                            memberName = "Next",
                            metadataToken =
                                0x06000003,
                            fileOffset = 24,
                            binaryVirtualAddress =
                                0x70001010,
                            rva = 0x1010,
                        ),
                    )
                else ->
                    listOf(target)
            }
        return FastAnalysisResult(
            index = index,
            routingPlan =
                EngineRoutingPlan(
                    emptyList(),
                    emptyList(),
                ),
            elapsedMs = 1,
            evidenceGraph =
                EvidenceGraph(
                    SHA,
                    targets,
                ),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val TARGET_ID = "target-1"
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
