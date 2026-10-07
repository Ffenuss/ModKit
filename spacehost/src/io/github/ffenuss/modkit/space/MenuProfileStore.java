package io.github.ffenuss.modkit.space;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;

final class MenuProfileStore {
    private MenuProfileStore() {}
    static MenuProfile importProfile(Context context, Uri uri) throws Exception {
        String authority = uri == null ? null : uri.getAuthority();
        if (uri == null || !"content".equals(uri.getScheme()) ||
            !("io.github.ffenuss.modkit.files".equals(authority) || "io.github.ffenuss.modkit.test.files".equals(authority)) ||
            uri.getPath() == null || !uri.getPath().startsWith("/space_menu/"))
            throw new IllegalArgumentException("Unsupported menu source");
        byte[] bytes;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) { bytes = read(input); }
        return save(context, bytes);
    }
    private static MenuProfile save(Context context, byte[] bytes) throws Exception {
        MenuProfile profile = new MenuProfile(new String(bytes, StandardCharsets.UTF_8));
        File root = new File(context.getFilesDir(), "modkit-menus");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Menu storage unavailable");
        AtomicFile file = new AtomicFile(new File(root, profile.packageName + ".json"));
        FileOutputStream output = file.startWrite();
        try { output.write(bytes); file.finishWrite(output); }
        catch (Exception error) { file.failWrite(output); throw error; }
        return profile;
    }

    /** Pull prepared menus even when the space was installed after the analysis. No APK transfer. */
    static List<MenuProfile> sync(Context context) {
        List<MenuProfile> changed = new ArrayList<>();
        for (String authority : new String[]{"io.github.ffenuss.modkit.test.space-menu", "io.github.ffenuss.modkit.space-menu"}) {
            try {
                byte[] index;
                try (InputStream input = context.getContentResolver().openInputStream(Uri.parse("content://" + authority + "/index"))) { index = read(input); }
                JSONObject json = new JSONObject(new String(index, StandardCharsets.UTF_8));
                if (json.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported menu index");
                JSONArray entries = json.getJSONArray("profiles");
                if (entries.length() > 256) throw new IllegalArgumentException("Menu index exceeds limit");
                android.content.SharedPreferences cache = context.getSharedPreferences("modkit_menu_sync", Context.MODE_PRIVATE);
                for (int i = 0; i < entries.length(); i++) {
                    try {
                        JSONObject entry = entries.getJSONObject(i);
                        String name = entry.getString("file"), hash = entry.getString("sha256");
                        if (name.length() > 330 || !name.matches("[A-Za-z0-9._-]+\\.json") || !hash.matches("[a-f0-9]{64}"))
                            throw new IllegalArgumentException("Invalid menu index entry");
                        String key = authority + "/" + name;
                        String pkg = cache.getString(key + "/package", "");
                        if (hash.equals(cache.getString(key + "/hash", "")) && SpacePolicy.validSession(pkg, 0)) {
                            File local = new File(new File(context.getFilesDir(), "modkit-menus"), pkg + ".json");
                            if (local.isFile()) try (InputStream input = new AtomicFile(local).openRead()) {
                                if (hash.equals(sha256(read(input)))) continue;
                            }
                        }
                        byte[] bytes;
                        Uri uri = new Uri.Builder().scheme("content").authority(authority).appendPath("profile").appendPath(name).build();
                        try (InputStream input = context.getContentResolver().openInputStream(uri)) { bytes = read(input); }
                        if (!hash.equals(sha256(bytes))) throw new IllegalArgumentException("Menu changed during sync");
                        MenuProfile profile = save(context, bytes);
                        cache.edit().putString(key + "/hash", hash).putString(key + "/package", profile.packageName).apply();
                        changed.add(profile);
                    } catch (Exception error) { android.util.Log.w("ModKitSpace", "Prepared menu rejected", error); }
                }
                return changed; // Prefer the test installation when its authenticated endpoint is present.
            } catch (Exception ignored) { /* ModKit may not be installed or updated yet; keep saved menus. */ }
        }
        return changed;
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
    static MenuProfile load(Context context, String pkg) throws Exception {
        if (!SpacePolicy.validSession(pkg, 0)) throw new IllegalArgumentException("Invalid menu key");
        File path = new File(new File(context.getFilesDir(), "modkit-menus"), pkg + ".json");
        if (!path.isFile()) return null;
        AtomicFile file = new AtomicFile(path);
        try (InputStream input = file.openRead()) {
            MenuProfile profile = new MenuProfile(new String(read(input), StandardCharsets.UTF_8));
            if (!pkg.equals(profile.packageName)) throw new IllegalArgumentException("Menu identity mismatch");
            return profile;
        }
    }
    private static byte[] read(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Menu content absent");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) >= 0) {
            if (bytes.size() + count > MenuProfile.MAX_BYTES) throw new IllegalArgumentException("Menu profile exceeds limit");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
}
