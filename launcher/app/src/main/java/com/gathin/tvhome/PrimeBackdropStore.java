package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Verified Prime backdrops kept across restarts: GTI, IMDb id, Fanart backdrop URL and expiry, nothing else.
 * Expendable: a missing or unreadable file restores nothing, and a failed write keeps the backdrops in memory.
 */
final class PrimeBackdropStore {
    private static final String HTTPS = "https://";
    private static final int MAX_URL_CHARS = 512;

    static final class Entry {
        final String gti;
        final String imdbId;
        final String backdrop;
        final long expiresAt;

        Entry(String gti, String imdbId, String backdrop, long expiresAt) {
            this.gti = gti;
            this.imdbId = imdbId;
            this.backdrop = backdrop;
            this.expiresAt = expiresAt;
        }
    }

    private final File file;

    PrimeBackdropStore(File file) {
        this.file = file;
    }

    List<Entry> load() {
        List<Entry> entries = new ArrayList<>();
        if (!file.isFile()) return entries;
        try {
            JSONArray array = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.getJSONObject(i);
                String backdrop = row.optString("backdrop", "");
                if (!backdrop.startsWith(HTTPS) || backdrop.length() > MAX_URL_CHARS) continue;
                entries.add(new Entry(row.getString("gti"), row.optString("imdbId", ""), backdrop,
                        row.getLong("expiresAt")));
            }
        } catch (Exception unreadable) {
            entries.clear(); // Corrupt or unreadable: restore nothing; the next save replaces the file.
        }
        return entries;
    }

    void save(List<Entry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (Entry e : entries) {
                array.put(new JSONObject().put("gti", e.gti).put("imdbId", e.imdbId)
                        .put("backdrop", e.backdrop).put("expiresAt", e.expiresAt));
            }
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) return;
            File temp = new File(parent, file.getName() + ".tmp");
            Files.write(temp.toPath(), array.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception unwritable) {
            // Expendable: the backdrops stay in memory for this run and are verified again after a restart.
        }
    }
}
