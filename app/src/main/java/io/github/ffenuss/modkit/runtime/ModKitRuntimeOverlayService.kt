package io.github.ffenuss.modkit.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.ffenuss.modkit.MainActivity
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicitly user-started overlay. The window itself cannot change another
 * process; each switch is acknowledged by the verified injected game probe.
 * A missing module, stale APK or failed byte comparison leaves the switch OFF.
 */
class ModKitRuntimeOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: WindowManager? = null
    private var root: View? = null
    private var activeSha: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START ||
            !Settings.canDrawOverlays(this)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        val sha = intent.getStringExtra(EXTRA_SHA)
        if (sha == null || !SHA_PATTERN.matches(sha)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val record = AutoModBuildRecord.load(this, sha)
        if (record == null || record.runtimeMenuItems.isEmpty() ||
            record.runtimeMenuItems.size > 24
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A new launch reconfigures the injected menu and resets native patches.
        // Rebuild the visible switches too, even if the same APK was launched again.

        createChannel()
        startForeground(
            NOTIFICATION_ID,
            notification("Переключатели готовятся"),
        )
        val authority = record.plan.packageName +
            BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX
        val installed = runCatching {
            AndroidRepackedRuntimeProbeTransport(this).inspectInstalled(
                record.plan.packageName, authority,
            )
        }.getOrNull()
        if (installed == null || !installed.exported || !installed.enabled ||
            installed.providerClassName != BinaryAndroidManifestProbeInjector.PROVIDER_CLASS ||
            installed.signerCertificateSha256.map(String::lowercase).toSet() !=
                record.plan.signerCertificateSha256.map(String::lowercase).toSet()
        ) {
            Toast.makeText(this, "APK с проверенным мод-меню не установлен.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        removeOverlay()
        activeSha = sha
        showOverlay(record, authority)
        // Android 13+ permits foreground-service notifications without
        // POST_NOTIFICATIONS consent. Update it through startForeground rather
        // than posting a separate notification that would require that grant.
        startForeground(
            NOTIFICATION_ID,
            notification("Меню доступно поверх игры · все моды сначала выключены"),
        )
        return START_NOT_STICKY
    }

    private fun showOverlay(record: AutoModBuildRecord, authority: String) {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        manager = windowManager
        val dp = resources.displayMetrics.density
        fun pixels(value: Int) = (value * dp + 0.5f).toInt()
        fun text(label: String, size: Float): TextView =
            TextView(this).apply {
                this.text = label
                setTextSize(size)
                setTextColor(Color.WHITE)
                setPadding(pixels(8), pixels(6), pixels(8), pixels(6))
            }
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(244, 26, 33, 31))
            setPadding(pixels(5), pixels(5), pixels(5), pixels(5))
        }
        val bubble = Button(this).apply {
            text = "MK"
            isAllCaps = false
            setTextColor(Color.WHITE)
        }
        host.addView(bubble, LinearLayout.LayoutParams(pixels(62), pixels(48)))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(pixels(6), pixels(6), pixels(6), pixels(6))
        }
        val heading = text("ModKit · моды", 17f)
        panel.addView(heading)
        panel.addView(text("Выключены при запуске. Включайте только нужные.", 12f))
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(panel)
        }
        val maxWidth = (resources.displayMetrics.widthPixels - pixels(30))
            .coerceAtMost(pixels(335))
        host.addView(
            scroll,
            LinearLayout.LayoutParams(maxWidth, pixels(430).coerceAtMost(
                resources.displayMetrics.heightPixels * 2 / 3,
            )).apply { topMargin = pixels(6) },
        )
        scroll.visibility = View.GONE

        val transport = AndroidRepackedRuntimeProbeTransport(this)
        for (item in record.runtimeMenuItems) {
            val toggle = Switch(this).apply {
                text = item.label
                setTextColor(Color.WHITE)
                textSize = 13f
                isChecked = false
            }
            panel.addView(toggle)
            panel.addView(text(item.detail, 11f))
            attachToggleListener(toggle, transport, authority, item.id)
        }

        val close = Button(this).apply {
            text = "Закрыть меню"
            setOnClickListener { stopSelf() }
        }
        panel.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        bubble.setOnClickListener {
            scroll.visibility = if (scroll.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = pixels(12)
            y = pixels(80)
        }
        try {
            windowManager.addView(host, params)
            root = host
        } catch (error: Exception) {
            Toast.makeText(this, "Android не разрешил открыть окно поверх игры.", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun attachToggleListener(
        toggle: Switch,
        transport: AndroidRepackedRuntimeProbeTransport,
        authority: String,
        id: String,
    ) {
        toggle.setOnCheckedChangeListener { _, desired ->
            if (!toggle.isEnabled) return@setOnCheckedChangeListener
            toggle.isEnabled = false
            scope.launch {
                val accepted = runCatching {
                    withContext(Dispatchers.IO) {
                        transport.setTestMenuSwitch(authority, id, desired)
                    }
                }.getOrDefault(false)
                if (!accepted) {
                    toggle.setOnCheckedChangeListener(null)
                    toggle.isChecked = !desired
                    attachToggleListener(toggle, transport, authority, id)
                    Toast.makeText(
                        this@ModKitRuntimeOverlayService,
                        "Невозможно переключить мод: нет подтверждения от игры.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                toggle.isEnabled = true
            }
        }
    }

    private fun removeOverlay() {
        val view = root ?: return
        runCatching { manager?.removeViewImmediate(view) }
        root = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, "ModKit · мод-меню", NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ModKitRuntimeOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val back = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("ModKit · мод-меню")
            .setContentText(message)
            .setContentIntent(back)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Закрыть", stop)
            .build()
    }

    override fun onDestroy() {
        removeOverlay()
        activeSha = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "modkit-runtime-menu"
        private const val NOTIFICATION_ID = 2713
        private const val ACTION_START = "io.github.ffenuss.modkit.RUNTIME_MENU_START"
        private const val ACTION_STOP = "io.github.ffenuss.modkit.RUNTIME_MENU_STOP"
        private const val EXTRA_SHA = "sha"
        private val SHA_PATTERN = Regex("[0-9a-fA-F]{64}")

        fun start(context: Context, artifactSha: String) {
            require(SHA_PATTERN.matches(artifactSha))
            require(Settings.canDrawOverlays(context)) {
                "Разрешите ModKit показывать окна поверх игр."
            }
            val intent = Intent(context, ModKitRuntimeOverlayService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SHA, artifactSha)
            context.startForegroundService(intent)
        }
    }
}
