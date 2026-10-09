package io.github.ffenuss.modkit.space;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import io.github.ffenuss.modkit.runtimeprobe.RuntimeNativeBridge;

/** Engine-neutral native executor bound to the loaded image, not a metadata token. */
final class SpaceNativeBackend implements SpaceNativeController.Backend {
    private final List<File> sources;
    private final Map<String, String> hashes = new HashMap<>();
    public String unavailableReason(NativePatch patch) {
        if (!patch.abi.equals(SpaceNativePayload.processAbi()))
            return "Рецепт для " + patch.abi + "; процесс использует " + SpaceNativePayload.processAbi();
        if (RuntimeNativeBridge.loadedModulePath(patch.module) == null)
            return "Библиотека " + patch.module + " ещё не загружена";
        return "Образ библиотеки или исходные байты не совпадают с анализом";
    }
    SpaceNativeBackend(List<File> sources) { this.sources = sources; }
    public boolean imageMatches(NativePatch patch) throws Exception {
        if (!patch.abi.equals(SpaceNativePayload.processAbi())) return false;
        String path = RuntimeNativeBridge.loadedModulePath(patch.module);
        if (path == null) return false;
        int bang = path.indexOf('!');
        File file = new File(bang < 0 ? path : path.substring(0, bang)).getCanonicalFile();
        String entry = "lib/" + patch.abi + "/" + patch.module;
        if (bang >= 0) {
            boolean owned = false;
            for (File source : sources) if (source.getCanonicalFile().equals(file)) owned = true;
            if (!owned || !path.substring(bang + 1).equals("/" + entry)) return false;
        }
        if (!file.isFile()) return false;
        String key = path + ":" + file.length() + ":" + file.lastModified();
        String hash = hashes.get(key);
        if (hash == null) {
            if (bang >= 0) {
                try (ZipFile zip = new ZipFile(file)) {
                    java.util.zip.ZipEntry item = zip.getEntry(entry);
                    if (item == null || item.getSize() <= 0 || item.getSize() > 512L * 1024 * 1024) return false;
                    try (InputStream input = zip.getInputStream(item)) { hash = digest(input); }
                }
            } else {
                if (file.length() > 512L * 1024 * 1024) return false;
                hash = SourceInventory.hash(file, new SourceInventory.Cancellation());
            }
            hashes.put(key, hash);
        }
        return patch.imageSha256.equals(hash);
    }
    private static String digest(InputStream input) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[32768]; int count; long size = 0;
        while ((count = input.read(buffer)) != -1) { size += count; if (size > 512L * 1024 * 1024) throw new IllegalArgumentException("Native image exceeds limit"); hash.update(buffer, 0, count); }
        return SpaceNativePayload.hex(hash.digest());
    }
    public boolean bytesMatch(NativePatch patch, byte[] bytes) { return RuntimeNativeBridge.codeMatches(patch.module, patch.address, bytes); }
    public int write(NativePatch patch, byte[] expected, byte[] replacement) { return RuntimeNativeBridge.patchCodeStatus(patch.module, patch.address, expected, replacement); }
}
