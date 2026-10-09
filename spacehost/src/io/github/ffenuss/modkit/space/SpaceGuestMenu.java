package io.github.ffenuss.modkit.space;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An Activity-owned overlay inside the original guest, with no system-window permission or root. */
final class SpaceGuestMenu {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "modkit-guest-menu"));
    private static SpaceGuestMenu installed;
    private final Application host;
    private final SpaceGuestBootstrap.Session session;
    private WeakReference<Activity> activity = new WeakReference<>(null);
    private LinearLayout root, rows;
    private ScrollView panel;
    private volatile MenuProfile profile;
    private volatile SpaceNativeController controller;
    private final Object lifecycle = new Object();
    private String message = "Проверяем меню и версию приложения…";
    private boolean expanded = true;
    private volatile boolean closed;
    private final Runnable capabilityTick = this::refreshCapabilities;
    private boolean refreshing;
    private ViewGroup container;
    private View.OnLayoutChangeListener containerLayout;
    private float horizontal = 1f, vertical = 0f;
    private final java.util.Set<String> busy = new java.util.HashSet<>();
    private final Inputs inputs;
    private Application.ActivityLifecycleCallbacks callbacks;
    interface Inputs { MenuProfile profile() throws Exception; List<File> sources() throws Exception; }
    private SpaceGuestMenu(Application host, SpaceGuestBootstrap.Session session, Inputs inputs) {
        this.host = host; this.session = session; this.inputs = inputs;
    }

    static synchronized void install(Application host, SpaceGuestBootstrap.Session session) {
        if (installed != null) {
            if (!installed.closed && installed.session.application == session.application &&
                installed.session.user == session.user && installed.session.packageName.equals(session.packageName)) return;
            if (!installed.close()) throw new IllegalStateException("Предыдущая сессия не восстановлена. Перезапустите пространство");
        }
        bind(host, session, new Inputs() {
            public MenuProfile profile() throws Exception { MenuProfileStore.sync(host); return MenuProfileStore.load(host, session.packageName); }
            public List<File> sources() throws Exception { return new ReferenceKernel(host.getClassLoader()).sources(session.packageName, session.user); }
        });
    }
    static synchronized SpaceGuestMenu bind(Application host, SpaceGuestBootstrap.Session session, Inputs inputs) {
        if (installed != null) throw new IllegalStateException("Guest menu already bound");
        if (!session.applicationCreated || !session.packageName.equals(session.application.getPackageName()))
            throw new IllegalArgumentException("Guest application is not ready");
        SpaceGuestMenu menu = new SpaceGuestMenu(host, session, inputs); installed = menu;
        menu.callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle saved) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                if (menu.closed) return;
                menu.activity = new WeakReference<>(a); menu.attach(a); menu.refreshCapabilities();
            }
            public void onActivityPaused(Activity a) { if (menu.activity.get() == a) { menu.detach(); menu.activity.clear(); } }
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle saved) {}
            public void onActivityDestroyed(Activity a) { if (menu.activity.get() == a) { menu.detach(); menu.activity.clear(); } }
        };
        session.application.registerActivityLifecycleCallbacks(menu.callbacks);
        IO.execute(menu::load);
        return menu;
    }
    boolean close() {
        boolean restored;
        synchronized (lifecycle) {
            closed = true;
            restored = controller == null || controller.close();
        }
        Runnable cleanup = () -> {
            UI.removeCallbacks(capabilityTick);
            session.application.unregisterActivityLifecycleCallbacks(callbacks);
            detach(); activity.clear(); busy.clear();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run(); else UI.post(cleanup);
        // Retain an uncertain session: a new guest must not hide failed restoration.
        if (restored) synchronized (SpaceGuestMenu.class) { if (installed == this) installed = null; }
        return restored;
    }
    private void load() {
        try {
            if (closed) return;
            MenuProfile loaded = inputs.profile();
            if (loaded == null) throw new IllegalStateException("Сначала выполните анализ этого приложения в ModKit");
            if (!session.packageName.equals(loaded.packageName)) throw new IllegalArgumentException("Menu belongs to another guest");
            List<File> sources = inputs.sources();
            if (!loaded.matches(sources, new SourceInventory.Cancellation()))
                throw new IllegalStateException("Версия приложения изменилась. Повторите анализ в ModKit");
            boolean hasRecipes = false;
            for (MenuProfile.Item item : loaded.items) hasRecipes |= item.patch != null;
            SpaceNativeController ready = null;
            String detail = "Для этой версии нет исполнимых рецептов";
            if (hasRecipes) {
                if (!SpaceNativePayload.load(host)) throw new IllegalStateException("Не удалось загрузить исполнитель");
                ready = new SpaceNativeController(loaded, new SpaceNativeBackend(sources)); ready.refresh();
                detail = "Изменения действуют только в этом процессе";
            }
            final SpaceNativeController prepared = ready; final String summary = detail;
            // Transfer controller ownership before publishing UI. close() shares this lock.
            synchronized (lifecycle) {
                if (closed) { if (prepared != null) prepared.close(); return; }
                profile = loaded; controller = prepared;
            }
            UI.post(() -> { if (!closed) { message = summary; render(); } });
        } catch (Exception | LinkageError error) {
            UI.post(() -> { if (closed) return; message = error.getMessage() == null ? "Не удалось подготовить меню" : error.getMessage(); render(); });
        }
    }
    private void refreshCapabilities() {
        UI.removeCallbacks(capabilityTick);
        if (closed || root == null || refreshing) return;
        SpaceNativeController current = controller;
        if (current == null) { UI.postDelayed(capabilityTick, 1000); return; }
        refreshing = true;
        IO.execute(() -> {
            if (closed) return;
            java.util.List<SpaceNativeController.State> before = new java.util.ArrayList<>();
            for (MenuProfile.Item item : profile.items) if (item.patch != null) before.add(current.state(item.id));
            current.refresh(); int index = 0; boolean changed = false;
            for (MenuProfile.Item item : profile.items) if (item.patch != null) changed |= before.get(index++) != current.state(item.id);
            final boolean update = changed;
            UI.post(() -> { refreshing = false; if (closed) return; if (update) render(); if (!closed && root != null) UI.postDelayed(capabilityTick, 1000); });
        });
    }
    private int dp(int n) { return (int)(n * session.application.getResources().getDisplayMetrics().density + .5f); }
    private TextView text(Activity a, String value, int size, int color) {
        TextView view = new TextView(a); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg;
    }
    private TextView action(Activity a, String title, Runnable onClick) {
        TextView view = text(a, title, 15, 0xffb8ddc8); view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setGravity(Gravity.CENTER); view.setBackground(background(0xff254237, 14)); view.setOnClickListener(v -> onClick.run()); return view;
    }
    private void attach(Activity a) {
        detach();
        if (closed || !session.packageName.equals(a.getPackageName())) return;
        ViewGroup decor = (ViewGroup)a.getWindow().getDecorView();
        root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackground(background(0xf5111915, 20)); root.setElevation(dp(12)); root.setTag("modkit-guest-menu");
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> place());
        LinearLayout header = new LinearLayout(a); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(a, "MK", 17, Color.WHITE); title.setPadding(dp(10), dp(10), dp(10), dp(10));
        title.setOnClickListener(v -> { expanded = !expanded; panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
            resize(a); if (expanded) refreshCapabilities(); });
        title.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY; boolean dragging;
            public boolean onTouch(View view, MotionEvent event) {
                if (root == null) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downX = event.getRawX(); downY = event.getRawY(); startX = root.getX(); startY = root.getY(); dragging = false; return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (Math.hypot(dx, dy) > ViewConfiguration.get(a).getScaledTouchSlop()) dragging = true;
                    if (dragging) move(startX + dx, startY + dy); return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) { if (!dragging) view.performClick(); return true; }
                return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            }
        });
        title.setContentDescription("Свернуть или открыть меню ModKit");
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView settings = action(a, "⋯", this::settings); settings.setContentDescription("Настройки меню ModKit"); header.addView(settings);
        root.addView(header);
        rows = new LinearLayout(a); rows.setOrientation(LinearLayout.VERTICAL); rows.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel = new ScrollView(a); panel.setFillViewport(false); panel.addView(rows); panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        int width = Math.max(dp(100), Math.min(dp(296), decor.getWidth() > 0 ? decor.getWidth() - dp(24) : a.getResources().getDisplayMetrics().widthPixels - dp(24)));
        root.addView(panel, new LinearLayout.LayoutParams(width, Math.min(dp(350), a.getResources().getDisplayMetrics().heightPixels / 2)));
        FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pos.gravity = Gravity.TOP | Gravity.LEFT; decor.addView(root, pos);
        container = decor; containerLayout = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> { resize(a); place(); };
        container.addOnLayoutChangeListener(containerLayout); resize(a);
        render();
    }
    private void resize(Activity a) {
        if (root == null || !(root.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) root.getParent();
        int available = parent.getWidth() > 0 ? parent.getWidth() : a.getResources().getDisplayMetrics().widthPixels;
        int width = expanded ? Math.min(dp(312), available - dp(24)) : dp(112);
        if (root.getLayoutParams().width != width) {
            root.getLayoutParams().width = width;
            root.requestLayout();
        }
    }
    private void place() {
        if (root == null || !(root.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) root.getParent();
        android.graphics.Rect safe = bounds(parent);
        move(safe.left + horizontal * Math.max(0, safe.width() - root.getWidth()),
            safe.top + vertical * Math.max(0, safe.height() - root.getHeight()));
    }
    private void move(float x, float y) {
        if (root == null || !(root.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) root.getParent();
        android.graphics.Rect safe = bounds(parent);
        float width = Math.max(0, safe.width() - root.getWidth());
        float height = Math.max(0, safe.height() - root.getHeight());
        x = Math.max(safe.left, Math.min(x, safe.left + width)); y = Math.max(safe.top, Math.min(y, safe.top + height));
        root.setX(x); root.setY(y);
        if (width > 0) horizontal = (x - safe.left) / width;
        if (height > 0) vertical = (y - safe.top) / height;
    }
    @SuppressWarnings("deprecation")
    private android.graphics.Rect bounds(ViewGroup parent) {
        android.view.WindowInsets insets = parent.getRootWindowInsets();
        return new android.graphics.Rect(dp(12) + (insets == null ? 0 : insets.getSystemWindowInsetLeft()),
            dp(12) + (insets == null ? 0 : insets.getSystemWindowInsetTop()),
            parent.getWidth() - dp(12) - (insets == null ? 0 : insets.getSystemWindowInsetRight()),
            parent.getHeight() - dp(12) - (insets == null ? 0 : insets.getSystemWindowInsetBottom()));
    }
    private void detach() {
        UI.removeCallbacks(capabilityTick);
        if (container != null && containerLayout != null) container.removeOnLayoutChangeListener(containerLayout);
        container = null; containerLayout = null;
        if (root != null && root.getParent() instanceof ViewGroup) ((ViewGroup)root.getParent()).removeView(root);
        root = null; rows = null; panel = null;
    }
    private void render() {
        if (closed) return;
        Activity a = activity.get(); if (a == null || rows == null) return;
        rows.removeAllViews();
        rows.addView(text(a, profile == null ? session.packageName : profile.label, 17, Color.WHITE));
        TextView subtitle = text(a, "Работает без root · пользователь " + session.user, 12, 0xffa1b4a8);
        subtitle.setPadding(0, dp(4), 0, dp(12)); rows.addView(subtitle);
        if (profile == null) { rows.addView(text(a, message, 13, 0xffc4ccc7)); return; }
        int available = 0;
        for (MenuProfile.Item item : profile.items) if (item.patch != null) {
            available++;
            LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(background(0xff1d2a23, 12));
            Switch toggle = new Switch(a); toggle.setText(item.title); toggle.setTextColor(Color.WHITE); toggle.setTextSize(14);
            SpaceNativeController.State state = controller == null ? SpaceNativeController.State.UNAVAILABLE : controller.state(item.id);
            toggle.setChecked(state == SpaceNativeController.State.ON); toggle.setEnabled(!busy.contains(item.id) && (state == SpaceNativeController.State.ON || state == SpaceNativeController.State.OFF));
            toggle.setTag("modkit-recipe:" + item.id);
            toggle.setOnClickListener(v -> {
                boolean desired = toggle.isChecked(); toggle.setChecked(controller.state(item.id) == SpaceNativeController.State.ON);
                busy.add(item.id); toggle.setEnabled(false);
                IO.execute(() -> {
                    if (closed) return;
                    boolean success = controller.set(item.id, desired);
                    UI.post(() -> { busy.remove(item.id); if (closed) return; if (!success) Toast.makeText(a, "Изменение не подтверждено. Проверьте состояние пункта", Toast.LENGTH_LONG).show(); render(); });
                });
            });
            row.addView(toggle);
            if (state == SpaceNativeController.State.UNAVAILABLE) row.addView(text(a, controller == null ? "Исполнитель ещё не готов" : controller.reason(item.id), 11, 0xffabb9b0));
            if (state == SpaceNativeController.State.ERROR) row.addView(text(a, "Состояние не подтверждено. Перезапустите приложение", 11, 0xffffb4ab));
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.bottomMargin = dp(8); rows.addView(row, spacing);
        }
        if (available == 0) rows.addView(text(a, message, 13, 0xffc4ccc7));
        int pending = profile.items.size() - available;
        if (pending > 0) {
            rows.addView(text(a, "Недоступных кандидатов: " + pending, 11, 0xffa1b4a8));
            int explanations = 0;
            for (MenuProfile.Item item : profile.items) if (item.patch == null && explanations++ < 3)
                rows.addView(text(a, item.title + " · " + item.detail, 11, 0xffa1b4a8));
        }
    }
    private void settings() {
        Activity a = activity.get(); if (closed || a == null) return;
        new AlertDialog.Builder(a).setTitle("Меню ModKit").setItems(new String[]{"Отключить все изменения", "Выбрать другое приложение"}, (dialog, choice) -> {
            // Fence clicks immediately; queued commands cannot outlive "choose another app".
            if (choice == 1) synchronized (lifecycle) { closed = true; }
            IO.execute(() -> {
                boolean restored = choice == 1 ? close() : controller == null || controller.restoreAll();
                UI.post(() -> {
                    render();
                    if (!restored) { Toast.makeText(a, "Не все изменения восстановлены. Перезапустите приложение", Toast.LENGTH_LONG).show(); return; }
                    if (choice == 1) {
                        Intent intent = new Intent().setClassName("com.dualspace.multispace.androidx", "com.dualspace.multispace.MainActivity");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        try { host.startActivity(intent); } catch (Exception error) { Toast.makeText(a, "Откройте список пространства", Toast.LENGTH_SHORT).show(); }
                    }
                });
            });
        }).setNegativeButton("Закрыть", null).show();
    }
}
