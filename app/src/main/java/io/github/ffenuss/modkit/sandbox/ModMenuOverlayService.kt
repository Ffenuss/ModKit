package io.github.ffenuss.modkit.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.ffenuss.modkit.R
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import java.util.concurrent.Executors

class ModMenuOverlayService : Service() {
    private val executor =
        Executors.newSingleThreadExecutor()
    private val main =
        android.os.Handler(
            Looper.getMainLooper(),
        )

    private var windowManager:
        WindowManager? = null
    private var overlayView:
        View? = null
    private var currentConfig:
        SandboxOverlayConfig? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                "ModKit Sandbox overlay запускается…",
            ),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val encoded =
            intent?.getStringExtra(
                RootSandboxOverlayLauncher
                    .CONFIG_EXTRA,
            )
        if (encoded.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val parsed =
            runCatching {
                SandboxOverlayConfigCodec
                    .decode(encoded)
            }.getOrElse {
                stopSelf()
                return START_NOT_STICKY
            }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        currentConfig = parsed
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                "Mod menu: " +
                    parsed.items.size +
                    " модов",
            ),
        )
        showOverlay(parsed)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?,
    ): IBinder? = null

    private fun showOverlay(
        config: SandboxOverlayConfig,
    ) {
        removeOverlay()

        val wm =
            getSystemService(
                WINDOW_SERVICE,
            ) as WindowManager
        windowManager = wm

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(4),
                    dp(4),
                    dp(4),
                    dp(4),
                )
            }

        val floating =
            Button(this).apply {
                text = "MK"
                textSize = 13f
                minWidth = dp(52)
                minimumWidth = dp(52)
                minHeight = dp(46)
                minimumHeight = dp(46)
            }
        root.addView(
            floating,
            LinearLayout.LayoutParams(
                dp(58),
                dp(50),
            ),
        )

        val panel =
            buildPanel(config).apply {
                visibility =
                    View.GONE
            }
        root.addView(
            panel,
            LinearLayout.LayoutParams(
                dp(300),
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
            },
        )

        floating.setOnClickListener {
            panel.visibility =
                if (
                    panel.visibility ==
                    View.VISIBLE
                ) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
        }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                WindowManager.LayoutParams
                    .WRAP_CONTENT,
                WindowManager.LayoutParams
                    .TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams
                        .FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity =
                    Gravity.TOP or
                        Gravity.START
                x = dp(12)
                y = dp(110)
            }

        installDrag(
            handle = floating,
            root = root,
            params = params,
            windowManager = wm,
        )

        runCatching {
            wm.addView(
                root,
                params,
            )
        }.onFailure {
            stopSelf()
            return
        }
        overlayView = root
    }

    private fun buildPanel(
        config: SandboxOverlayConfig,
    ): View {
        val body =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(12),
                    dp(10),
                    dp(12),
                    dp(10),
                )
                background =
                    GradientDrawable()
                        .apply {
                            setColor(
                                Color.argb(
                                    238,
                                    28,
                                    28,
                                    32,
                                ),
                            )
                            cornerRadius =
                                dp(14).toFloat()
                        }
            }

        body.addView(
            TextView(this).apply {
                text = "ModKit"
                setTextColor(
                    Color.WHITE,
                )
                textSize = 18f
            },
        )
        body.addView(
            TextView(this).apply {
                text =
                    config.packageName +
                        " · PID " +
                        config.pid
                setTextColor(
                    Color.LTGRAY,
                )
                textSize = 11f
                setPadding(
                    0,
                    0,
                    0,
                    dp(8),
                )
            },
        )

        val list =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }
        config.items.forEach {
            item ->
            list.addView(
                buildToggleRow(
                    config = config,
                    item = item,
                ),
            )
        }

        val scroll =
            ScrollView(this).apply {
                isFillViewport = false
                addView(
                    list,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams
                            .MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams
                            .WRAP_CONTENT,
                    ),
                )
            }
        body.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                    .MATCH_PARENT,
                dp(360),
            ),
        )

        val close =
            Button(this).apply {
                text = "Закрыть меню"
                setOnClickListener {
                    stopSelf()
                }
            }
        body.addView(
            close,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams
                    .MATCH_PARENT,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
            ).apply {
                topMargin = dp(6)
            },
        )
        return body
    }

    @Suppress("DEPRECATION")
    private fun buildToggleRow(
        config: SandboxOverlayConfig,
        item: SandboxOverlayItem,
    ): View {
        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
                setPadding(
                    0,
                    dp(3),
                    0,
                    dp(3),
                )
            }

        val title =
            TextView(this).apply {
                text = item.title
                setTextColor(
                    Color.WHITE,
                )
                textSize = 13f
                maxLines = 2
            }
        row.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams
                    .WRAP_CONTENT,
                1f,
            ),
        )

        val toggle =
            Switch(this).apply {
                isChecked =
                    item.enabled
            }
        var internalChange =
            false
        toggle.setOnCheckedChangeListener {
                button,
                checked,
            ->
            if (internalChange) {
                return@setOnCheckedChangeListener
            }
            button.isEnabled = false
            executor.execute {
                val result =
                    runCatching {
                        RootSandboxLiveToggleCoordinator
                            .setEnabled(
                                packageName =
                                    config.packageName,
                                pid =
                                    config.pid,
                                target =
                                    item
                                        .toLiveTarget(),
                                enabled =
                                    checked,
                                cancellation =
                                    AtomicCancellationSignal(),
                            )
                    }
                main.post {
                    if (result.isFailure) {
                        internalChange = true
                        toggle.isChecked =
                            !checked
                        internalChange = false
                        Toast.makeText(
                            this,
                            result.exceptionOrNull()
                                ?.message
                                ?: "Live toggle failed",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    button.isEnabled =
                        true
                }
            }
        }
        row.addView(
            toggle,
        )
        return row
    }

    private fun installDrag(
        handle: View,
        root: View,
        params: WindowManager.LayoutParams,
        windowManager: WindowManager,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        handle.setOnTouchListener {
                _,
                event,
            ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX =
                        event.rawX
                    downRawY =
                        event.rawY
                    startX =
                        params.x
                    startY =
                        params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx =
                        event.rawX -
                            downRawX
                    val dy =
                        event.rawY -
                            downRawY
                    if (
                        kotlin.math.abs(dx) >
                        dp(4) ||
                        kotlin.math.abs(dy) >
                        dp(4)
                    ) {
                        moved = true
                    }
                    params.x =
                        startX +
                            dx.toInt()
                    params.y =
                        startY +
                            dy.toInt()
                    runCatching {
                        windowManager
                            .updateViewLayout(
                                root,
                                params,
                            )
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        handle.performClick()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun removeOverlay() {
        val view =
            overlayView
        val wm =
            windowManager
        overlayView = null
        if (
            view != null &&
            wm != null
        ) {
            runCatching {
                wm.removeView(
                    view,
                )
            }
        }
    }

    private fun createNotificationChannel() {
        val manager =
            getSystemService(
                NOTIFICATION_SERVICE,
            ) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ModKit Sandbox overlay",
                NotificationManager
                    .IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(
        text: String,
    ): Notification =
        Notification.Builder(
            this,
            CHANNEL_ID,
        )
            .setContentTitle(
                "ModKit Sandbox",
            )
            .setContentText(
                text,
            )
            .setSmallIcon(
                R.drawable
                    .ic_modkit_notification,
            )
            .setOngoing(true)
            .build()

    private fun dp(
        value: Int,
    ): Int =
        (
            value *
                resources.displayMetrics
                    .density
            ).toInt()

    companion object {
        private const val CHANNEL_ID =
            "modkit_sandbox_overlay"
        private const val NOTIFICATION_ID =
            7109
    }
}
