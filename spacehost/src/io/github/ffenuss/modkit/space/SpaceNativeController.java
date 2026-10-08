package io.github.ffenuss.modkit.space;

import java.util.HashMap;
import java.util.Map;

/** All writes occur in the guest process. An uncertain write never becomes a successful switch. */
final class SpaceNativeController {
    interface Backend {
        boolean imageMatches(NativePatch patch) throws Exception;
        boolean bytesMatch(NativePatch patch, byte[] bytes);
        int write(NativePatch patch, byte[] expected, byte[] replacement);
    }
    enum State { OFF, ON, UNAVAILABLE, ERROR }
    private final Backend backend;
    private final Map<String, NativePatch> patches = new HashMap<>();
    private final Map<String, State> states = new HashMap<>();
    SpaceNativeController(MenuProfile profile, Backend backend) {
        this.backend = backend;
        for (MenuProfile.Item item : profile.items) if (item.patch != null) {
            patches.put(item.id, item.patch); states.put(item.id, State.UNAVAILABLE);
        }
    }
    synchronized State state(String id) { return states.getOrDefault(id, State.UNAVAILABLE); }
    synchronized void refresh() {
        for (Map.Entry<String, NativePatch> entry : patches.entrySet()) {
            if (state(entry.getKey()) != State.UNAVAILABLE) continue;
            try {
                if (backend.imageMatches(entry.getValue()) && backend.bytesMatch(entry.getValue(), entry.getValue().expected))
                    states.put(entry.getKey(), State.OFF);
            } catch (Exception | LinkageError ignored) { }
        }
    }
    synchronized boolean set(String id, boolean enabled) {
        NativePatch patch = patches.get(id); State before = state(id);
        if (patch == null || before == State.ERROR || before == State.UNAVAILABLE) return false;
        if ((before == State.ON) == enabled) return true;
        try {
            if (!backend.imageMatches(patch)) { states.put(id, State.ERROR); return false; }
            byte[] from = enabled ? patch.expected : patch.replacement;
            byte[] to = enabled ? patch.replacement : patch.expected;
            int result = backend.write(patch, from, to);
            if (result == 1 && backend.bytesMatch(patch, to)) {
                states.put(id, enabled ? State.ON : State.OFF); return true;
            }
            // A rejected precondition may leave the known old state. A partial write is unknown.
            states.put(id, result == 0 && backend.bytesMatch(patch, from) ? before : State.ERROR);
        } catch (Exception | LinkageError failure) { states.put(id, State.ERROR); }
        return false;
    }
    synchronized boolean restoreAll() {
        boolean restored = true;
        for (String id : patches.keySet()) {
            if (state(id) == State.ON) restored &= set(id, false);
            if (state(id) == State.ERROR) restored = false;
        }
        return restored;
    }
}
