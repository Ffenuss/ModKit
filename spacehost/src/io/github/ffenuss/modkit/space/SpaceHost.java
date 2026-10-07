package io.github.ffenuss.modkit.space;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
    private static final ExecutorService ANALYSIS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "modkit-space-inventory"); t.setDaemon(true); return t;
    });
    private static SourceInventory.Cancellation analysisToken;
    private static TextView analysisStatus;
    private static Application application;
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static LinearLayout overlay;
    private static TextView status;
    private static View panel;
    private static String target;
    private static int userId;
    private static long generation;
    private static boolean checking;
    private SpaceHost() {}

    public static synchronized void start(Application app) {
        if (Build.VERSION.SDK_INT < 26 || application != null || app == null || !HOST.equals(app.getPackageName()) || !isHostProcess(app)) return;
        application = app;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle state) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {
                activity = new WeakReference<>(a);
                if (MAIN.equals(a.getClass().getName())) attachHostButton(a);
                if (!Settings.canDrawOverlays(app)) removeOverlay();
                else if (target != null) showOverlay();
            }
            public void onActivityPaused(Activity a) { if (activity.get() == a) activity.clear(); }
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            public void onActivityDestroyed(Activity a) { if (activity.get() == a) activity.clear(); }
        });
        Log.i(TAG, "Host UI registered; guest files unchanged");
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
                cancelAnalysis();
                target = packageName; userId = virtualUser; generation++;
                if (analysisStatus != null) analysisStatus.setText("Проверка состава ещё не запускалась");
                Log.i(TAG, "Launch requested package=" + target + " user=" + userId);
                if (Settings.canDrawOverlays(application)) showOverlay();
                else Log.i(TAG, "Overlay permission absent; guest launch remains available");
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.e(TAG, "Host launch identity rejected", error);
        }
    }

    private static void attachHostButton(Activity a) {
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (decor.findViewWithTag("modkit-space-entry") != null) return;
        Button entry = new Button(a);
        entry.setTag("modkit-space-entry"); entry.setText("MK · пространство");
        entry.setAllCaps(false); entry.setContentDescription("Настроить оверлей пространства ModKit");
        entry.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(a)) {
                new AlertDialog.Builder(a).setTitle("Оверлей ModKit")
                    .setMessage("Разрешите окна поверх приложений. После этого MK будет появляться при запуске игры из пространства.")
                    .setPositiveButton("Разрешить", (dialog, which) -> {
                        try { a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + HOST))); }
                        catch (RuntimeException error) { toast("Не удалось открыть разрешения оверлея"); }
                    }).setNegativeButton("Позже", null).show();
            } else {
                if (target == null) { target = "com.android.vending"; userId = 0; generation++; }
                showOverlay();
                if (panel != null) { panel.setVisibility(View.VISIBLE); refresh(); }
            }
        });
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.TOP | Gravity.END; p.topMargin = dp(28); p.rightMargin = dp(8);
        decor.addView(entry, p);
    }

    private static int dp(int value) { return (int) (value * application.getResources().getDisplayMetrics().density + .5f); }
    private static TextView label(String message, int size) {
        TextView t = new TextView(application); t.setText(message); t.setTextColor(Color.WHITE);
        t.setTextSize(size); t.setPadding(dp(8), dp(6), dp(8), dp(6)); return t;
    }
    private static Button button(String message, Runnable action) {
        Button b = new Button(application); b.setText(message); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); return b;
    }

    private static void showOverlay() {
        if (application == null || !Settings.canDrawOverlays(application)) { removeOverlay(); return; }
        if (overlay != null) { refresh(); return; }
        LinearLayout host = new LinearLayout(application); host.setOrientation(LinearLayout.VERTICAL);
        host.setPadding(dp(4), dp(4), dp(4), dp(4)); host.setBackgroundColor(Color.argb(245, 24, 31, 38));
        Button bubble = button("MK", () -> {
            panel.setVisibility(panel.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
            if (panel.getVisibility() == View.VISIBLE) refresh();
        });
        bubble.setContentDescription("Открыть оверлей ModKit Space"); host.addView(bubble, new LinearLayout.LayoutParams(dp(64), dp(48)));
        LinearLayout content = new LinearLayout(application); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(label("ModKit · пространство", 17));
        status = label("Проверяем виртуальное окружение…", 12); content.addView(status);
        content.addView(label("Добавляйте оригинальные приложения через список пространства. APK игры не изменяется.", 12));
        content.addView(button("Открыть пространство / добавить приложение", SpaceHost::openSpace));
        content.addView(button("Открыть Google Play в пространстве", () -> openGooglePlay()));
        content.addView(button("Обновить состояние", SpaceHost::refresh));
        analysisStatus = label("Проверка состава ещё не запускалась", 12); content.addView(analysisStatus);
        content.addView(button("Проверить оригинальные APK", SpaceHost::analyzeSources));
        content.addView(button("Отменить проверку", () -> {
            cancelAnalysis();
            if (analysisStatus != null) analysisStatus.setText("Проверка отменена");
        }));
        content.addView(label("Проверка состава не включает поиск модов или управление игрой. Эти функции ещё не подключены.", 12));
        content.addView(button("Скрыть оверлей", SpaceHost::removeOverlay));
        ScrollView scroll = new ScrollView(application); scroll.addView(content); scroll.setVisibility(View.GONE); panel = scroll;
        int width = Math.min(dp(330), application.getResources().getDisplayMetrics().widthPixels - dp(24));
        int height = Math.min(dp(430), application.getResources().getDisplayMetrics().heightPixels * 2 / 3);
        host.addView(scroll, new LinearLayout.LayoutParams(width, height));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END; params.x = dp(10); params.y = dp(90);
        try {
            ((WindowManager) application.getSystemService(Context.WINDOW_SERVICE)).addView(host, params);
            overlay = host; refresh();
        } catch (RuntimeException error) { overlay = null; status = null; panel = null; analysisStatus = null; Log.e(TAG, "Overlay window rejected", error); }
    }

    private static void refresh() {
        if (!Settings.canDrawOverlays(application)) { removeOverlay(); return; }
        if (checking || status == null || target == null) return;
        final String pkg = target; final int user = userId; final long epoch = generation;
        final TextView destination = status; checking = true;
        IO.execute(() -> {
            String text;
            try {
                ReferenceKernel kernel = new ReferenceKernel(application.getClassLoader());
                text = "Запуск запрошен: " + pkg + "\nПользователь пространства: " + user + "\n"
                    + "Приложение: " + (kernel.installed(pkg, user) ? "есть в пространстве" : "не найдено")
                    + "\nGoogle Play: " + state(kernel.installed("com.android.vending", user))
                    + "\nСервисы Google: " + state(kernel.installed("com.google.android.gms", user))
                    + "\nGoogle Services Framework: " + state(kernel.installed("com.google.android.gsf", user));
            } catch (Exception error) { text = "Не удалось проверить виртуальное окружение.\n" + error.getClass().getSimpleName(); Log.e(TAG, "Kernel query failed", error); }
            final String result = text;
            UI.post(() -> {
                checking = false;
                if (generation == epoch && status == destination) destination.setText(result);
                else if (status != null) refresh();
            });
        });
    }
    private static void cancelAnalysis() {
        if (analysisToken != null) analysisToken.cancel();
        analysisToken = null;
    }
    private static void analyzeSources() {
        if (target == null || analysisStatus == null) return;
        cancelAnalysis();
        final String pkg = target; final int user = userId; final long epoch = generation;
        final TextView destination = analysisStatus;
        final SourceInventory.Cancellation token = new SourceInventory.Cancellation();
        analysisToken = token; destination.setText("Получаем оригинальные APK из пространства…");
        ANALYSIS.execute(() -> {
            try {
                token.check();
                ReferenceKernel kernel = new ReferenceKernel(application.getClassLoader());
                List<File> inputs = kernel.sources(pkg, user);
                SourceInventory.Result result = SourceInventory.scan(pkg, user, inputs, token, message -> UI.post(() -> {
                    if (generation == epoch && analysisToken == token && analysisStatus == destination) destination.setText(message);
                }));
                // An update/uninstall can replace the kernel record without changing the old file.
                List<File> current = kernel.sources(pkg, user);
                if (current.size() != result.sources.size()) throw new IllegalStateException("APK set changed");
                for (int i = 0; i < current.size(); i++) {
                    token.check();
                    if (!current.get(i).getCanonicalFile().equals(result.sources.get(i).file))
                        throw new IllegalStateException("APK source changed");
                    result.sources.get(i).verify(token);
                }
                UI.post(() -> {
                    if (generation == epoch && analysisToken == token && analysisStatus == destination) {
                        destination.setText(result.report); analysisToken = null;
                    }
                });
            } catch (Exception error) {
                UI.post(() -> {
                    if (generation == epoch && analysisToken == token && analysisStatus == destination) {
                        destination.setText("Проверка не завершена: " + error.getClass().getSimpleName()
                            + "\nОригинальные APK не изменены. Повторите проверку после завершения установки приложения.");
                        analysisToken = null;
                    }
                });
                Log.w(TAG, "Original source inventory did not complete", error);
            }
        });
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
        if (overlay != null) try { ((WindowManager) application.getSystemService(Context.WINDOW_SERVICE)).removeViewImmediate(overlay); }
        catch (RuntimeException error) { Log.w(TAG, "Overlay already removed", error); }
        cancelAnalysis();
        overlay = null; panel = null; status = null; analysisStatus = null; generation++;
    }
}
