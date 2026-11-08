package io.github.ffenuss.modkit.observation

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.ffenuss.modkit.analysis.NativeVisibleCounterParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User-triggered only. Exports normalized values and phases, not screenshots,
 * whole accessibility hierarchies or unrelated UI text.
 */
object NativeMetricObservationExport {
    fun save(context: Context, snapshot: NativeMetricCaptureState): String {
        require(snapshot.complete) { "Eight observations required before exporting" }
        val plan = requireNotNull(snapshot.plan)
        require(snapshot.observations.size == 8)
        val phaseEntries = JSONArray()
        snapshot.observations.forEach { sample ->
            require(sample.sourcePackage == plan.packageName)
            val metric = NativeVisibleCounterParser.parseSingle(plan.metricLabel, sample.nodeText)
            phaseEntries.put(JSONObject()
                .put("phase", sample.phase.name)
                .put("integerValue", metric)
                .put("elapsedRealtimeMs", sample.elapsedRealtimeMs))
        }
        val association = snapshot.association
        require(association == null || (association.fingerprint == plan.fingerprint &&
            !association.selectable && association.packageName == plan.packageName)) {
            "Association is for a different analyzed source"
        }
        val report = JSONObject()
            .put("schema", "modkit.user-visible-metric-observation.v1")
            .put("userInitiated", true)
            .put("captureMethod", "manual accessibility overlay tap")
            .put("selectable", false)
            .put("originalArtifactSha256", plan.originalArtifactSha256)
            .put("targetPackage", plan.packageName)
            .put("module", plan.moduleName)
            .put("abi", plan.candidate.abi)
            .put("symbol", plan.candidate.symbol)
            .put("experimentFingerprint", plan.fingerprint)
            .put("actionLabel", plan.actionLabel)
            .put("metricLabel", plan.metricLabel)
            .put("observations", phaseEntries)
            .put("associationStatus", association?.status ?: "NOT_EVALUATED")
            .put("blocker", association?.blocker ?:
                "User-collected metric text does not establish native code causality")
        val output = report.toString(2).toByteArray(Charsets.UTF_8)
        require(output.size in 32..32_768) { "Unexpected evidence size" }
        val name = "native-metric-" + plan.fingerprint.take(12) + "-" +
            System.currentTimeMillis() + ".json"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val metadata = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/ModKit-validation")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, metadata
            )) { "Android did not create the explicit report file" }
            try {
                requireNotNull(resolver.openOutputStream(uri)).use { it.write(output) }
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
            return "ModKit-validation/" + name
        }
        val folder = requireNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        ) { "App-private external storage is unavailable" }
        require(folder.isDirectory || folder.mkdirs())
        File(folder, name).writeBytes(output)
        return "app Documents/" + name
    }
}
