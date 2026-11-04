package io.github.ffenuss.modkit.spacecompanion;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Schema-2 multi-game catalog is a list of untrusted build records, not a
 * capability grant. Each launch requires a fresh signed-publisher read,
 * exact artifact identity, installed signer and all APK bytes.
 */
public final class SpaceCatalogReader {
    private static final int MAX_JSON = 256 * 1024;
    private static final int MAX_GAMES = 32;
    private static final String[] MODKIT = {
            "io.github.ffenuss.modkit.test", "io.github.ffenuss.modkit"
    };
    private static final String PKG =
            "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+";
    private static final String SHA = "[a-f0-9]{64}";

    public static final class Entry {
        public final String publisher, packageName, artifactSha256;
        public final List<String> signers, sources;

        private Entry(String publisher, String packageName, String artifactSha256,
                List<String> signers, List<String> sources) {
            this.publisher = publisher;
            this.packageName = packageName;
            this.artifactSha256 = artifactSha256;
            this.signers = Collections.unmodifiableList(new ArrayList<>(signers));
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }

        public boolean matches(Entry other) {
            return other != null && publisher.equals(other.publisher) &&
                    packageName.equals(other.packageName) &&
                    artifactSha256.equals(other.artifactSha256) &&
                    signers.equals(other.signers) && sources.equals(other.sources);
        }
    }

    public static final class Catalog {
        public final List<Entry> entries;
        public final String message;

        private Catalog(List<Entry> entries, String message) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.message = message;
        }

        static Catalog empty(String reason) {
            return new Catalog(Collections.emptyList(), reason);
        }
    }

    public static final class Verification {
        public final boolean ready;
        public final String message;

        private Verification(boolean ready, String message) {
            this.ready = ready;
            this.message = message;
        }
    }

    private SpaceCatalogReader() {}

    public static Catalog load(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String publisher : MODKIT) {
            if (pm.checkSignatures(context.getPackageName(), publisher) !=
                    PackageManager.SIGNATURE_MATCH) continue;
            try {
                return loadFrom(context, publisher);
            } catch (Exception rejected) {
                // Never silently fall back to a different installed publisher.
                return Catalog.empty("Каталог ModKit недоступен: " +
                        rejected.getClass().getSimpleName());
            }
        }
        return Catalog.empty("Нет ModKit с совпадающей подписью.");
    }

    private static Catalog loadFrom(Context context, String publisher) throws Exception {
        Uri uri = new Uri.Builder().scheme("content")
                .authority(publisher + ".space-menu").appendPath("catalog").build();
        JSONObject object = new JSONObject(new String(readUri(context, uri), StandardCharsets.UTF_8));
        if (object.getInt("schema") != 2 || !"none".equals(object.getString("backend")))
            throw new IOException("Unsupported catalog backend");
        JSONArray profiles = object.getJSONArray("profiles");
        if (profiles.length() > MAX_GAMES) throw new IOException("Too many game profiles");
        List<Entry> entries = new ArrayList<>();
        Set<String> packages = new HashSet<>();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject item = profiles.getJSONObject(i);
            String pkg = item.getString("packageName");
            String artifact = item.getString("artifactSha256");
            if (pkg.length() > 255 || !pkg.matches(PKG) ||
                    !artifact.matches(SHA) || !packages.add(pkg))
                throw new IOException("Duplicate or invalid game");
            JSONArray signerArray = item.getJSONArray("signers");
            if (signerArray.length() < 1 || signerArray.length() > 4)
                throw new IOException("Invalid signer count");
            List<String> signers = new ArrayList<>();
            for (int j = 0; j < signerArray.length(); j++) {
                String hash = signerArray.getString(j);
                if (!hash.matches(SHA) || signers.contains(hash))
                    throw new IOException("Invalid or duplicate signer");
                signers.add(hash);
            }
            JSONArray sourcesArray = item.getJSONArray("sources");
            if (sourcesArray.length() < 1 || sourcesArray.length() > 64)
                throw new IOException("Invalid split count");
            List<String> sources = new ArrayList<>();
            for (int j = 0; j < sourcesArray.length(); j++) {
                JSONObject source = sourcesArray.getJSONObject(j);
                String hash = source.getString("sha256");
                long size = source.getLong("size");
                if (!hash.matches(SHA) || size <= 0)
                    throw new IOException("Invalid APK fingerprint");
                String id = hash + ":" + size;
                if (sources.contains(id))
                    throw new IOException("Duplicate APK fingerprint");
                sources.add(id);
            }
            Collections.sort(signers);
            Collections.sort(sources);
            entries.add(new Entry(publisher, pkg, artifact, signers, sources));
        }
        return new Catalog(entries, entries.isEmpty() ?
                "Нет сохранённых сборок в ModKit." : "Профилей в ModKit: " + entries.size());
    }

    /** Re-read publisher catalog and file identities on every tap. Run on IO thread. */
    public static Verification verify(Context context, Entry selected) {
        if (selected == null) return new Verification(false, "Не выбрана игра.");
        Catalog latest = load(context);
        Entry candidate = null;
        for (Entry entry : latest.entries) {
            if (entry.packageName.equals(selected.packageName)) {
                candidate = entry;
                break;
            }
        }
        if (!selected.matches(candidate))
            return new Verification(false, "Профиль изменён или отозван. Обновите список.");
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = packageInfo(pm, selected.packageName);
            List<String> actualSigners = signingFingerprints(info);
            if (!new HashSet<>(selected.signers).equals(new HashSet<>(actualSigners)) ||
                    actualSigners.size() != selected.signers.size())
                throw new IOException("Installed signer differs from catalog");

            ApplicationInfo installed = info.applicationInfo;
            if (installed == null || installed.sourceDir == null)
                throw new IOException("Base APK missing");
            List<String> actual = new ArrayList<>();
            actual.add(fileFingerprint(new File(installed.sourceDir)));
            if (installed.splitSourceDirs != null)
                for (String split : installed.splitSourceDirs)
                    actual.add(fileFingerprint(new File(split)));
            if (!SourceIdentityPolicy.matches(selected.sources, actual))
                throw new IOException("Installed APK set does not match build");
            Intent launch = pm.getLaunchIntentForPackage(selected.packageName);
            if (launch == null || launch.getComponent() == null ||
                    !selected.packageName.equals(launch.getComponent().getPackageName()))
                throw new IOException("No launcher for verified game");
            return new Verification(true, "Проверено: " + selected.packageName);
        } catch (Exception changed) {
            return new Verification(false,
                    "Подпись или APK не совпадают: " + changed.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo packageInfo(PackageManager pm, String pkg)
            throws PackageManager.NameNotFoundException {
        int flags = Build.VERSION.SDK_INT >= 28 ?
                PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 33)
            return pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags));
        return pm.getPackageInfo(pkg, flags);
    }

    @SuppressWarnings("deprecation")
    private static List<String> signingFingerprints(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28 ?
                (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) :
                info.signatures;
        if (signatures == null || signatures.length == 0 || signatures.length > 4)
            throw new IOException("Missing installed signer");
        List<String> results = new ArrayList<>();
        for (Signature signature : signatures) {
            String hash = sha256(signature.toByteArray());
            if (results.contains(hash)) throw new IOException("Duplicate installed signer");
            results.add(hash);
        }
        return results;
    }

    private static String fileFingerprint(File file) throws Exception {
        if (!file.isFile()) throw new IOException("APK source unavailable");
        long size = file.length();
        long modified = file.lastModified();
        if (size <= 0) throw new IOException("Empty APK");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        if (size != file.length() || modified != file.lastModified())
            throw new IOException("APK changed during verification");
        return hex(digest.digest()) + ":" + size;
    }

    private static byte[] readUri(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("No catalog content");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192];
            int n;
            while ((n = in.read(bytes)) != -1) {
                if (n > MAX_JSON - buffer.size())
                    throw new IOException("Catalog exceeds limit");
                buffer.write(bytes, 0, n);
            }
            return buffer.toByteArray();
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) {
            out.append(Character.forDigit((b >>> 4) & 15, 16));
            out.append(Character.forDigit(b & 15, 16));
        }
        return out.toString();
    }
}
