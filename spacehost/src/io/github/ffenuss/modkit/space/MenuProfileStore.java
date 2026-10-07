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
        MenuProfile profile = new MenuProfile(new String(bytes, StandardCharsets.UTF_8));
        File root = new File(context.getFilesDir(), "modkit-menus");
        if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Menu storage unavailable");
        AtomicFile file = new AtomicFile(new File(root, profile.packageName + ".json"));
        FileOutputStream output = file.startWrite();
        try { output.write(bytes); file.finishWrite(output); }
        catch (Exception error) { file.failWrite(output); throw error; }
        return profile;
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
