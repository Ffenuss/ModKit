package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceGraph
import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.UserFindingStatus
import io.github.ffenuss.modkit.domain.ProofLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MutationPreflightEngineTest {
    @Test
    fun exactBinaryNativeTargetCanPassOnlyWithConcreteSameSizeReplacement() {
        val preparation = preparation(
            status = PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE,
        )
        val request = MutationRequest(
            id = "m1",
            artifactSha256 = SHA,
            targetId = TARGET_ID,
            kind = MutationKind.NATIVE_IN_PLACE_BYTES,
            expectedOriginalSha256 = "1".repeat(64),
            expectedOriginalSize = 4,
            replacement = MutationPayloadRef(
                sha256 = "2".repeat(64),
                size = 4,
                storagePath = "/tmp/replacement.bin",
            ),
        )

        val result = MutationPreflightEngine.validate(preparation, listOf(request))

        assertTrue(result.readyForApply)
        assertEquals(1, result.readyItems.size)
        assertTrue(result.globalBlockers.isEmpty())
    }

    @Test
    fun nativeReplacementWithChangedLengthIsBlocked() {
        val preparation = preparation(
            status = PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE,
        )
        val request = MutationRequest(
            id = "m1",
            artifactSha256 = SHA,
            targetId = TARGET_ID,
            kind = MutationKind.NATIVE_IN_PLACE_BYTES,
            expectedOriginalSha256 = "1".repeat(64),
            expectedOriginalSize = 4,
            replacement = MutationPayloadRef(
                sha256 = "2".repeat(64),
                size = 5,
            ),
        )

        val result = MutationPreflightEngine.validate(preparation, listOf(request))

        assertFalse(result.readyForApply)
        assertEquals(MutationPreflightStatus.BLOCKED, result.items.single().status)
        assertTrue(
            result.items.single().blockers.any {
                "сохранять размер" in it
            },
        )
    }

    @Test
    fun unconfirmedEvidenceNeverBecomesWritableFromMutationRequest() {
        val preparation = preparation(
            status = PreparationTargetStatus.NEEDS_CONFIRMATION,
        )
        val request = MutationRequest(
            id = "m1",
            artifactSha256 = SHA,
            targetId = TARGET_ID,
            kind = MutationKind.NATIVE_IN_PLACE_BYTES,
            expectedOriginalSha256 = "1".repeat(64),
            expectedOriginalSize = 4,
            replacement = MutationPayloadRef(
                sha256 = "2".repeat(64),
                size = 4,
            ),
        )

        val result = MutationPreflightEngine.validate(preparation, listOf(request))

        assertFalse(result.readyForApply)
        assertEquals(MutationPreflightStatus.BLOCKED, result.items.single().status)
    }

    @Test
    fun shaMismatchBlocksOtherwiseValidMutation() {
        val preparation = preparation(
            status = PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE,
        )
        val request = MutationRequest(
            id = "m1",
            artifactSha256 = "b".repeat(64),
            targetId = TARGET_ID,
            kind = MutationKind.NATIVE_IN_PLACE_BYTES,
            expectedOriginalSha256 = "1".repeat(64),
            expectedOriginalSize = 4,
            replacement = MutationPayloadRef(
                sha256 = "2".repeat(64),
                size = 4,
            ),
        )

        val result = MutationPreflightEngine.validate(preparation, listOf(request))

        assertFalse(result.readyForApply)
        assertTrue(
            result.items.single().blockers.any {
                "другой версии" in it
            },
        )
    }

    @Test
    fun duplicateTargetMutationsAreRejectedAsConflicting() {
        val preparation = preparation(
            status = PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE,
        )
        val base = MutationRequest(
            id = "m1",
            artifactSha256 = SHA,
            targetId = TARGET_ID,
            kind = MutationKind.CONFIG_VALUE,
            parameters = mapOf("key" to "mode", "value" to "test"),
        )

        val result = MutationPreflightEngine.validate(
            preparation,
            listOf(base, base.copy(id = "m2")),
        )

        assertFalse(result.readyForApply)
        assertTrue(
            result.globalBlockers.any {
                "конфликтующих" in it
            },
        )
    }

    private fun preparation(
        status: PreparationTargetStatus,
    ): PatchPreparationPlan {
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
            fileOffset = 0x200,
            proofLevel = if (status == PreparationTargetStatus.READY) {
                ProofLevel.CHANGE_READY
            } else {
                ProofLevel.EXACT_BINARY
            },
            userStatus = when (status) {
                PreparationTargetStatus.READY -> UserFindingStatus.READY
                PreparationTargetStatus.RUNTIME_REQUIRED -> UserFindingStatus.RUNTIME_REQUIRED
                PreparationTargetStatus.NEEDS_CONFIRMATION -> UserFindingStatus.CONFIRMING
                PreparationTargetStatus.BLOCKED -> UserFindingStatus.FOUND
                PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE -> UserFindingStatus.CONFIRMED
            },
            blockers = emptyList(),
            facts = emptyList(),
        )
        return PatchPreparationPlan(
            artifactSha256 = SHA,
            sourceShaVerified = true,
            preparedAtEpochMs = 1,
            targets = listOf(
                PreparedTarget(
                    target = target,
                    status = status,
                    blockers = if (status == PreparationTargetStatus.NEEDS_CONFIRMATION) {
                        listOf("Need confirmation")
                    } else {
                        emptyList()
                    },
                ),
            ),
            globalBlockers = emptyList(),
        )
    }

    companion object {
        private const val TARGET_ID = "target-1"
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
