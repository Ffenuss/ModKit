package io.github.ffenuss.modkit.spacecompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fail-closed contract for a cooperating build. A provider's reported labels
 * are never used as command IDs or arbitrary UI controls. Only individually
 * implemented and reviewed recipe IDs may cross this boundary.
 *
 * This is NOT engine-agnostic runtime modification. Additional adapters must
 * independently implement and prove their own explicit recipe IDs.
 */
public final class VerifiedRecipeContract {
    public static final String VERSION = "owned_fixture_recipe_v2";
    public static final String MOVEMENT = "movement_x2";
    public static final String SHIELD = "damage_shield";

    public static final class Recipe {
        public final String id;
        public final String label;
        public final boolean enabled;

        private Recipe(String id, String label, boolean enabled) {
            this.id = id;
            this.label = label;
            this.enabled = enabled;
        }
    }

    private VerifiedRecipeContract() {}

    public static String labelFor(String id) {
        if (MOVEMENT.equals(id)) return "Движение ×2";
        if (SHIELD.equals(id)) return "Защита здоровья";
        return null;
    }

    public static List<Recipe> accept(
            String contract, boolean active, String[] ids,
            String[] labels, boolean[] states) {
        if (!VERSION.equals(contract) || ids == null || labels == null ||
                states == null || ids.length != 2 ||
                labels.length != ids.length || states.length != ids.length)
            return null;
        List<Recipe> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.length; i++) {
            String name = labelFor(ids[i]);
            if (name == null || !name.equals(labels[i]) ||
                    !seen.add(ids[i]) || (!active && states[i]))
                return null;
            result.add(new Recipe(ids[i], name, states[i]));
        }
        if (!seen.contains(MOVEMENT) || !seen.contains(SHIELD)) return null;
        return Collections.unmodifiableList(result);
    }
}
