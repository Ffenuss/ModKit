package io.github.ffenuss.modkit.space;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;

public final class SpaceTargetStoreTest {
    public static void main(String[] args) {
        Map<String, Object> disk = new HashMap<>();
        SharedPreferences preferences = preferences(disk);
        SpaceTargetStore targets = new SpaceTargetStore(preferences);
        if (targets.selected() != null) throw new AssertionError("Fresh space selected a target");
        targets.select("io.fixture.first", 7);
        targets.register("io.fixture.second", 0);
        targets.register("io.fixture.first", 0);
        assertSelection(targets, "io.fixture.first", 7);
        assertSelection(new SpaceTargetStore(preferences(disk)), "io.fixture.first", 7);
        if (targets.choices().length != 3) throw new AssertionError("Lost a separate virtual session");
        targets.select("io.fixture.second", 0);
        targets.register("io.fixture.first", 0);
        assertSelection(targets, "io.fixture.second", 0);
        Set<String> stored = new HashSet<>((Set<String>) disk.get("targets"));
        stored.add("-1:io.fixture.bad"); stored.add("junk"); disk.put("targets", stored);
        if (targets.choices().length != 3) throw new AssertionError("Malformed targets exposed");
        disk.put("selected", "2147483648:io.fixture.first");
        if (new SpaceTargetStore(preferences).selected() != null) throw new AssertionError("Invalid selection restored");
        try { targets.select("../bad", 0); throw new AssertionError("Invalid target selected"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("PASS: imports preserve app/user, restart restoration, target validation");
    }

    private static void assertSelection(SpaceTargetStore targets, String pkg, int user) {
        SpaceTargetStore.Session selected = targets.selected();
        if (selected == null || !pkg.equals(selected.packageName) || user != selected.user)
            throw new AssertionError("Menu sync changed the selected app/user");
    }

    private static SharedPreferences preferences(Map<String, Object> disk) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getString": return disk.getOrDefault(args[0], args[1]);
                    case "getStringSet": return Collections.unmodifiableSet(new HashSet<>((Set<String>) disk.getOrDefault(args[0], args[1])));
                    case "edit":
                        Map<String, Object> changes = new HashMap<>();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editor, action, values) -> {
                                switch (action.getName()) {
                                    case "putString": changes.put((String) values[0], values[1]); return editor;
                                    case "putStringSet": changes.put((String) values[0], new HashSet<>((Set<String>) values[1])); return editor;
                                    case "apply": disk.putAll(changes); return null;
                                    default: throw new UnsupportedOperationException(action.getName());
                                }
                            });
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
    }
}
