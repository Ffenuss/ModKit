package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.EngineRoutingPlan
import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.analysis.nativecode.AArch64Disassembler
import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppArm64CallResolverTest {
    @Test
    fun resolvesDirectCallToExactMethodAddress() {
        val source = target(
            id = "source",
            name = "Game.Player.Update",
            address = 0x1000,
            token = 1,
        )
        val callee = target(
            id = "callee",
            name = "Game.Player.Tick",
            address = 0x1010,
            token = 2,
        )
        val result =
            FastAnalysisResult(
                index =
                    ArtifactIndex(
                        artifactSha256 = SHA,
                        sources =
                            listOf(
                                ArtifactSource(
                                    "base.apk",
                                    1,
                                    SHA,
                                ),
                            ),
                        entries = emptyList(),
                    ),
                routingPlan =
                    EngineRoutingPlan(
                        emptyList(),
                        emptyList(),
                    ),
                elapsedMs = 1,
                evidenceGraph =
                    EvidenceGraph(
                        SHA,
                        listOf(
                            source,
                            callee,
                        ),
                    ),
            )
        val disassembly =
            AArch64Disassembler.disassemble(
                code =
                    words(
                        0x94000004L,
                        0xD65F03C0L,
                    ),
                startAddress = 0x1000,
            )

        val calls =
            Il2CppArm64CallResolver
                .resolveOutgoingCalls(
                    result = result,
                    sourceTarget =
                        source,
                    disassembly =
                        disassembly,
                )

        assertEquals(1, calls.size)
        assertTrue(
            calls.single()
                .exactIl2CppTarget,
        )
        assertEquals(
            "callee",
            calls.single().targetId,
        )
    }

    private fun target(
        id: String,
        name: String,
        address: Long,
        token: Long,
    ) =
        EvidenceTarget(
            id = id,
            runtimeId = "unity_il2cpp",
            kind = EvidenceTargetKind.METHOD,
            displayName = name,
            artifact = ARTIFACT,
            abi = "arm64-v8a",
            declaringType = "Game.Player",
            memberName =
                name.substringAfterLast('.'),
            metadataToken = token,
            rva = address - 0x1000,
            binaryVirtualAddress =
                address,
            runtimeVirtualAddress = null,
            fileOffset =
                address - 0x1000,
            proofLevel =
                ProofLevel.EXACT_BINARY,
            userStatus =
                UserFindingStatus.CONFIRMED,
            blockers = emptyList(),
            facts = emptyList(),
        )

    private fun words(
        vararg values: Long,
    ): ByteArray =
        ByteArray(values.size * 4)
            .also {
                out ->
                values.forEachIndexed {
                        index,
                        word,
                    ->
                    val base = index * 4
                    out[base] =
                        word.toByte()
                    out[base + 1] =
                        (word ushr 8).toByte()
                    out[base + 2] =
                        (word ushr 16).toByte()
                    out[base + 3] =
                        (word ushr 24).toByte()
                }
            }

    companion object {
        private const val ARTIFACT =
            "base.apk:lib/arm64-v8a/libil2cpp.so"
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
