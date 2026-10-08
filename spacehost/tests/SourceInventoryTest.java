package io.github.ffenuss.modkit.space;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SourceInventoryTest {
    interface Attempt { void run() throws Exception; }
    static void rejects(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    static void apk(File file, String[] names, byte[][] contents) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i])); zip.write(contents[i]); zip.closeEntry();
            }
        }
    }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("space-inventory-test").toFile();
        File base = new File(dir, "base.apk"), split = new File(dir, "split.apk");
        try {
            apk(base, new String[]{"classes.dex", "assets/il2cpp_data/Metadata/global-metadata.dat"},
                new byte[][]{new byte[]{'d','e','x','\n','0','3','5',0}, new byte[]{(byte)0xaf,0x1b,(byte)0xb1,(byte)0xfa}});
            apk(split, new String[]{"lib/arm64-v8a/libil2cpp.so"}, new byte[][]{new byte[]{0x7f,'E','L','F'}});
            SourceInventory.Cancellation token = new SourceInventory.Cancellation();
            String before = SourceInventory.hash(base, token);
            SourceInventory.Result result = SourceInventory.scan("com.game.test", 7, Arrays.asList(base, split), token, m -> {});
            if (result.user != 7 || result.sources.size() != 2 || !result.report.contains("DEX-заголовки: 1")
                || !result.report.contains("ELF-заголовки: 1") || !result.report.contains("IL2CPP metadata-заголовки: 1")
                || !result.report.contains("arm64-v8a") || !before.equals(SourceInventory.hash(base, token)))
                throw new AssertionError(result.report);
            SourceInventory.Result again = SourceInventory.scan("com.game.test", 8, Arrays.asList(base, split), token, m -> {});
            if (!result.setSha256.equals(again.setSha256) || result.sessionId.equals(again.sessionId)) throw new AssertionError("Session identity");
            rejects(() -> SourceInventory.scan("com.game.test", 0, Arrays.asList(base, base), token, m -> {}));
            rejects(() -> SourceInventory.scan("com.game.test", -1, Arrays.asList(base), token, m -> {}));
            rejects(() -> SourceInventory.scan("com.game.test", 0, Arrays.asList(new File(dir, "missing.apk")), token, m -> {}));
            SourceInventory.Cancellation cancelled = new SourceInventory.Cancellation(); cancelled.cancel();
            rejects(() -> SourceInventory.scan("com.game.test", 0, Arrays.asList(base), cancelled, m -> {}));
            // Change source after initial hashes but before ZIP inventory: must reject instead of returning stale evidence.
            rejects(() -> SourceInventory.scan("com.game.test", 0, Arrays.asList(base), token, m -> {
                if (m.startsWith("Читаем")) try {
                    apk(base, new String[]{"replacement"}, new byte[][]{new byte[]{1,2,3}});
                } catch (IOException e) { throw new RuntimeException(e); }
            }));
            File notZip = new File(dir, "invalid.apk"); Files.write(notZip.toPath(), new byte[]{1,2,3});
            rejects(() -> SourceInventory.scan("com.game.test", 0, Arrays.asList(notZip), token, m -> {}));
            Files.delete(notZip.toPath());
            System.out.println("PASS: base/split inventory, immutable inputs, session isolation, cancellation and mutation rejection");
        } finally {
            Files.deleteIfExists(base.toPath()); Files.deleteIfExists(split.toPath()); Files.deleteIfExists(dir.toPath());
        }
    }
}
