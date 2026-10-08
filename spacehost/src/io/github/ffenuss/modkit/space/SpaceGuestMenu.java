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
    private MenuProfile profile;
    private SpaceNativeController controller;
    private String message = "Проверяем меню и версию приложения…";
    private boolean expanded = true;
    private SpaceGuestMenu(Application host, SpaceGuestBootstrap.Session session) { this.host = host; this.session = session; }

    static synchronized void install(Application host, SpaceGuestBootstrap.Session session) {
        if (installed != null) return;
        SpaceGuestMenu menu = new SpaceGuestMenu(host, session); installed = menu;
        session.application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle saved) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                menu.activity = new WeakReference<>(a); menu.attach(a); menu.refreshCapabilities();
            }
            public void onActivityPaused(Activity a) { if (menu.activity.get() == a) { menu.detach(); menu.activity.clear(); } }
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle saved) {}
            public void onActivityDestroyed(Activity a) { if (menu.activity.get() == a) { menu.detach(); menu.activity.clear(); } }
        });
        IO.execute(menu::load);
    }
    private void load() {
        try {
            MenuProfileStore.sync(host);
            MenuProfile loaded = MenuProfileStore.load(host, session.packageName);
            if (loaded == null) throw new IllegalStateException("Сначала выполните анализ этого приложения в ModKit");
            ReferenceKernel kernel = new ReferenceKernel(host.getClassLoader());
            List<File> sources = kernel.sources(session.packageName, session.user);
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
            UI.post(() -> { profile = loaded; controller = prepared; message = summary; render(); });
        } catch (Exception | LinkageError error) {
            UI.post(() -> { message = error.getMessage() == null ? "Не удалось подготовить меню" : error.getMessage(); render(); });
        }
    }
    private void refreshCapabilities() {
        SpaceNativeController current = controller;
        if (current != null) IO.execute(() -> { current.refresh(); UI.post(this::render); });
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
        if (!session.packageName.equals(a.getPackageName())) return;
        ViewGroup decor = (ViewGroup)a.getWindow().getDecorView();
        root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackground(background(0xf5111915, 20)); root.setElevation(dp(12)); root.setTag("modkit-guest-menu");
        LinearLayout header = new LinearLayout(a); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(a, "MK", 17, Color.WHITE); title.setPadding(dp(10), dp(10), dp(10), dp(10));
        title.setOnClickListener(v -> { expanded = !expanded; panel.setVisibility(expanded ? View.VISIBLE : View.GONE); if (expanded) refreshCapabilities(); });
        title.setContentDescription("Свернуть или открыть меню ModKit");
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView settings = action(a, "⋯", this::settings); settings.setContentDescription("Настройки меню ModKit"); header.addView(settings);
        root.addView(header);
        rows = new LinearLayout(a); rows.setOrientation(LinearLayout.VERTICAL); rows.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel = new ScrollView(a); panel.setFillViewport(false); panel.addView(rows); panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        int width = Math.min(dp(296), a.getResources().getDisplayMetrics().widthPixels - dp(32));
        root.addView(panel, new LinearLayout.LayoutParams(width, Math.min(dp(350), a.getResources().getDisplayMetrics().heightPixels / 2)));
        FrameLayout.LayoutParams pos = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pos.gravity = Gravity.TOP | Gravity.END; pos.topMargin = dp(52); pos.rightMargin = dp(12); decor.addView(root, pos);
        render();
    }
    private void detach() {
        if (root != null && root.getParent() instanceof ViewGroup) ((ViewGroup)root.getParent()).removeView(root);
        root = null; rows = null; panel = null;
    }
    private void render() {
        Activity a = activity.get(); if (a == null || rows == null) return;
        rows.removeAllViews();
        rows.addView(text(a, profile == null ? session.packageName : profile.label, 17, Color.WHITE));
        TextView subtitle = text(a, "Без root · пользователь " + session.user, 12, 0xffa1b4a8);
        subtitle.setPadding(0, dp(4), 0, dp(12)); rows.addView(subtitle);
        if (profile == null) { rows.addView(text(a, message, 13, 0xffc4ccc7)); return; }
        int available = 0;
        for (MenuProfile.Item item : profile.items) if (item.patch != null) {
            available++;
            LinearLayout row = new LinearLayout(a); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setBackground(background(0xff1d2a23, 12));
            Switch toggle = new Switch(a); toggle.setText(item.title); toggle.setTextColor(Color.WHITE); toggle.setTextSize(14);
            SpaceNativeController.State state = controller == null ? SpaceNativeController.State.UNAVAILABLE : controller.state(item.id);
            toggle.setChecked(state == SpaceNativeController.State.ON); toggle.setEnabled(state == SpaceNativeController.State.ON || state == SpaceNativeController.State.OFF);
            toggle.setOnClickListener(v -> {
                boolean desired = toggle.isChecked(); toggle.setChecked(controller.state(item.id) == SpaceNativeController.State.ON); toggle.setEnabled(false);
                IO.execute(() -> {
                    boolean success = controller.set(item.id, desired);
                    UI.post(() -> { if (!success) Toast.makeText(a, "Изменение не подтверждено. Проверьте состояние пункта", Toast.LENGTH_LONG).show(); render(); });
                });
            });
            row.addView(toggle);
            if (state == SpaceNativeController.State.UNAVAILABLE) row.addView(text(a, "Библиотека или версия ещё не подтверждена", 11, 0xffabb9b0));
            if (state == SpaceNativeController.State.ERROR) row.addView(text(a, "Состояние не подтверждено. Перезапустите приложение", 11, 0xffffb4ab));
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2); spacing.bottomMargin = dp(8); rows.addView(row, spacing);
        }
        if (available == 0) rows.addView(text(a, message, 13, 0xffc4ccc7));
        int pending = profile.items.size() - available;
        if (pending > 0) rows.addView(text(a, "Других кандидатов: " + pending + ". Для них пока нет исполнителя", 11, 0xffa1b4a8));
    }
    private void settings() {
        Activity a = activity.get(); if (a == null) return;
        new AlertDialog.Builder(a).setTitle("Меню ModKit").setItems(new String[]{"Отключить все изменения", "Выбрать другое приложение"}, (dialog, choice) -> {
            IO.execute(() -> {
                boolean restored = controller == null || controller.restoreAll();
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
