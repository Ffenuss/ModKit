package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.runtime.RuntimeEscalationStage
import io.github.ffenuss.modkit.runtime.RuntimeStageAttempt
import io.github.ffenuss.modkit.runtime.RuntimeStageAttemptLedger
import io.github.ffenuss.modkit.runtime.RuntimeStageAttemptState
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStageAttemptCacheTest {
    @Test
    fun stageAttemptLedgerIsContentAddressedByArtifactSha() {
        val root = Files.createTempDirectory("modkit-runtime-attempt-cache").toFile()
        try {
            val cache = EngineResultCache(File(root, "cache"))
            val ledger = RuntimeStageAttemptLedger(
                artifactSha256 = SHA,
                attempts = listOf(
                    RuntimeStageAttempt(
                        stage = RuntimeEscalationStage.NON_ROOT_RUNTIME,
                        state = RuntimeStageAttemptState.BLOCKED,
                        attemptedAtEpochMs = 1234,
                        requestedTargetIds = setOf("target"),
                        resolvedTargetIds = emptySet(),
                        blockers = emptyList(),
                    ),
                ),
            )

            assertTrue(cache.saveRuntimeStageAttempts(SHA, ledger))
            assertEquals(ledger, cache.loadRuntimeStageAttempts(SHA))
            assertNull(cache.loadRuntimeStageAttempts(OTHER_SHA))
            assertFalse(cache.saveRuntimeStageAttempts(OTHER_SHA, ledger))
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val OTHER_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
