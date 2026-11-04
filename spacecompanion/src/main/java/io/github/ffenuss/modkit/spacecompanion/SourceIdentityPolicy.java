package io.github.ffenuss.modkit.spacecompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Compares complete APK sets. File order is irrelevant, missing or duplicate
 * split identities fail closed. This policy does not infer runtime capability. */
public final class SourceIdentityPolicy {
    private SourceIdentityPolicy() {}

    public static boolean matches(List<String> expected, List<String> actual) {
        if (expected == null || actual == null ||
                expected.isEmpty() || expected.size() > 64 ||
                expected.size() != actual.size()) return false;
        if (new HashSet<>(expected).size() != expected.size() ||
                new HashSet<>(actual).size() != actual.size()) return false;
        for (String entry : expected) if (!valid(entry)) return false;
        for (String entry : actual) if (!valid(entry)) return false;
        List<String> a = new ArrayList<>(expected), b = new ArrayList<>(actual);
        Collections.sort(a);
        Collections.sort(b);
        return a.equals(b);
    }

    private static boolean valid(String identity) {
        if (identity == null || !identity.matches("[a-f0-9]{64}:[1-9][0-9]*")) return false;
        try {
            return Long.parseLong(identity.substring(65)) > 0;
        } catch (NumberFormatException bad) {
            return false;
        }
    }
}
