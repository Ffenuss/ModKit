package io.github.ffenuss.modkit.space;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Registering an available menu must never change the selected virtual session. */
final class SpaceTargetStore {
    private final SharedPreferences preferences;
    SpaceTargetStore(SharedPreferences preferences) { this.preferences = preferences; }

    void register(String pkg, int user) {
        String key = key(pkg, user);
        Set<String> targets = new TreeSet<>(preferences.getStringSet("targets", Collections.emptySet()));
        if (targets.add(key)) preferences.edit().putStringSet("targets", targets).apply();
    }

    void select(String pkg, int user) {
        register(pkg, user);
        preferences.edit().putString("selected", key(pkg, user)).apply();
    }

    Session selected() { return parse(preferences.getString("selected", "")); }

    String[] choices() {
        Set<String> valid = new TreeSet<>();
        for (String key : preferences.getStringSet("targets", Collections.emptySet()))
            if (parse(key) != null) valid.add(key);
        return valid.toArray(new String[0]);
    }

    private static String key(String pkg, int user) {
        if (!SpacePolicy.validSession(pkg, user)) throw new IllegalArgumentException("Invalid virtual target");
        return user + ":" + pkg;
    }

    static Session parse(String key) {
        if (key == null) return null;
        int colon = key.indexOf(':');
        if (colon < 1) return null;
        try {
            int user = Integer.parseInt(key.substring(0, colon));
            String pkg = key.substring(colon + 1);
            return SpacePolicy.validSession(pkg, user) ? new Session(pkg, user) : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    static final class Session {
        final String packageName;
        final int user;
        Session(String packageName, int user) { this.packageName = packageName; this.user = user; }
    }
}
