package io.github.ffenuss.modkit.space;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;
import io.github.ffenuss.modkit.runtimeprobe.RuntimeNativeBridge;

/** Download only from ModKit's authenticated read-only provider, never from arbitrary URLs. */
final class SpaceNativePayload {
    private SpaceNativePayload() {}
    static String processAbi() {
        String[] supported = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        if (supported.length == 0) throw new IllegalStateException("Unknown process ABI");
        return supported[0];
    }
    static boolean load(Context host) throws Exception {
        String abi = processAbi();
        Exception last = null;
        for (String authority : new String[]{"io.github.ffenuss.modkit.test.space-menu", "io.github.ffenuss.modkit.space-menu"}) {
            try {
                JSONObject manifest = new JSONObject(new String(read(host, authority, "runtime", 16384), StandardCharsets.UTF_8));
                if (manifest.getInt("schema") != 1 || !"native-v1".equals(manifest.getString("api"))) throw new IllegalArgumentException("Runtime API mismatch");
                JSONArray payloads = manifest.getJSONArray("payloads"); JSONObject chosen = null;
                if (payloads.length() > 4) throw new IllegalArgumentException("Runtime manifest exceeds limit");
                for (int i = 0; i < payloads.length(); i++) if (abi.equals(payloads.getJSONObject(i).getString("abi"))) {
                    if (chosen != null) throw new IllegalArgumentException("Ambiguous runtime ABI"); chosen = payloads.getJSONObject(i);
                }
                if (chosen == null) throw new IllegalStateException("Runtime ABI unavailable");
                String hash = chosen.getString("sha256"); long size = chosen.getLong("size");
                if (!hash.matches("[a-f0-9]{64}") || size <= 20 || size > 8 * 1024 * 1024) throw new IllegalArgumentException("Invalid runtime identity");
                byte[] bytes = read(host, authority, "native/" + abi, 8 * 1024 * 1024);
                if (bytes.length != size || !hash.equals(hash(bytes)) || bytes[0] != 127 || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F')
                    throw new IllegalArgumentException("Runtime payload changed");
                int machine = (bytes[18] & 255) | ((bytes[19] & 255) << 8);
                int expected = "arm64-v8a".equals(abi) ? 183 : "armeabi-v7a".equals(abi) ? 40 : "x86_64".equals(abi) ? 62 : "x86".equals(abi) ? 3 : -1;
                if (machine != expected || bytes[5] != 1) throw new IllegalArgumentException("Runtime ELF ABI mismatch");
                File root = new File(host.getCodeCacheDir(), "modkit-native");
                if (!root.isDirectory() && !root.mkdirs()) throw new IllegalStateException("Runtime storage unavailable");
                File file = new File(root, hash + ".so");
                if (!file.isFile() || !hash.equals(SourceInventory.hash(file, new SourceInventory.Cancellation()))) {
                    if (file.exists() && !file.delete()) throw new IllegalStateException("Stale runtime cannot be removed");
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
                }
                if (!file.setReadOnly()) throw new IllegalStateException("Runtime payload must be read-only");
                return RuntimeNativeBridge.loadVerifiedFile(file);
            } catch (Exception failure) { last = failure; }
        }
        throw new IllegalStateException("Откройте обновлённый ModKit и передайте меню", last);
    }
    private static byte[] read(Context context, String authority, String path, int limit) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(Uri.parse("content://" + authority + "/" + path))) {
            if (input == null) throw new IllegalArgumentException("Runtime payload absent");
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0 || output.size() + count > limit) throw new IllegalArgumentException("Runtime payload exceeds limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
