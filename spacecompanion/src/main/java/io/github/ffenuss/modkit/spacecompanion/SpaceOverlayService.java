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
import java.util.LinkedHashMap;
import java.util.Map;
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
    private LinearLayout recipeContainer;
    private final Map<String, Button> recipeButtons = new LinkedHashMap<>();
    private final Map<String, Boolean> recipeStates = new LinkedHashMap<>();
    private final Map<String, Button> nativeButtons = new LinkedHashMap<>();
    private final Map<String, Boolean> nativeStates = new LinkedHashMap<>();
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
            io.execute(() -> restoreSession(previous));
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

        // Both the cooperating fixture and a separately PREPARED probe can
        // report executable capabilities. Unknown apps keep this panel empty.
        recipeContainer = new LinearLayout(this);
        recipeContainer.setOrientation(LinearLayout.VERTICAL);
        details.addView(recipeContainer);
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
        if (session == null || recipeContainer == null || closing) return;
        for (Button button : recipeButtons.values()) button.setEnabled(false);
        for (Button button : nativeButtons.values()) button.setEnabled(false);
        final int token = generation.get();
        if (!RuntimeControlBridge.supports(session)) {
            io.execute(() -> {
                PreparedNativeRecipeBridge.Status status =
                        PreparedNativeRecipeBridge.read(this, session);
                main.post(() -> {
                    if (token != generation.get() || closing || recipeContainer == null) return;
                    renderNativeRecipes(status);
                });
            });
            return;
        }
        io.execute(() -> {
            RuntimeControlBridge.RecipeSnapshot status =
                    RuntimeControlBridge.discoverRecipes(this, session);
            main.post(() -> {
                if (token != generation.get() || closing || recipeContainer == null) return;
                renderRecipes(status);
            });
        });
    }

    /**
     * An ON state here means a checked native binary patch. It is NOT proof
     * of an actual gameplay effect; the UI must say so explicitly.
     */
    private void renderNativeRecipes(PreparedNativeRecipeBridge.Status snapshot) {
        if (recipeContainer == null) return;
        recipeContainer.removeAllViews();
        nativeButtons.clear();
        nativeStates.clear();
        if (!snapshot.reachable || snapshot.recipes.isEmpty()) {
            updateRecipeSummary();
            return; // no fake controls for unprepared games
        }
        for (PreparedNativeRecipeContract.Recipe recipe : snapshot.recipes) {
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText("Нативный патч · " + recipe.label + ": " +
                    (recipe.applied ? "включено" : "выключено"));
            button.setEnabled(snapshot.active);
            button.setOnClickListener(v -> toggleNativeRecipe(recipe.id));
            nativeButtons.put(recipe.id, button);
            nativeStates.put(recipe.id, recipe.applied);
            recipeContainer.addView(button);
        }
        updateRecipeSummary();
    }

    private void toggleNativeRecipe(String id) {
        SpaceCatalogReader.Entry session = active;
        Boolean current = nativeStates.get(id);
        if (session == null || current == null || closing) return;
        boolean desired = !current;
        final int token = generation.get();
        for (Button button : nativeButtons.values()) button.setEnabled(false);
        io.execute(() -> {
            PreparedNativeRecipeBridge.Status result =
                    PreparedNativeRecipeBridge.set(this, session, id, desired);
            main.post(() -> {
                if (token != generation.get() || closing || recipeContainer == null) return;
                PreparedNativeRecipeContract.Recipe ack = result.find(id);
                if (!result.reachable || !result.active || ack == null ||
                        ack.applied != desired) {
                    requestStop();
                    return;
                }
                renderNativeRecipes(result);
            });
        });
    }

    private void restoreSession(SpaceCatalogReader.Entry session) {
        if (RuntimeControlBridge.supports(session)) {
            restoreSession(session);
        } else {
            PreparedNativeRecipeBridge.restore(this, session);
        }
    }

    /** Only provider-acknowledged, adapter-reviewed recipes get UI controls. */
    private void renderRecipes(RuntimeControlBridge.RecipeSnapshot snapshot) {
        if (recipeContainer == null) return;
        recipeContainer.removeAllViews();
        recipeButtons.clear();
        recipeStates.clear();
        if (!snapshot.reachable) {
            TextView denied = new TextView(this);
            denied.setTextColor(0xFFF1C75B);
            denied.setText("Исполняемые рецепты не подтверждены");
            recipeContainer.addView(denied);
            return;
        }
        for (VerifiedRecipeContract.Recipe recipe : snapshot.recipes) {
            Button action = new Button(this);
            action.setAllCaps(false);
            action.setText(recipe.label + ": " +
                    (recipe.enabled ? "включено" : "выключено"));
            action.setEnabled(snapshot.active);
            action.setOnClickListener(v -> toggleRecipe(recipe.id));
            recipeButtons.put(recipe.id, action);
            recipeStates.put(recipe.id, recipe.enabled);
            recipeContainer.addView(action);
        }
        updateRecipeSummary();
    }

    private void updateRecipeSummary() {
        if (summary == null) return;
        if (!nativeStates.isEmpty()) {
            int count = 0;
            for (Boolean applied : nativeStates.values()) {
                if (Boolean.TRUE.equals(applied)) count++;
            }
            summary.setText(count > 0 ?
                    "Бинарные патчи применены: " + count +
                            " · эффект в игре не подтверждён" :
                    "Подготовленные нативные патчи: эффект в игре не подтверждён");
            return;
        }
        if (Boolean.TRUE.equals(recipeStates.get(VerifiedRecipeContract.MOVEMENT)) &&
                Boolean.TRUE.equals(recipeStates.get(VerifiedRecipeContract.SHIELD))) {
            summary.setText("В тестовой игре включены 2 проверенных рецепта");
        } else if (Boolean.TRUE.equals(recipeStates.get(VerifiedRecipeContract.MOVEMENT))) {
            summary.setText("Применено в тестовой игре: движение ×2");
        } else if (Boolean.TRUE.equals(recipeStates.get(VerifiedRecipeContract.SHIELD))) {
            summary.setText("Применено в тестовой игре: защита здоровья");
        } else {
            summary.setText("Только сведения об игре — модификации не активны");
        }
    }

    private void toggleRecipe(String id) {
        final SpaceCatalogReader.Entry session = active;
        final Boolean previous = recipeStates.get(id);
        if (session == null || previous == null || closing) return;
        final boolean next = !previous;
        final int token = generation.get();
        for (Button button : recipeButtons.values()) button.setEnabled(false);
        io.execute(() -> {
            RuntimeControlBridge.RecipeSnapshot result =
                    RuntimeControlBridge.setRecipe(this, session, id, next);
            main.post(() -> {
                if (token != generation.get() || closing || recipeContainer == null) return;
                if (!result.reachable || !result.active ||
                        result.find(id) == null || result.find(id).enabled != next) {
                    requestStop();
                    return;
                }
                renderRecipes(result);
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
            restoreSession(session);
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
                updateRecipeSummary();
                if (recipeContainer != null) refreshLiveControl();
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
            recipeContainer = null;
            recipeButtons.clear();
            recipeStates.clear();
            nativeButtons.clear();
            nativeStates.clear();
        }
        active = null;
    }

    @Override public void onDestroy() {
        generation.incrementAndGet();
        final SpaceCatalogReader.Entry orphan = active;
        unregisterPublisher();
        removeOverlay();
        if (orphan != null) {
            io.execute(() -> restoreSession(orphan));
        }
        io.shutdown();
        super.onDestroy();
    }
}
