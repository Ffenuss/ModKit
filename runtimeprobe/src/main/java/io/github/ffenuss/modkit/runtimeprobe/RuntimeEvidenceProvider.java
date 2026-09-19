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
    private static final int MAX_MAPS_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CMDLINE_BYTES = 4096;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
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
        if (!"resolveLoadedSymbol".equals(method)) {
            throw new IllegalArgumentException("Unsupported runtime probe call.");
        }
        if (arg == null || extras == null) {
            throw new IllegalArgumentException("Runtime native lookup requires module and symbol.");
        }
        String symbol = extras.getString("symbol");
        if (symbol == null) {
            throw new IllegalArgumentException("Runtime native lookup symbol is missing.");
        }

        long address = RuntimeNativeBridge.resolveLoadedSymbol(arg, symbol);
        Bundle result = new Bundle();
        result.putInt("schemaVersion", 1);
        result.putString("packageName", probeContext().getPackageName());
        result.putInt("pid", Process.myPid());
        result.putString("moduleName", arg);
        result.putString("symbolName", symbol);
        result.putLong("resolvedRuntimeAddress", address);
        return result;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        enforceCaller();
        if (!("/" + PATH_EVIDENCE).equals(uri.getPath())) {
            throw new FileNotFoundException("Unsupported runtime probe path.");
        }
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Runtime probe is read-only.");
        }

        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(
                    () -> writeEvidence(pipe[1]),
                    "ModKitRuntimeProbe"
            );
            writer.setDaemon(true);
            writer.start();
            return pipe[0];
        } catch (Exception failure) {
            FileNotFoundException wrapped =
                    new FileNotFoundException("Could not create runtime evidence pipe.");
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
