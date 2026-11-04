package io.github.ffenuss.modkit.spacecompanion;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pull-only authenticated schema-1 handoff. Neither imported data nor its
 * labels contain instructions, execution endpoints or native patch permissions.
 * All APK hashing must run off the main/UI thread.
 */
public final class SpaceProfileReader {
    private static final int MAX_JSON = 256 * 1024;
    private static final String[] MODKIT_PACKAGES = {
            "io.github.ffenuss.modkit.test", "io.github.ffenuss.modkit"
    };
    private static final String APK_PACKAGE =
            "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+";
    private static final String HASH = "[a-f0-9]{64}";

    public static final class Result {
        public final boolean ready;
        public final String packageName;
        public final String artifactSha256;
        public final String message;

        private Result(boolean ready, String pkg, String artifact, String text) {
            this.ready = ready;
            this.packageName = pkg;
            this.artifactSha256 = artifact;
            this.message = text;
        }

        static Result unavailable(String message) {
            return new Result(false, "", "", message);
        }

        static Result verified(String pkg, String artifact) {
            return new Result(true, pkg, artifact, "Профиль подтверждён: " + pkg);
        }

        boolean sameIdentity(Result other) {
            return other != null && ready && other.ready &&
                    packageName.equals(other.packageName) &&
                    artifactSha256.equals(other.artifactSha256);
        }
    }

    private SpaceProfileReader() {}

    public static Result load(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String modkit : MODKIT_PACKAGES) {
            if (pm.checkSignatures(context.getPackageName(), modkit) !=
                    PackageManager.SIGNATURE_MATCH) continue;
            try {
                return readVerified(context, modkit);
            } catch (Exception invalid) {
                // Never fall back to an untrusted authority or cached profile.
                return Result.unavailable("Профиль не подтверждён: " +
                        invalid.getClass().getSimpleName());
            }
        }
        return Result.unavailable("Подписанный тем же ключом ModKit не установлен.");
    }

    private static Result readVerified(Context context, String publisher) throws Exception {
        String authority = publisher + ".space-menu";
        JSONObject index = new JSONObject(new String(readUri(context,
                new Uri.Builder().scheme("content").authority(authority)
                        .appendPath("index").build()), StandardCharsets.UTF_8));
        if (index.getInt("schema") != 1) throw new IOException("Unexpected index schema");
        JSONArray entries = index.getJSONArray("profiles");
        if (entries.length() > 1) throw new IOException("Only one active profile is supported");
        if (entries.length() == 0) return Result.unavailable("Нет активного профиля в ModKit.");
        JSONObject entry = entries.getJSONObject(0);
        String fileName = entry.getString("file");
        String expectedHash = entry.getString("sha256");
        if (fileName.length() > 330 || !fileName.matches("[A-Za-z0-9._-]+\\.json") ||
                !expectedHash.matches(HASH))
            throw new IOException("Invalid index entry");

        byte[] profileBytes = readUri(context, new Uri.Builder().scheme("content")
                .authority(authority).appendPath("profile").appendPath(fileName).build());
        if (!sha256(profileBytes).equals(expectedHash))
            throw new IOException("Profile changed while reading");
        JSONObject json = new JSONObject(new String(profileBytes, StandardCharsets.UTF_8));
        if (json.getInt("schema") != 1 || !"none".equals(json.getString("backend")) ||
                json.getJSONArray("items").length() != 0 ||
                json.getJSONArray("engines").length() != 0)
            throw new IOException("Executable menu entries are not supported");

        String pkg = json.getString("packageName");
        String artifact = json.getString("artifactSha256");
        if (pkg.length() > 255 || !pkg.matches(APK_PACKAGE) ||
                !artifact.matches(HASH) ||
                !fileName.equals(pkg + "-" + artifact + ".json"))
            throw new IOException("Profile identity mismatch");

        JSONArray array = json.getJSONArray("sources");
        if (array.length() < 1 || array.length() > 64)
            throw new IOException("Missing or excessive APK splits");
        List<String> expected = new ArrayList<>();
        Set<String> fingerprints = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject source = array.getJSONObject(i);
            String hash = source.getString("sha256");
            long size = source.getLong("size");
            if (!hash.matches(HASH) || size <= 0)
                throw new IOException("Invalid source identity");
            String identity = hash + ":" + size;
            if (!fingerprints.add(identity))
                throw new IOException("Duplicate source identity");
            expected.add(identity);
        }

        PackageManager pm = context.getPackageManager();
        ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
        List<String> actual = new ArrayList<>();
        if (appInfo.sourceDir == null || appInfo.sourceDir.isEmpty())
            throw new IOException("Installed base APK missing");
        actual.add(fileIdentity(new File(appInfo.sourceDir)));
        if (appInfo.splitSourceDirs != null)
            for (String split : appInfo.splitSourceDirs)
                actual.add(fileIdentity(new File(split)));
        if (!SourceIdentityPolicy.matches(expected, actual))
            throw new IOException("The installed APK set changed");

        Intent launcher = pm.getLaunchIntentForPackage(pkg);
        if (launcher == null || launcher.getComponent() == null ||
                !pkg.equals(launcher.getComponent().getPackageName()))
            throw new IOException("Installed APK has no trusted launcher");
        return Result.verified(pkg, artifact);
    }

    private static String fileIdentity(File file) throws Exception {
        if (!file.isFile() || file.length() <= 0) throw new IOException("Missing APK file");
        long size = file.length();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        // Reject changes that happen during the streamed inventory.
        if (size != file.length()) throw new IOException("APK file changed during read");
        return hex(digest.digest()) + ":" + size;
    }

    private static byte[] readUri(Context context, Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Missing profile stream");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > MAX_JSON - output.size())
                    throw new IOException("Oversized profile");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            result.append(Character.forDigit((b >>> 4) & 15, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }
}
