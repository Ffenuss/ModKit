package io.github.ffenuss.modkit.space;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only inventory bound to the exact original base/split set. No extraction or patching. */
final class SourceInventory {
    interface Progress { void update(String message); }
    static final class Cancellation {
        private volatile boolean cancelled;
        void cancel() { cancelled = true; }
        void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Analysis cancelled");
        }
    }
    static final class Source {
        final File file;
        final long size;
        final String sha256;
        Source(File file, Cancellation token) throws IOException {
            this.file = file.getCanonicalFile();
            if (!this.file.isFile() || !this.file.canRead()) throw new IOException("Original APK is unreadable");
            size = this.file.length(); sha256 = hash(this.file, token);
        }
        void verify(Cancellation token) throws IOException {
            if (file.length() != size || !sha256.equals(hash(file, token)))
                throw new IOException("Original APK changed during analysis; restart required");
        }
    }
    static final class Result {
        final String sessionId, packageName, setSha256, report;
        final int user;
        final List<Source> sources;
        Result(String session, String pkg, int user, String identity, List<Source> sources, String report) {
            sessionId = session; packageName = pkg; this.user = user; setSha256 = identity;
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources)); this.report = report;
        }
    }
    private SourceInventory() {}
    static Result scan(String pkg, int user, List<File> files, Cancellation token, Progress progress) throws IOException {
        if (!SpacePolicy.validSession(pkg, user) || files == null || files.isEmpty() || files.size() > 128)
            throw new IOException("Invalid original APK set");
        String session = UUID.randomUUID().toString();
        List<Source> sources = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (File file : files) {
            token.check();
            if (file == null) throw new IOException("Missing original APK");
            progress.update("Проверяем SHA-256: APK " + (sources.size() + 1) + "/" + files.size());
            Source source = new Source(file, token);
            if (!paths.add(source.file.getPath())) throw new IOException("Duplicate original APK");
            sources.add(source);
        }
        MessageDigest identity = digest();
        // Base first, then declared split order. Binary hashes have a fixed length; no path leakage.
        for (Source source : sources) identity.update(source.sha256.getBytes(StandardCharsets.US_ASCII));
        String setHash = hex(identity.digest());
        int entries = 0, dex = 0, elf = 0, metadata = 0;
        Set<String> abis = new java.util.TreeSet<>();
        StringBuilder details = new StringBuilder();
        for (int index = 0; index < sources.size(); index++) {
            token.check(); progress.update("Читаем состав APK " + (index + 1) + "/" + sources.size());
            try (ZipFile zip = new ZipFile(sources.get(index).file)) {
                Set<String> names = new HashSet<>();
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    token.check(); ZipEntry entry = all.nextElement();
                    if (++entries > 100000) throw new IOException("APK inventory exceeds 100000 entries");
                    String name = entry.getName();
                    if (!names.add(name)) throw new IOException("Ambiguous duplicate ZIP entry");
                    if (entry.isDirectory()) continue;
                    byte[] header = new byte[8]; int read = 0;
                    try (InputStream input = zip.getInputStream(entry)) {
                        while (read < header.length) {
                            token.check(); int count = input.read(header, read, header.length - read);
                            if (count < 0) break;
                            if (count == 0) throw new IOException("ZIP stream made no progress");
                            read += count;
                        }
                    }
                    if (read >= 8 && header[0] == 'd' && header[1] == 'e' && header[2] == 'x' && header[3] == '\n'
                        && header[4] >= '0' && header[4] <= '9' && header[5] >= '0' && header[5] <= '9'
                        && header[6] >= '0' && header[6] <= '9' && header[7] == 0) dex++;
                    if (read >= 4 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F') {
                        elf++;
                        String[] parts = name.split("/");
                        if (parts.length == 3 && "lib".equals(parts[0])) abis.add(parts[1]);
                    }
                    if (read >= 4 && (header[0] & 255) == 0xaf && (header[1] & 255) == 0x1b
                        && (header[2] & 255) == 0xb1 && (header[3] & 255) == 0xfa) metadata++;
                }
            }
            Source source = sources.get(index);
            details.append(index == 0 ? "Base APK" : "Split " + index).append(": ").append(source.size)
                .append(" bytes\nSHA-256: ").append(source.sha256).append('\n');
        }
        for (Source source : sources) { token.check(); source.verify(token); }
        token.check();
        String report = "ModKit · состав оригинального приложения\nПакет: " + pkg + "\nПользователь: " + user
            + "\nСессия: " + session + "\nSHA-256 набора: " + setHash + "\nAPK: " + sources.size()
            + "\nZIP-записи: " + entries + "\nDEX-заголовки: " + dex + "\nELF-заголовки: " + elf
            + "\nABI по путям: " + abis + "\nIL2CPP metadata-заголовки: " + metadata + "\n\n" + details
            + "\nЭто инвентаризация файлов. Заголовок не доказывает корректность бинарного файла. "
            + "Моды и управление памятью пока не подключены.\n";
        return new Result(session, pkg, user, setHash, sources, report);
    }
    static String hash(File file, Cancellation token) throws IOException {
        MessageDigest digest = digest(); byte[] buffer = new byte[65536];
        try (InputStream input = new java.io.FileInputStream(file)) {
            for (;;) { token.check(); int count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count); }
        }
        return hex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return value.toString();
    }
}
