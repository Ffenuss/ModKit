package io.github.ffenuss.modkit.analysis

import android.content.Context
import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import org.json.JSONObject
import java.io.File

internal class AnalysisRunStore(context: Context) {
    private val file = File(context.filesDir, "analysis-run.json")
    private val lock = Any()

    data class Snapshot(
        val status: String,
        val runId: Long,
        val target: AnalysisTargetDescriptor,
        val progress: EngineProgress?,
        val startedAtEpochMs: Long,
        val artifactSha256: String?,
    )

    fun load(): Snapshot? = synchronized(lock) {
        if (!file.isFile) return@synchronized null
        runCatching {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val target = when (obj.getString("targetType")) {
                "uri" -> AnalysisTargetDescriptor.FileUri(
                    uri = obj.getString("targetValue"),
                    label = obj.getString("targetLabel"),
                )
                "package" -> AnalysisTargetDescriptor.InstalledPackage(
                    packageName = obj.getString("targetValue"),
                    label = obj.getString("targetLabel"),
                )
                else -> return@runCatching null
            }
            val progressObj = obj.optJSONObject("progress")
            val progress = progressObj?.let {
                EngineProgress(
                    engineId = it.optString("engineId", "artifact.fast-index"),
                    scheduleClass = runCatching {
                        EngineScheduleClass.valueOf(it.optString("scheduleClass", "FAST"))
                    }.getOrDefault(EngineScheduleClass.FAST),
                    state = runCatching {
                        RunState.valueOf(it.optString("state", "RUNNING"))
                    }.getOrDefault(RunState.RUNNING),
                    currentTask = it.optString("currentTask").takeIf(String::isNotBlank),
                    currentArtifact = it.optString("currentArtifact").takeIf(String::isNotBlank),
                    processed = it.optLong("processed", Long.MIN_VALUE).takeIf { value -> value != Long.MIN_VALUE },
                    total = it.optLong("total", Long.MIN_VALUE).takeIf { value -> value != Long.MIN_VALUE },
                    lastHeartbeatEpochMs = it.optLong("lastHeartbeatEpochMs", Long.MIN_VALUE)
                        .takeIf { value -> value != Long.MIN_VALUE },
                )
            }
            Snapshot(
                status = obj.getString("status"),
                runId = obj.getLong("runId"),
                target = target,
                progress = progress,
                startedAtEpochMs = obj.optLong("startedAtEpochMs", System.currentTimeMillis()),
                artifactSha256 = obj.optString("artifactSha256")
                    .takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                    ?.lowercase(),
            )
        }.getOrNull()
    }

    fun write(
        status: String,
        runId: Long,
        target: AnalysisTargetDescriptor,
        progress: EngineProgress?,
        startedAtEpochMs: Long,
        artifactSha256: String? = null,
    ) = synchronized(lock) {
        val previousArtifactSha = runCatching {
            if (!file.isFile) null else JSONObject(file.readText(Charsets.UTF_8))
                .takeIf { it.optLong("runId", Long.MIN_VALUE) == runId }
                ?.optString("artifactSha256")
                ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                ?.lowercase()
        }.getOrNull()
        val resolvedArtifactSha = artifactSha256
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.lowercase()
            ?: previousArtifactSha

        val obj = JSONObject()
            .put("status", status)
            .put("runId", runId)
            .put("targetLabel", target.label)
            .put("startedAtEpochMs", startedAtEpochMs)

        resolvedArtifactSha?.let { obj.put("artifactSha256", it) }

        when (target) {
            is AnalysisTargetDescriptor.FileUri -> {
                obj.put("targetType", "uri")
                obj.put("targetValue", target.uri)
            }
            is AnalysisTargetDescriptor.InstalledPackage -> {
                obj.put("targetType", "package")
                obj.put("targetValue", target.packageName)
            }
        }

        progress?.let {
            obj.put(
                "progress",
                JSONObject()
                    .put("engineId", it.engineId)
                    .put("scheduleClass", it.scheduleClass.name)
                    .put("state", it.state.name)
                    .put("currentTask", it.currentTask)
                    .put("currentArtifact", it.currentArtifact)
                    .put("processed", it.processed)
                    .put("total", it.total)
                    .put("lastHeartbeatEpochMs", it.lastHeartbeatEpochMs),
            )
        }

        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(obj.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not persist analysis state" }
        }
    }

    fun clear() = synchronized(lock) {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    companion object {
        const val RUNNING = "RUNNING"
        const val CANCELLING = "CANCELLING"
        const val STALLED = "STALLED"
        const val COMPLETED = "COMPLETED"
        const val CANCELLED = "CANCELLED"
        const val FAILED = "FAILED"
    }
}
