package dev.modkit.fixture;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Owned fixture: distinguish adapted local queries from the real Android installer record. */
public final class InstallerProbe {
    private InstallerProbe() {}
    @SuppressWarnings("deprecation")
    public static String describe(Context context) {
        PackageManager manager = context.getPackageManager();
        String own = context.getPackageName();
        try {
            String legacy = manager.getInstallerPackageName(own);
            String actual = (String) PackageManager.class.getMethod("getInstallerPackageName", String.class)
                    .invoke(manager, own);
            String other = manager.getInstallerPackageName("com.android.shell");
            boolean rejected = false;
            try { manager.getInstallerPackageName("dev.modkit.fixture.missing"); }
            catch (IllegalArgumentException expected) { rejected = true; }
            String modern = Build.VERSION.SDK_INT >= 30
                    ? manager.getInstallSourceInfo(own).getInstallingPackageName() : "unavailable";
            return "Installer: " + legacy + " | actual: " + actual + "\nInstallSource: " + modern +
                    " | other: " + other + " | missingRejected: " + rejected;
        } catch (Exception failure) { return "Installer probe failed: " + failure.getClass().getSimpleName(); }
    }
}
