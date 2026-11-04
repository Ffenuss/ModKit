package io.github.ffenuss.modkit.spacecompanion;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal buildable cross-app host. This is an authenticated profile viewer and
 * ordinary Android launcher, NOT a guest virtualization engine or live overlay.
 */
public final class SpaceCompanionActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private TextView status;
    private Button start;
    private ContentObserver updates;
    private SpaceProfileReader.Result current;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setTextSize(24);
        title.setText("Space Companion · проверка игры");
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView explanation = new TextView(this);
        explanation.setText("Проверка профиля ModKit и установленного APK. " +
                "Это обычный запуск Android: виртуализация и мод-меню пока недоступны.");
        root.addView(explanation);
        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, padding, 0, padding);
        root.addView(status);
        Button refresh = new Button(this);
        refresh.setText("Проверить профиль");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);
        start = new Button(this);
        start.setText("Запустить проверенную установленную игру");
        start.setEnabled(false);
        start.setOnClickListener(v -> launchVerified());
        root.addView(start);
        setContentView(root);

        updates = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                refresh();
            }
        };
        for (String publisher : new String[]{
                "io.github.ffenuss.modkit.test", "io.github.ffenuss.modkit"}) {
            // Android 10 may throw SecurityException when observing a missing
            // authority. Never crash just because release ModKit is absent
            // while a valid debug ModKit is installed (or vice versa).
            if (getPackageManager().checkSignatures(getPackageName(), publisher) !=
                    android.content.pm.PackageManager.SIGNATURE_MATCH) continue;
            try {
                getContentResolver().registerContentObserver(
                        Uri.parse("content://" + publisher + ".space-menu"),
                        true, updates);
            } catch (SecurityException | IllegalArgumentException unavailable) {
                // onResume still performs a fresh authenticated read.
            }
        }
    }

    @Override public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        final int version = generation.incrementAndGet();
        current = null;
        start.setEnabled(false);
        status.setText("Проверка профиля и файлов APK...");
        io.execute(() -> {
            SpaceProfileReader.Result result = SpaceProfileReader.load(this);
            runOnUiThread(() -> {
                if (isDestroyed() || generation.get() != version) return;
                current = result.ready ? result : null;
                status.setText(result.message);
                start.setEnabled(result.ready);
            });
        });
    }

    private void launchVerified() {
        final SpaceProfileReader.Result selected = current;
        if (selected == null || !selected.ready) return;
        start.setEnabled(false);
        final int version = generation.incrementAndGet();
        io.execute(() -> {
            // A stale/cached UI selection cannot authorize launch. Re-read the
            // publisher's index and rehash every installed APK on each click.
            SpaceProfileReader.Result verified = SpaceProfileReader.load(this);
            runOnUiThread(() -> {
                if (isDestroyed() || generation.get() != version) return;
                if (!selected.sameIdentity(verified)) {
                    current = null;
                    status.setText("Сборка изменилась. Повторите проверку.");
                    return;
                }
                Intent intent = getPackageManager().getLaunchIntentForPackage(selected.packageName);
                if (intent == null || intent.getComponent() == null ||
                        !selected.packageName.equals(intent.getComponent().getPackageName())) {
                    current = null;
                    status.setText("Экран запуска игры больше недоступен.");
                    return;
                }
                try {
                    startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException failure) {
                    status.setText("Не удалось запустить проверенную игру.");
                } finally {
                    refresh();
                }
            });
        });
    }

    @Override public void onDestroy() {
        generation.incrementAndGet();
        getContentResolver().unregisterContentObserver(updates);
        io.shutdownNow();
        super.onDestroy();
    }
}
