package io.github.ffenuss.modkit.runtimeprobe;

public final class RuntimeNativeBridge {
    private static final int MAX_MODULE_CHARS = 255;
    private static final int MAX_SYMBOL_CHARS = 1024;

    private static volatile boolean loadAttempted;
    private static volatile boolean loaded;

    private RuntimeNativeBridge() {}

    public static boolean ensureLoaded() {
        if (loadAttempted) return loaded;
        synchronized (RuntimeNativeBridge.class) {
            if (!loadAttempted) {
                try {
                    System.loadLibrary("modkit_runtime_probe");
                    loaded = true;
                } catch (Throwable ignored) {
                    loaded = false;
                } finally {
                    loadAttempted = true;
                }
            }
        }
        return loaded;
    }

    public static boolean startPassiveDlsymTrace() {
        if (!ensureLoaded()) return false;
        return nativeStartPassiveDlsymTrace();
    }

    public static boolean stopPassiveDlsymTrace() {
        if (!ensureLoaded()) return false;
        return nativeStopPassiveDlsymTrace();
    }

    public static boolean passiveDlsymTraceActive() {
        if (!ensureLoaded()) return false;
        return nativePassiveDlsymTraceActive();
    }

    public static int passiveDlsymHookedSlotCount() {
        if (!ensureLoaded()) return 0;
        return nativePassiveDlsymHookedSlotCount();
    }

    public static boolean passiveDlsymIncomplete() {
        if (!ensureLoaded()) return true;
        return nativePassiveDlsymIncomplete();
    }

    public static boolean passiveDlsymRestoreFailed() {
        if (!ensureLoaded()) return true;
        return nativePassiveDlsymRestoreFailed();
    }

    public static boolean startPassiveJniTrace() {
        if (!ensureLoaded()) return false;
        return nativeStartPassiveJniTrace();
    }

    public static boolean stopPassiveJniTrace() {
        if (!ensureLoaded()) return false;
        return nativeStopPassiveJniTrace();
    }

    public static boolean passiveJniTraceActive() {
        if (!ensureLoaded()) return false;
        return nativePassiveJniTraceActive();
    }

    public static int passiveJniOnLoadHookedSlotCount() {
        if (!ensureLoaded()) return 0;
        return nativePassiveJniOnLoadHookedSlotCount();
    }

    public static boolean passiveRegisterNativesHooked() {
        if (!ensureLoaded()) return false;
        return nativePassiveRegisterNativesHooked();
    }

    public static boolean passiveJniIncomplete() {
        if (!ensureLoaded()) return true;
        return nativePassiveJniIncomplete();
    }

    public static boolean passiveJniRestoreFailed() {
        if (!ensureLoaded()) return true;
        return nativePassiveJniRestoreFailed();
    }

    public static long resolveLoadedSymbol(
            String moduleName,
            String symbolName
    ) {
        if (!validModule(moduleName) || !validSymbol(symbolName)) {
            return 0L;
        }
        if (!ensureLoaded()) return 0L;
        return nativeResolveLoadedSymbol(moduleName, symbolName);
    }

    private static boolean validModule(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_MODULE_CHARS) {
            return false;
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            return false;
        }
        return value.endsWith(".so");
    }

    private static boolean validSymbol(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_SYMBOL_CHARS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (Character.isISOControl(ch) || Character.isWhitespace(ch)) {
                return false;
            }
        }
        return true;
    }

    private static native boolean nativeStartPassiveDlsymTrace();

    private static native boolean nativeStopPassiveDlsymTrace();

    private static native boolean nativePassiveDlsymTraceActive();

    private static native int nativePassiveDlsymHookedSlotCount();

    private static native boolean nativePassiveDlsymIncomplete();

    private static native boolean nativePassiveDlsymRestoreFailed();

    private static native boolean nativeStartPassiveJniTrace();

    private static native boolean nativeStopPassiveJniTrace();

    private static native boolean nativePassiveJniTraceActive();

    private static native int nativePassiveJniOnLoadHookedSlotCount();

    private static native boolean nativePassiveRegisterNativesHooked();

    private static native boolean nativePassiveJniIncomplete();

    private static native boolean nativePassiveJniRestoreFailed();

    private static native long nativeResolveLoadedSymbol(
            String moduleName,
            String symbolName
    );
}
