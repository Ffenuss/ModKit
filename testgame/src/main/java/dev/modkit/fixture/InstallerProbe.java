package dev.modkit.fixture;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Owned fixture for installer-source compatibility. Direct calls contain real
 * local branches; reflection independently reads Android's unmodified record.
 */
public final class InstallerProbe {
    private InstallerProbe() {}

    @SuppressWarnings("deprecation")
    public static String describe(Context context) {
        PackageManager manager = context.getPackageManager();
        String own = context.getPackageName();
        try {
            String legacy = manager.getInstallerPackageName(own);
            String legacyGate = "com.android.shell".equals(legacy) ? "ALLOWED" : "BLOCKED";

            String actual = (String) PackageManager.class
                    .getMethod("getInstallerPackageName", String.class)
                    .invoke(manager, own);

            String modern = "unavailable";
            String modernGate = "unavailable";
            if (Build.VERSION.SDK_INT >= 30) {
                modern = manager.getInstallSourceInfo(own).getInstallingPackageName();
                modernGate = "com.android.shell".equals(modern) ? "ALLOWED" : "BLOCKED";
            }

            return "Installer direct: " + legacy +
                    " | gate: " + legacyGate +
                    " | actual: " + actual +
                    "\nInstallSource direct: " + modern +
                    " | gate: " + modernGate;
        } catch (Exception failure) {
            return "Installer probe failed: " + failure.getClass().getSimpleName();
        }
    }
}
