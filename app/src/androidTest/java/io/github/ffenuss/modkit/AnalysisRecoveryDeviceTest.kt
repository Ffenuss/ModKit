package io.github.ffenuss.modkit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import io.github.ffenuss.modkit.patch.PatchLabDiagnosticReportWriter
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise checkpoint replacement on Android's filesystem, not just the desktop JVM. */
@RunWith(AndroidJUnit4::class)
class AnalysisRecoveryDeviceTest {
    @Test fun newCacheInstanceRestoresPartialAndExportsWithoutStartingAnEngine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "recovery-test-").toFile()
        try {
            val sha = "c".repeat(64)
            val cacheRoot = root.resolve("cache")
            val cache = EngineResultCache(cacheRoot)
            assertTrue(cache.saveArtifactIndex(sha, ArtifactIndex(sha, emptyList(), emptyList())))
            assertTrue(cache.saveDexInventory(sha, DexInventoryResult(emptyList(), listOf("fixture warning"))))
            var elapsed = 0L
            val journal = EngineExecutionJournal(sha, cache, monotonicMs = { elapsed })
            val engine = PlannedEngine("elf.universal-inventory", EngineScheduleClass.TARGETED, true, "fixture")
            journal.start(engine)
            elapsed = 2_500L
            assertTrue(journal.progress(EngineProgress(engine.id, engine.scheduleClass, RunState.RUNNING,
                currentTask = "ELF extraction", currentArtifact = "libfixture.so", processed = 256, total = 1024)))
            journal.stalled(engine.id, 180_000)

            val restored = requireNotNull(EngineResultCache(cacheRoot).restorePartialResult(sha))
            assertNotNull(restored.dexInventory)
            assertNull(restored.elfInventory)
            val attempt = restored.engineExecutions.records.single()
            assertEquals(EngineExecutionStatus.INTERRUPTED, attempt.status)
            assertEquals(2_500L, attempt.elapsedMs)
            assertNull(attempt.finishedAtEpochMs)
            assertTrue(restored.engineWarnings.any { it.contains("fixture warning") })
            val before = requireNotNull(cache.loadEngineExecutions(sha))
            val report = PatchLabDiagnosticReportWriter.write(root.resolve("report"), "Partial fixture", restored, null)
            ZipFile(report).use { zip ->
                val executions = zip.getInputStream(zip.getEntry("analysis/executions.tsv")).bufferedReader().use { it.readText() }
                assertTrue(executions.contains("INTERRUPTED"))
                assertTrue(executions.contains("libfixture.so"))
                assertTrue(executions.contains("180000"))
            }
            assertEquals("Export must not start or mutate analysis", before, cache.loadEngineExecutions(sha))
        } finally { root.deleteRecursively() }
    }
}
