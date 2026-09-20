package io.github.ffenuss.modkit.runtimeprobe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public final class RuntimeEvidenceProvider extends ContentProvider {
    public static final String CALLER_PACKAGE = "io.github.ffenuss.modkit";
    public static final String PATH_EVIDENCE = "evidence";
    public static final String PATH_NATIVE_TRACE = "native-trace";
    public static final String PATH_NATIVE_JNI_TRACE = "native-jni-trace";
    private static final int MAX_MAPS_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CMDLINE_BYTES = 4096;
    private static final Object TRACE_LOCK = new Object();
    private static final String TRACE_MODE_DLSYM = "DLSYM";
    private static final String TRACE_MODE_JNI = "JNI";
    private static String traceMode = "";
    private static JniProducerSnapshot stoppedJniProducer;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context != null) {
            RuntimeModMenu.install(context);
        }
        return true;
    }

    @Override
    public String getType(Uri uri) {
        if (("/" + PATH_NATIVE_TRACE).equals(uri.getPath())) {
            return "application/vnd.io.github.ffenuss.modkit.native-trace-v1";
        }
        if (("/" + PATH_NATIVE_JNI_TRACE).equals(uri.getPath())) {
            return "application/vnd.io.github.ffenuss.modkit.native-jni-trace-v1";
        }
        return "application/vnd.io.github.ffenuss.modkit.runtime-evidence-v1";
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        enforceCaller();
        MatrixCursor cursor = new MatrixCursor(
                new String[]{"schemaVersion", "packageName", "pid"}
        );
        Context context = probeContext();
        cursor.addRow(new Object[]{1, context.getPackageName(), Process.myPid()});
        return cursor;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();

        if ("configureTestMenu".equals(method)) {
            int itemCount =
                    RuntimeModMenu.configure(
                            probeContext(),
                            extras
                    );
            Bundle result = baseReply();
            result.putInt("itemCount", itemCount);
            result.putInt(
                    "patchItemCount",
                    RuntimeModMenu.patchItemCount()
            );
            result.putInt(
                    "infoItemCount",
                    RuntimeModMenu.infoItemCount()
            );
            return result;
        }
        if ("clearTestMenu".equals(method)) {
            RuntimeModMenu.clear(probeContext());
            Bundle result = baseReply();
            result.putInt("itemCount", 0);
            result.putInt("patchItemCount", 0);
            result.putInt("infoItemCount", 0);
            return result;
        }
        if ("testMenuStatus".equals(method)) {
            Bundle result = baseReply();
            result.putInt(
                    "itemCount",
                    RuntimeModMenu.itemCount()
            );
            result.putInt(
                    "patchItemCount",
                    RuntimeModMenu.patchItemCount()
            );
            result.putInt(
                    "infoItemCount",
                    RuntimeModMenu.infoItemCount()
            );
            return result;
        }

        if ("resolveLoadedSymbol".equals(method)) {
            if (arg == null || extras == null) {
                throw new IllegalArgumentException(
                        "Runtime native lookup requires module and symbol."
                );
            }
            String symbol = extras.getString("symbol");
            if (symbol == null) {
                throw new IllegalArgumentException(
                        "Runtime native lookup symbol is missing."
                );
            }

            long address = RuntimeNativeBridge.resolveLoadedSymbol(arg, symbol);
            Bundle result = baseReply();
            result.putString("moduleName", arg);
            result.putString("symbolName", symbol);
            result.putLong("resolvedRuntimeAddress", address);
            return result;
        }

        if ("nativeTraceStart".equals(method)) {
            synchronized (TRACE_LOCK) {
                RuntimeNativeTraceBuffer.Snapshot before =
                        RuntimeNativeTraceBuffer.snapshot();
                if (before.active) {
                    throw new IllegalStateException(
                            "Another native trace session is already active."
                    );
                }
                traceMode = TRACE_MODE_DLSYM;
                stoppedJniProducer = null;
                RuntimeNativeTraceBuffer.start();
                boolean producerReady =
                        RuntimeNativeBridge.startPassiveDlsymTrace();
                if (!producerReady) {
                    RuntimeNativeTraceBuffer.stop();
                }
                return traceStatusBundle(
                        RuntimeNativeTraceBuffer.snapshot(),
                        producerReady
                );
            }
        }
        if ("nativeTraceStop".equals(method)) {
            synchronized (TRACE_LOCK) {
                if (!TRACE_MODE_DLSYM.equals(traceMode)) {
                    throw new IllegalStateException(
                            "The active trace session is not a passive dlsym session."
                    );
                }
                boolean restored =
                        RuntimeNativeBridge.stopPassiveDlsymTrace();
                RuntimeNativeTraceBuffer.Snapshot snapshot =
                        RuntimeNativeTraceBuffer.stop();
                return traceStatusBundle(
                        snapshot,
                        restored
                );
            }
        }
        if ("nativeTraceStatus".equals(method)) {
            synchronized (TRACE_LOCK) {
                if (!TRACE_MODE_DLSYM.equals(traceMode)) {
                    throw new IllegalStateException(
                            "No passive dlsym trace session is selected."
                    );
                }
                return traceStatusBundle(
                        RuntimeNativeTraceBuffer.snapshot(),
                        RuntimeNativeBridge.ensureLoaded()
                );
            }
        }
        if ("nativeJniTraceStart".equals(method)) {
            synchronized (TRACE_LOCK) {
                RuntimeNativeTraceBuffer.Snapshot before =
                        RuntimeNativeTraceBuffer.snapshot();
                if (before.active) {
                    throw new IllegalStateException(
                            "Another native trace session is already active."
                    );
                }
                traceMode = TRACE_MODE_JNI;
                stoppedJniProducer = null;
                RuntimeNativeTraceBuffer.start();
                boolean producerReady =
                        RuntimeNativeBridge.startPassiveJniTrace();
                if (!producerReady) {
                    RuntimeNativeTraceBuffer.stop();
                }
                return jniTraceStatusBundle(
                        RuntimeNativeTraceBuffer.snapshot(),
                        producerReady,
                        JniProducerSnapshot.current()
                );
            }
        }
        if ("nativeJniTraceStop".equals(method)) {
            synchronized (TRACE_LOCK) {
                if (!TRACE_MODE_JNI.equals(traceMode)) {
                    throw new IllegalStateException(
                            "The active trace session is not a passive JNI session."
                    );
                }
                JniProducerSnapshot beforeStop =
                        JniProducerSnapshot.current();
                boolean restored =
                        RuntimeNativeBridge.stopPassiveJniTrace();
                RuntimeNativeTraceBuffer.Snapshot snapshot =
                        RuntimeNativeTraceBuffer.stop();
                stoppedJniProducer =
                        JniProducerSnapshot.afterStop(
                                restored,
                                beforeStop
                        );
                return jniTraceStatusBundle(
                        snapshot,
                        restored,
                        stoppedJniProducer
                );
            }
        }
        if ("nativeJniTraceStatus".equals(method)) {
            synchronized (TRACE_LOCK) {
                if (!TRACE_MODE_JNI.equals(traceMode)) {
                    throw new IllegalStateException(
                            "No passive JNI registration trace session is selected."
                    );
                }
                boolean ready = RuntimeNativeBridge.ensureLoaded() &&
                        !RuntimeNativeBridge.passiveJniRestoreFailed();
                RuntimeNativeTraceBuffer.Snapshot snapshot =
                        RuntimeNativeTraceBuffer.snapshot();
                JniProducerSnapshot producer =
                        !snapshot.active &&
                                stoppedJniProducer != null
                            ? stoppedJniProducer
                            : JniProducerSnapshot.current();
                return jniTraceStatusBundle(
                        snapshot,
                        ready,
                        producer
                );
            }
        }

        throw new IllegalArgumentException("Unsupported runtime probe call.");
    }

    private Bundle baseReply() {
        Bundle result = new Bundle();
        result.putInt("schemaVersion", 1);
        result.putString("packageName", probeContext().getPackageName());
        result.putInt("pid", Process.myPid());
        return result;
    }

    private Bundle traceStatusBundle(
            RuntimeNativeTraceBuffer.Snapshot snapshot,
            boolean producerReady
    ) {
        Bundle result = baseReply();
        result.putString("sessionId", snapshot.sessionId);
        result.putBoolean("active", snapshot.active);
        result.putBoolean("truncated", snapshot.truncated);
        result.putInt("eventCount", snapshot.eventCount);
        result.putLong(
                "startedAtEpochMs",
                snapshot.startedAtEpochMs
        );
        result.putLong(
                "stoppedAtEpochMs",
                snapshot.stoppedAtEpochMs
        );
        result.putInt("traceBytes", snapshot.bytes.length);
        result.putString("producerKind", "PLT_DLSYM_GOT");
        result.putBoolean("producerReady", producerReady);
        result.putBoolean(
                "producerActive",
                RuntimeNativeBridge.passiveDlsymTraceActive()
        );
        result.putInt(
                "hookedSlotCount",
                RuntimeNativeBridge.passiveDlsymHookedSlotCount()
        );
        result.putBoolean(
                "producerIncomplete",
                RuntimeNativeBridge.passiveDlsymIncomplete()
        );
        result.putBoolean(
                "producerRestoreFailed",
                RuntimeNativeBridge.passiveDlsymRestoreFailed()
        );
        return result;
    }

    private Bundle jniTraceStatusBundle(
            RuntimeNativeTraceBuffer.Snapshot snapshot,
            boolean producerReady,
            JniProducerSnapshot producer
    ) {
        Bundle result = baseReply();
        result.putString("sessionId", snapshot.sessionId);
        result.putBoolean("active", snapshot.active);
        result.putBoolean("truncated", snapshot.truncated);
        result.putInt("eventCount", snapshot.eventCount);
        result.putLong("startedAtEpochMs", snapshot.startedAtEpochMs);
        result.putLong("stoppedAtEpochMs", snapshot.stoppedAtEpochMs);
        result.putInt("traceBytes", snapshot.bytes.length);
        result.putString("producerKind", "ART_JNI_ONLOAD_WRAPPER_JNI_TABLE");
        result.putBoolean("producerReady", producerReady);
        result.putBoolean(
                "producerActive",
                RuntimeNativeBridge.passiveJniTraceActive()
        );
        result.putInt(
                "jniOnLoadLookupHookedSlotCount",
                producer.jniOnLoadLookupHookedSlotCount
        );
        result.putBoolean(
                "jniOnLoadInvocationReady",
                producer.jniOnLoadInvocationReady
        );
        result.putBoolean(
                "registerNativesHooked",
                producer.registerNativesHooked
        );
        result.putBoolean(
                "producerIncomplete",
                producer.incomplete
        );
        result.putBoolean(
                "producerRestoreFailed",
                producer.restoreFailed
        );
        return result;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        enforceCaller();
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Runtime probe is read-only.");
        }

        String path = uri.getPath();
        if (("/" + PATH_EVIDENCE).equals(path)) {
            return openPipe("ModKitRuntimeProbe", this::writeEvidence);
        }
        if (("/" + PATH_NATIVE_TRACE).equals(path)) {
            synchronized (TRACE_LOCK) {
                if (!TRACE_MODE_DLSYM.equals(traceMode)) {
                    throw new FileNotFoundException(
                            "No passive dlsym trace session is available."
                    );
                }
            }
            RuntimeNativeTraceBuffer.Snapshot snapshot =
                    RuntimeNativeTraceBuffer.snapshot();
            if (snapshot.sessionId.isEmpty()) {
                throw new FileNotFoundException(
                        "No native trace session has been created."
                );
            }
            if (snapshot.active) {
                throw new FileNotFoundException(
                        "Native trace session must be stopped before export."
                );
            }
            return openPipe(
                    "ModKitNativeTrace",
                    descriptor -> writeNativeTrace(descriptor, snapshot)
            );
        }
        if (("/" + PATH_NATIVE_JNI_TRACE).equals(path)) {
            RuntimeNativeTraceBuffer.Snapshot snapshot;
            JniProducerSnapshot producer;
            synchronized (TRACE_LOCK) {
                if (!TRACE_MODE_JNI.equals(traceMode)) {
                    throw new FileNotFoundException(
                            "No passive JNI trace session is available."
                    );
                }
                snapshot = RuntimeNativeTraceBuffer.snapshot();
                producer = stoppedJniProducer;
            }
            if (snapshot.sessionId.isEmpty()) {
                throw new FileNotFoundException(
                        "No passive JNI trace session has been created."
                );
            }
            if (snapshot.active) {
                throw new FileNotFoundException(
                        "Passive JNI trace session must be stopped before export."
                );
            }
            if (producer == null) {
                throw new FileNotFoundException(
                        "Passive JNI producer provenance is unavailable."
                );
            }
            JniProducerSnapshot exactProducer = producer;
            return openPipe(
                    "ModKitNativeJniTrace",
                    descriptor -> writeNativeJniTrace(
                            descriptor,
                            snapshot,
                            exactProducer
                    )
            );
        }
        throw new FileNotFoundException("Unsupported runtime probe path.");
    }

    private interface PipeWriter {
        void write(ParcelFileDescriptor descriptor);
    }

    private ParcelFileDescriptor openPipe(
            String threadName,
            PipeWriter writer
    ) throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread thread = new Thread(
                    () -> writer.write(pipe[1]),
                    threadName
            );
            thread.setDaemon(true);
            thread.start();
            return pipe[0];
        } catch (Exception failure) {
            FileNotFoundException wrapped =
                    new FileNotFoundException(
                            "Could not create runtime probe pipe."
                    );
            wrapped.initCause(failure);
            throw wrapped;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Runtime probe is read-only.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Runtime probe is read-only.");
    }

    @Override
    public int update(
            Uri uri,
            ContentValues values,
            String selection,
            String[] selectionArgs
    ) {
        throw new UnsupportedOperationException("Runtime probe is read-only.");
    }

    private void enforceCaller() {
        Context context = probeContext();
        int uid = Binder.getCallingUid();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null || !Arrays.asList(packages).contains(CALLER_PACKAGE)) {
            throw new SecurityException("Runtime evidence is available only to ModKit.");
        }
    }

    private Context probeContext() {
        Context context = getContext();
        if (context == null) {
            throw new IllegalStateException("Runtime probe context is unavailable.");
        }
        return context;
    }

    private void writeEvidence(ParcelFileDescriptor descriptor) {
        try (ParcelFileDescriptor.AutoCloseOutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            Context context = probeContext();
            ReadResult mapsResult =
                    readBounded(new File("/proc/self/maps"), MAX_MAPS_BYTES);
            ReadResult cmdlineResult =
                    readBounded(new File("/proc/self/cmdline"), MAX_CMDLINE_BYTES);
            byte[] maps = mapsResult.bytes;
            String processIdentity = decodeCmdline(cmdlineResult.bytes);
            boolean mapsTruncated = mapsResult.truncated;

            String header =
                    "MODKIT_RUNTIME_EVIDENCE_V1\n" +
                    "packageName=" + context.getPackageName() + "\n" +
                    "pid=" + Process.myPid() + "\n" +
                    "processIdentity=" + processIdentity + "\n" +
                    "capturedAtEpochMs=" + System.currentTimeMillis() + "\n" +
                    "mapsSha256=" + sha256(maps) + "\n" +
                    "mapsBytes=" + maps.length + "\n" +
                    "mapsTruncated=" + mapsTruncated + "\n" +
                    "---MAPS---\n";
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.write(maps);
            output.flush();
        } catch (Exception ignored) {
            // Pipe closure is itself the failure signal to the ModKit caller.
        }
    }

    private void writeNativeTrace(
            ParcelFileDescriptor descriptor,
            RuntimeNativeTraceBuffer.Snapshot snapshot
    ) {
        try (ParcelFileDescriptor.AutoCloseOutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            ReadResult cmdlineResult =
                    readBounded(new File("/proc/self/cmdline"), MAX_CMDLINE_BYTES);
            String processIdentity = decodeCmdline(cmdlineResult.bytes);
            byte[] trace = snapshot.bytes;

            String header =
                    "MODKIT_NATIVE_TRACE_V1\n" +
                    "packageName=" + probeContext().getPackageName() + "\n" +
                    "pid=" + Process.myPid() + "\n" +
                    "processIdentity=" + processIdentity + "\n" +
                    "sessionId=" + snapshot.sessionId + "\n" +
                    "startedAtEpochMs=" + snapshot.startedAtEpochMs + "\n" +
                    "stoppedAtEpochMs=" + snapshot.stoppedAtEpochMs + "\n" +
                    "eventCount=" + snapshot.eventCount + "\n" +
                    "traceSha256=" + sha256(trace) + "\n" +
                    "traceBytes=" + trace.length + "\n" +
                    "truncated=" + snapshot.truncated + "\n" +
                    "producerKind=PLT_DLSYM_GOT\n" +
                    "hookedSlotCount=" +
                    RuntimeNativeBridge.passiveDlsymHookedSlotCount() + "\n" +
                    "producerIncomplete=" +
                    RuntimeNativeBridge.passiveDlsymIncomplete() + "\n" +
                    "producerRestoreFailed=" +
                    RuntimeNativeBridge.passiveDlsymRestoreFailed() + "\n" +
                    "---TRACE---\n";
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.write(trace);
            output.flush();
        } catch (Exception ignored) {
            // Pipe closure is itself the failure signal to the ModKit caller.
        }
    }

    private void writeNativeJniTrace(
            ParcelFileDescriptor descriptor,
            RuntimeNativeTraceBuffer.Snapshot snapshot,
            JniProducerSnapshot producer
    ) {
        try (ParcelFileDescriptor.AutoCloseOutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            ReadResult cmdlineResult =
                    readBounded(new File("/proc/self/cmdline"), MAX_CMDLINE_BYTES);
            String processIdentity = decodeCmdline(cmdlineResult.bytes);
            byte[] trace = snapshot.bytes;

            String header =
                    "MODKIT_NATIVE_JNI_TRACE_V1\n" +
                    "packageName=" + probeContext().getPackageName() + "\n" +
                    "pid=" + Process.myPid() + "\n" +
                    "processIdentity=" + processIdentity + "\n" +
                    "sessionId=" + snapshot.sessionId + "\n" +
                    "startedAtEpochMs=" + snapshot.startedAtEpochMs + "\n" +
                    "stoppedAtEpochMs=" + snapshot.stoppedAtEpochMs + "\n" +
                    "eventCount=" + snapshot.eventCount + "\n" +
                    "traceSha256=" + sha256(trace) + "\n" +
                    "traceBytes=" + trace.length + "\n" +
                    "truncated=" + snapshot.truncated + "\n" +
                    "producerKind=ART_JNI_ONLOAD_WRAPPER_JNI_TABLE\n" +
                    "jniOnLoadLookupHookedSlotCount=" +
                    producer.jniOnLoadLookupHookedSlotCount + "\n" +
                    "jniOnLoadInvocationReady=" +
                    producer.jniOnLoadInvocationReady + "\n" +
                    "registerNativesHooked=" +
                    producer.registerNativesHooked + "\n" +
                    "producerIncomplete=" +
                    producer.incomplete + "\n" +
                    "producerRestoreFailed=" +
                    producer.restoreFailed + "\n" +
                    "---TRACE---\n";
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.write(trace);
            output.flush();
        } catch (Exception ignored) {
            // Pipe closure is itself the failure signal to the ModKit caller.
        }
    }

    private static final class JniProducerSnapshot {
        final int jniOnLoadLookupHookedSlotCount;
        final boolean jniOnLoadInvocationReady;
        final boolean registerNativesHooked;
        final boolean incomplete;
        final boolean restoreFailed;

        JniProducerSnapshot(
                int jniOnLoadLookupHookedSlotCount,
                boolean jniOnLoadInvocationReady,
                boolean registerNativesHooked,
                boolean incomplete,
                boolean restoreFailed
        ) {
            this.jniOnLoadLookupHookedSlotCount =
                    jniOnLoadLookupHookedSlotCount;
            this.jniOnLoadInvocationReady =
                    jniOnLoadInvocationReady;
            this.registerNativesHooked = registerNativesHooked;
            this.incomplete = incomplete;
            this.restoreFailed = restoreFailed;
        }

        static JniProducerSnapshot current() {
            return new JniProducerSnapshot(
                    RuntimeNativeBridge.passiveJniOnLoadLookupHookedSlotCount(),
                    RuntimeNativeBridge.passiveJniOnLoadInvocationReady(),
                    RuntimeNativeBridge.passiveRegisterNativesHooked(),
                    RuntimeNativeBridge.passiveJniIncomplete(),
                    RuntimeNativeBridge.passiveJniRestoreFailed()
            );
        }

        static JniProducerSnapshot afterStop(
                boolean restored,
                JniProducerSnapshot beforeStop
        ) {
            return new JniProducerSnapshot(
                    beforeStop.jniOnLoadLookupHookedSlotCount,
                    beforeStop.jniOnLoadInvocationReady,
                    beforeStop.registerNativesHooked,
                    beforeStop.incomplete ||
                            RuntimeNativeBridge.passiveJniIncomplete(),
                    beforeStop.restoreFailed ||
                            RuntimeNativeBridge.passiveJniRestoreFailed() ||
                            !restored
            );
        }
    }

    private static ReadResult readBounded(File file, int limit) throws Exception {
        if (!file.isFile() || !file.canRead()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            boolean truncated = false;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) break;
                int remaining = limit - total;
                if (remaining <= 0) {
                    truncated = true;
                    break;
                }
                int keep = Math.min(read, remaining);
                output.write(buffer, 0, keep);
                total += keep;
                if (keep < read) {
                    truncated = true;
                    break;
                }
            }
            if (!truncated && total == limit && input.read() >= 0) {
                truncated = true;
            }
            return new ReadResult(output.toByteArray(), truncated);
        }
    }

    private static final class ReadResult {
        final byte[] bytes;
        final boolean truncated;

        ReadResult(byte[] bytes, boolean truncated) {
            this.bytes = bytes;
            this.truncated = truncated;
        }
    }

    private static String decodeCmdline(byte[] bytes) {
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, StandardCharsets.UTF_8).trim();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            builder.append(String.format("%02x", value & 0xff));
        }
        return builder.toString();
    }
}
