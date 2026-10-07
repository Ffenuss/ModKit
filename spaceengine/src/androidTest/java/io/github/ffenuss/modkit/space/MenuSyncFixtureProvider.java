package io.github.ffenuss.modkit.space;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.FileNotFoundException;

/** Owned fixture runs in the separate test APK, without the target's Kotlin runtime. */
public final class MenuSyncFixtureProvider extends ContentProvider {
    private final Map<String, String> profiles = new LinkedHashMap<>();
    private boolean corrupt;
    @Override public boolean onCreate() { return true; }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (!"fixture".equals(method) || extras == null) throw new IllegalArgumentException();
        profiles.clear();
        for (String key : extras.keySet()) if (key.endsWith(".json")) profiles.put(key, extras.getString(key));
        corrupt = extras.getBoolean("corrupt"); return new Bundle();
    }
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        try {
            String text;
            if ("/index".equals(uri.getPath())) {
                JSONArray entries = new JSONArray();
                for (Map.Entry<String, String> entry : profiles.entrySet()) {
                    StringBuilder hash = new StringBuilder();
                    for (byte b : MessageDigest.getInstance("SHA-256").digest(entry.getValue().getBytes(StandardCharsets.UTF_8)))
                        hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                    entries.put(new JSONObject().put("file", entry.getKey()).put("sha256",
                        corrupt ? "0000000000000000000000000000000000000000000000000000000000000000" : hash.toString()));
                }
                text = new JSONObject().put("schema", 1).put("profiles", entries).toString();
            } else text = profiles.get(uri.getLastPathSegment());
            if (text == null) throw new FileNotFoundException();
            return openPipeHelper(uri, "application/json", null, text.getBytes(StandardCharsets.UTF_8), (output, u, mime, options, bytes) -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) { stream.write(bytes); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
        } catch (Exception error) { throw new FileNotFoundException(error.toString()); }
    }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new IllegalStateException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
}
