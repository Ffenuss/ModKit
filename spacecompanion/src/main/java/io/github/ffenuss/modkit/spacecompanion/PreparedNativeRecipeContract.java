package io.github.ffenuss.modkit.spacecompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict data-only contract for native patches prepared INSIDE a signed APK.
 * An accepted binary write is NOT evidence of a changed game mechanic. */
public final class PreparedNativeRecipeContract {
    public static final String VERSION = "modkit_prepared_native_v1";
    private static final int MAX_RECIPES = 64;

    public static final class Recipe {
        public final String id;
        public final String label;
        public final boolean applied;

        Recipe(String id, String label, boolean applied) {
            this.id = id;
            this.label = label;
            this.applied = applied;
        }
    }

    private PreparedNativeRecipeContract() {}

    public static List<Recipe> accept(
            String version, String packageName, String expectedPackage,
            int schema, int pid, boolean accepted, String[] ids,
            String[] labels, boolean[] applied) {
        if (!VERSION.equals(version) || expectedPackage == null ||
                !expectedPackage.equals(packageName) || schema != 1 ||
                pid <= 0 || !accepted || ids == null || labels == null ||
                applied == null || ids.length > MAX_RECIPES ||
                ids.length != labels.length || ids.length != applied.length)
            return null;
        List<Recipe> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i], label = labels[i];
            if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,96}") ||
                    !unique.add(id) || label == null || label.isEmpty() ||
                    label.length() > 180)
                return null;
            for (int j = 0; j < label.length(); j++) {
                if (Character.isISOControl(label.charAt(j))) return null;
            }
            result.add(new Recipe(id, label, applied[i]));
        }
        return Collections.unmodifiableList(result);
    }
}
