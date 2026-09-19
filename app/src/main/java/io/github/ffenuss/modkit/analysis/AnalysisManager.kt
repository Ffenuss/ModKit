package io.github.ffenuss.modkit.analysis

import android.content.Context
import android.net.Uri
import io.github.ffenuss.modkit.data.InstalledAppRepository
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AnalysisManager {
    private const val WATCHDOG_INTERVAL_MS = 5_000L
    private const val STALLED_AFTER_MS = 20_000L

    private data class PreparedInput(
        val files: List<File>,
        val knownSha256: Map<String, String> = emptyMap(),
    )

    private val lock = Any()
    private val nextRunId = AtomicLong(System.currentTimeMillis())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<AnalysisRunState>(AnalysisRunState.Idle)

    val state: StateFlow<AnalysisRunState> = mutableState.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var store: AnalysisRunStore? = null
    @Volatile private var activeJob: Job? = null
    @Volatile private var watchdogJob: Job? = null
    @Volatile private var activeSignal: AtomicCancellationSignal? = null
    @Volatile private var activeSkipController: EngineSkipController? = null
    private var lastPersistAtMs = 0L

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext != null) return
            val application = context.applicationContext
            appContext = application
            store = AnalysisRunStore(application)
            val saved = store?.load()
            if (saved != null && saved.status in setOf(
                    AnalysisRunStore.RUNNING,
                    AnalysisRunStore.CANCELLING,
                    AnalysisRunStore.STALLED,
                )
            ) {
                mutableState.value = AnalysisRunState.Interrupted(
                    target = saved.target,
                    previousProgress = saved.progress,
                    message = "Предыдущий анализ был прерван системой или перезапуском. Можно продолжить ту же цель; завершённые движки будут переиспользованы из SHA-привязанного кэша.",
                )
            }
        }
    }

    fun startFile(uri: Uri, label: String) =
        start(AnalysisTargetDescriptor.FileUri(uri.toString(), label))

    fun startInstalled(target: InstalledAppTarget) =
        start(AnalysisTargetDescriptor.InstalledPackage(target.packageName, target.label))

    fun resumeInterrupted() {
        val current = mutableState.value as? AnalysisRunState.Interrupted ?: return
        start(current.target)
    }

    fun dismissInterrupted() {
        synchronized(lock) {
            if (activeJob == null) {
                store?.clear()
                mutableState.value = AnalysisRunState.Idle
            }
        }
    }

    fun cancel() {
        val job: Job?
        synchronized(lock) {
            val current = mutableState.value
            val cancelling = when (current) {
                is AnalysisRunState.Running -> AnalysisRunState.Cancelling(
                    current.runId,
                    current.target,
                    current.progress?.copy(
                        state = RunState.CANCELLING,
                        currentTask = "Отмена анализа…",
                        lastHeartbeatEpochMs = System.currentTimeMillis(),
                    ),
                    current.startedAtEpochMs,
                    current.partialResult,
                )
                is AnalysisRunState.Stalled -> AnalysisRunState.Cancelling(
                    current.runId,
                    current.target,
                    current.progress?.copy(
                        state = RunState.CANCELLING,
                        currentTask = "Останавливаем зависший этап…",
                        lastHeartbeatEpochMs = System.currentTimeMillis(),
                    ),
                    current.startedAtEpochMs,
                    current.partialResult,
                )
                else -> return
            }
            mutableState.value = cancelling
            persist(
                AnalysisRunStore.CANCELLING,
                cancelling.runId,
                cancelling.target,
                cancelling.progress,
                cancelling.startedAtEpochMs,
                force = true,
            )
            activeSignal?.cancel()
            job = activeJob
        }
        job?.cancel(CancellationException("Cancelled by user"))
    }

    fun skipStalled() {
        synchronized(lock) {
            val stalled = mutableState.value as? AnalysisRunState.Stalled ?: return
            val engineId = stalled.progress?.engineId ?: return
            if (stalled.progress?.scheduleClass == EngineScheduleClass.FAST) return
            activeSkipController?.request(engineId)
            val now = System.currentTimeMillis()
            val progress = stalled.progress.copy(
                state = RunState.RUNNING,
                currentTask = "Пропускаем зависший движок " + engineId + "…",
                lastHeartbeatEpochMs = now,
            )
            mutableState.value = AnalysisRunState.Running(
                runId = stalled.runId,
                target = stalled.target,
                progress = progress,
                startedAtEpochMs = stalled.startedAtEpochMs,
                partialResult = stalled.partialResult,
            )
            persist(
                AnalysisRunStore.RUNNING,
                stalled.runId,
                stalled.target,
                progress,
                stalled.startedAtEpochMs,
                force = true,
            )
        }
    }

    fun retryStalled() {
        val stalled = mutableState.value as? AnalysisRunState.Stalled ?: return
        val oldJob = activeJob
        val oldWatchdog = watchdogJob
        activeSignal?.cancel()
        oldWatchdog?.cancel()
        oldJob?.cancel()
        scope.launch {
            listOfNotNull(oldJob, oldWatchdog).joinAll()
            synchronized(lock) {
                activeJob = null
                watchdogJob = null
                activeSignal = null
                activeSkipController = null
            }
            start(stalled.target)
        }
    }

    fun clearTerminalState() {
        synchronized(lock) {
            if (activeJob == null && mutableState.value !is AnalysisRunState.Interrupted) {
                mutableState.value = AnalysisRunState.Idle
                store?.clear()
            }
        }
    }

    private fun start(target: AnalysisTargetDescriptor) {
        val context = requireNotNull(appContext) {
            "AnalysisManager.initialize(context) must be called first"
        }
        synchronized(lock) {
            check(activeJob?.isActive != true) { "Another analysis is already running" }
        }

        val runId = nextRunId.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        val signal = AtomicCancellationSignal()
        val skipController = EngineSkipController()
        val initial = EngineProgress(
            engineId = "target.prepare",
            scheduleClass = EngineScheduleClass.FAST,
            state = RunState.RUNNING,
            currentTask = "Подготовка входа",
            lastHeartbeatEpochMs = startedAt,
        )

        synchronized(lock) {
            activeSignal = signal
            activeSkipController = skipController
            mutableState.value = AnalysisRunState.Running(runId, target, initial, startedAt)
            persist(AnalysisRunStore.RUNNING, runId, target, initial, startedAt, force = true)
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val progressSink = ProgressSink {
                    publishProgress(runId, target, startedAt, it)
                }

                val prepared = when (target) {
                    is AnalysisTargetDescriptor.FileUri -> {
                        val uri = Uri.parse(target.uri)
                        val materialized = withContext(Dispatchers.IO) {
                            TargetMaterializer.fromUri(
                                context = context,
                                uri = uri,
                                cancellation = signal,
                                progress = progressSink,
                            )
                        }
                        PreparedInput(
                            files = listOf(materialized.file),
                            knownSha256 = mapOf(
                                materialized.file.absolutePath to materialized.sha256,
                            ),
                        )
                    }
                    is AnalysisTargetDescriptor.InstalledPackage -> {
                        val installed = withContext(Dispatchers.IO) {
                            InstalledAppRepository(context).find(target.packageName)
                        } ?: error("Установленное приложение больше недоступно: ${target.packageName}")
                        PreparedInput(files = installed.apkFiles)
                    }
                }

                var result = withContext(Dispatchers.IO) {
                    FastArtifactIndexer.index(
                        files = prepared.files,
                        cancellation = signal,
                        progress = progressSink,
                        knownSha256 = prepared.knownSha256,
                    )
                }
                publishPartial(runId, result)

                val workspace = AnalysisWorkspace(
                    index = result.index,
                    sources = result.index.sources.zip(prepared.files).map { (descriptor, file) ->
                        WorkspaceSource(descriptor, file)
                    },
                )
                result = RoutedEngineScheduler.execute(
                    initial = result,
                    workspace = workspace,
                    outputRoot = File(context.filesDir, "analysis-results"),
                    cancellation = signal,
                    skipController = skipController,
                    cache = EngineResultCache(File(context.filesDir, "analysis-cache")),
                    progress = progressSink,
                    onPartial = { partial -> publishPartial(runId, partial) },
                )

                synchronized(lock) {
                    if (currentRunId() == runId) {
                        mutableState.value = AnalysisRunState.Completed(runId, target, result)
                        store?.write(
                            AnalysisRunStore.COMPLETED,
                            runId,
                            target,
                            null,
                            startedAt,
                        )
                    }
                }
            } catch (_: AnalysisCancelledException) {
                markCancelled(runId, target, startedAt)
            } catch (_: CancellationException) {
                markCancelled(runId, target, startedAt)
            } catch (failure: Throwable) {
                synchronized(lock) {
                    if (currentRunId() == runId) {
                        val message = failure.message ?: failure.javaClass.simpleName
                        mutableState.value = AnalysisRunState.Failed(runId, target, message)
                        store?.write(
                            AnalysisRunStore.FAILED,
                            runId,
                            target,
                            null,
                            startedAt,
                        )
                    }
                }
            } finally {
                synchronized(lock) {
                    if (currentRunId() == runId &&
                        mutableState.value !is AnalysisRunState.Running &&
                        mutableState.value !is AnalysisRunState.Cancelling &&
                        mutableState.value !is AnalysisRunState.Stalled
                    ) {
                        activeJob = null
                        activeSignal = null
                        activeSkipController?.clear()
                        activeSkipController = null
                        watchdogJob?.cancel()
                        watchdogJob = null
                    }
                }
            }
        }

        synchronized(lock) {
            activeJob = job
            watchdogJob = scope.launch {
                while (job.isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    val now = System.currentTimeMillis()
                    synchronized(lock) {
                        val current = mutableState.value
                        if (current is AnalysisRunState.Running && current.runId == runId) {
                            val heartbeat = current.progress?.lastHeartbeatEpochMs
                                ?: current.startedAtEpochMs
                            val age = now - heartbeat
                            if (age >= STALLED_AFTER_MS) {
                                val stalled = AnalysisRunState.Stalled(
                                    runId = runId,
                                    target = target,
                                    progress = current.progress?.copy(state = RunState.STALLED),
                                    startedAtEpochMs = startedAt,
                                    heartbeatAgeMs = age,
                                    partialResult = current.partialResult,
                                )
                                mutableState.value = stalled
                                persist(
                                    AnalysisRunStore.STALLED,
                                    runId,
                                    target,
                                    stalled.progress,
                                    startedAt,
                                    force = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        job.start()
    }

    private fun publishProgress(
        runId: Long,
        target: AnalysisTargetDescriptor,
        startedAt: Long,
        progress: EngineProgress,
    ) {
        synchronized(lock) {
            when (val current = mutableState.value) {
                is AnalysisRunState.Running -> if (current.runId == runId) {
                    val normalized = progress.copy(state = RunState.RUNNING)
                    mutableState.value = current.copy(progress = normalized)
                    persist(AnalysisRunStore.RUNNING, runId, target, normalized, startedAt)
                }
                is AnalysisRunState.Stalled -> if (current.runId == runId) {
                    val recovered = progress.copy(state = RunState.RUNNING)
                    mutableState.value = AnalysisRunState.Running(
                        runId,
                        target,
                        recovered,
                        startedAt,
                        current.partialResult,
                    )
                    persist(
                        AnalysisRunStore.RUNNING,
                        runId,
                        target,
                        recovered,
                        startedAt,
                        force = true,
                    )
                }
                is AnalysisRunState.Cancelling -> if (current.runId == runId) {
                    val cancelling = progress.copy(
                        state = RunState.CANCELLING,
                        currentTask = "Отмена выполняется… ${progress.currentTask.orEmpty()}",
                    )
                    mutableState.value = current.copy(progress = cancelling)
                    persist(
                        AnalysisRunStore.CANCELLING,
                        runId,
                        target,
                        cancelling,
                        startedAt,
                    )
                }
                else -> Unit
            }
        }
    }

    private fun publishPartial(
        runId: Long,
        result: FastAnalysisResult,
    ) {
        synchronized(lock) {
            when (val current = mutableState.value) {
                is AnalysisRunState.Running -> if (current.runId == runId) {
                    mutableState.value = current.copy(partialResult = result)
                }
                is AnalysisRunState.Cancelling -> if (current.runId == runId) {
                    mutableState.value = current.copy(partialResult = result)
                }
                is AnalysisRunState.Stalled -> if (current.runId == runId) {
                    mutableState.value = current.copy(partialResult = result)
                }
                else -> Unit
            }
        }
    }

    private fun markCancelled(
        runId: Long,
        target: AnalysisTargetDescriptor,
        startedAt: Long,
    ) {
        synchronized(lock) {
            if (currentRunId() == runId) {
                val partial = when (val current = mutableState.value) {
                    is AnalysisRunState.Running -> current.partialResult
                    is AnalysisRunState.Cancelling -> current.partialResult
                    is AnalysisRunState.Stalled -> current.partialResult
                    else -> null
                }
                mutableState.value = AnalysisRunState.Cancelled(runId, target, partial)
                store?.write(
                    AnalysisRunStore.CANCELLED,
                    runId,
                    target,
                    null,
                    startedAt,
                )
            }
        }
    }

    private fun persist(
        status: String,
        runId: Long,
        target: AnalysisTargetDescriptor,
        progress: EngineProgress?,
        startedAt: Long,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAtMs < 2_000L) return
        store?.write(status, runId, target, progress, startedAt)
        lastPersistAtMs = now
    }

    private fun currentRunId(): Long? = when (val state = mutableState.value) {
        is AnalysisRunState.Running -> state.runId
        is AnalysisRunState.Cancelling -> state.runId
        is AnalysisRunState.Stalled -> state.runId
        is AnalysisRunState.Completed -> state.runId
        is AnalysisRunState.Cancelled -> state.runId
        is AnalysisRunState.Failed -> state.runId
        else -> null
    }
}
