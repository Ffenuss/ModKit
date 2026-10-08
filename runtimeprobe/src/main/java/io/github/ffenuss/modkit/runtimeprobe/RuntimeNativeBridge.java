package io.github.ffenuss.modkit.runtimeprobe;

public final class RuntimeNativeBridge {
    private static final int MAX_MODULE_CHARS = 255;
    private static final int MAX_SYMBOL_CHARS = 1024;

    private static volatile boolean loadAttempted;
    private static volatile boolean loaded;

    private RuntimeNativeBridge() {}

    /** Load a verified private payload staged by the signed-space bridge. */
    public static synchronized boolean loadVerifiedFile(java.io.File file) {
        if (loaded) return true;
        try {
            if (file == null || !file.isFile() || file.canWrite()) return false;
            System.load(file.getCanonicalPath());
            loaded = true; loadAttempted = true;
            return true;
        } catch (Exception | LinkageError error) { return false; }
    }

    public static String loadedModulePath(String module) {
        return validModule(module) && ensureLoaded() ? nativeLoadedModulePath(module) : null;
    }

    public static boolean codeMatches(String module, long address, byte[] expected) {
        return validModule(module) && address > 0 && expected != null && expected.length > 0 &&
            expected.length <= 64 && expected.length % 4 == 0 && ensureLoaded() &&
            nativeCodeMatches(module, address, expected);
    }

    /** 1: applied and protections restored; 0: rejected; -1: write/protection state uncertain. */
    public static int patchCodeStatus(String module, long address, byte[] expected, byte[] replacement) {
        if (!validModule(module) || address <= 0 || expected == null || replacement == null ||
            expected.length == 0 || expected.length != replacement.length || expected.length > 64 ||
            expected.length % 4 != 0 || !ensureLoaded()) return 0;
        return nativePatchCodeStatus(module, address, expected, replacement);
    }

    private static native String nativeLoadedModulePath(String module);
    private static native boolean nativeCodeMatches(String module, long address, byte[] expected);
    private static native int nativePatchCodeStatus(String module, long address, byte[] expected, byte[] replacement);

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

    public static int passiveJniOnLoadLookupHookedSlotCount() {
        if (!ensureLoaded()) return 0;
        return nativePassiveJniOnLoadHookedSlotCount();
    }

    public static boolean passiveRegisterNativesHooked() {
        if (!ensureLoaded()) return false;
        return nativePassiveRegisterNativesHooked();
    }

    public static boolean passiveJniOnLoadInvocationReady() {
        if (!ensureLoaded()) return false;
        return nativePassiveJniOnLoadInvocationReady();
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

    public static boolean patchCode(
            String moduleName,
            long binaryVirtualAddress,
            byte[] expectedBytes,
            byte[] replacementBytes
    ) {
        if (!validModule(moduleName) ||
                binaryVirtualAddress <= 0L ||
                expectedBytes == null ||
                replacementBytes == null ||
                expectedBytes.length == 0 ||
                expectedBytes.length != replacementBytes.length ||
                expectedBytes.length > 64 ||
                expectedBytes.length % 4 != 0) {
            return false;
        }
        if (!ensureLoaded()) return false;
        return nativePatchCode(
                moduleName,
                binaryVirtualAddress,
                expectedBytes,
                replacementBytes
        );
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

    private static native boolean nativePassiveJniOnLoadInvocationReady();

    private static native boolean nativePassiveJniIncomplete();

    private static native boolean nativePassiveJniRestoreFailed();

    private static native long nativeResolveLoadedSymbol(
            String moduleName,
            String symbolName
    );

    private static native boolean nativePatchCode(
            String moduleName,
            long binaryVirtualAddress,
            byte[] expectedBytes,
            byte[] replacementBytes
    );
}
