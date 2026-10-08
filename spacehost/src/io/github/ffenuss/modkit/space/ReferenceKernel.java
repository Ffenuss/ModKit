package io.github.ffenuss.modkit.space;

import java.lang.reflect.Method;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** ABI verified against SHA256 251acbe2…6319. No guesses based on obfuscated names. */
final class ReferenceKernel {
    private final Object core;
    private final Method installedAsUser;
    private final Object activityManager;
    private final Method launchPackage;
    ReferenceKernel(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.lody.virtual.client.core.VirtualCore", false, loader);
        core = type.getMethod("i").invoke(null);
        installedAsUser = type.getMethod("cp", int.class, String.class);
        Class<?> manager = Class.forName("com.lody.virtual.client.h.i", false, loader);
        activityManager = manager.getMethod("b").invoke(null);
        launchPackage = manager.getMethod("as", int.class, String.class, boolean.class);
        if (core == null || activityManager == null || installedAsUser.getReturnType() != boolean.class || launchPackage.getReturnType() != boolean.class)
            throw new IllegalStateException("Unexpected virtual kernel ABI");
    }
    boolean installed(String pkg, int user) throws ReflectiveOperationException {
        if (!SpacePolicy.validSession(pkg, user)) throw new IllegalArgumentException("Invalid virtual identity");
        return (Boolean) installedAsUser.invoke(core, user, pkg);
    }
    /** Resolve through virtual package metadata; never through the host PackageManager. */
    List<File> sources(String pkg, int user) throws ReflectiveOperationException {
        if (!installed(pkg, user)) throw new IllegalStateException("App absent for virtual user");
        Object record = core.getClass().getMethod("ck", String.class, int.class).invoke(core, pkg, 0);
        if (record == null || !"com.lody.virtual.remote.InstalledAppInfo".equals(record.getClass().getName()))
            throw new IllegalStateException("Unexpected installed-app record");
        Object value = record.getClass().getMethod("f", int.class).invoke(record, user);
        if (!(value instanceof ApplicationInfo)) throw new IllegalStateException("Application info unavailable");
        ApplicationInfo info = (ApplicationInfo) value;
        if (!pkg.equals(info.packageName) || info.sourceDir == null)
            throw new IllegalStateException("Virtual package identity mismatch");
        List<File> files = new ArrayList<>();
        files.add(new File(info.sourceDir));
        if (info.splitSourceDirs != null) for (String split : info.splitSourceDirs) {
            if (split == null) throw new IllegalStateException("Missing split path");
            files.add(new File(split));
        }
        return files;
    }
    boolean launch(String pkg, int user) throws ReflectiveOperationException {
        if (!installed(pkg, user)) return false;
        return (Boolean) launchPackage.invoke(activityManager, user, pkg, true);
    }
}
