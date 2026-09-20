package io.github.ffenuss.modkit.runtimeprobe;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RuntimeModMenu {
    static final String MODE_PATCH = "PATCH";
    static final String MODE_INFO = "INFO";

    private static final String PREFS = "modkit_runtime_menu_v1";
    private static final String KEY_CONFIG = "config";
    private static final int MAX_ITEMS = 64;
    private static final int MAX_LABEL_CHARS = 180;
    private static final int MAX_DETAIL_CHARS = 320;
    private static final int MAX_PATCH_BYTES = 64;

    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Boolean> ACTIVE = new HashMap<>();
    private static final Set<String> APPLYING = new HashSet<>();

    private static volatile boolean installed;
    private static volatile Config config = Config.empty();
    private static WeakReference<Activity> resumedActivity =
            new WeakReference<>(null);
    private static View attachedOverlay;

    private RuntimeModMenu() {}

    static void install(Context context) {
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) {
            return;
        }
        synchronized (LOCK) {
            if (installed) return;
            installed = true;
            config = load(appContext);
        }

        Application application = (Application) appContext;
        application.registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityCreated(
                            Activity activity,
                            Bundle state
                    ) {}

                    @Override
                    public void onActivityStarted(Activity activity) {}

                    @Override
                    public void onActivityResumed(Activity activity) {
                        synchronized (LOCK) {
                            resumedActivity =
                                    new WeakReference<>(activity);
                        }
                        refresh(activity);
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                        synchronized (LOCK) {
                            Activity current = resumedActivity.get();
                            if (current == activity) {
                                resumedActivity =
                                        new WeakReference<>(null);
                            }
                        }
                        detach();
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {}

                    @Override
                    public void onActivitySaveInstanceState(
                            Activity activity,
                            Bundle state
                    ) {}

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                        synchronized (LOCK) {
                            Activity current = resumedActivity.get();
                            if (current == activity) {
                                resumedActivity =
                                        new WeakReference<>(null);
                            }
                        }
                        detach();
                    }
                }
        );
    }

    static int configure(
            Context context,
            Bundle extras
    ) {
        if (extras == null) {
            throw new IllegalArgumentException(
                    "Runtime test menu configuration is missing."
            );
        }
        Config parsed = parseBundle(extras);
        persist(context.getApplicationContext(), parsed);
        synchronized (LOCK) {
            config = parsed;
            ACTIVE.clear();
            APPLYING.clear();
        }
        requestRefresh();
        return parsed.items.size();
    }

    static void clear(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CONFIG)
                .apply();
        synchronized (LOCK) {
            config = Config.empty();
            ACTIVE.clear();
            APPLYING.clear();
        }
        requestRefresh();
    }

    static int itemCount() {
        return config.items.size();
    }

    static int patchItemCount() {
        int count = 0;
        for (Item item : config.items) {
            if (MODE_PATCH.equals(item.mode)) count++;
        }
        return count;
    }

    static int infoItemCount() {
        int count = 0;
        for (Item item : config.items) {
            if (MODE_INFO.equals(item.mode)) count++;
        }
        return count;
    }

    private static void requestRefresh() {
        MAIN.post(() -> {
            Activity activity;
            synchronized (LOCK) {
                activity = resumedActivity.get();
            }
            if (activity == null) {
                detach();
                return;
            }
            refresh(activity);
        });
    }

    private static void refresh(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> refresh(activity));
            return;
        }
        detach();
        Config snapshot = config;
        if (snapshot.items.isEmpty() ||
                activity.isFinishing() ||
                activity.isDestroyed()) {
            return;
        }

        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            return;
        }
        ViewGroup host = (ViewGroup) content;
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setClickable(false);
        host.addView(
                overlay,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
        attachedOverlay = overlay;

        Button bubble = new Button(activity);
        bubble.setText("MK");
        bubble.setAllCaps(false);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13f);
        bubble.setBackground(
                rounded(Color.argb(235, 78, 56, 126), dp(activity, 18))
        );
        FrameLayout.LayoutParams bubbleParams =
                new FrameLayout.LayoutParams(
                        dp(activity, 58),
                        dp(activity, 46),
                        Gravity.TOP | Gravity.END
                );
        bubbleParams.topMargin = dp(activity, 22);
        bubbleParams.rightMargin = dp(activity, 14);
        overlay.addView(bubble, bubbleParams);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVisibility(View.GONE);
        scroll.setBackground(
                rounded(Color.argb(245, 35, 33, 41), dp(activity, 16))
        );
        FrameLayout.LayoutParams panelParams =
                new FrameLayout.LayoutParams(
                        Math.min(
                                dp(activity, 340),
                                Math.max(
                                        dp(activity, 260),
                                        screenWidth(activity) -
                                                dp(activity, 28)
                                )
                        ),
                        Math.min(
                                dp(activity, 540),
                                Math.max(
                                        dp(activity, 280),
                                        screenHeight(activity) * 2 / 3
                                )
                        ),
                        Gravity.TOP | Gravity.END
                );
        panelParams.topMargin = dp(activity, 76);
        panelParams.rightMargin = dp(activity, 14);
        overlay.addView(scroll, panelParams);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(
                dp(activity, 14),
                dp(activity, 12),
                dp(activity, 14),
                dp(activity, 14)
        );
        scroll.addView(
                column,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        TextView title = text(
                activity,
                "ModKit Test Menu",
                17f,
                Color.WHITE
        );
        column.addView(title);

        TextView summary = text(
                activity,
                "Runtime-переключатели: " +
                        snapshot.patchCount() +
                        " · диагностика: " +
                        snapshot.infoCount(),
                12f,
                Color.LTGRAY
        );
        summary.setPadding(0, 0, 0, dp(activity, 8));
        column.addView(summary);

        for (Item item : snapshot.items) {
            if (MODE_PATCH.equals(item.mode)) {
                addPatchItem(activity, column, item);
            } else {
                addInfoItem(activity, column, item);
            }
        }

        bubble.setOnClickListener(view -> {
            scroll.setVisibility(
                    scroll.getVisibility() == View.VISIBLE
                            ? View.GONE
                            : View.VISIBLE
            );
        });
    }

    private static void addPatchItem(
            Activity activity,
            LinearLayout column,
            Item item
    ) {
        CheckBox checkBox = new CheckBox(activity);
        checkBox.setText(item.label);
        checkBox.setTextColor(Color.WHITE);
        checkBox.setTextSize(14f);
        synchronized (LOCK) {
            checkBox.setChecked(
                    Boolean.TRUE.equals(ACTIVE.get(item.id))
            );
        }
        final boolean[] internalChange = {false};
        checkBox.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (internalChange[0]) return;
                    boolean ok = apply(item, checked);
                    if (!ok) {
                        internalChange[0] = true;
                        button.setChecked(!checked);
                        internalChange[0] = false;
                        Toast.makeText(
                                activity,
                                "Патч не применён: target/байты не совпали или модуль ещё не загружен.",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
        );
        column.addView(checkBox);

        if (!item.detail.isEmpty()) {
            TextView detail = text(
                    activity,
                    item.detail,
                    11f,
                    Color.LTGRAY
            );
            detail.setPadding(
                    dp(activity, 38),
                    0,
                    0,
                    dp(activity, 6)
            );
            column.addView(detail);
        }
    }

    private static void addInfoItem(
            Activity activity,
            LinearLayout column,
            Item item
    ) {
        TextView info = text(
                activity,
                "ⓘ " + item.label,
                13f,
                Color.rgb(225, 214, 255)
        );
        info.setPadding(0, dp(activity, 6), 0, 0);
        column.addView(info);
        if (!item.detail.isEmpty()) {
            TextView detail = text(
                    activity,
                    item.detail,
                    11f,
                    Color.LTGRAY
            );
            detail.setPadding(
                    dp(activity, 18),
                    0,
                    0,
                    dp(activity, 5)
            );
            column.addView(detail);
        }
    }

    private static boolean apply(
            Item item,
            boolean enable
    ) {
        synchronized (LOCK) {
            if (APPLYING.contains(item.id)) {
                return false;
            }
            APPLYING.add(item.id);
        }
        try {
            byte[] original = parseHex(item.originalHex);
            byte[] replacement = parseHex(item.replacementHex);
            boolean ok = RuntimeNativeBridge.patchCode(
                    item.moduleName,
                    item.binaryVirtualAddress,
                    enable ? original : replacement,
                    enable ? replacement : original
            );
            if (ok) {
                synchronized (LOCK) {
                    ACTIVE.put(item.id, enable);
                }
            }
            return ok;
        } finally {
            synchronized (LOCK) {
                APPLYING.remove(item.id);
            }
        }
    }

    private static void detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(RuntimeModMenu::detach);
            return;
        }
        View current = attachedOverlay;
        attachedOverlay = null;
        if (current == null) return;
        if (current.getParent() instanceof ViewGroup) {
            ((ViewGroup) current.getParent()).removeView(current);
        }
    }

    private static Config parseBundle(Bundle extras) {
        ArrayList<String> ids = extras.getStringArrayList("ids");
        ArrayList<String> labels = extras.getStringArrayList("labels");
        ArrayList<String> details = extras.getStringArrayList("details");
        ArrayList<String> modes = extras.getStringArrayList("modes");
        ArrayList<String> modules = extras.getStringArrayList("modules");
        ArrayList<String> originals =
                extras.getStringArrayList("originalHex");
        ArrayList<String> replacements =
                extras.getStringArrayList("replacementHex");
        long[] addresses =
                extras.getLongArray("binaryVirtualAddresses");

        if (ids == null ||
                labels == null ||
                details == null ||
                modes == null ||
                modules == null ||
                originals == null ||
                replacements == null ||
                addresses == null) {
            throw new IllegalArgumentException(
                    "Runtime test menu bundle is incomplete."
            );
        }
        int count = ids.size();
        if (count < 0 || count > MAX_ITEMS ||
                labels.size() != count ||
                details.size() != count ||
                modes.size() != count ||
                modules.size() != count ||
                originals.size() != count ||
                replacements.size() != count ||
                addresses.length != count) {
            throw new IllegalArgumentException(
                    "Runtime test menu arrays have inconsistent sizes."
            );
        }

        List<Item> items = new ArrayList<>();
        Set<String> uniqueIds = new HashSet<>();
        for (int index = 0; index < count; index++) {
            String id = requireText(
                    ids.get(index),
                    "item id",
                    128
            );
            if (!uniqueIds.add(id)) {
                throw new IllegalArgumentException(
                        "Runtime test menu contains duplicate item id."
                );
            }
            String label = requireText(
                    labels.get(index),
                    "item label",
                    MAX_LABEL_CHARS
            );
            String detail =
                    details.get(index) == null
                            ? ""
                            : details.get(index).trim();
            if (detail.length() > MAX_DETAIL_CHARS) {
                throw new IllegalArgumentException(
                        "Runtime test menu detail is too long."
                );
            }
            String mode = requireText(
                    modes.get(index),
                    "item mode",
                    16
            );
            if (!MODE_PATCH.equals(mode) &&
                    !MODE_INFO.equals(mode)) {
                throw new IllegalArgumentException(
                        "Unsupported runtime test menu item mode."
                );
            }

            String module =
                    modules.get(index) == null
                            ? ""
                            : modules.get(index).trim();
            String original =
                    originals.get(index) == null
                            ? ""
                            : originals.get(index).trim();
            String replacement =
                    replacements.get(index) == null
                            ? ""
                            : replacements.get(index).trim();
            long address = addresses[index];

            if (MODE_PATCH.equals(mode)) {
                if (!validModule(module) || address <= 0L) {
                    throw new IllegalArgumentException(
                            "Patch menu item lacks a valid module/address."
                    );
                }
                byte[] before = parseHex(original);
                byte[] after = parseHex(replacement);
                if (before.length == 0 ||
                        before.length != after.length ||
                        before.length > MAX_PATCH_BYTES ||
                        before.length % 4 != 0 ||
                        java.util.Arrays.equals(before, after)) {
                    throw new IllegalArgumentException(
                            "Patch menu item byte ranges are invalid."
                    );
                }
            } else {
                module = "";
                original = "";
                replacement = "";
                address = 0L;
            }

            items.add(
                    new Item(
                            id,
                            label,
                            detail,
                            mode,
                            module,
                            address,
                            original,
                            replacement
                    )
            );
        }
        return new Config(items);
    }

    private static void persist(
            Context context,
            Config value
    ) {
        JSONArray items = new JSONArray();
        try {
            for (Item item : value.items) {
                JSONObject object = new JSONObject();
                object.put("id", item.id);
                object.put("label", item.label);
                object.put("detail", item.detail);
                object.put("mode", item.mode);
                object.put("module", item.moduleName);
                object.put(
                        "binaryVirtualAddress",
                        item.binaryVirtualAddress
                );
                object.put("originalHex", item.originalHex);
                object.put(
                        "replacementHex",
                        item.replacementHex
                );
                items.put(object);
            }
            JSONObject root = new JSONObject();
            root.put("schemaVersion", 1);
            root.put("items", items);
            context.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
            ).edit().putString(
                    KEY_CONFIG,
                    root.toString()
            ).apply();
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Could not persist runtime test menu.",
                    failure
            );
        }
    }

    private static Config load(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                );
        String raw = preferences.getString(KEY_CONFIG, null);
        if (raw == null || raw.isEmpty()) {
            return Config.empty();
        }
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("schemaVersion", 0) != 1) {
                return Config.empty();
            }
            JSONArray array = root.getJSONArray("items");
            if (array.length() > MAX_ITEMS) {
                return Config.empty();
            }
            List<Item> items = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.getJSONObject(index);
                items.add(
                        new Item(
                                object.getString("id"),
                                object.getString("label"),
                                object.optString("detail", ""),
                                object.getString("mode"),
                                object.optString("module", ""),
                                object.optLong(
                                        "binaryVirtualAddress",
                                        0L
                                ),
                                object.optString(
                                        "originalHex",
                                        ""
                                ),
                                object.optString(
                                        "replacementHex",
                                        ""
                                )
                        )
                );
            }
            return new Config(items);
        } catch (Exception ignored) {
            return Config.empty();
        }
    }

    private static String requireText(
            String value,
            String label,
            int maxChars
    ) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() ||
                normalized.length() > maxChars) {
            throw new IllegalArgumentException(
                    "Invalid " + label + "."
            );
        }
        return normalized;
    }

    private static boolean validModule(String value) {
        return value != null &&
                !value.isEmpty() &&
                value.length() <= 255 &&
                !value.contains("/") &&
                !value.contains("\\") &&
                !value.contains("..") &&
                value.endsWith(".so");
    }

    private static byte[] parseHex(String value) {
        String normalized =
                value == null
                        ? ""
                        : value.replaceAll(
                                "[^0-9A-Fa-f]",
                                ""
                        );
        if (normalized.length() % 2 != 0) {
            throw new IllegalArgumentException(
                    "Hex value contains an incomplete byte."
            );
        }
        byte[] bytes =
                new byte[normalized.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] =
                    (byte) Integer.parseInt(
                            normalized.substring(
                                    index * 2,
                                    index * 2 + 2
                            ),
                            16
                    );
        }
        return bytes;
    }

    private static TextView text(
            Context context,
            String value,
            float sizeSp,
            int color
    ) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private static GradientDrawable rounded(
            int color,
            int radius
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static int dp(
            Context context,
            int value
    ) {
        return Math.round(
                value *
                        context.getResources()
                                .getDisplayMetrics()
                                .density
        );
    }

    private static int screenWidth(Context context) {
        DisplayMetrics metrics =
                context.getResources().getDisplayMetrics();
        return metrics.widthPixels;
    }

    private static int screenHeight(Context context) {
        DisplayMetrics metrics =
                context.getResources().getDisplayMetrics();
        return metrics.heightPixels;
    }

    private static final class Config {
        final List<Item> items;

        Config(List<Item> items) {
            this.items = Collections.unmodifiableList(
                    new ArrayList<>(items)
            );
        }

        int patchCount() {
            int count = 0;
            for (Item item : items) {
                if (MODE_PATCH.equals(item.mode)) count++;
            }
            return count;
        }

        int infoCount() {
            int count = 0;
            for (Item item : items) {
                if (MODE_INFO.equals(item.mode)) count++;
            }
            return count;
        }

        static Config empty() {
            return new Config(Collections.emptyList());
        }
    }

    private static final class Item {
        final String id;
        final String label;
        final String detail;
        final String mode;
        final String moduleName;
        final long binaryVirtualAddress;
        final String originalHex;
        final String replacementHex;

        Item(
                String id,
                String label,
                String detail,
                String mode,
                String moduleName,
                long binaryVirtualAddress,
                String originalHex,
                String replacementHex
        ) {
            this.id = id;
            this.label = label;
            this.detail = detail;
            this.mode = mode;
            this.moduleName = moduleName;
            this.binaryVirtualAddress = binaryVirtualAddress;
            this.originalHex = originalHex;
            this.replacementHex = replacementHex;
        }
    }
}
