package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.nativecode.AArch64Disassembly

data class Il2CppDirectCallResolution(
    val callSiteAddress: Long,
    val targetAddress: Long,
    val targetId: String?,
    val targetDisplayName: String?,
    val exactIl2CppTarget: Boolean,
)

/**
 * Resolves direct AArch64 BL call targets against the exact IL2CPP method
 * addresses already present in the evidence graph.
 */
object Il2CppArm64CallResolver {
    fun resolveOutgoingCalls(
        result: FastAnalysisResult,
        sourceTarget: EvidenceTarget,
        disassembly: AArch64Disassembly,
    ): List<Il2CppDirectCallResolution> {
        val artifact =
            sourceTarget.artifact
                ?: return emptyList()
        val abi =
            sourceTarget.abi
                ?: return emptyList()

        val exactByAddress =
            result.evidenceGraph
                ?.targets
                .orEmpty()
                .asSequence()
                .filter {
                    it.runtimeId ==
                        "unity_il2cpp" &&
                        it.kind ==
                        EvidenceTargetKind.METHOD &&
                        it.artifact ==
                        artifact &&
                        it.abi == abi &&
                        it.binaryVirtualAddress != null
                }
                .groupBy {
                    requireNotNull(
                        it.binaryVirtualAddress,
                    )
                }

        return disassembly.instructions
            .asSequence()
            .filter {
                it.mnemonic == "bl"
            }
            .mapNotNull {
                instruction ->
                val address =
                    instruction.operands
                        .trim()
                        .removePrefix("0x")
                        .toLongOrNull(16)
                        ?: return@mapNotNull null
                val exact =
                    exactByAddress[address]
                        ?.singleOrNull()
                Il2CppDirectCallResolution(
                    callSiteAddress =
                        instruction.address,
                    targetAddress =
                        address,
                    targetId =
                        exact?.id,
                    targetDisplayName =
                        exact?.displayName,
                    exactIl2CppTarget =
                        exact != null,
                )
            }
            .toList()
    }
}
