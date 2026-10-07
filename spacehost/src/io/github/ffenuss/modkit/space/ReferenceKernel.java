package io.github.ffenuss.modkit.space;

import java.lang.reflect.Method;

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
    boolean launch(String pkg, int user) throws ReflectiveOperationException {
        if (!installed(pkg, user)) return false;
        return (Boolean) launchPackage.invoke(activityManager, user, pkg, true);
    }
}
