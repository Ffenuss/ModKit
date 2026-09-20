package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
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
    fun givesConservativePresetAdviceFromMethodConvention() {
        val lifecycle =
            target(
                id =
                    "il2cpp:method:Assembly-CSharp.dll:" +
                        "6000001:Assembly-CSharp.dll",
                memberName = memberName,
            )
        val predicate =
            target(
                id =
                    "il2cpp:method:Assembly-CSharp.dll:" +
                        "6000002:Assembly-CSharp.dll",
                memberName = "IsReady",
            )

        assertTrue(
            Il2CppPatchTargetBrowser
                .presetAdvice(lifecycle)
                .contains("обычно void"),
        )
        assertTrue(
            Il2CppPatchTargetBrowser
                .presetAdvice(predicate)
                .contains("0 обычно означает"),
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
            memberName = "Update",
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
