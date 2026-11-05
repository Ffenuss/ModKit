package io.github.ffenuss.modkit.spacecompanion;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import java.util.Collections;
import java.util.List;

/**
 * Companion bridge to a separately PREPARED, signed runtime-probe APK.
 * The target is not required to implement its own controller/provider.
 *
 * The probe only permits toggling patch IDs that ModKit had already loaded
 * into the target. It never accepts raw addresses, bytes or DEX instructions
 * from the overlay. A binary patch acknowledgement is not a gameplay proof.
 */
public final class PreparedNativeRecipeBridge {
    private static final String PROVIDER =
            "io.github.ffenuss.modkit.runtimeprobe.RuntimeEvidenceProvider";
    private static final String AUTHORITY_SUFFIX = ".modkit.runtimeprobe";

    public static final class Status {
        public final boolean reachable;
        public final boolean active;
        public final List<PreparedNativeRecipeContract.Recipe> recipes;
        public final String message;

        Status(boolean reachable, boolean active,
                List<PreparedNativeRecipeContract.Recipe> recipes, String message) {
            this.reachable = reachable;
            this.active = active;
            this.recipes = recipes;
            this.message = message;
        }

        public PreparedNativeRecipeContract.Recipe find(String id) {
            for (PreparedNativeRecipeContract.Recipe recipe : recipes) {
                if (recipe.id.equals(id)) return recipe;
            }
            return null;
        }
    }

    private PreparedNativeRecipeBridge() {}

    public static Status read(Context context, SpaceCatalogReader.Entry entry) {
        if (entry == null) return unavailable("Не выбрана игра");
        if (!SpaceCatalogReader.verify(context, entry).ready)
            return unavailable("Подпись или состав APK изменились");
        return perform(context, entry, "preparedNativeStatusV1", null);
    }

    public static Status set(Context context, SpaceCatalogReader.Entry entry,
            String id, boolean desired) {
        if (entry == null || id == null) return unavailable("Нет подтверждённого рецепта");
        if (!SpaceCatalogReader.verify(context, entry).ready)
            return unavailable("Сборка игры изменилась");
        Status before = perform(context, entry, "preparedNativeStatusV1", null);
        if (!before.reachable || !before.active || before.find(id) == null)
            return unavailable("Рецепт не зарегистрирован в активном рантайме");
        Bundle request = new Bundle();
        request.putString("id", id);
        request.putBoolean("enabled", desired);
        Status after = perform(context, entry, "preparedNativeSetV1", request);
        PreparedNativeRecipeContract.Recipe acknowledged = after.find(id);
        if (!after.reachable || !after.active ||
                acknowledged == null || acknowledged.applied != desired)
            return unavailable("Рантайм не подтвердил замену байтов");
        return after;
    }

    /** Off is safe even after the publisher withdraws a previously used plan. */
    public static void restore(Context context, SpaceCatalogReader.Entry entry) {
        if (entry != null) perform(context, entry, "preparedNativeRestoreV1", null);
    }

    private static Status perform(Context context, SpaceCatalogReader.Entry entry,
            String method, Bundle extra) {
        try {
            String authority = entry.packageName + AUTHORITY_SUFFIX;
            ProviderInfo installed = context.getPackageManager()
                    .resolveContentProvider(authority, 0);
            if (installed == null ||
                    !entry.packageName.equals(installed.packageName) ||
                    !PROVIDER.equals(installed.name))
                return unavailable("Подготовленный Runtime Probe не установлен");
            Uri uri = new Uri.Builder().scheme("content")
                    .authority(authority).appendPath("evidence").build();
            Bundle response = context.getContentResolver().call(uri, method, null, extra);
            if (response == null || !response.containsKey("active"))
                return unavailable("Рантайм не ответил");
            boolean active = response.getBoolean("active");
            List<PreparedNativeRecipeContract.Recipe> parsed =
                    PreparedNativeRecipeContract.accept(
                            response.getString("contract"),
                            response.getString("packageName"),
                            entry.packageName, response.getInt("schemaVersion", -1),
                            response.getInt("pid", -1),
                            response.getBoolean("operationAccepted", false),
                            response.getStringArray("ids"),
                            response.getStringArray("labels"),
                            response.getBooleanArray("enabledStates"));
            if (parsed == null)
                return unavailable("Непроверенный ответ нативного рантайма");
            return new Status(true, active, parsed,
                    active ? "Бинарные патчи доступны (эффект не подтверждён)" :
                            "Игра не активна");
        } catch (RuntimeException exception) {
            return unavailable("Рантайм недоступен: " +
                    exception.getClass().getSimpleName());
        }
    }

    private static Status unavailable(String message) {
        return new Status(false, false, Collections.emptyList(), message);
    }
}
