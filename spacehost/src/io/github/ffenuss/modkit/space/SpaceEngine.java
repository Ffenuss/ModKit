package io.github.ffenuss.modkit.space;

import android.app.Application;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Private, content-bound engine carrier; only Java/platform types cross the boundary. */
final class SpaceEngine {
    private static final String ASSET = "modkit-space-engine.apk";
    private static Method analyze;
    private SpaceEngine() {}
    private static synchronized Method load(Application app, SourceInventory.Cancellation token) throws Exception {
        if (analyze != null) return analyze;
        String expected;
        try (InputStream input = app.getAssets().open("modkit-space-engine.sha256")) {
            byte[] bytes = new byte[64]; int read = 0;
            while (read < bytes.length) {
                token.check(); int count = input.read(bytes, read, bytes.length - read);
                if (count <= 0) throw new IllegalStateException("Truncated engine identity");
                read += count;
            }
            if (input.read() != -1) throw new IllegalStateException("Unexpected engine identity suffix");
            expected = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
        }
        if (!expected.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid engine identity");
        File root = new File(app.getCodeCacheDir(), "modkit-engine");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Engine cache unavailable");
        File engine = new File(root, expected + ".apk");
        if (engine.exists() && (!expected.equals(SourceInventory.hash(engine, token)) || engine.canWrite())) {
            if (!engine.delete()) throw new IllegalStateException("Cannot replace invalid engine cache");
        }
        if (!engine.exists()) {
            File staged = File.createTempFile("engine-", ".apk", root);
            try {
                // Android 14+: make the file read-only before writing through the already opened descriptor.
                try (FileOutputStream output = new FileOutputStream(staged); InputStream input = app.getAssets().open(ASSET)) {
                    if (!staged.setReadOnly()) throw new IllegalStateException("Cannot protect engine DEX");
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) >= 0) { token.check(); output.write(buffer, 0, count); }
                    output.getFD().sync();
                }
                if (!expected.equals(SourceInventory.hash(staged, token))) throw new IllegalStateException("Engine digest mismatch");
                if (!staged.renameTo(engine)) throw new IllegalStateException("Cannot commit engine DEX");
            } finally { if (staged.exists()) staged.delete(); }
        }
        token.check();
        ClassLoader loader = new EngineLoader(engine.getPath(), root.getPath(), app.getClassLoader());
        Class<?> bridge = Class.forName("io.github.ffenuss.modkit.spaceengine.SpaceAnalysisBridge", true, loader);
        analyze = bridge.getMethod("analyze", File[].class, File.class, BooleanSupplier.class, Consumer.class);
        return analyze;
    }
    static String run(Application app, SourceInventory.Result sources, SourceInventory.Cancellation token, Consumer<String> progress) throws Exception {
        token.check();
        File[] files = new File[sources.sources.size()];
        for (int i = 0; i < files.length; i++) files[i] = sources.sources.get(i).file;
        File scratch = new File(app.getCacheDir(), "modkit-analysis/" + sources.sessionId);
        if (!scratch.mkdirs()) throw new IllegalStateException("Analysis workspace unavailable");
        try {
            Object result = load(app, token).invoke(null, files, scratch, (BooleanSupplier) () -> {
                try { token.check(); return false; } catch (java.io.IOException cancelled) { return true; }
            }, progress);
            if (!(result instanceof String)) throw new IllegalStateException("Engine ABI returned no report");
            token.check(); return (String) result;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw error;
        } finally { deleteTree(scratch); }
    }
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
    private static final class EngineLoader extends DexClassLoader {
        EngineLoader(String dex, String output, ClassLoader parent) { super(dex, output, null, parent); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!privateNamespace(name)) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name);
            // Do not silently mix an incomplete carrier with the host's older Kotlin classes.
            if (type == null) type = findClass(name);
            if (resolve) resolveClass(type);
            return type;
        }
    }
    static boolean privateNamespace(String name) {
        return name.startsWith("kotlin.") || name.startsWith("io.github.ffenuss.modkit.analysis.")
            || name.startsWith("io.github.ffenuss.modkit.domain.") || name.startsWith("io.github.ffenuss.modkit.spaceengine.");
    }
}
