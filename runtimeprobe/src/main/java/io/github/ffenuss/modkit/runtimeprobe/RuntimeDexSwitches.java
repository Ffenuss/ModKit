package io.github.ffenuss.modkit.runtimeprobe;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Called by instrumented getters. Reads are lock-free; flags reset on process restart. */
public final class RuntimeDexSwitches {
    private static volatile Set<String> available = Collections.emptySet();
    private static volatile Set<String> enabled = Collections.emptySet();
    private RuntimeDexSwitches() {}

    static void initialize(Context context) {
        try (InputStream input = context.getAssets().open("modkit-dex-switches.txt")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (out.size() + count > 4096) throw new IllegalArgumentException("DEX catalog too large");
                out.write(buffer, 0, count);
            }
            Set<String> ids = new HashSet<>();
            for (String id : new String(out.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                if (!id.matches("dex:[0-9a-f]{32}") || !ids.add(id))
                    throw new IllegalArgumentException("Invalid DEX catalog");
            }
            if (ids.isEmpty() || ids.size() > 24) throw new IllegalArgumentException("Invalid DEX catalog size");
            available = Collections.unmodifiableSet(ids);
        } catch (Exception ignored) {
            // Native-only builds have no catalog. A corrupt/missing catalog never enables DEX changes.
            available = Collections.emptySet();
        }
    }

    public static boolean isEnabled(String id) { return enabled.contains(id); }
    static boolean isAvailable(String id) { return available.contains(id); }

    // RuntimeModMenu serializes this operation with configuration replacement and snapshots.
    static boolean setEnabled(String id, boolean value) {
        if (!available.contains(id)) return false;
        Set<String> next = new HashSet<>(enabled);
        if (value) next.add(id); else next.remove(id);
        enabled = Collections.unmodifiableSet(next);
        return isEnabled(id) == value;
    }
}
