package io.github.ffenuss.modkit.spacecompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * User-initiated, clearly labelled information overlay atop the user's normal
 * installed game. No virtualization, process injection, runtime code writes,
 * elevated permissions, or pretend mod switches.
 */
public final class SpaceOverlayService extends Service {
    static final String ACTION_START = "io.github.ffenuss.modkit.spacecompanion.OVERLAY_START";
    static final String ACTION_STOP = "io.github.ffenuss.modkit.spacecompanion.OVERLAY_STOP";
    static final String EXTRA_PACKAGE = "package";
    static final String EXTRA_ARTIFACT = "artifact";
    static final String EXTRA_PUBLISHER = "publisher";

    private static final String CHANNEL = "modkit_overlay_visible";
    private static final int NOTIFICATION_ID = 1204;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private LinearLayout root;
    private LinearLayout details;
    private TextView summary;
    private Button liveMoveButton;
    private boolean liveMoveEnabled;
    private boolean closing;
    private SpaceCatalogReader.Entry active;
    private ContentObserver changes;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || !Settings.canDrawOverlays(this)) {
            requestStop();
            return START_NOT_STICKY;
        }
        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        String artifact = intent.getStringExtra(EXTRA_ARTIFACT);
        String publisher = intent.getStringExtra(EXTRA_PUBLISHER);
        if (!OverlaySessionPolicy.valid(pkg, artifact, publisher)) {
            requestStop();
            return START_NOT_STICKY;
        }

        // Always start in the visible foreground-activity interaction path.
        // Do not use an overlay permission to start a service from background.
        showNotification();
        closing = false;
        final SpaceCatalogReader.Entry previous = active;
        if (previous != null) {
            io.execute(() -> RuntimeControlBridge.disableForCleanup(this, previous));
        }
        final int token = generation.incrementAndGet();
        removeOverlay();
        unregisterPublisher();
        io.execute(() -> {
            SpaceCatalogReader.Catalog catalog = SpaceCatalogReader.load(this);
            SpaceCatalogReader.Entry match = OverlaySessionPolicy.find(
                    catalog.entries, pkg, artifact, publisher);
            SpaceCatalogReader.Verification verified = match == null ?
                    null : SpaceCatalogReader.verify(this, match);
            main.post(() -> {
                if (token != generation.get()) return;
                if (match == null || verified == null || !verified.ready ||
                        !Settings.canDrawOverlays(this)) {
                    requestStop();
                    return;
                }
                active = match;
                try {
                    addOverlay(match);
                    observePublisher(publisher);
                } catch (RuntimeException denied) {
                    requestStop();
                }
            });
        });
        return START_NOT_STICKY;
    }

    private void showNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "ModKit · плавающее меню",
                NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, SpaceOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, SpaceCompanionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openAction = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("ModKit · меню поверх игры")
                .setContentText("Только сведения о подтверждённой игре")
                .setContentIntent(openAction)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Закрыть", stopAction)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION_ID, notification);
    }

    private void observePublisher(String publisher) {
        changes = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                final SpaceCatalogReader.Entry session = active;
                if (session == null) return;
                final int token = generation.get();
                io.execute(() -> {
                    SpaceCatalogReader.Verification check =
                            SpaceCatalogReader.verify(SpaceOverlayService.this, session);
                    main.post(() -> {
                        if (generation.get() == token && !check.ready) requestStop();
                    });
                });
            }
        };
        Uri uri = Uri.parse("content://" + publisher + ".space-menu/catalog");
        try {
            getContentResolver().registerContentObserver(uri, false, changes);
        } catch (SecurityException | IllegalArgumentException unavailable) {
            changes = null;
            requestStop();
        }
    }

    private void addOverlay(SpaceCatalogReader.Entry selected) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xEF17212E);
        int pad = dp(6);
        root.setPadding(pad, pad, pad, pad);

        Button bubble = new Button(this);
        bubble.setText("MK");
        bubble.setAllCaps(false);
        bubble.setContentDescription("ModKit · открыть плавающее меню");
        root.addView(bubble);
        details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(8), dp(4), dp(8), dp(6));
        TextView label = new TextView(this);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(15);
        label.setText("Подтверждённая игра: " + selected.packageName);
        details.addView(label);
        summary = new TextView(this);
        summary.setTextColor(0xFFF1C75B);
        summary.setText("Только сведения об игре — модификации не активны");
        details.addView(summary);

        if (RuntimeControlBridge.supports(selected)) {
            // Only our explicitly cooperative game fixture has an executor.
            // No runtime switch is rendered for unsupported third-party games.
            liveMoveButton = new Button(this);
            liveMoveButton.setAllCaps(false);
            liveMoveButton.setText("Движение ×2: проверка...");
            liveMoveButton.setEnabled(false);
            liveMoveButton.setOnClickListener(v -> toggleLiveMovement());
            details.addView(liveMoveButton);
        }
        Button recheck = new Button(this);
        recheck.setText("Сверить APK");
        recheck.setAllCaps(false);
        recheck.setOnClickListener(v -> recheck());
        details.addView(recheck);
        Button close = new Button(this);
        close.setText("Закрыть меню");
        close.setAllCaps(false);
        close.setOnClickListener(v -> requestStop());
        details.addView(close);
        details.setVisibility(View.GONE);
        root.addView(details);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(110);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int baseX, baseY;
            boolean moved;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        baseX = params.x;
                        baseY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (Math.abs(dx) + Math.abs(dy) > dp(8)) moved = true;
                        if (moved && root != null) {
                            params.x = Math.max(0, baseX + (int) dx);
                            params.y = Math.max(0, baseY + (int) dy);
                            windows.updateViewLayout(root, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            details.setVisibility(details.getVisibility() == View.GONE ?
                                    View.VISIBLE : View.GONE);
                            if (details.getVisibility() == View.VISIBLE) recheck();
                        }
                        return true;
                    default: return false;
                }
            }
        });
        windows.addView(root, params);
        refreshLiveControl();
    }

    private void refreshLiveControl() {
        final SpaceCatalogReader.Entry session = active;
        if (session == null || liveMoveButton == null || closing) return;
        liveMoveButton.setEnabled(false);
        liveMoveButton.setText("Движение ×2: проверка...");
        final int token = generation.get();
        io.execute(() -> {
            RuntimeControlBridge.State state = RuntimeControlBridge.read(this, session);
            main.post(() -> {
                if (token != generation.get() || closing || liveMoveButton == null) return;
                liveMoveEnabled = state.enabled;
                liveMoveButton.setEnabled(state.reachable && state.active);
                liveMoveButton.setText(state.active ?
                        (state.enabled ? "Движение ×2: включено" : "Движение ×2: выключено") :
                        "Движение ×2: игра не активна");
            });
        });
    }

    private void toggleLiveMovement() {
        final SpaceCatalogReader.Entry session = active;
        if (session == null || liveMoveButton == null || closing) return;
        final boolean next = !liveMoveEnabled;
        final int token = generation.get();
        liveMoveButton.setEnabled(false);
        liveMoveButton.setText("Движение ×2: применение...");
        io.execute(() -> {
            RuntimeControlBridge.State result = RuntimeControlBridge.set(this, session, next);
            main.post(() -> {
                if (token != generation.get() || closing || liveMoveButton == null) return;
                if (!result.reachable || !result.active || result.enabled != next) {
                    // Do not leave an ambiguous state or false-success toggle.
                    requestStop();
                    return;
                }
                liveMoveEnabled = result.enabled;
                liveMoveButton.setEnabled(true);
                liveMoveButton.setText(result.enabled ?
                        "Движение ×2: включено" : "Движение ×2: выключено");
                if (summary != null) summary.setText(result.enabled ?
                        "Применено в тестовой игре: движение ×2" :
                        "Только сведения об игре — модификации не активны");
            });
        });
    }

    /**
     * Cleanup always sends the safe OFF command, even if ModKit has withdrawn
     * the original catalog entry. Deleting an authorization must not leave
     * a previously enabled fixture movement multiplier running.
     */
    private void requestStop() {
        if (closing) return;
        closing = true;
        generation.incrementAndGet();
        final SpaceCatalogReader.Entry session = active;
        unregisterPublisher();
        removeOverlay();
        if (session == null) {
            stopSelf();
            return;
        }
        io.execute(() -> {
            RuntimeControlBridge.disableForCleanup(this, session);
            main.post(this::stopSelf);
        });
    }

    private void recheck() {
        final SpaceCatalogReader.Entry session = active;
        if (session == null || summary == null) return;
        summary.setText("Перепроверка подписей и APK...");
        final int token = generation.get();
        io.execute(() -> {
            SpaceCatalogReader.Verification result =
                    SpaceCatalogReader.verify(this, session);
            main.post(() -> {
                if (token != generation.get() || summary == null) return;
                if (!result.ready) {
                    requestStop();
                    return;
                }
                if (liveMoveButton != null) {
                    refreshLiveControl();
                } else {
                    summary.setText("Только сведения об игре — модификации не активны");
                }
            });
        });
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void unregisterPublisher() {
        if (changes != null) {
            getContentResolver().unregisterContentObserver(changes);
            changes = null;
        }
    }

    private void removeOverlay() {
        if (root != null) {
            try { windows.removeView(root); }
            catch (RuntimeException ignored) { /* Window already detached. */ }
            root = null;
            details = null;
            summary = null;
            liveMoveButton = null;
            liveMoveEnabled = false;
        }
        active = null;
    }

    @Override public void onDestroy() {
        generation.incrementAndGet();
        final SpaceCatalogReader.Entry orphan = active;
        unregisterPublisher();
        removeOverlay();
        if (orphan != null) {
            io.execute(() -> RuntimeControlBridge.disableForCleanup(this, orphan));
        }
        io.shutdown();
        super.onDestroy();
    }
}
