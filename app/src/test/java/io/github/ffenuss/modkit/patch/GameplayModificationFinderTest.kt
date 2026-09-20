package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameplayModificationFinderTest {
    @Test
    fun proposesConcreteVoidDamageMutationWithoutClaimingGodMode() {
        val target = target(
            token = 0x06000001,
            name = "TakeDamage",
            offset = 0x400,
        )
        val result = result(
            target = target,
            returnKind = Il2CppNativeReturnKind.VOID,
        )

        val opportunity = GameplayModificationFinder.find(
            result = result,
            preparation = preparation(target),
        ).single()

        assertEquals(
            GameplayModificationCategory.DAMAGE,
            opportunity.category,
        )
        assertEquals(
            GameplayMutationAction.SKIP_METHOD,
            opportunity.action,
        )
        assertTrue(opportunity.selectable)
        assertEquals(
            "C0 03 5F D6",
            opportunity.replacementHex,
        )
        assertTrue(opportunity.title.contains("TakeDamage"))
        assertFalse(opportunity.title.contains("бессмерт", ignoreCase = true))
    }

    @Test
    fun proposesBooleanInvulnerabilityFlagOnlyWhenReturnTypeIsProven() {
        val target = target(
            token = 0x06000002,
            name = "get_IsInvincible",
            offset = 0x500,
        )
        val result = result(
            target = target,
            returnKind = Il2CppNativeReturnKind.BOOLEAN,
        )

        val opportunity = GameplayModificationFinder.find(
            result = result,
            preparation = preparation(target),
        ).single()

        assertEquals(
            GameplayMutationAction.FORCE_TRUE,
            opportunity.action,
        )
        assertTrue(opportunity.selectable)
        assertEquals(
            "20 00 80 D2 C0 03 5F D6",
            opportunity.replacementHex,
        )
    }

    @Test
    fun speedFloatIsDiscoveredButNotAutoPatchedYet() {
        val target = target(
            token = 0x06000003,
            name = "get_MoveSpeed",
            offset = 0x600,
        )
        val result = result(
            target = target,
            returnKind = Il2CppNativeReturnKind.FLOATING_POINT,
        )

        val opportunity = GameplayModificationFinder.find(
            result = result,
            preparation = preparation(target),
        ).single()

        assertEquals(
            GameplayModificationCategory.MOVEMENT,
            opportunity.category,
        )
        assertEquals(
            GameplayMutationAction.DISCOVERY_ONLY,
            opportunity.action,
        )
        assertFalse(opportunity.selectable)
        assertTrue(
            opportunity.blocker.orEmpty()
                .contains("float/double"),
        )
    }

    @Test
    fun sharedNativeBodyBlocksAutomaticSuggestion() {
        val first = target(
            token = 0x06000004,
            name = "TakeDamage",
            offset = 0x700,
        )
        val second = target(
            token = 0x06000005,
            name = "OnDamage",
            offset = 0x700,
        )
        val result = result(
            target = first,
            returnKind = Il2CppNativeReturnKind.VOID,
            extraTarget = second,
        )
        val plan = PatchPreparationPlan(
            artifactSha256 = SHA,
            sourceShaVerified = true,
            preparedAtEpochMs = 1,
            targets = listOf(
                prepared(first),
                prepared(second),
            ),
            globalBlockers = emptyList(),
        )

        val opportunities = GameplayModificationFinder.find(
            result = result,
            preparation = plan,
        )

        assertEquals(2, opportunities.size)
        assertTrue(opportunities.all { !it.selectable })
        assertTrue(
            opportunities.all {
                it.blocker.orEmpty().contains("общий")
            },
        )
    }

    @Test
    fun purchaseAndPaymentSurfacesAreNotOfferedAsAutoMods() {
        val target = target(
            token = 0x06000006,
            name = "ValidatePurchaseReceipt",
            offset = 0x800,
        )
        val result = result(
            target = target,
            returnKind = Il2CppNativeReturnKind.BOOLEAN,
        )

        assertTrue(
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).isEmpty(),
        )
    }

    private fun result(
        target: EvidenceTarget,
        returnKind: Il2CppNativeReturnKind,
        extraTarget: EvidenceTarget? = null,
    ): FastAnalysisResult {
        val allTargets = listOfNotNull(target, extraTarget)
        val bindings = allTargets.map {
            Il2CppMethodBinaryBinding(
                methodIndex = (it.metadataToken!! and 0xffff).toInt(),
                managedIdentity = it.displayName,
                metadataToken = it.metadataToken,
                imageName = "Assembly-CSharp.dll",
                moduleName = "Assembly-CSharp.dll",
                slotIndex = 0,
                functionVirtualAddress =
                    0x100000 + requireNotNull(it.fileOffset),
                functionFileOffset = it.fileOffset,
                returnTypeIndex = 1,
                returnKind = returnKind,
                returnTypeProof = "test",
            )
        }
        val index = ArtifactIndex(
            artifactSha256 = SHA,
            sources =
                listOf(
                    ArtifactSource(
                        "split_config.arm64_v8a.apk",
                        1024,
                        "b".repeat(64),
                    ),
                ),
            entries = emptyList(),
            detectedAbis = setOf("arm64-v8a"),
            runtimeProfiles = emptyList(),
        )
        return FastAnalysisResult(
            index = index,
            routingPlan = EngineRoutingPlan(
                emptyList(),
                emptyList(),
            ),
            elapsedMs = 1,
            il2cppBinaryBinding =
                Il2CppBinaryBindingResult(
                    evidence =
                        listOf(
                            Il2CppBinaryEvidence(
                                libraryEntry = ARTIFACT,
                                machine = 183,
                                pointerSize = 8,
                                relativeRelocationCount = 1,
                                codeRegistrationVirtualAddress = null,
                                metadataRegistrationVirtualAddress = 0x2000,
                                codegenRegisterVirtualAddress = null,
                                moduleArrayDiscovery = "TEST",
                                modules = emptyList(),
                                bindings = bindings,
                                blockers = emptyList(),
                            ),
                        ),
                    exactBindingCount = bindings.size,
                    warnings = emptyList(),
                ),
            evidenceGraph =
                EvidenceGraph(
                    artifactSha256 = SHA,
                    targets = allTargets,
                ),
        )
    }

    private fun preparation(
        target: EvidenceTarget,
    ) = PatchPreparationPlan(
        artifactSha256 = SHA,
        sourceShaVerified = true,
        preparedAtEpochMs = 1,
        targets = listOf(prepared(target)),
        globalBlockers = emptyList(),
    )

    private fun prepared(
        target: EvidenceTarget,
    ) = PreparedTarget(
        target = target,
        status =
            PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE,
        blockers = emptyList(),
    )

    private fun target(
        token: Long,
        name: String,
        offset: Long,
    ) = EvidenceTarget(
        id =
            "il2cpp:method:Assembly-CSharp.dll:" +
                token.toString(16) +
                ":Assembly-CSharp.dll",
        runtimeId = "unity_il2cpp",
        kind = EvidenceTargetKind.METHOD,
        displayName = "Game.Player." + name,
        artifact = ARTIFACT,
        abi = "arm64-v8a",
        declaringType = "Game.Player",
        memberName = name,
        metadataToken = token,
        rva = null,
        binaryVirtualAddress = 0x100000 + offset,
        runtimeVirtualAddress = null,
        fileOffset = offset,
        proofLevel = ProofLevel.EXACT_BINARY,
        userStatus = UserFindingStatus.CONFIRMED,
        blockers = emptyList(),
        facts = emptyList(),
    )

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val ARTIFACT =
            "split_config.arm64_v8a.apk:lib/arm64-v8a/libil2cpp.so"
    }
}
