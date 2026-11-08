package io.github.ffenuss.modkit.observation

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ffenuss.modkit.analysis.NativeVisibleCounterParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * System-authorized, opt-in service. Unlike a generic screen reader it does
 * not process accessibility events, inspect other packages, or run without
 * a user-started experiment. Only the capture button reads the *active*
 * game's UI hierarchy; only one exact bounded integer node is kept.
 *
 * No gesture injection, screenshot, keylogging, network or code modification.
 */
class NativeMetricAccessibilityService : AccessibilityService() {
    companion object {
        private val connectedMutable = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connectedMutable
        private const val MAX_VISITED_NODES = 2048
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windows by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var overlay: LinearLayout? = null
    private var progressLabel: TextView? = null
    private var captureButton: Button? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedMutable.value = true
        scope.launch {
            NativeMetricCaptureSession.state.collect { snapshot ->
                if (snapshot.active) {
                    showOrUpdateOverlay(snapshot)
                } else {
                    removeOverlay()
                }
            }
        }
    }

    // Deliberately no event-based text inspection or background monitoring.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        NativeMetricCaptureSession.clear()
        removeOverlay()
    }

    override fun onDestroy() {
        connectedMutable.value = false
        NativeMetricCaptureSession.clear()
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private fun showOrUpdateOverlay(state: NativeMetricCaptureState) {
        if (overlay == null) {
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (12 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
                background = GradientDrawable().apply {
                    setColor(Color.argb(246, 20, 25, 34))
                    cornerRadius = 20 * resources.displayMetrics.density
                }
            }
            val label = TextView(this).apply {
                setTextColor(Color.WHITE)
                maxLines = 5
                textSize = 12f
            }
            val capture = Button(this).apply {
                text = "Снять значение"
                setOnClickListener { captureChosenMetric() }
            }
            val stop = Button(this).apply {
                text = "Стоп и очистить"
                setOnClickListener { NativeMetricCaptureSession.clear() }
            }
            panel.addView(label)
            panel.addView(capture)
            panel.addView(stop)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (8 * resources.displayMetrics.density).toInt()
                y = (80 * resources.displayMetrics.density).toInt()
            }
            try {
                windows.addView(panel, params)
                overlay = panel
                progressLabel = label
                captureButton = capture
            } catch (_: RuntimeException) {
                NativeMetricCaptureSession.reportError(
                    "Android не разрешил показать окно записи. Выключите эксперимент."
                )
                NativeMetricCaptureSession.clear()
                return
            }
        }
        val phase = state.nextPhase
        val title = "ModKit · " + state.observations.size + "/8\n" +
            (phase?.userInstruction() ?: "Запись завершена.")
        progressLabel?.text = title + (state.error?.let { "\n" + it } ?: "")
        captureButton?.isEnabled = phase != null
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        progressLabel = null
        captureButton = null
        runCatching { windows.removeViewImmediate(view) }
    }

    private fun captureChosenMetric() {
        val snapshot = NativeMetricCaptureSession.state.value
        val plan = snapshot.plan ?: return
        if (!snapshot.active) return
        val root = rootInActiveWindow
        if (root == null) {
            NativeMetricCaptureSession.reportError(
                "Игра не предоставляет доступное дерево интерфейса."
            )
            return
        }
        if (root.packageName?.toString() != plan.packageName) {
            NativeMetricCaptureSession.reportError(
                "Сначала откройте выбранную игру: " + plan.packageName
            )
            return
        }
        val nodeText = findOneVisibleMetric(root, plan.packageName, plan.metricLabel)
        if (nodeText == null) {
            NativeMetricCaptureSession.reportError(
                "Точное значение не найдено или встречается несколько раз. " +
                    "Изображения внутри SurfaceView пока не поддерживаются."
            )
            return
        }
        NativeMetricCaptureSession.recordFromUserTap(
            packageName = plan.packageName,
            metricNodeText = nodeText,
            elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        )
    }

    /** Single bounded tree walk; never saves or logs other UI text. */
    private fun findOneVisibleMetric(
        root: AccessibilityNodeInfo,
        packageName: String,
        label: String,
    ): String? {
        NativeVisibleCounterParser.requireLabel(label)
        val prefix = Regex("^" + Regex.escape(label) + "\\s*[:=]")
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        var match: String? = null
        while (pending.isNotEmpty()) {
            if (++visited > MAX_VISITED_NODES) return null
            val node = pending.removeFirst()
            if (node.packageName?.toString() != packageName) continue
            if (node.isVisibleToUser) {
                val text = node.text?.toString()
                if (text != null && text.length <= 160 && prefix.containsMatchIn(text)) {
                    if (match != null) return null
                    match = text
                }
                if (node.childCount > 256) return null
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let(pending::add)
                }
            }
        }
        if (match == null) return null
        return runCatching {
            NativeVisibleCounterParser.parseSingle(label, match)
            match
        }.getOrNull()
    }
}
