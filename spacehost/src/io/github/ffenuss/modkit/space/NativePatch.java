package io.github.ffenuss.modkit.space;

import org.json.JSONObject;
import java.util.Arrays;

/** Explicit executable data. No addresses are inferred from method names or metadata tokens. */
final class NativePatch {
    final String module, abi, imageSha256;
    final long address;
    final byte[] expected, replacement;
    NativePatch(JSONObject json) throws Exception {
        module = json.getString("module"); abi = json.getString("abi"); imageSha256 = json.getString("imageSha256");
        address = json.getLong("address");
        if (!module.matches("[A-Za-z0-9_-]+\\.so") || module.length() > 255 ||
            !Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64").contains(abi) ||
            !imageSha256.matches("[a-f0-9]{64}") || address <= 0 || address % 4 != 0 || address > Long.MAX_VALUE - 64)
            throw new IllegalArgumentException("Invalid native recipe identity");
        expected = decode(json.getString("expected")); replacement = decode(json.getString("replacement"));
        if (expected.length != replacement.length || Arrays.equals(expected, replacement))
            throw new IllegalArgumentException("Native recipe must change equal-sized byte ranges");
    }
    private static byte[] decode(String hex) {
        if (hex.length() < 8 || hex.length() > 128 || hex.length() % 8 != 0 || !hex.matches("[a-f0-9]+"))
            throw new IllegalArgumentException("Invalid native recipe bytes");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    boolean overlaps(NativePatch other) {
        return module.equals(other.module) && abi.equals(other.abi) &&
            address < other.address + other.expected.length && other.address < address + expected.length;
    }
}
