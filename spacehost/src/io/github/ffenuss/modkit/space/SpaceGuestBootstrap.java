package io.github.ffenuss.modkit.space;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

/** Host-owned bootstrap in the virtual client process; no guest APK injection. */
final class SpaceGuestBootstrap {
    private static boolean installed;
    private static volatile Session session;
    private SpaceGuestBootstrap() {}

    static synchronized void install(Application host) {
        if (installed || host == null) return;
        try {
            ClassLoader loader = host.getClassLoader();
            Class<?> coreType = Class.forName("com.lody.virtual.client.core.VirtualCore", false, loader);
            Object core = coreType.getMethod("i").invoke(null);
            if (!Boolean.TRUE.equals(coreType.getMethod("ad").invoke(core))) return;
            Class<?> contract = Class.forName("com.lody.virtual.client.core.k", false, loader);
            if (!contract.isInterface() || contract.getDeclaredMethods().length != 3)
                throw new IllegalStateException("Guest callback ABI changed");
            for (String name : new String[]{"b", "d"})
                if (contract.getMethod(name, Application.class).getReturnType() != void.class)
                    throw new IllegalStateException("Application callback ABI changed");
            if (contract.getMethod("c", Intent.class).getReturnType() != void.class)
                throw new IllegalStateException("Intent callback ABI changed");
            Object original = coreType.getMethod("bo").invoke(core);
            Object chained = GuestCallbackChain.wrap(contract, original, (method, args) -> {
                if (("b".equals(method.getName()) || "d".equals(method.getName())) && args != null && args.length == 1) {
                    try { observe(loader, (Application) args[0], "b".equals(method.getName())); }
                    catch (Exception error) { Log.w("ModKitGuest", "Guest session rejected", error); }
                }
            });
            coreType.getMethod("ax", contract).invoke(core, chained);
            installed = true;
            Log.i("ModKitGuest", "Guest lifecycle bridge installed; runtime executor unavailable");
        } catch (Exception | LinkageError error) {
            Log.w("ModKitGuest", "Guest lifecycle bridge unavailable", error);
        }
    }

    private static void observe(ClassLoader hostLoader, Application guest, boolean created) throws Exception {
        Class<?> clientType = Class.forName("com.lody.virtual.client.b", false, hostLoader);
        Object client = clientType.getMethod("get").invoke(null);
        String pkg = (String) clientType.getMethod("getCurrentPackage").invoke(client);
        int virtualUid = (Integer) clientType.getMethod("getVUid").invoke(client);
        if (virtualUid < 0) throw new IllegalStateException("Virtual UID absent");
        Class<?> users = Class.forName("com.lody.virtual.os.VUserHandle", false, hostLoader);
        int user = (Integer) users.getMethod("s", int.class).invoke(null, virtualUid);
        if (!SpacePolicy.validSession(pkg, user) || guest == null || !pkg.equals(guest.getPackageName()) ||
            clientType.getMethod("getCurrentApplication").invoke(client) != guest)
            throw new IllegalStateException("Guest identity mismatch");
        // Google dependencies keep their original lifecycle and are never mod targets here.
        if ("com.android.vending".equals(pkg) || pkg.startsWith("com.google.android.")) return;
        session = new Session(pkg, user, guest, guest.getClassLoader(), created);
        Log.i("ModKitGuest", "Guest lifecycle package=" + pkg + " user=" + user + " created=" + created + " backend=none");
    }

    static Session current() { return session; }
    static final class Session {
        final String packageName;
        final int user;
        final Application application;
        final ClassLoader classLoader;
        final boolean applicationCreated;
        Session(String pkg, int user, Application app, ClassLoader loader, boolean created) {
            packageName = pkg; this.user = user; application = app; classLoader = loader; applicationCreated = created;
        }
    }
}
