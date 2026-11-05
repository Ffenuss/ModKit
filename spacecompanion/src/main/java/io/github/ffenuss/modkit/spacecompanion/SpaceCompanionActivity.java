package io.github.ffenuss.modkit.spacecompanion;

import android.app.Activity;
import android.provider.Settings;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private TextView catalogStatus;
    private LinearLayout catalogRows;
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
                "Это обычный запуск Android. Доступно плавающее информационное меню; виртуализация и работающие моды пока отсутствуют.");
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
        TextView gamesTitle = new TextView(this);
        gamesTitle.setTextSize(20);
        gamesTitle.setPadding(0, padding, 0, padding / 2);
        gamesTitle.setText("Сборки игр · отдельные профили");
        root.addView(gamesTitle);
        catalogStatus = new TextView(this);
        root.addView(catalogStatus);
        catalogRows = new LinearLayout(this);
        catalogRows.setOrientation(LinearLayout.VERTICAL);
        root.addView(catalogRows);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

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
        catalogStatus.setText("Загрузка списка сборок...");
        catalogRows.removeAllViews();
        io.execute(() -> {
            SpaceProfileReader.Result result = SpaceProfileReader.load(this);
            SpaceCatalogReader.Catalog catalog = SpaceCatalogReader.load(this);
            runOnUiThread(() -> {
                if (isDestroyed() || generation.get() != version) return;
                current = result.ready ? result : null;
                status.setText(result.message);
                start.setEnabled(result.ready);
                catalogStatus.setText(catalog.message);
                // Catalog entries are *not* pre-verified. Each button re-reads
                // the signed publisher and hashes its own game on every tap.
                for (SpaceCatalogReader.Entry entry : catalog.entries) {
                    Button game = new Button(this);
                    game.setText("Проверить и запустить: " + entry.packageName);
                    game.setAllCaps(false);
                    game.setOnClickListener(v -> launchCatalogEntry(entry, false));
                    catalogRows.addView(game);
                    Button overlay = new Button(this);
                    overlay.setText("Оверлей и запуск: " + entry.packageName);
                    overlay.setAllCaps(false);
                    overlay.setOnClickListener(v -> launchCatalogEntry(entry, true));
                    catalogRows.addView(overlay);
                }
            });
        });
    }

    private void launchCatalogEntry(
            SpaceCatalogReader.Entry selected, boolean withOverlay) {
        if (selected == null) return;
        if (withOverlay && !Settings.canDrawOverlays(this)) {
            status.setText("Разрешите показывать окно поверх других приложений. " +
                    "После возврата нажмите кнопку запуска ещё раз.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException unavailable) {
                status.setText("Настройка разрешения оверлея недоступна.");
            }
            return;
        }
        final int version = generation.incrementAndGet();
        start.setEnabled(false);
        status.setText("Проверка подписи и APK: " + selected.packageName);
        io.execute(() -> {
            SpaceCatalogReader.Verification verification =
                    SpaceCatalogReader.verify(this, selected);
            // For an instrumented, preconfigured game, instantiate its own
            // Runtime Probe provider BEFORE bringing its Activity forward.
            // This registers lifecycle callbacks in time for the next resume.
            // An unprepared game is merely reported unavailable; it is never
            // patched or modified during this read-only prewarm.
            if (verification.ready && withOverlay &&
                    !RuntimeControlBridge.supports(selected)) {
                PreparedNativeRecipeBridge.read(this, selected);
            }
            runOnUiThread(() -> {
                if (isDestroyed() || generation.get() != version) return;
                if (!verification.ready) {
                    status.setText(verification.message);
                    return;
                }
                Intent intent = getPackageManager()
                        .getLaunchIntentForPackage(selected.packageName);
                if (intent == null || intent.getComponent() == null ||
                        !selected.packageName.equals(intent.getComponent().getPackageName())) {
                    status.setText("Экран запуска игры больше недоступен.");
                    return;
                }
                Intent serviceIntent = new Intent(this, SpaceOverlayService.class)
                        .setAction(SpaceOverlayService.ACTION_START)
                        .putExtra(SpaceOverlayService.EXTRA_PACKAGE, selected.packageName)
                        .putExtra(SpaceOverlayService.EXTRA_ARTIFACT, selected.artifactSha256)
                        .putExtra(SpaceOverlayService.EXTRA_PUBLISHER, selected.publisher);
                try {
                    if (withOverlay) startForegroundService(serviceIntent);
                    startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException failure) {
                    if (withOverlay) stopService(serviceIntent);
                    status.setText("Не удалось открыть проверенную игру или меню.");
                } finally {
                    refresh();
                }
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
