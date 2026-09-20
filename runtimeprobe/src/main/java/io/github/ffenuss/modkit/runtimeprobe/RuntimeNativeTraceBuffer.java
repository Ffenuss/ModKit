package io.github.ffenuss.modkit.runtimeprobe;

import android.os.Process;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class RuntimeNativeTraceBuffer {
    public static final int MAX_TRACE_BYTES = 4 * 1024 * 1024;
    public static final int MAX_EVENTS = 100_000;
    private static final int MAX_FIELD_CHARS = 4096;

    private static final Object LOCK = new Object();
    private static ByteArrayOutputStream trace = new ByteArrayOutputStream();
    private static String sessionId = "";
    private static boolean active;
    private static boolean truncated;
    private static int eventCount;
    private static long startedAtEpochMs;
    private static long stoppedAtEpochMs;

    private RuntimeNativeTraceBuffer() {}

    public static String start() {
        synchronized (LOCK) {
            trace = new ByteArrayOutputStream();
            sessionId =
                    Long.toHexString(SystemClock.elapsedRealtimeNanos()) +
                    "-" + Process.myPid();
            active = true;
            truncated = false;
            eventCount = 0;
            startedAtEpochMs = System.currentTimeMillis();
            stoppedAtEpochMs = 0L;
            return sessionId;
        }
    }

    public static Snapshot stop() {
        synchronized (LOCK) {
            active = false;
            if (stoppedAtEpochMs == 0L) {
                stoppedAtEpochMs = System.currentTimeMillis();
            }
            return snapshotLocked();
        }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return snapshotLocked();
        }
    }

    public static boolean appendDlsym(
            String moduleName,
            String symbolName,
            long runtimeAddress
    ) {
        return appendLine(
                "DLSYM",
                moduleName,
                symbolName,
                hex(runtimeAddress)
        );
    }

    public static boolean appendJniOnLoad(
            String moduleName,
            long runtimeAddress
    ) {
        return appendLine(
                "JNI_ON_LOAD",
                moduleName,
                "JNI_OnLoad",
                hex(runtimeAddress)
        );
    }

    public static boolean appendRegisterNative(
            String moduleName,
            String className,
            String methodName,
            String signature,
            long runtimeAddress
    ) {
        return appendLine(
                "JNI_REGISTER_NATIVE",
                moduleName,
                className,
                methodName,
                signature,
                hex(runtimeAddress)
        );
    }

    public static boolean appendRegisterNativeClass(
            Class<?> targetClass,
            String moduleName,
            String methodName,
            String signature,
            long runtimeAddress
    ) {
        if (targetClass == null) return false;
        String className = targetClass.getName().replace('.', '/');
        return appendRegisterNative(
                moduleName,
                className,
                methodName,
                signature,
                runtimeAddress
        );
    }

    private static boolean appendLine(String... fields) {
        synchronized (LOCK) {
            if (!active || truncated) return false;
            if (eventCount >= MAX_EVENTS) {
                truncated = true;
                active = false;
                stoppedAtEpochMs = System.currentTimeMillis();
                return false;
            }
            for (String field : fields) {
                if (!validField(field)) return false;
            }

            String line = String.join("\t", fields) + "\n";
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TRACE_BYTES - trace.size()) {
                truncated = true;
                active = false;
                stoppedAtEpochMs = System.currentTimeMillis();
                return false;
            }
            trace.write(bytes, 0, bytes.length);
            eventCount++;
            return true;
        }
    }

    private static boolean validField(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_FIELD_CHARS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\t' || ch == '\n' || ch == '\r' || Character.isISOControl(ch)) {
                return false;
            }
        }
        return true;
    }

    private static String hex(long value) {
        if (value <= 0L) return "0x0";
        return "0x" + Long.toHexString(value);
    }

    private static Snapshot snapshotLocked() {
        return new Snapshot(
                sessionId,
                active,
                truncated,
                eventCount,
                startedAtEpochMs,
                stoppedAtEpochMs,
                trace.toByteArray()
        );
    }

    public static final class Snapshot {
        public final String sessionId;
        public final boolean active;
        public final boolean truncated;
        public final int eventCount;
        public final long startedAtEpochMs;
        public final long stoppedAtEpochMs;
        public final byte[] bytes;

        Snapshot(
                String sessionId,
                boolean active,
                boolean truncated,
                int eventCount,
                long startedAtEpochMs,
                long stoppedAtEpochMs,
                byte[] bytes
        ) {
            this.sessionId = sessionId;
            this.active = active;
            this.truncated = truncated;
            this.eventCount = eventCount;
            this.startedAtEpochMs = startedAtEpochMs;
            this.stoppedAtEpochMs = stoppedAtEpochMs;
            this.bytes = bytes;
        }
    }
}
