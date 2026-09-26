package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class EngineExecutionJournalTest {
    private val engine = PlannedEngine("dex.inventory", EngineScheduleClass.TARGETED, true, "fixture")

    @Test fun persistsCheckpointsAndRecoversInterruptionWithoutCountingDowntime() {
        val root = Files.createTempDirectory("engine-journal").toFile()
        try {
            val cache = EngineResultCache(root)
            cache.saveArtifactIndex("sha", ArtifactIndex("sha", emptyList(), emptyList()))
            var wall = 10_000L
            var mono = 0L
            val journal = EngineExecutionJournal("sha", cache, wallClock = { wall }, monotonicMs = { mono })
            journal.start(engine)
            mono = 100L
            wall = 10_100L
            assertFalse(journal.progress(event("first header", 1)))
            assertEquals(0L, cache.loadEngineExecutions("sha")!!.records.single().elapsedMs)
            mono = 2_500L
            wall = 12_500L
            assertTrue(journal.progress(event("classes2.dex", 2)))
            val restored = cache.restorePartialResult("sha")!!
            val interrupted = restored.engineExecutions.records.single()
            assertEquals(EngineExecutionStatus.INTERRUPTED, interrupted.status)
            assertEquals(2_500L, interrupted.elapsedMs)
            assertEquals("classes2.dex", interrupted.currentTask)
            assertEquals(2L, interrupted.processed)
            assertNull(interrupted.finishedAtEpochMs)
            assertTrue(restored.engineWarnings.single().contains("Process ended"))
            assertNull(cache.loadEngineExecutions("other-sha"))

            // A new process/run retains the interrupted attempt, then records cache retrieval separately.
            wall = 500_000L
            val retry = EngineExecutionJournal("sha", cache, wallClock = { wall }, monotonicMs = { mono })
            retry.start(engine)
            mono += 17
            retry.finish(engine.id, EngineExecutionStatus.COMPLETED, cacheHit = true)
            val attempts = cache.loadEngineExecutions("sha")!!.records
            assertEquals(2, attempts.size)
            assertEquals(EngineExecutionStatus.INTERRUPTED, attempts.first().status)
            assertEquals(17L, attempts.last().elapsedMs)
            assertTrue(attempts.last().cacheHit)
            assertTrue(retry.snapshot().latestWarnings().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun watchdogAndTerminalWriteSurviveWithoutNextProgressEvent() {
        val root = Files.createTempDirectory("engine-watchdog").toFile()
        try {
            val cache = EngineResultCache(root)
            var mono = 0L
            var wall = 50_000L
            val journal = EngineExecutionJournal("sha", cache, wallClock = { wall }, monotonicMs = { mono })
            journal.start(engine)
            mono = 31_000L
            wall = 1L // Clock correction must not create a negative duration.
            journal.stalled(engine.id, 31_000L)
            journal.finish(engine.id, EngineExecutionStatus.SKIPPED, reason = "Skipped by user")
            val saved = cache.loadEngineExecutions("sha")!!.records.single()
            assertEquals(31_000L, saved.elapsedMs)
            assertEquals(1, saved.watchdogCount)
            assertEquals(31_000L, saved.maxHeartbeatAgeMs)
            assertEquals(EngineExecutionStatus.SKIPPED, saved.status)
            assertEquals("Skipped by user", saved.failureMessage)
            assertEquals(EngineExecutionStatus.SKIPPED, cache.loadEngineExecutions("sha")!!
                .afterInterruption().records.single().status)
        } finally { root.deleteRecursively() }
    }

    @Test fun retryHistoryAndMessagesAreBoundedWithAnExplicitDroppedCount() {
        val journal = EngineExecutionJournal("sha")
        repeat(EngineExecutionJournal.MAX_RECORDS + 3) {
            journal.start(engine)
            journal.finish(engine.id, EngineExecutionStatus.FAILED,
                failure = IllegalStateException("x".repeat(10_000)))
        }
        assertEquals(128, journal.snapshot().records.size)
        assertEquals(3, journal.snapshot().droppedRecords)
        assertEquals(2_048, journal.snapshot().records.last().failureMessage!!.length)
        assertEquals(128, journal.snapshot().records.map { it.attemptId }.toSet().size)
    }

    @Test fun corruptJournalCannotInventACompletedEngineOrDestroyCompletedInventory() {
        val root = Files.createTempDirectory("engine-corrupt").toFile()
        try {
            val cache = EngineResultCache(root)
            cache.saveArtifactIndex("sha", ArtifactIndex("sha", emptyList(), emptyList()))
            cache.saveDexInventory("sha", DexInventoryResult(emptyList(), listOf("saved warning")))
            val journal = EngineExecutionJournal("sha", cache)
            journal.start(engine)
            val file = root.resolve("sha/${EngineResultCache.ENGINE_EXECUTIONS_ID}/1/result.bin")
            file.writeText("incomplete write")
            val restored = cache.restorePartialResult("sha")!!
            assertNotNull(restored.dexInventory)
            assertTrue(restored.engineExecutions.records.isEmpty())
            assertTrue(restored.engineWarnings.contains("dex.inventory: saved warning"))
        } finally { root.deleteRecursively() }
    }

    private fun event(task: String, count: Long) = EngineProgress(engine.id, engine.scheduleClass,
        RunState.RUNNING, currentTask = task, processed = count, total = 3)
}
