package io.github.ffenuss.modkit.space;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Host-only UI. Guest installation and execution remain owned by the existing virtual kernel. */
public final class SpaceHost {
    private static final String HOST = "com.dualspace.multispace.androidx";
    private static final String MAIN = "com.dualspace.multispace.MainActivity";
    private static final String RUNNABLE = "com.dualspace.multispace.va.f";
    private static final String TAG = "ModKitSpace";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "modkit-space-status"); t.setDaemon(true); return t;
    });
    private static Application application;
    private static SpaceTargetStore targets;
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static LinearLayout overlay;
    private static TextView status;
    private static View panel;
    private static LinearLayout menuContent;
    private static long menuEpoch = -1;
    private static String target;
    private static int userId;
    private static long generation;
    private static boolean checking;
    private static SourceInventory.Cancellation profileToken;
    private static MenuUpdates menuUpdates;
    private SpaceHost() {}

    /** Called after the reference host's initialization, including its early-return path. */
    public static void guestBootstrap(Application host) { SpaceGuestBootstrap.install(host); }

    public static synchronized void start(Application app) {
        if (Build.VERSION.SDK_INT < 26 || application != null || app == null || !HOST.equals(app.getPackageName()) || !isHostProcess(app)) return;
        application = app;
        targets = new SpaceTargetStore(app.getSharedPreferences("modkit_space_profiles", Context.MODE_PRIVATE));
        menuUpdates = new MenuUpdates(app, UI, SpaceHost::syncMenus);
        menuUpdates.register();
        SpaceTargetStore.Session restored = targets.selected();
        if (restored != null) { target = restored.packageName; userId = restored.user; }
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle state) { consumeIntent(a, a.getIntent()); }
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                activity = new WeakReference<>(a);
                if (MAIN.equals(a.getClass().getName())) { consumeIntent(a, a.getIntent()); attachHostButton(a); menuUpdates.register(); syncMenus(); }
                if (!MAIN.equals(a.getClass().getName())) removeOverlay();
            }
            public void onActivityPaused(Activity a) { if (activity.get() == a) { removeOverlay(); activity.clear(); } }
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            public void onActivityDestroyed(Activity a) { if (activity.get() == a) { removeOverlay(); activity.clear(); } }
        });
        Log.i(TAG, "Host UI registered; guest files unchanged");
    }

    private static void syncMenus() {
        IO.execute(() -> {
            List<MenuProfile> changed = MenuProfileStore.sync(application);
            UI.post(() -> {
                boolean activeChanged = false;
                for (MenuProfile profile : changed) {
                    targets.register(profile.packageName, 0);
                    activeChanged |= profile.packageName.equals(target);
                }
                if (activeChanged) { cancelProfileCheck(); generation++; refresh(); }
            });
        });
    }

    /** MainActivity sets its intent in onNewIntent; lifecycle resume consumes that updated intent. */
    public static void consumeIntent(Activity source, Intent intent) {
        if (application == null || source == null || !MAIN.equals(source.getClass().getName()) || intent == null ||
            !"io.github.ffenuss.modkit.OPEN_SPACE_MENU".equals(intent.getAction())) return;
        final Uri uri = intent.getData();
        intent.setAction(Intent.ACTION_MAIN); intent.setData(null);
        IO.execute(() -> {
            try {
                MenuProfile imported = MenuProfileStore.importProfile(application, uri);
                UI.post(() -> {
                    targets.register(imported.packageName, 0);
                    if (imported.packageName.equals(target)) { cancelProfileCheck(); generation++; refresh(); }
                    toast("Меню сохранено: " + imported.label + ". Выберите оригинальное приложение в пространстве.");
                });
            } catch (Exception error) { Log.w(TAG, "Menu import rejected", error); UI.post(() -> toast("Не удалось принять профиль меню из ModKit")); }
        });
    }

    private static boolean isHostProcess(Application app) {
        if (Build.VERSION.SDK_INT >= 28) return HOST.equals(Application.getProcessName());
        ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo p : processes)
            if (p.pid == android.os.Process.myPid()) return HOST.equals(p.processName);
        return false; // Unknown identity must never create an overlay in a guest process.
    }

    /** Inserted into the exact host launch Runnable, not into any guest APK. */
    public static void beforeLaunch(Object runnable) {
        if (application == null || runnable == null || !RUNNABLE.equals(runnable.getClass().getName())) return;
        try {
            Field pkg = runnable.getClass().getDeclaredField("c");
            Field user = runnable.getClass().getDeclaredField("b");
            if (pkg.getType() != String.class || user.getType() != int.class) throw new IllegalStateException("Launch ABI changed");
            pkg.setAccessible(true); user.setAccessible(true);
            final String packageName = (String) pkg.get(runnable);
            final int virtualUser = user.getInt(runnable);
            if (!SpacePolicy.validSession(packageName, virtualUser)) return;
            UI.post(() -> {
                cancelProfileCheck(); target = packageName; userId = virtualUser; generation++;
                rememberTarget(packageName, virtualUser);
                Log.i(TAG, "Launch requested package=" + target + " user=" + userId);
                // The guest owns its Activity overlay. Never leave a second system window over it.
                removeOverlay();
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e(TAG, "Host launch identity rejected", error);
        }
    }

    private static void attachHostButton(Activity a) {
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (decor.findViewWithTag("modkit-space-entry") != null) return;
        Button entry = new Button(a);
        entry.setTag("modkit-space-entry"); entry.setText("MK");
        entry.setAllCaps(false); entry.setContentDescription("Настроить оверлей пространства ModKit");
        entry.setOnClickListener(v -> {
            if (overlay != null) { removeOverlay(); return; }
            if (target == null) { toast("Выберите приложение в списке пространства"); return; }
            showOverlay();
        });
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.TOP | Gravity.END; p.topMargin = dp(28); p.rightMargin = dp(8);
        decor.addView(entry, p);
    }

    private static int dp(int value) { return (int) (value * application.getResources().getDisplayMetrics().density + .5f); }
    private static TextView label(String message, int size) {
        TextView t = new TextView(uiContext()); t.setText(message); t.setTextColor(Color.WHITE);
        t.setTextSize(size); t.setPadding(dp(8), dp(6), dp(8), dp(6)); return t;
    }
    private static Button button(String message, Runnable action) {
        Button b = new Button(uiContext()); b.setText(message); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private static Context uiContext() { Activity a = activity.get(); return a == null ? application : a; }

    private static void showOverlay() {
        Activity a = activity.get();
        if (application == null || a == null || a.isFinishing() || a.isDestroyed() || !MAIN.equals(a.getClass().getName())) { removeOverlay(); return; }
        if (overlay != null) { refresh(); return; }
        LinearLayout host = new LinearLayout(a); host.setOrientation(LinearLayout.VERTICAL);
        host.setPadding(dp(8), dp(8), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xf5111915); bg.setCornerRadius(dp(16)); host.setBackground(bg); host.setElevation(dp(8));
        host.setTag("modkit-space-panel");
        LinearLayout content = new LinearLayout(a); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(label("ModKit · пространство", 17));
        status = label("Проверяем виртуальное окружение…", 12); content.addView(status);
        content.addView(label("Добавляйте оригинальные приложения через список пространства. APK игры не изменяется.", 12));
        content.addView(button("Открыть Google Play в пространстве", () -> openGooglePlay()));
        content.addView(button("Обновить состояние", SpaceHost::refresh));
        content.addView(button("Настройки · выбрать приложение", SpaceHost::chooseTarget));
        content.addView(label("Профиль меню сохраняется отдельно для каждого приложения и пользователя пространства. Анализ выполняется в ModKit.", 12));
        menuContent = new LinearLayout(a); menuContent.setOrientation(LinearLayout.VERTICAL); content.addView(menuContent);
        content.addView(button("Закрыть", SpaceHost::removeOverlay));
        ScrollView scroll = new ScrollView(a); scroll.addView(content); panel = scroll;
        int width = Math.min(dp(330), application.getResources().getDisplayMetrics().widthPixels - dp(24));
        int height = Math.min(dp(430), application.getResources().getDisplayMetrics().heightPixels * 2 / 3);
        host.addView(scroll, new LinearLayout.LayoutParams(width, height));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.TOP | Gravity.END; params.rightMargin = dp(10); params.topMargin = dp(90);
        try {
            ((ViewGroup) a.getWindow().getDecorView()).addView(host, params);
            overlay = host; refresh();
        } catch (RuntimeException error) { overlay = null; status = null; panel = null; menuContent = null; menuEpoch = -1; Log.e(TAG, "Overlay window rejected", error); }
    }

    private static void refresh() {
        if (overlay == null || activity.get() == null) return;
        if (status == null || target == null) return;
        if (menuContent != null && menuEpoch != generation) {
            menuEpoch = generation; menuContent.removeAllViews();
            menuContent.addView(label("Меню: " + target + "\nПроверяем профиль и исходную версию APK…", 12));
        }
        if (checking) return;
        final String pkg = target; final int user = userId; final long epoch = generation;
        final TextView destination = status; checking = true;
        final SourceInventory.Cancellation token = new SourceInventory.Cancellation(); profileToken = token;
        IO.execute(() -> {
            String text;
            MenuProfile profile = null; String profileMessage = ""; boolean matches = false;
            try {
                MenuProfileStore.sync(application);
                ReferenceKernel kernel = new ReferenceKernel(application.getClassLoader());
                text = "Запуск запрошен: " + pkg + "\nПользователь пространства: " + user + "\n"
                    + "Приложение: " + (kernel.installed(pkg, user) ? "есть в пространстве" : "не найдено")
                    + "\nGoogle Play: " + state(kernel.installed("com.android.vending", user))
                    + "\nСервисы Google: " + state(kernel.installed("com.google.android.gms", user))
                    + "\nGoogle Services Framework: " + state(kernel.installed("com.google.android.gsf", user));
                try {
                    profile = MenuProfileStore.load(application, pkg);
                    if (profile == null) profileMessage = "Меню не найдено. Выполните анализ в ModKit: пространство подхватит профиль автоматически.";
                    else {
                        List<File> sources = kernel.sources(pkg, user);
                        matches = profile.matches(sources, token);
                        List<File> current = kernel.sources(pkg, user);
                        if (current.size() != sources.size()) matches = false;
                        else for (int i = 0; i < current.size(); i++)
                            if (!current.get(i).getCanonicalFile().equals(sources.get(i).getCanonicalFile())) matches = false;
                        profileMessage = matches ? "Версия APK совпадает с анализом" : "APK отличается от анализа. Повторите анализ в ModKit.";
                    }
                } catch (Exception error) { profileMessage = "Профиль или версия APK не подтверждены: " + error.getClass().getSimpleName(); }

            } catch (Exception error) { text = "Не удалось проверить виртуальное окружение.\n" + error.getClass().getSimpleName(); Log.e(TAG, "Kernel query failed", error); }
            final String result = text;
            final MenuProfile selectedProfile = profile; final String menuMessage = profileMessage; final boolean versionMatches = matches;
            UI.post(() -> {
                checking = false;
                if (profileToken == token) profileToken = null;
                if (generation == epoch && status == destination) {
                    destination.setText(result); renderMenu(selectedProfile, menuMessage, versionMatches);
                }
                else if (status != null) refresh();
            });
        });
    }
    private static void cancelProfileCheck() {
        if (profileToken != null) profileToken.cancel();
        profileToken = null;
    }

    private static void renderMenu(MenuProfile profile, String message, boolean matches) {
        if (menuContent == null) return;
        menuContent.removeAllViews(); menuContent.addView(label(message, 12));
        if (profile == null || !matches) return;
        menuContent.addView(label(profile.label + " · " + profile.genre, 16));
        menuContent.addView(label(android.text.TextUtils.join("\n", profile.engines), 12));
        if (profile.truncated) menuContent.addView(label("Анализ неполный: часть пунктов/данных не включена", 12));
        int executable = 0;
        for (MenuProfile.Item item : profile.items) if (item.patch != null) executable++;
        menuContent.addView(label("Рецептов для включения: " + executable, 14));
        menuContent.addView(label(executable > 0
                ? "Переключатели откроются в меню запущенного приложения после проверки библиотеки."
                : "Для этой версии пока нет исполнимых рецептов. Остальные находки доступны в ModKit.", 12));
    }

    private static void rememberTarget(String pkg, int user) {
        targets.select(pkg, user);
    }

    private static void chooseTarget() {
        Activity a = activity.get();
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        final String[] choices = targets.choices();
        if (choices.length == 0) { toast("Добавьте и откройте приложение через список пространства"); return; }
        AlertDialog dialog = new AlertDialog.Builder(a)
            .setTitle("Приложение для меню модов")
            .setItems(choices, (ignored, position) -> {
                SpaceTargetStore.Session selection = SpaceTargetStore.parse(choices[position]);
                if (selection == null) return;
                final int selectedUser = selection.user;
                final String selectedPackage = selection.packageName;
                IO.execute(() -> {
                    try {
                        ReferenceKernel kernel = new ReferenceKernel(application.getClassLoader());
                        if (!kernel.installed(selectedPackage, selectedUser)) {
                            UI.post(() -> toast("Приложение удалено из пространства. Добавьте его снова.")); return;
                        }
                        boolean accepted = kernel.launch(selectedPackage, selectedUser);
                        UI.post(() -> {
                            if (!accepted) { toast("Ядро отклонило запуск приложения"); return; }
                            cancelProfileCheck(); target = selectedPackage; userId = selectedUser; generation++;
                            rememberTarget(target, userId); removeOverlay();
                        });
                    } catch (Exception error) { Log.w(TAG, "Target selection failed", error); UI.post(() -> toast("Не удалось выбрать приложение")); }
                });
            }).setNegativeButton("Закрыть", null).create();
        try { dialog.show(); } catch (RuntimeException error) { toast("Не удалось открыть выбор приложения"); }
    }
    private static String state(boolean installed) { return installed ? "есть внутри пространства" : "отсутствует внутри пространства"; }

    private static void openGooglePlay() {
        final int user = userId;
        IO.execute(() -> {
            try {
                ReferenceKernel kernel = new ReferenceKernel(application.getClassLoader());
                if (!kernel.installed("com.android.vending", user)) { UI.post(() -> toast("Google Play отсутствует у этого пользователя пространства")); return; }
                boolean accepted = kernel.launch("com.android.vending", user);
                UI.post(() -> { if (!accepted) toast("Ядро пространства отклонило запуск Google Play"); });
            } catch (Exception error) { Log.e(TAG, "Google Play launch failed", error); UI.post(() -> toast("Не удалось запустить Google Play внутри пространства")); }
        });
    }
    private static void openSpace() {
        try { application.startActivity(new Intent().setClassName(HOST, MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
        catch (RuntimeException error) { toast("Не удалось открыть пространство"); }
    }
    private static void toast(String message) { Toast.makeText(application, message, Toast.LENGTH_LONG).show(); }
    private static void removeOverlay() {
        cancelProfileCheck();
        if (overlay != null) try {
            if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
        catch (RuntimeException error) { Log.w(TAG, "Overlay already removed", error); }
        overlay = null; panel = null; status = null; menuContent = null; menuEpoch = -1; generation++;
    }
}
