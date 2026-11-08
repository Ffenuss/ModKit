package io.github.ffenuss.modkit.observation

import io.github.ffenuss.modkit.analysis.NativeMetricAssociation
import io.github.ffenuss.modkit.analysis.NativeMetricExperimentPlan
import io.github.ffenuss.modkit.analysis.NativeScalarEffectPhase
import io.github.ffenuss.modkit.analysis.NativeVisibleCounterParser
import io.github.ffenuss.modkit.analysis.NativeVisibleMetricExperiment
import io.github.ffenuss.modkit.analysis.NativeVisibleMetricObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Volatile, user-started measurement log. No disk, background persistence,
 * arbitrary screen text, external network, or automatic patch execution.
 */
data class NativeMetricCaptureState(
    val plan: NativeMetricExperimentPlan? = null,
    val active: Boolean = false,
    val observations: List<NativeVisibleMetricObservation> = emptyList(),
    val error: String? = null,
    val association: NativeMetricAssociation? = null,
) {
    val nextPhase: NativeScalarEffectPhase?
        get() = if (active) NativeScalarEffectPhase.entries.getOrNull(observations.size) else null
    val complete: Boolean get() = plan != null && !active &&
        observations.size == NativeScalarEffectPhase.entries.size
}

/**
 * Explicit foreground capture state machine. Observations are accepted ONLY
 * after the corresponding capture button was pressed, from a single exact
 * package and metric, with strictly increasing monotonic timestamps.
 *
 * Samples do not authenticate that any third-party code was modified: the
 * resulting correlation remains informational and unselectable.
 */
object NativeMetricCaptureSession {
    private const val MIN_CAPTURE_SPACING_MS = 250L
    private val mutable = MutableStateFlow(NativeMetricCaptureState())
    val state: StateFlow<NativeMetricCaptureState> = mutable

    @Synchronized
    fun begin(plan: NativeMetricExperimentPlan) {
        val expected = NativeVisibleMetricExperiment.prepare(
            plan.originalArtifactSha256, plan.packageName, plan.moduleName,
            plan.actionLabel, plan.metricLabel, plan.candidate, plan.replacementValue,
        )
        require(expected == plan) { "Experiment identity does not match original ELF proposal" }
        require(!mutable.value.active) { "Another recording is already in progress" }
        mutable.value = NativeMetricCaptureState(plan = plan, active = true)
    }

    @Synchronized
    fun recordFromUserTap(
        packageName: String,
        metricNodeText: String,
        elapsedRealtimeMs: Long,
    ): Boolean {
        val old = mutable.value
        val plan = old.plan
        val phase = old.nextPhase
        if (!old.active || plan == null || phase == null) {
            mutable.value = old.copy(error = "Запись не запущена.")
            return false
        }
        if (packageName != plan.packageName) {
            mutable.value = old.copy(error = "Откройте только выбранную игру: " + plan.packageName)
            return false
        }
        val lastTimestamp = old.observations.lastOrNull()?.elapsedRealtimeMs
        if (elapsedRealtimeMs <= 0 ||
            (lastTimestamp != null && elapsedRealtimeMs - lastTimestamp < MIN_CAPTURE_SPACING_MS)) {
            mutable.value = old.copy(error = "Повторите действие и снимите показатель отдельно.")
            return false
        }
        val value = runCatching {
            NativeVisibleCounterParser.parseSingle(plan.metricLabel, metricNodeText)
        }.getOrElse {
            mutable.value = old.copy(error = "Показатель не совпал с выбранным названием.")
            return false
        }
        // Store ONLY the normalized label and bounded integer, not unrelated
        // accessibility-node content or any other screen text.
        val normalized = plan.metricLabel + ": " + value
        val samples = old.observations + NativeVisibleMetricObservation(
            phase = phase,
            sourcePackage = packageName,
            nodeText = normalized,
            elapsedRealtimeMs = elapsedRealtimeMs,
        )
        mutable.value = old.copy(
            active = samples.size < NativeScalarEffectPhase.entries.size,
            observations = samples,
            error = null,
            association = null,
        )
        return true
    }

    /** Explicit, local-only evaluation; never turns association into a PATCH. */
    @Synchronized
    fun evaluate(): NativeMetricAssociation? {
        val old = mutable.value
        if (!old.complete) {
            mutable.value = old.copy(error = "Нужно восемь отдельных наблюдений.")
            return null
        }
        val plan = requireNotNull(old.plan)
        return runCatching {
            NativeVisibleMetricExperiment.assess(plan, old.observations)
        }.fold(
            onSuccess = { association ->
                mutable.value = old.copy(association = association, error = null)
                association
            },
            onFailure = {
                mutable.value = old.copy(
                    error = "Повторяемый эффект не подтверждён. Проверьте порядок и значения.",
                    association = null,
                )
                null
            },
        )
    }

    /** Only bounded local status messages; never retain arbitrary screen text. */
    @Synchronized
    fun reportError(message: String) {
        mutable.value = mutable.value.copy(error = message.take(180))
    }

    @Synchronized
    fun clear() {
        mutable.value = NativeMetricCaptureState()
    }
}

/** User instructions; ON/OFF are manually performed, NOT driven by ModKit. */
fun NativeScalarEffectPhase.userInstruction(): String = when (this) {
    NativeScalarEffectPhase.START ->
        "0 · Откройте игру до первого действия. Снимите исходное значение."
    NativeScalarEffectPhase.UNMODIFIED_ACTION ->
        "1 · Без патча выполните выбранное действие один раз и снимите значение."
    NativeScalarEffectPhase.UNMODIFIED_CONTROL_ACTION ->
        "2 · Повторите действие без патча и снимите значение."
    NativeScalarEffectPhase.PATCH_ENABLED_ACTION ->
        "3 · Только в разрешённой тестовой среде включите проверяемое изменение, выполните действие и снимите значение."
    NativeScalarEffectPhase.PATCH_DISABLED_ACTION ->
        "4 · Выключите изменение, выполните действие и снимите значение."
    NativeScalarEffectPhase.PATCH_ENABLED_REPEAT_ACTION ->
        "5 · Повторно включите изменение, выполните действие и снимите значение."
    NativeScalarEffectPhase.PATCH_DISABLED_REPEAT_ACTION ->
        "6 · Повторно выключите изменение, выполните действие и снимите значение."
    NativeScalarEffectPhase.OVERLAY_CLOSED_ACTION ->
        "7 · Закройте меню изменения, выполните действие и снимите значение."
}
