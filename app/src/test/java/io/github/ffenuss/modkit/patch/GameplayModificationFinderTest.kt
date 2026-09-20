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
import io.github.ffenuss.modkit.analysis.Il2CppFastDumpResult
import io.github.ffenuss.modkit.analysis.Il2CppFieldDefinition
import io.github.ffenuss.modkit.analysis.Il2CppImageDefinition
import io.github.ffenuss.modkit.analysis.Il2CppMetadataModel
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
    fun hiddenLibraryAliasStillBlocksProjectSuggestion() {
        val project =
            target(
                token = 0x06000008,
                name = "TakeDamage",
                offset = 0xa00,
            )
        val hiddenAlias =
            target(
                token = 0x06000009,
                name = "SharedDamageThunk",
                offset = 0xa00,
                imageName = "Plugin.Runtime.dll",
            )
        val result =
            result(
                target = project,
                returnKind = Il2CppNativeReturnKind.VOID,
                extraTarget = hiddenAlias,
            )
        val plan =
            PatchPreparationPlan(
                artifactSha256 = SHA,
                sourceShaVerified = true,
                preparedAtEpochMs = 1,
                targets = listOf(prepared(project)),
                globalBlockers = emptyList(),
            )

        val opportunity =
            GameplayModificationFinder.find(
                result = result,
                preparation = plan,
                projectCodeOnly = true,
            ).single()

        assertFalse(opportunity.selectable)
        assertTrue(
            opportunity.blocker.orEmpty()
                .contains("общий"),
        )
    }

    @Test
    fun ammoConsumptionCanBeOfferedAsConcreteSkipMutation() {
        val target = target(
            token = 0x06000007,
            name = "ConsumeAmmo",
            offset = 0x900,
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
            GameplayModificationCategory.INVENTORY,
            opportunity.category,
        )
        assertEquals(
            GameplayMutationAction.SKIP_METHOD,
            opportunity.action,
        )
        assertTrue(opportunity.selectable)
    }

    @Test
    fun categoryCapKeepsSuggestionListDiverse() {
        val damageNames =
            listOf(
                "TakeDamage",
                "ReceiveDamage",
                "ApplyDamage",
                "OnDamage",
                "Hurt",
                "ApplyHurt",
            )
        val damageTargets =
            damageNames.mapIndexed { index, name ->
                target(
                    token = 0x06000100L + index,
                    name = name,
                    offset = 0x1000L + index * 4L,
                )
            }
        val movement =
            target(
                token = 0x06000200,
                name = "get_MoveSpeed",
                offset = 0x2000,
            )
        val allTargets = damageTargets + movement
        val bindings =
            allTargets.map { item ->
                Il2CppMethodBinaryBinding(
                    methodIndex = 0,
                    managedIdentity = item.displayName,
                    metadataToken = requireNotNull(item.metadataToken),
                    imageName = "Assembly-CSharp.dll",
                    moduleName = "Assembly-CSharp.dll",
                    slotIndex = 0,
                    functionVirtualAddress =
                        0x100000 + requireNotNull(item.fileOffset),
                    functionFileOffset = item.fileOffset,
                    returnTypeIndex = 1,
                    returnKind =
                        if (item === movement) {
                            Il2CppNativeReturnKind.FLOATING_POINT
                        } else {
                            Il2CppNativeReturnKind.VOID
                        },
                    returnTypeProof = "test",
                )
            }
        val base = result(
            target = damageTargets.first(),
            returnKind = Il2CppNativeReturnKind.VOID,
        )
        val analysis =
            base.copy(
                il2cppBinaryBinding =
                    Il2CppBinaryBindingResult(
                        evidence =
                            listOf(
                                base.il2cppBinaryBinding!!
                                    .evidence.single()
                                    .copy(bindings = bindings),
                            ),
                        exactBindingCount =
                            bindings.size,
                        warnings = emptyList(),
                    ),
                evidenceGraph =
                    EvidenceGraph(
                        artifactSha256 = SHA,
                        targets = allTargets,
                    ),
            )
        val plan =
            PatchPreparationPlan(
                artifactSha256 = SHA,
                sourceShaVerified = true,
                preparedAtEpochMs = 1,
                targets =
                    allTargets.map(::prepared),
                globalBlockers = emptyList(),
            )

        val opportunities =
            GameplayModificationFinder.find(
                result = analysis,
                preparation = plan,
                limit = 24,
                perCategoryLimit = 2,
            )

        assertEquals(
            2,
            opportunities.count {
                it.category ==
                    GameplayModificationCategory.DAMAGE
            },
        )
        assertTrue(
            opportunities.any {
                it.category ==
                    GameplayModificationCategory.MOVEMENT
            },
        )
    }

    @Test
    fun gradientConstructorDoesNotBecomeDeathOrHealthMod() {
        val target =
            target(
                token = 0x06000300,
                name = ".cctor",
                offset = 0x3000,
                declaringType =
                    "Coffee.UIExtensions.UIGradient",
            )
        val result =
            result(
                target = target,
                returnKind = Il2CppNativeReturnKind.VOID,
            )

        assertTrue(
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).isEmpty(),
        )
    }

    @Test
    fun uiRegenPointsDoesNotBecomeHealthRegenerationMod() {
        val target =
            target(
                token = 0x06000301,
                name = "get_regenPoints",
                offset = 0x3010,
                declaringType =
                    "UnityEngine.UI.Extensions.CableCurve",
            )
        val result =
            result(
                target = target,
                returnKind =
                    Il2CppNativeReturnKind.INTEGER,
            )

        assertTrue(
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).isEmpty(),
        )
    }

    @Test
    fun genericJumpCheckIsNotPresentedAsMovementCheat() {
        val target =
            target(
                token = 0x06000302,
                name = "JumpCheck",
                offset = 0x3020,
                declaringType =
                    "BBstudio.BGMCommand",
            )
        val result =
            result(
                target = target,
                returnKind =
                    Il2CppNativeReturnKind.BOOLEAN,
            )

        assertTrue(
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).isEmpty(),
        )
    }

    @Test
    fun proxyCooldownConstructorIsHidden() {
        val target =
            target(
                token = 0x06000303,
                name = ".ctor",
                offset = 0x3030,
                declaringType =
                    "PROXY_AUTO.PROXY_MapObj_GetNpcCooldown_Node",
            )
        val result =
            result(
                target = target,
                returnKind = Il2CppNativeReturnKind.VOID,
            )

        assertTrue(
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).isEmpty(),
        )
    }

    @Test
    fun heroDataFieldsAreExposedAsNonSelectableModelSignals() {
        val target =
            target(
                token = 0x06000320,
                name = "Heartbeat",
                offset = 0x3200,
                declaringType = "Game.Runtime",
            )
        val base =
            result(
                target = target,
                returnKind =
                    Il2CppNativeReturnKind.VOID,
            )
        val metadata =
            Il2CppMetadataModel(
                sizeBytes = 1,
                magicValid = true,
                metadataVersion = 31,
                layoutProfile = "test",
                tableRanges = emptyList(),
                declaredTypeCount = 1,
                declaredMethodCount = 0,
                declaredFieldCount = 4,
                declaredImageCount = 1,
                images =
                    listOf(
                        Il2CppImageDefinition(
                            index = 0,
                            name = "Assembly-CSharp.dll",
                            assemblyIndex = 0,
                            typeStart = 77,
                            typeCount = 2,
                            token = 0x2000001,
                        ),
                    ),
                types = emptyList(),
                methods = emptyList(),
                fields =
                    listOf(
                        Il2CppFieldDefinition(
                            index = 0,
                            declaringTypeIndex = 77,
                            declaringType = "IGame.HeroData",
                            name = "level",
                            typeIndex = 1,
                            token = 0x040043af,
                        ),
                        Il2CppFieldDefinition(
                            index = 1,
                            declaringTypeIndex = 77,
                            declaringType = "IGame.HeroData",
                            name = "rank",
                            typeIndex = 1,
                            token = 0x040043b0,
                        ),
                        Il2CppFieldDefinition(
                            index = 2,
                            declaringTypeIndex = 77,
                            declaringType = "IGame.HeroData",
                            name = "quality",
                            typeIndex = 1,
                            token = 0x040043b1,
                        ),
                        Il2CppFieldDefinition(
                            index = 3,
                            declaringTypeIndex = 78,
                            declaringType = "IGame.QualityManager",
                            name = "deviceLevel",
                            typeIndex = 1,
                            token = 0x040043b2,
                        ),
                    ),
                structuredSupported = true,
                truncated = false,
                warnings = emptyList(),
            )
        val analysis =
            base.copy(
                il2cppFastDump =
                    Il2CppFastDumpResult(
                        metadataEntry =
                            "base.apk:assets/global-metadata.dat",
                        libraryEntries =
                            listOf(
                                "split_config.arm64_v8a.apk:lib/arm64-v8a/libil2cpp.so",
                            ),
                        metadata = metadata,
                        dumpFilePath = "dump.cs",
                        preview = "",
                        warnings = emptyList(),
                    ),
            )

        val signals =
            GameplayModificationFinder.find(
                result = analysis,
                preparation = preparation(target),
            ).filter {
                it.confidence ==
                    GameplayModificationConfidence
                        .SEMANTIC_MODEL_SIGNAL
            }

        assertTrue(
            signals.any {
                it.targetDisplayName ==
                    "IGame.HeroData.level"
            },
        )
        assertTrue(
            signals.any {
                it.targetDisplayName ==
                    "IGame.HeroData.rank"
            },
        )
        assertTrue(
            signals.any {
                it.targetDisplayName ==
                    "IGame.HeroData.quality"
            },
        )
        assertTrue(signals.all { !it.selectable })
        assertTrue(
            signals.none {
                it.targetDisplayName ==
                    "IGame.QualityManager.deviceLevel"
            },
        )
    }

    @Test
    fun virtualTextureCanRunIsNotPresentedAsPlayerMovement() {
        val target =
            target(
                token = 0x06000310,
                name = "IsCanRun",
                offset = 0x3100,
                declaringType =
                    "VTRuntime.VirtualTextureController",
            )
        val result =
            result(
                target = target,
                returnKind =
                    Il2CppNativeReturnKind.BOOLEAN,
            )

        assertTrue(
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).isEmpty(),
        )
    }

    @Test
    fun quadTreeRemoveItemIsNotPresentedAsInventoryMod() {
        val target =
            target(
                token = 0x06000311,
                name = "RemoveItem",
                offset = 0x3110,
                declaringType =
                    "MapRuntime.LooseQuadTree",
            )
        val result =
            result(
                target = target,
                returnKind =
                    Il2CppNativeReturnKind.VOID,
            )

        assertTrue(
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).isEmpty(),
        )
    }

    @Test
    fun qualityLevelIsNotPresentedAsPlayerProgression() {
        val target =
            target(
                token = 0x06000312,
                name = "get_deviceLevel",
                offset = 0x3120,
                declaringType =
                    "IGame.QualityManager",
            )
        val result =
            result(
                target = target,
                returnKind =
                    Il2CppNativeReturnKind.INTEGER,
            )

        assertTrue(
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).isEmpty(),
        )
    }

    @Test
    fun generatedLuaWrapIsNotPresentedAsNumericGameplayMod() {
        val target =
            target(
                token = 0x06000313,
                name = "get_MoveSpeed",
                offset = 0x3130,
                declaringType =
                    "CurvedLineRendererLeadWrap",
            )
        val result =
            result(
                target = target,
                returnKind =
                    Il2CppNativeReturnKind.INTEGER,
            )

        assertTrue(
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).isEmpty(),
        )
    }

    @Test
    fun purchaseAndPaymentSurfacesAreVisibleButNotAutoPatched() {
        val target = target(
            token = 0x06000006,
            name = "ValidatePurchaseReceipt",
            offset = 0x800,
        )
        val result = result(
            target = target,
            returnKind = Il2CppNativeReturnKind.BOOLEAN,
        )

        val opportunity =
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
            ).single()

        assertEquals(
            GameplayModificationCategory.SENSITIVE_SURFACE,
            opportunity.category,
        )
        assertEquals(
            GameplayModificationConfidence.SENSITIVE_SURFACE_SIGNAL,
            opportunity.confidence,
        )
        assertEquals(
            GameplayMutationAction.DISCOVERY_ONLY,
            opportunity.action,
        )
        assertFalse(opportunity.selectable)
        assertTrue(
            opportunity.title.contains(
                "receipt",
                ignoreCase = true,
            ),
        )
    }

    @Test
    fun sensitiveLibrarySurfaceRemainsVisibleInProjectOnlyMode() {
        val target =
            target(
                token = 0x06000018,
                name = "ValidatePurchaseReceipt",
                offset = 0x830,
                imageName = "Store.Billing.dll",
                declaringType =
                    "Store.Billing.ReceiptValidator",
            )
        val result =
            result(
                target = target,
                returnKind =
                    Il2CppNativeReturnKind.BOOLEAN,
            )

        val opportunity =
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation(target),
                projectCodeOnly = true,
            ).single()

        assertEquals(
            GameplayModificationCategory.SENSITIVE_SURFACE,
            opportunity.category,
        )
        assertFalse(opportunity.selectable)
    }

    @Test
    fun antiCheatAndAuthenticationSurfacesAreReportedAsSensitive() {
        val first =
            target(
                token = 0x06000016,
                name = "IntegrityCheck",
                offset = 0x810,
                declaringType = "Game.Security.AntiCheatManager",
            )
        val second =
            target(
                token = 0x06000017,
                name = "AuthenticateSessionToken",
                offset = 0x820,
                declaringType = "Game.Network.AuthService",
            )
        val base =
            result(
                target = first,
                returnKind = Il2CppNativeReturnKind.BOOLEAN,
                extraTarget = second,
            )

        val opportunities =
            GameplayModificationFinder.find(
                result = base,
                preparation =
                    PatchPreparationPlan(
                        artifactSha256 = SHA,
                        sourceShaVerified = true,
                        preparedAtEpochMs = 1,
                        targets =
                            listOf(
                                prepared(first),
                                prepared(second),
                            ),
                        globalBlockers = emptyList(),
                    ),
            )

        assertEquals(2, opportunities.size)
        assertTrue(
            opportunities.all {
                it.category ==
                    GameplayModificationCategory
                        .SENSITIVE_SURFACE
            },
        )
        assertTrue(
            opportunities.all {
                !it.selectable &&
                    it.action ==
                    GameplayMutationAction
                        .DISCOVERY_ONLY
            },
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
                imageName =
                    Il2CppPatchTargetBrowser
                        .imageName(it)
                        ?: "Assembly-CSharp.dll",
                moduleName =
                    Il2CppPatchTargetBrowser
                        .imageName(it)
                        ?: "Assembly-CSharp.dll",
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
        imageName: String = "Assembly-CSharp.dll",
        declaringType: String = "Game.Player",
    ) = EvidenceTarget(
        id =
            "il2cpp:method:" + imageName + ":" +
                token.toString(16) +
                ":" + imageName,
        runtimeId = "unity_il2cpp",
        kind = EvidenceTargetKind.METHOD,
        displayName = declaringType + "." + name,
        artifact = ARTIFACT,
        abi = "arm64-v8a",
        declaringType = declaringType,
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
