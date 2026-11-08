package com.gathin.tvhome;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** Private, expendable encoded images only; filenames contain no URLs or content metadata. */
final class ShelfPosterStore {
    static final long LIMIT_BYTES = 30L * 1024 * 1024;
    private final File root;
    private final long limit;

    ShelfPosterStore(File root, long limit) {
        this.root = root;
        this.limit = limit;
    }

    synchronized byte[] read(String key) throws IOException {
        File file = new File(root, hash(key));
        if (!file.isFile()) return null;
        if (file.length() > 4 * 1024 * 1024) { Files.deleteIfExists(file.toPath()); return null; }
        byte[] bytes = Files.readAllBytes(file.toPath());
        file.setLastModified(System.currentTimeMillis());
        return bytes;
    }

    synchronized void put(String key, byte[] bytes) throws IOException {
        if (bytes.length > limit || bytes.length > 4 * 1024 * 1024) return;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Poster cache unavailable");
        File target = new File(root, hash(key));
        Files.deleteIfExists(target.toPath());
        File[] files = root.listFiles();
        if (files == null) throw new IOException("Poster cache unavailable");
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total + bytes.length <= limit) break;
            long length = file.length();
            Files.delete(file.toPath());
            total -= length;
        }
        File temp = new File(root, target.getName() + ".tmp");
        try {
            Files.write(temp.toPath(), bytes);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
    }

    synchronized void clear() throws IOException {
        File[] files = root.listFiles();
        if (files != null) for (File file : files) Files.deleteIfExists(file.toPath());
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
