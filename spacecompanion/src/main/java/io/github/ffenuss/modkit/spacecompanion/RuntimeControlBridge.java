package io.github.ffenuss.modkit.spacecompanion;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/**
 * No code injection: only a specific cooperating repository-owned fixture
 * implements the versioned control provider. Third-party games never get
 * synthetic, nonfunctional mod switches.
 */
public final class RuntimeControlBridge {
    public static final String OWNED_PACKAGE = "dev.modkit.fixture";
    private static final String AUTHORITY = "dev.modkit.fixture.modkit-runtime";
    private static final String URI_TEXT = "content://" + AUTHORITY;
    private static final String CONTRACT = "owned_fixture_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ACTIVE = "active";

    public static final class State {
        public final boolean reachable;
        public final boolean active;
        public final boolean enabled;
        public final String message;
        State(boolean reachable, boolean active, boolean enabled, String message) {
            this.reachable = reachable;
            this.active = active;
            this.enabled = enabled;
            this.message = message;
        }
    }

    private RuntimeControlBridge() {}

    public static boolean supports(SpaceCatalogReader.Entry entry) {
        return entry != null && OWNED_PACKAGE.equals(entry.packageName);
    }

    /** Call on an IO thread; a live binder call may start the fixture process. */
    public static State read(Context context, SpaceCatalogReader.Entry entry) {
        if (!supports(entry)) return unsupported();
        return perform(context, "status", null);
    }

    /**
     * Verified before enabling or disabling during a normal overlay session.
     * The separate disableForCleanup path is reserved for always-safe OFF.
     */
    public static State set(
            Context context, SpaceCatalogReader.Entry entry, boolean enabled) {
        if (!supports(entry)) return unsupported();
        SpaceCatalogReader.Verification verification =
                SpaceCatalogReader.verify(context, entry);
        if (!verification.ready)
            return new State(false, false, false, verification.message);
        Bundle request = new Bundle();
        request.putBoolean(KEY_ENABLED, enabled);
        return perform(context, "set_double_movement", request);
    }

    /** Revocation and service shutdown must still be able to restore OFF. */
    public static void disableForCleanup(Context context, SpaceCatalogReader.Entry entry) {
        if (!supports(entry)) return;
        Bundle request = new Bundle();
        request.putBoolean(KEY_ENABLED, false);
        perform(context, "set_double_movement", request);
    }

    private static State unsupported() {
        return new State(false, false, false, "Исполняемый рецепт для этой игры отсутствует");
    }

    private static State perform(Context context, String method, Bundle args) {
        try {
            // The URI overload is API 29+, while Companion supports API 26.
            Bundle result = Build.VERSION.SDK_INT >= 29 ?
                    context.getContentResolver().call(Uri.parse(URI_TEXT), method, null, args) :
                    context.getContentResolver().call(AUTHORITY, method, null, args);
            if (result == null || !CONTRACT.equals(result.getString("contract")) ||
                    !result.containsKey(KEY_ACTIVE) || !result.containsKey(KEY_ENABLED))
                return new State(false, false, false, "Игра не поддерживает проверенный контракт");
            boolean active = result.getBoolean(KEY_ACTIVE);
            boolean enabled = active && result.getBoolean(KEY_ENABLED);
            return new State(true, active, enabled,
                    active ? (enabled ? "Движение ×2 включено" : "Движение ×2 выключено")
                            : "Игра не находится в активной сессии");
        } catch (RuntimeException failure) {
            return new State(false, false, false,
                    "Канал управления недоступен: " + failure.getClass().getSimpleName());
        }
    }
}
