package io.github.ffenuss.modkit.patch

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

class Il2CppPatchTargetBrowserTest {
    @Test
    fun extractsImageNameFromStableMethodId() {
        val target = target(
            id =
                "il2cpp:method:Assembly-CSharp.dll:" +
                    "6000001:Assembly-CSharp.dll",
        )

        assertEquals(
            "Assembly-CSharp.dll",
            Il2CppPatchTargetBrowser.imageName(target),
        )
        assertTrue(
            Il2CppPatchTargetBrowser.isAssemblyCSharp(
                target,
            ),
        )
    }

    @Test
    fun searchMatchesManagedIdentityAndImage() {
        val target = target(
            id =
                "il2cpp:method:Assembly-CSharp.dll:" +
                    "6000001:Assembly-CSharp.dll",
            displayName = "Game.Player.Update",
        )

        assertTrue(
            Il2CppPatchTargetBrowser.matches(
                target,
                "player.update",
            ),
        )
        assertTrue(
            Il2CppPatchTargetBrowser.matches(
                target,
                "assembly-csharp",
            ),
        )
        assertFalse(
            Il2CppPatchTargetBrowser.matches(
                target,
                "microsoft.codeanalysis",
            ),
        )
    }

    @Test
    fun classifiesProjectAndFrameworkOrigins() {
        val project =
            target(
                id =
                    "il2cpp:method:Assembly-CSharp.dll:" +
                        "6000001:Assembly-CSharp.dll",
            )
        val framework =
            target(
                id =
                    "il2cpp:method:System.Runtime.dll:" +
                        "6000002:System.Runtime.dll",
            )

        assertEquals(
            "Код проекта",
            Il2CppPatchTargetBrowser.originLabel(
                project,
            ),
        )
        assertEquals(
            ".NET / системная библиотека",
            Il2CppPatchTargetBrowser.originLabel(
                framework,
            ),
        )
    }

    @Test
    fun semanticAdviceUsesProvenReturnKindInsteadOfMethodName() {
        val target =
            target(
                id =
                    "il2cpp:method:Assembly-CSharp.dll:" +
                        "6000001:Assembly-CSharp.dll",
                memberName = "Update",
            )
        val result = resultWithBinding(
            target = target,
            returnKind = Il2CppNativeReturnKind.BOOLEAN,
        )

        assertTrue(
            Il2CppPatchTargetBrowser
                .presetAdvice(result, target)
                .contains("доказал bool"),
        )
        assertFalse(
            Il2CppPatchTargetBrowser
                .presetAdvice(result, target)
                .contains("обычно void"),
        )
    }

    @Test
    fun reconstructedSourceViewExplainsIl2CppLimitAndShowsReadableSignature() {
        val target =
            target(
                id =
                    "il2cpp:method:Assembly-CSharp.dll:" +
                        "6000001:Assembly-CSharp.dll",
                memberName = "Update",
            )
        val result =
            resultWithBinding(
                target = target,
                returnKind =
                    Il2CppNativeReturnKind.BOOLEAN,
            )

        val view =
            Il2CppPatchTargetBrowser
                .reconstructedSourceView(
                    result,
                    target,
                )

        assertTrue(
            view.contains("namespace Game"),
        )
        assertTrue(
            view.contains("class Player"),
        )
        assertTrue(
            view.contains("public bool Update()"),
        )
        assertTrue(
            view.contains(
                "не исходный .cs файл",
            ),
        )
    }

    @Test
    fun unknownReturnKindDoesNotGuessFromLifecycleName() {
        val target =
            target(
                id =
                    "il2cpp:method:Assembly-CSharp.dll:" +
                        "6000001:Assembly-CSharp.dll",
                memberName = "Update",
            )
        val result = resultWithBinding(
            target = target,
            returnKind = Il2CppNativeReturnKind.UNKNOWN,
        )

        assertTrue(
            Il2CppPatchTargetBrowser
                .presetAdvice(result, target)
                .contains("не доказан"),
        )
    }

    @Test
    fun duplicateMethodTokenAcrossImagesKeepsOwningImageBinding() {
        val project =
            target(
                id =
                    "il2cpp:method:Assembly-CSharp.dll:" +
                        "6000001:Assembly-CSharp.dll",
            )
        val artifact = requireNotNull(project.artifact)
        fun binding(
            image: String,
            kind: Il2CppNativeReturnKind,
        ) =
            Il2CppMethodBinaryBinding(
                methodIndex = 0,
                managedIdentity = project.displayName,
                metadataToken = 0x06000001,
                imageName = image,
                moduleName = image,
                slotIndex = 0,
                functionVirtualAddress = 0x1000,
                functionFileOffset = 0x200,
                returnTypeIndex = 7,
                returnKind = kind,
                returnTypeProof = "test-proof",
            )
        val result =
            resultWithBinding(
                target = project,
                returnKind =
                    Il2CppNativeReturnKind.BOOLEAN,
            ).copy(
                il2cppBinaryBinding =
                    Il2CppBinaryBindingResult(
                        evidence =
                            listOf(
                                Il2CppBinaryEvidence(
                                    libraryEntry = artifact,
                                    machine = 183,
                                    pointerSize = 8,
                                    relativeRelocationCount = 0,
                                    codeRegistrationVirtualAddress = null,
                                    metadataRegistrationVirtualAddress = 0x3000,
                                    codegenRegisterVirtualAddress = null,
                                    moduleArrayDiscovery = "TEST",
                                    modules = emptyList(),
                                    bindings =
                                        listOf(
                                            binding(
                                                "Assembly-CSharp.dll",
                                                Il2CppNativeReturnKind.BOOLEAN,
                                            ),
                                            binding(
                                                "System.Runtime.dll",
                                                Il2CppNativeReturnKind.VOID,
                                            ),
                                        ),
                                    blockers = emptyList(),
                                ),
                            ),
                        exactBindingCount = 2,
                        warnings = emptyList(),
                    ),
            )

        val resolved =
            Il2CppPatchTargetBrowser.bindingFor(
                result,
                project,
            )

        assertEquals(
            Il2CppNativeReturnKind.BOOLEAN,
            requireNotNull(resolved).returnKind,
        )
        assertEquals(
            "Assembly-CSharp.dll",
            resolved.imageName,
        )
    }

    @Test
    fun nonMethodIdHasNoImageName() {
        val target =
            target(id = "runtime:unity_il2cpp")

        assertEquals(
            null,
            Il2CppPatchTargetBrowser.imageName(target),
        )
        assertFalse(
            Il2CppPatchTargetBrowser.isAssemblyCSharp(
                target,
            ),
        )
    }

    private fun resultWithBinding(
        target: EvidenceTarget,
        returnKind: Il2CppNativeReturnKind,
    ): FastAnalysisResult {
        val binding =
            Il2CppMethodBinaryBinding(
                methodIndex = 0,
                managedIdentity = target.displayName,
                metadataToken = requireNotNull(target.metadataToken),
                imageName = "Assembly-CSharp.dll",
                moduleName = "Assembly-CSharp.dll",
                slotIndex = 0,
                functionVirtualAddress = 0x1000,
                functionFileOffset = 0x200,
                returnTypeIndex = 7,
                returnKind = returnKind,
                returnTypeProof = "test-proof",
            )
        return FastAnalysisResult(
            index =
                io.github.ffenuss.modkit.analysis.ArtifactIndex(
                    artifactSha256 =
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    sources = emptyList(),
                    entries = emptyList(),
                    detectedAbis = setOf("arm64-v8a"),
                    runtimeProfiles = emptyList(),
                ),
            routingPlan =
                io.github.ffenuss.modkit.analysis.EngineRoutingPlan(
                    emptyList(),
                    emptyList(),
                ),
            elapsedMs = 0,
            il2cppBinaryBinding =
                Il2CppBinaryBindingResult(
                    evidence =
                        listOf(
                            Il2CppBinaryEvidence(
                                libraryEntry =
                                    requireNotNull(target.artifact),
                                machine = 183,
                                pointerSize = 8,
                                relativeRelocationCount = 0,
                                codeRegistrationVirtualAddress = null,
                                metadataRegistrationVirtualAddress = 0x3000,
                                codegenRegisterVirtualAddress = null,
                                moduleArrayDiscovery = "TEST",
                                modules = emptyList(),
                                bindings = listOf(binding),
                                blockers = emptyList(),
                            ),
                        ),
                    exactBindingCount = 1,
                    warnings = emptyList(),
                ),
        )
    }

    private fun target(
        id: String,
        displayName: String = "Game.Player.Update",
        memberName: String = "Update",
    ) =
        EvidenceTarget(
            id = id,
            runtimeId = "unity_il2cpp",
            kind = EvidenceTargetKind.METHOD,
            displayName = displayName,
            artifact =
                "base.apk:lib/arm64-v8a/libil2cpp.so",
            abi = "arm64-v8a",
            declaringType = "Game.Player",
            memberName = memberName,
            metadataToken = 0x06000001,
            rva = null,
            binaryVirtualAddress = 0x1000,
            runtimeVirtualAddress = null,
            fileOffset = 0x200,
            proofLevel = ProofLevel.EXACT_BINARY,
            userStatus = UserFindingStatus.CONFIRMED,
            blockers = emptyList(),
            facts = emptyList(),
        )
}
