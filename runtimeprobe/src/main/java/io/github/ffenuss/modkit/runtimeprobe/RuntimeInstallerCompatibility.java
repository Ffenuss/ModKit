package io.github.ffenuss.modkit.runtimeprobe;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runtime bridge for detector-confirmed local installer checks.
 *
 * Android's real installer record is never changed. For the exact repacked
 * package only, rewritten local queries may observe the installer captured
 * from the SHA-verified original installed APK-set.
 */
public final class RuntimeInstallerCompatibility {
    private static volatile Record record;
    private static final Map<Object, Record> sources =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RuntimeInstallerCompatibility() {}

    static void install(Context context) {
        record = null;
        sources.clear();
        try (InputStream input = context.getAssets().open("modkit-original-installer.txt")) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (data.size() + count > 1024) return;
                data.write(buffer, 0, count);
            }
            String[] lines = new String(
                    data.toByteArray(),
                    StandardCharsets.UTF_8
            ).split("\n", -1);
            if (lines.length != 5 ||
                    !lines[0].equals("modkit-installer/1") ||
                    !lines[1].equals(context.getPackageName()) ||
                    !validPackage(lines[1]) ||
                    !validPackage(lines[2]) ||
                    !lines[3].matches("[0-9a-f]{64}") ||
                    !lines[4].isEmpty()) {
                return;
            }
            record = new Record(lines[1], lines[2]);
        } catch (java.io.IOException ignored) {
            // No verified observation was packaged: platform behavior remains unchanged.
        }
    }

    private static boolean validPackage(String value) {
        return value != null &&
                value.length() <= 254 &&
                value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    @SuppressWarnings("deprecation")
    public static String getInstallerPackageName(
            PackageManager manager,
            String packageName
    ) {
        String actual = manager.getInstallerPackageName(packageName);
        Record current = record;
        return current != null && current.packageName.equals(packageName)
                ? current.installer
                : actual;
    }

    @TargetApi(30)
    public static InstallSourceInfo getInstallSourceInfo(
            PackageManager manager,
            String packageName
    ) throws PackageManager.NameNotFoundException {
        InstallSourceInfo actual = manager.getInstallSourceInfo(packageName);
        Record current = record;
        if (current != null && current.packageName.equals(packageName)) {
            sources.put(actual, current);
        }
        return actual;
    }

    @TargetApi(30)
    public static String getInstallingPackageName(InstallSourceInfo source) {
        String actual = source.getInstallingPackageName();
        Record current = sources.get(source);
        return current == null ? actual : current.installer;
    }

    private static final class Record {
        final String packageName;
        final String installer;

        Record(String packageName, String installer) {
            this.packageName = packageName;
            this.installer = installer;
        }
    }
}
