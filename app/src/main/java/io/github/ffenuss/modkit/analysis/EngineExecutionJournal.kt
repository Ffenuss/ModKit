package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import java.io.Serializable
import java.util.UUID

enum class EngineExecutionStatus {
    RUNNING, COMPLETED, COMPLETED_WITH_WARNINGS, SKIPPED, CANCELLED, FAILED, INTERRUPTED,
}

/** Execution is not evidence that a modification works. Durations cover this attempt only. */
data class EngineExecutionRecord(
    val attemptId: String,
    val engineId: String,
    val scheduleClass: EngineScheduleClass,
    val status: EngineExecutionStatus,
    val startedAtEpochMs: Long,
    val lastHeartbeatEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val elapsedMs: Long = 0,
    val currentTask: String? = null,
    val currentArtifact: String? = null,
    val processed: Long? = null,
    val total: Long? = null,
    val cacheHit: Boolean = false,
    val warnings: List<String> = emptyList(),
    val failureClass: String? = null,
    val failureMessage: String? = null,
    val watchdogCount: Int = 0,
    val maxHeartbeatAgeMs: Long = 0,
) : Serializable

data class EngineExecutionLedger(
    val records: List<EngineExecutionRecord> = emptyList(),
    val droppedRecords: Int = 0,
) : Serializable {
    fun afterInterruption(): EngineExecutionLedger = copy(records = records.map { record ->
        if (record.status != EngineExecutionStatus.RUNNING) record else record.copy(
            status = EngineExecutionStatus.INTERRUPTED,
            // The actual process-death time is unknown. Do not count downtime as work.
            finishedAtEpochMs = null,
            failureMessage = "Process ended before engine completion; elapsedMs is the last checkpoint.",
        )
    })

    fun latestWarnings(): List<String> = records.groupBy { it.engineId }.values.flatMap { attempts ->
        val last = attempts.last()
        (last.warnings + listOfNotNull(last.failureMessage)).map { "${last.engineId}: $it" }
    }.distinct()
}

/** Bounded, SHA-bound checkpoints. Terminal writes are synchronous even during cancellation. */
class EngineExecutionJournal(
    private val artifactSha256: String,
    private val cache: EngineResultCache? = null,
    initial: EngineExecutionLedger = EngineExecutionLedger(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var ledger = (cache?.loadEngineExecutions(artifactSha256) ?: initial).afterInterruption()
    private var activeStartMs = 0L
    private var lastPersistMs = 0L

    @Synchronized fun snapshot(): EngineExecutionLedger = ledger

    @Synchronized fun start(engine: PlannedEngine) {
        check(ledger.records.none { it.status == EngineExecutionStatus.RUNNING })
        activeStartMs = monotonicMs()
        val now = wallClock()
        val record = EngineExecutionRecord(
            UUID.randomUUID().toString(), engine.id, engine.scheduleClass,
            EngineExecutionStatus.RUNNING, now, now,
            currentTask = "Starting engine",
        )
        val records = ledger.records + record
        val dropped = (records.size - MAX_RECORDS).coerceAtLeast(0)
        ledger = EngineExecutionLedger(records.takeLast(MAX_RECORDS), ledger.droppedRecords + dropped)
        persist()
    }

    /** True when a checkpoint was due, so the caller can also update the recoverable UI snapshot. */
    @Synchronized fun progress(event: EngineProgress): Boolean {
        val current = active(event.engineId) ?: return false
        replace(current.copy(
            lastHeartbeatEpochMs = wallClock(), elapsedMs = elapsed(),
            currentTask = event.currentTask?.take(MAX_TEXT),
            currentArtifact = event.currentArtifact?.take(MAX_TEXT),
            processed = event.processed, total = event.total,
        ))
        if (monotonicMs() - lastPersistMs < CHECKPOINT_MS) return false
        persist()
        return true
    }

    @Synchronized fun stalled(engineId: String, heartbeatAgeMs: Long) {
        val current = active(engineId) ?: return
        replace(current.copy(
            elapsedMs = elapsed(), watchdogCount = current.watchdogCount + 1,
            maxHeartbeatAgeMs = maxOf(current.maxHeartbeatAgeMs, heartbeatAgeMs),
        ))
        persist()
    }

    @Synchronized fun finish(
        engineId: String,
        status: EngineExecutionStatus,
        cacheHit: Boolean = false,
        warnings: List<String> = emptyList(),
        failure: Throwable? = null,
        reason: String? = null,
    ) {
        require(status != EngineExecutionStatus.RUNNING)
        val current = active(engineId) ?: return
        replace(current.copy(
            status = status, finishedAtEpochMs = wallClock(), elapsedMs = elapsed(),
            cacheHit = cacheHit, warnings = warnings.distinct().take(32).map { it.take(MAX_TEXT) },
            failureClass = failure?.javaClass?.name,
            failureMessage = (reason ?: failure?.message)?.take(MAX_TEXT),
        ))
        persist()
    }

    private fun active(engineId: String): EngineExecutionRecord? = ledger.records.lastOrNull()
        ?.takeIf { it.engineId == engineId && it.status == EngineExecutionStatus.RUNNING }

    private fun replace(record: EngineExecutionRecord) {
        ledger = ledger.copy(records = ledger.records.dropLast(1) + record)
    }

    private fun elapsed(): Long = (monotonicMs() - activeStartMs).coerceAtLeast(0)

    private fun persist() {
        cache?.saveEngineExecutions(artifactSha256, ledger)
        lastPersistMs = monotonicMs()
    }

    companion object {
        const val MAX_RECORDS = 128
        private const val CHECKPOINT_MS = 2_000L
        private const val MAX_TEXT = 2_048
    }
}

internal fun FastAnalysisResult.engineOutputWarnings(engineId: String): List<String> = when (engineId) {
    "dex.inventory" -> dexInventory?.let { it.warnings + it.records.flatMap { record -> record.warnings } }
    "elf.universal-inventory" -> elfInventory?.warnings
    "unreal.package-inventory" -> unrealAssetInventory?.warnings
    "flutter.asset-inventory" -> flutterAssetInventory?.warnings
    "il2cpp.fast-dump" -> il2cppFastDump?.warnings
    "il2cpp.codegen-bind" -> il2cppBinaryBinding?.warnings
    else -> null
}.orEmpty().distinct()

internal fun FastAnalysisResult.hasEngineOutput(engineId: String): Boolean = when (engineId) {
    "dex.inventory" -> dexInventory != null
    "elf.universal-inventory" -> elfInventory != null
    "unreal.package-inventory" -> unrealAssetInventory != null
    "flutter.asset-inventory" -> flutterAssetInventory != null
    "il2cpp.fast-dump" -> il2cppFastDump != null
    "il2cpp.codegen-bind" -> il2cppBinaryBinding != null
    else -> false
}
