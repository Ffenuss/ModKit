package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.Il2CppBinaryBindingResult
import io.github.ffenuss.modkit.analysis.Il2CppBinaryEvidence
import io.github.ffenuss.modkit.analysis.Il2CppMethodBinaryBinding
import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItemMode
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeGameplayTestMenuBuilderTest {
    @Test
    fun emitsLocalExactPatchAndSensitiveInfoOnly() {
        val root =
            Files.createTempDirectory(
                "modkit-runtime-menu-",
            ).toFile()
        try {
            val speed =
                target(
                    token = 0x06000001,
                    name = "get_MoveSpeed",
                    offset = 0x100,
                    declaringType =
                        "Game.PlayerMovement",
                )
            val gacha =
                target(
                    token = 0x06000002,
                    name = "get_SummonResult",
                    offset = 0x200,
                    declaringType =
                        "Game.GachaService",
                )
            val result =
                FastAnalysisResult(
                    index =
                        ArtifactIndex(
                            artifactSha256 = SHA,
                            sources = emptyList(),
                            entries = emptyList(),
                            detectedAbis =
                                setOf("arm64-v8a"),
                            runtimeProfiles =
                                emptyList(),
                        ),
                    routingPlan =
                        EngineRoutingPlan(
                            emptyList(),
                            emptyList(),
                        ),
                    elapsedMs = 1,
                    il2cppBinaryBinding =
                        Il2CppBinaryBindingResult(
                            evidence =
                                listOf(
                                    Il2CppBinaryEvidence(
                                        libraryEntry =
                                            ARTIFACT,
                                        machine = 183,
                                        pointerSize = 8,
                                        relativeRelocationCount =
                                            1,
                                        codeRegistrationVirtualAddress =
                                            null,
                                        metadataRegistrationVirtualAddress =
                                            0x1000,
                                        codegenRegisterVirtualAddress =
                                            null,
                                        moduleArrayDiscovery =
                                            "TEST",
                                        modules =
                                            emptyList(),
                                        bindings =
                                            listOf(
                                                binding(
                                                    speed,
                                                    Il2CppNativeReturnKind
                                                        .FLOAT32,
                                                ),
                                                binding(
                                                    gacha,
                                                    Il2CppNativeReturnKind
                                                        .POINTER_OR_REFERENCE,
                                                ),
                                            ),
                                        blockers =
                                            emptyList(),
                                    ),
                                ),
                            exactBindingCount = 2,
                            warnings = emptyList(),
                        ),
                    evidenceGraph =
                        EvidenceGraph(
                            artifactSha256 = SHA,
                            targets =
                                listOf(
                                    speed,
                                    gacha,
                                ),
                        ),
                )
            val preparation =
                PatchPreparationPlan(
                    artifactSha256 = SHA,
                    sourceShaVerified = true,
                    preparedAtEpochMs = 1,
                    targets =
                        listOf(
                            prepared(speed),
                            prepared(gacha),
                        ),
                    globalBlockers =
                        emptyList(),
                )

            val analysisRoot =
                root.resolve("analysis-results")
            val library =
                analysisRoot.resolve(
                    SHA +
                        "/il2cpp/native/" +
                        "arm64-v8a-libil2cpp.so",
                )
            library.parentFile.mkdirs()
            RandomAccessFile(
                library,
                "rw",
            ).use { raf ->
                raf.setLength(0x400)
                raf.seek(0x100)
                raf.write(
                    byteArrayOf(
                        0x1f,
                        0x20,
                        0x03,
                        0xd5.toByte(),
                        0x1f,
                        0x20,
                        0x03,
                        0xd5.toByte(),
                    ),
                )
            }

            val menu =
                RuntimeGameplayTestMenuBuilder.build(
                    result = result,
                    preparation = preparation,
                    analysisResultsRoot =
                        analysisRoot,
                    stagingRoot =
                        root.resolve("staging"),
                )

            val patch =
                menu.items.single {
                    it.mode ==
                        RepackedRuntimeTestMenuItemMode
                            .PATCH
                }
            assertTrue(
                patch.label.contains(
                    "MoveSpeed",
                    ignoreCase = true,
                ),
            )
            assertEquals(
                "libil2cpp.so",
                patch.moduleName,
            )
            assertEquals(
                "00 10 20 1E C0 03 5F D6",
                patch.replacementHex,
            )

            val info =
                menu.items.single {
                    it.mode ==
                        RepackedRuntimeTestMenuItemMode
                            .INFO
                }
            assertTrue(
                info.label.contains(
                    "server reward",
                    ignoreCase = true,
                ),
            )
            assertEquals("", info.replacementHex)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun binding(
        target: EvidenceTarget,
        returnKind: Il2CppNativeReturnKind,
    ) =
        Il2CppMethodBinaryBinding(
            methodIndex =
                requireNotNull(target.metadataToken)
                    .toInt(),
            managedIdentity =
                target.displayName,
            metadataToken =
                requireNotNull(target.metadataToken),
            imageName =
                "Assembly-CSharp.dll",
            moduleName =
                "Assembly-CSharp.dll",
            slotIndex = 0,
            functionVirtualAddress =
                requireNotNull(
                    target.fileOffset,
                ),
            functionFileOffset =
                target.fileOffset,
            returnTypeIndex = 1,
            returnKind = returnKind,
            returnTypeProof = "test",
        )

    private fun target(
        token: Long,
        name: String,
        offset: Long,
        declaringType: String,
    ) =
        EvidenceTarget(
            id =
                "il2cpp:method:Assembly-CSharp.dll:" +
                    token.toString(16) +
                    ":Assembly-CSharp.dll:" +
                    name,
            runtimeId = "unity_il2cpp",
            kind =
                EvidenceTargetKind.METHOD,
            displayName =
                declaringType + "." + name,
            artifact = ARTIFACT,
            abi = "arm64-v8a",
            declaringType = declaringType,
            memberName = name,
            metadataToken = token,
            rva = null,
            binaryVirtualAddress =
                offset,
            runtimeVirtualAddress = null,
            fileOffset = offset,
            proofLevel =
                ProofLevel.EXACT_BINARY,
            userStatus =
                UserFindingStatus.CONFIRMED,
            blockers = emptyList(),
            facts = emptyList(),
        )

    private fun prepared(
        target: EvidenceTarget,
    ) =
        PreparedTarget(
            target = target,
            status =
                PreparationTargetStatus
                    .CONFIRMED_NEEDS_CHANGE,
            blockers = emptyList(),
        )

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val ARTIFACT =
            "split_config.arm64_v8a.apk:lib/arm64-v8a/libil2cpp.so"
    }
}
