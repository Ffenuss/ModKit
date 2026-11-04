package io.github.ffenuss.modkit.spacecompanion;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.util.Collections;
import java.util.List;

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

    public static final class RecipeSnapshot {
        public final boolean reachable;
        public final boolean active;
        public final List<VerifiedRecipeContract.Recipe> recipes;
        public final String message;

        RecipeSnapshot(boolean reachable, boolean active,
                List<VerifiedRecipeContract.Recipe> recipes, String message) {
            this.reachable = reachable;
            this.active = active;
            this.recipes = recipes;
            this.message = message;
        }

        public VerifiedRecipeContract.Recipe find(String id) {
            for (VerifiedRecipeContract.Recipe recipe : recipes)
                if (recipe.id.equals(id)) return recipe;
            return null;
        }
    }

    /** Discover actual executable capabilities in a known, verified build. */
    public static RecipeSnapshot discoverRecipes(
            Context context, SpaceCatalogReader.Entry entry) {
        if (!supports(entry)) return unavailableRecipes("Неподдерживаемая игра");
        return performRecipeCall(context, "recipes_v2", null);
    }

    /** Rehash the installed game and re-read the catalog before every write. */
    public static RecipeSnapshot setRecipe(
            Context context, SpaceCatalogReader.Entry entry,
            String id, boolean enabled) {
        if (!supports(entry) || VerifiedRecipeContract.labelFor(id) == null)
            return unavailableRecipes("Неизвестный исполняемый рецепт");
        SpaceCatalogReader.Verification proof = SpaceCatalogReader.verify(context, entry);
        if (!proof.ready) return unavailableRecipes(proof.message);
        // Read a valid capability snapshot first: never issue set against a
        // missing/changed/incompatible provider that merely shares an authority.
        RecipeSnapshot before = discoverRecipes(context, entry);
        if (!before.reachable || !before.active || before.find(id) == null)
            return unavailableRecipes("Проверенная игровая сессия недоступна");
        Bundle request = new Bundle();
        request.putString("recipeId", id);
        request.putBoolean(KEY_ENABLED, enabled);
        RecipeSnapshot after = performRecipeCall(context, "set_recipe_v2", request);
        VerifiedRecipeContract.Recipe acknowledged = after.find(id);
        if (!after.reachable || !after.active ||
                acknowledged == null || acknowledged.enabled != enabled)
            return unavailableRecipes("Игра не подтвердила изменение рецепта");
        return after;
    }

    /** Always-safe OFF for both approved controls, even if profile was revoked. */
    public static void disableAllRecipesForCleanup(
            Context context, SpaceCatalogReader.Entry entry) {
        if (!supports(entry)) return;
        for (String id : new String[]{
                VerifiedRecipeContract.MOVEMENT, VerifiedRecipeContract.SHIELD}) {
            Bundle off = new Bundle();
            off.putString("recipeId", id);
            off.putBoolean(KEY_ENABLED, false);
            performRecipeCall(context, "set_recipe_v2", off);
        }
    }

    private static RecipeSnapshot unavailableRecipes(String message) {
        return new RecipeSnapshot(false, false, Collections.emptyList(), message);
    }

    private static RecipeSnapshot performRecipeCall(
            Context context, String operation, Bundle args) {
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse(URI_TEXT), operation, null, args);
            if (result == null || !result.containsKey(KEY_ACTIVE))
                return unavailableRecipes("Рантайм не сообщил состояние");
            boolean active = result.getBoolean(KEY_ACTIVE);
            List<VerifiedRecipeContract.Recipe> recipes =
                    VerifiedRecipeContract.accept(
                            result.getString("contract"), active,
                            result.getStringArray("ids"),
                            result.getStringArray("labels"),
                            result.getBooleanArray("enabledStates"));
            if (recipes == null)
                return unavailableRecipes("Несовместимый список исполняемых рецептов");
            return new RecipeSnapshot(true, active, recipes,
                    active ? "Рецепты доступны" : "Игровая сессия не активна");
        } catch (RuntimeException failure) {
            return unavailableRecipes("Рантайм недоступен: " +
                    failure.getClass().getSimpleName());
        }
    }

    private static State unsupported() {
        return new State(false, false, false, "Исполняемый рецепт для этой игры отсутствует");
    }

    private static State perform(Context context, String method, Bundle args) {
        try {
            // The Uri overload is available since API 11, including our
            // supported Android API 26-28 baseline.
            Bundle result = context.getContentResolver()
                    .call(Uri.parse(URI_TEXT), method, null, args);
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
