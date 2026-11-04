package io.github.ffenuss.modkit.spacecompanion;

import java.util.List;

/** Immutable session identity prevents a stale menu from switching targets. */
public final class OverlaySessionPolicy {
    private static final String PKG =
            "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+";
    private static final String SHA = "[a-f0-9]{64}";

    private OverlaySessionPolicy() {}

    public static boolean valid(String pkg, String artifact, String publisher) {
        return pkg != null && pkg.length() <= 255 && pkg.matches(PKG) &&
                artifact != null && artifact.matches(SHA) &&
                ("io.github.ffenuss.modkit".equals(publisher) ||
                 "io.github.ffenuss.modkit.test".equals(publisher));
    }

    public static SpaceCatalogReader.Entry find(
            List<SpaceCatalogReader.Entry> entries,
            String pkg, String artifact, String publisher) {
        if (!valid(pkg, artifact, publisher) || entries == null) return null;
        SpaceCatalogReader.Entry found = null;
        for (SpaceCatalogReader.Entry entry : entries) {
            if (pkg.equals(entry.packageName) &&
                    artifact.equals(entry.artifactSha256) &&
                    publisher.equals(entry.publisher)) {
                if (found != null) return null;
                found = entry;
            }
        }
        return found;
    }
}
