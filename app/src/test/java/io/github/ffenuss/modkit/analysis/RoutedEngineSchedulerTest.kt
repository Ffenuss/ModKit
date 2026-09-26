package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoutedEngineSchedulerTest {
    @Test fun failedStageKeepsEarlierOutputAndCacheRetryRetainsFailureHistory() = withRoot { root ->
        val cache = EngineResultCache(root)
        val initial = fixture("dex.inventory", "il2cpp.fast-dump")
        cache.saveArtifactIndex("sha", initial.index)
        val first = run(initial, root, cache)
        assertNotNull(first.dexInventory)
        assertNull(first.il2cppFastDump)
        assertEquals(listOf(EngineExecutionStatus.COMPLETED, EngineExecutionStatus.FAILED),
            first.engineExecutions.records.map { it.status })
        assertTrue(first.engineExecutions.records.last().failureMessage!!.contains("global-metadata.dat"))
        assertTrue(cache.restorePartialResult("sha")!!.engineWarnings.any { it.contains("global-metadata.dat") })
        val retry = run(initial, root, cache)
        assertEquals(4, retry.engineExecutions.records.size)
        assertTrue(retry.engineExecutions.records[2].cacheHit)
        assertEquals(EngineExecutionStatus.FAILED, retry.engineExecutions.records[1].status)
    }

    @Test fun parserWarningsAreNotReportedAsCleanSuccessAndSurviveCache() = withRoot { root ->
        val cache = EngineResultCache(root)
        val initial = fixture("dex.inventory").let { it.copy(index = it.index.copy(entries = listOf(
            ArtifactEntry("missing.apk", "classes.dex", 112, format = BinaryFormat.DEX),
        ))) }
        val first = run(initial, root, cache)
        assertEquals(EngineExecutionStatus.COMPLETED_WITH_WARNINGS, first.engineExecutions.records.single().status)
        assertTrue(first.engineExecutions.records.single().warnings.single().contains("unavailable"))
        val retry = run(initial, root, cache)
        assertEquals(EngineExecutionStatus.COMPLETED_WITH_WARNINGS, retry.engineExecutions.records.last().status)
        assertTrue(retry.engineExecutions.records.last().cacheHit)
    }

    @Test fun signalCancellationPublishesAndPersistsTerminalPartialBeforeRethrow() = withRoot { root ->
        val cache = EngineResultCache(root)
        val signal = AtomicCancellationSignal()
        var partial: FastAnalysisResult? = null
        val failure = runCatching {
            run(fixture("dex.inventory"), root, cache, signal, ProgressSink {
                if (it.currentTask == "DEX inventory ready") signal.cancel()
            }) { partial = it }
        }.exceptionOrNull()
        assertTrue(failure is AnalysisCancelledException)
        assertEquals(EngineExecutionStatus.CANCELLED, partial!!.engineExecutions.records.single().status)
        assertEquals(EngineExecutionStatus.CANCELLED, cache.loadEngineExecutions("sha")!!.records.single().status)
    }

    @Test fun realCoroutineCancellationStillCheckpointsSynchronously() = withRoot { root ->
        val cache = EngineResultCache(root)
        var partial: FastAnalysisResult? = null
        runBlocking {
            val child = launch {
                val runningJob = currentCoroutineContext().job
                val initial = fixture("dex.inventory")
                RoutedEngineScheduler.execute(initial, AnalysisWorkspace(initial.index, emptyList()), root,
                    AtomicCancellationSignal(), EngineSkipController(), cache, ProgressSink { runningJob.cancel() },
                    onPartial = { partial = it })
            }
            child.join()
            assertTrue(child.isCancelled)
        }
        assertEquals(EngineExecutionStatus.CANCELLED, partial!!.engineExecutions.records.single().status)
        assertEquals(EngineExecutionStatus.CANCELLED, cache.loadEngineExecutions("sha")!!.records.single().status)
    }

    @Test fun skipIsConsumedAndFollowingEngineStillRuns() = withRoot { root ->
        val skip = EngineSkipController()
        val initial = fixture("dex.inventory", "il2cpp.fast-dump")
        val result = runBlocking {
            RoutedEngineScheduler.execute(initial, AnalysisWorkspace(initial.index, emptyList()), root,
                AtomicCancellationSignal(), skip, progress = ProgressSink {
                    if (it.engineId == "dex.inventory") skip.request(it.engineId)
                }, onPartial = {})
        }
        assertEquals(EngineExecutionStatus.SKIPPED, result.engineExecutions.records.first().status)
        assertEquals(EngineExecutionStatus.FAILED, result.engineExecutions.records.last().status)
        assertFalse(skip.isRequested("dex.inventory"))
    }

    @Test fun fatalMemoryErrorStopsSchedulingAndPreservesEarlierStages() = withRoot { root ->
        val cache = EngineResultCache(root)
        var partial: FastAnalysisResult? = null
        val failure = runCatching {
            run(fixture("dex.inventory", "elf.universal-inventory", "il2cpp.fast-dump"), root, cache,
                progress = ProgressSink {
                    if (it.engineId == "elf.universal-inventory") throw OutOfMemoryError("fixture exhaustion")
                }) { partial = it }
        }.exceptionOrNull()
        assertTrue(failure is OutOfMemoryError)
        assertNotNull(partial!!.dexInventory)
        assertEquals(2, partial!!.engineExecutions.records.size)
        val failed = cache.loadEngineExecutions("sha")!!.records.last()
        assertEquals(EngineExecutionStatus.FAILED, failed.status)
        assertEquals("java.lang.OutOfMemoryError", failed.failureClass)
    }

    @Test fun unrequestedConfirmationIsExplicitlySkippedWithoutInventingOutput() = withRoot { root ->
        val initial = fixture("il2cpp.codegen-bind").let { result -> result.copy(
            routingPlan = EngineRoutingPlan(result.routingPlan.engines.map {
                it.copy(scheduleClass = EngineScheduleClass.CONFIRMATION)
            }, emptyList())) }
        val result = run(initial, root)
        assertEquals(EngineExecutionStatus.SKIPPED, result.engineExecutions.records.single().status)
        assertNull(result.il2cppBinaryBinding)
    }

    private fun run(initial: FastAnalysisResult, root: File, cache: EngineResultCache? = null,
        signal: CancellationSignal = AtomicCancellationSignal(), progress: ProgressSink = ProgressSink {},
        partial: (FastAnalysisResult) -> Unit = {}): FastAnalysisResult = runBlocking {
        RoutedEngineScheduler.execute(initial, AnalysisWorkspace(initial.index, emptyList()), root,
            signal, EngineSkipController(), cache, progress, partial)
    }

    private fun fixture(vararg engines: String) = FastAnalysisResult(
        ArtifactIndex("sha", emptyList(), emptyList()), EngineRoutingPlan(engines.map {
            PlannedEngine(it, EngineScheduleClass.TARGETED, true, "test input")
        }, emptyList()), 0,
    )

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("scheduler").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
