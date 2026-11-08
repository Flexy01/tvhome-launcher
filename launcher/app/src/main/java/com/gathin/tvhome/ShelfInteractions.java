package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Device-only event journal. All access runs on ShelfPanel's existing worker. */
final class ShelfInteractions implements AutoCloseable {
    private final File file;
    private SQLiteDatabase database;

    static final class Summary {
        final long impressions, selections, lastImpressionAt, lastSelectionAt;

        Summary(long impressions, long selections, long lastImpressionAt, long lastSelectionAt) {
            this.impressions = impressions;
            this.selections = selections;
            this.lastImpressionAt = lastImpressionAt;
            this.lastSelectionAt = lastSelectionAt;
        }
    }

    ShelfInteractions(Context context) {
        file = new File(context.getNoBackupFilesDir(), "shelf-interactions.db");
    }

    private void open() {
        if (database != null) return;
        SQLiteDatabase opened = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            opened.setMaximumSize(2 * 1024 * 1024);
            opened.execSQL("PRAGMA cache_size=-128");
            opened.beginTransaction();
            try {
                opened.execSQL("CREATE TABLE IF NOT EXISTS events (package TEXT NOT NULL, content_id INTEGER NOT NULL, "
                        + "event TEXT NOT NULL CHECK(event IN ('impression','selection')), occurred_at INTEGER NOT NULL)");
                opened.execSQL("CREATE INDEX IF NOT EXISTS event_lookup ON events(package, content_id, event, occurred_at)");
                opened.execSQL("CREATE TABLE IF NOT EXISTS summaries (package TEXT NOT NULL, content_id INTEGER NOT NULL, "
                        + "impressions INTEGER NOT NULL DEFAULT 0, selections INTEGER NOT NULL DEFAULT 0, "
                        + "last_impression_at INTEGER NOT NULL DEFAULT 0, last_selection_at INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY(package, content_id))");
                if (opened.getVersion() == 0) {
                    // Only surviving raw events can be recovered when upgrading the old journal.
                    opened.execSQL("INSERT INTO summaries SELECT package, content_id, sum(event='impression'), sum(event='selection'), "
                            + "max(CASE WHEN event='impression' THEN occurred_at ELSE 0 END), "
                            + "max(CASE WHEN event='selection' THEN occurred_at ELSE 0 END) "
                            + "FROM events GROUP BY package, content_id ORDER BY max(occurred_at) DESC, max(rowid) DESC LIMIT 512");
                    opened.setVersion(1);
                }
                // Only accepted inserts count; summary updates and both retention limits commit together.
                opened.execSQL("CREATE TRIGGER IF NOT EXISTS summarize_event AFTER INSERT ON events BEGIN "
                        + "INSERT OR IGNORE INTO summaries(package, content_id) VALUES(NEW.package, NEW.content_id); "
                        + "UPDATE summaries SET impressions=impressions+(NEW.event='impression'), selections=selections+(NEW.event='selection'), "
                        + "last_impression_at=max(last_impression_at, CASE WHEN NEW.event='impression' THEN NEW.occurred_at ELSE 0 END), "
                        + "last_selection_at=max(last_selection_at, CASE WHEN NEW.event='selection' THEN NEW.occurred_at ELSE 0 END) "
                        + "WHERE package=NEW.package AND content_id=NEW.content_id; "
                        + "DELETE FROM summaries WHERE rowid IN (SELECT rowid FROM summaries "
                        + "ORDER BY max(last_impression_at,last_selection_at) DESC, rowid DESC LIMIT -1 OFFSET 512); END");
                opened.setTransactionSuccessful();
            } finally {
                opened.endTransaction();
            }
            database = opened;
        } catch (RuntimeException e) {
            opened.close();
            throw e;
        }
    }

    Map<String, Map<Long, Summary>> readSummaries() {
        if (database == null && !file.isFile()) return Collections.emptyMap();
        open();
        Map<String, Map<Long, Summary>> result = new HashMap<>();
        try (Cursor rows = database.rawQuery("SELECT package, content_id, impressions, selections, "
                + "last_impression_at, last_selection_at FROM summaries LIMIT 512", null)) {
            while (rows.moveToNext()) {
                result.computeIfAbsent(rows.getString(0), pkg -> new HashMap<>()).put(rows.getLong(1),
                        new Summary(rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5)));
            }
        }
        return result;
    }

    void record(String pkg, long contentId, String event, long time) {
        if (pkg == null || pkg.isEmpty() || pkg.length() > 255 || contentId <= 0
                || (!"impression".equals(event) && !"selection".equals(event))
                || time <= 0 || time > Long.MAX_VALUE - 60_000) return;
        open();
        long window = "impression".equals(event) ? 60_000 : 1_000;
        database.beginTransaction();
        try {
            // Summary timestamps also suppress duplicate callbacks after their raw events are pruned.
            String lastTime = "impression".equals(event) ? "last_impression_at" : "last_selection_at";
            long from = Math.max(1, time - window + 1), through = time + window - 1;
            database.execSQL("INSERT INTO events(package, content_id, event, occurred_at) SELECT ?,?,?,? WHERE NOT EXISTS "
                            + "(SELECT 1 FROM events WHERE package=? AND content_id=? AND event=? AND occurred_at BETWEEN ? AND ?) "
                            + "AND NOT EXISTS (SELECT 1 FROM summaries WHERE package=? AND content_id=? AND " + lastTime + " BETWEEN ? AND ?)",
                    new Object[]{pkg, contentId, event, time, pkg, contentId, event, from, through,
                            pkg, contentId, from, through});
            database.execSQL("DELETE FROM events WHERE rowid <= (SELECT rowid FROM events ORDER BY rowid DESC LIMIT 1 OFFSET 2048)");
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    @Override public void close() {
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
