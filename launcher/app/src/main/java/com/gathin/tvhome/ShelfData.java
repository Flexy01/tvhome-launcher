package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Read-only snapshots. Provider rows, including intents, stay in memory only. */
final class ShelfData {
    private static final String[] WATCH = {"_id", "package_name", "title", "intent_uri", "poster_art_uri",
            "watch_next_type", "last_playback_position_millis", "duration_millis", "last_engagement_time_utc_millis",
            "type", "release_date", TvContract.WatchNextPrograms.COLUMN_AUTHOR};
    private static final String[] PREVIEW = {"_id", "package_name", "title", "intent_uri", "poster_art_uri", "type", "live", "release_date", "content_id", TvContract.PreviewPrograms.COLUMN_AUTHOR};

    static final class Item {
        final long id;
        final String pkg, title, intent, poster;
        final long position, duration, engaged;
        final int watchNextType, type;
        final String releaseDate;
        /** The provider's content id (Turkcell: the TV+ page id); null when the provider has none. */
        final String contentId;
        final String author;
        long score;
        Item(long id, String pkg, String title, String intent, String poster, long position, long duration, long engaged) {
            this(id, pkg, title, intent, poster, position, duration, engaged, -1);
        }
        Item(long id, String pkg, String title, String intent, String poster, long position, long duration, long engaged, int watchNextType) {
            this(id, pkg, title, intent, poster, position, duration, engaged, watchNextType, -1, null);
        }
        Item(long id, String pkg, String title, String intent, String poster, long position, long duration, long engaged,
             int watchNextType, int type, String releaseDate) {
            this(id, pkg, title, intent, poster, position, duration, engaged, watchNextType, type, releaseDate, null);
        }
        Item(long id, String pkg, String title, String intent, String poster, long position, long duration, long engaged,
             int watchNextType, int type, String releaseDate, String contentId) {
            this(id, pkg, title, intent, poster, position, duration, engaged, watchNextType, type, releaseDate, contentId, null);
        }
        Item(long id, String pkg, String title, String intent, String poster, long position, long duration, long engaged,
             int watchNextType, int type, String releaseDate, String contentId, String author) {
            this.id = id; this.pkg = pkg; this.title = title; this.intent = intent; this.poster = poster;
            this.position = position; this.duration = duration; this.engaged = engaged;
            this.watchNextType = watchNextType; this.type = type; this.releaseDate = releaseDate;
            this.contentId = contentId; this.author = author;
        }
        Item withAuthor(String resolvedAuthor) {
            return new Item(id, pkg, title, intent, poster, position, duration, engaged,
                    watchNextType, type, releaseDate, contentId, resolvedAuthor);
        }
        ShelfIdentity.Query artworkQuery() { return ShelfIdentity.parse(title, intent, poster, type, releaseDate); }
    }

    static final class Snapshot {
        final List<Item> watching = new ArrayList<>();
        final List<Item> recommended = new ArrayList<>();
        String error;
    }

    private ShelfData() {}

    static Snapshot load(Context context, ShelfInteractions interactions) {
        Snapshot result = new Snapshot();
        ContentResolver resolver = context.getContentResolver();
        try {
            try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, WATCH, null, null, null)) {
                if (c == null) throw new IllegalStateException("Watch Next unavailable");
                while (c.moveToNext()) {
                    Item item = read(c);
                    if (usable(item) && ShelfLogic.continueEligible(integer(c, "watch_next_type", -1), item.position, item.duration)) {
                        result.watching.add(item);
                    }
                }
            }
            long now = System.currentTimeMillis();
            try (Cursor c = resolver.query(TvContract.PreviewPrograms.CONTENT_URI, PREVIEW, null, null, null)) {
                if (c == null) throw new IllegalStateException("Preview unavailable");
                while (c.moveToNext()) {
                    if (!ShelfLogic.previewEligible(integer(c, "type", -1), integer(c, "live", -1))) continue;
                    Item item = read(c);
                    if (!usable(item)) continue;
                    result.recommended.add(item);
                }
            }
            List<Item> continueItems = continueItems(result.watching);
            result.watching.clear();
            result.watching.addAll(continueItems);
            Map<String, Map<Long, ShelfInteractions.Summary>> summaries;
            try { summaries = interactions.readSummaries(); }
            catch (android.database.sqlite.SQLiteException e) {
                android.util.Log.w("ShelfInteractions", "Local summary read failed", e);
                summaries = java.util.Collections.emptyMap();
            }
            rank(result.recommended, result.watching, now, pkg -> LastOpened.get(context, pkg), summaries);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            result.error = e.getClass().getSimpleName();
            result.watching.clear();
            result.recommended.clear();
        }
        return result;
    }

    static List<Item> continueItems(List<Item> watching) {
        List<Item> result = new ArrayList<>(watching);
        result.sort(Comparator.comparingLong((Item i) -> i.engaged).reversed());
        return result;
    }

    static void rank(List<Item> previews, List<Item> watching, long now, Function<String, Long> lastOpened) {
        rank(previews, watching, now, lastOpened, java.util.Collections.emptyMap());
    }

    static void rank(List<Item> previews, List<Item> watching, long now, Function<String, Long> lastOpened,
                     Map<String, Map<Long, ShelfInteractions.Summary>> summaries) {
        long day = now / 86_400_000L;
        for (Item item : previews) {
            Map<Long, ShelfInteractions.Summary> app = summaries.get(item.pkg);
            item.score = ShelfLogic.scoreItem(item, watching, day, now, lastOpened.apply(item.pkg),
                    app == null ? null : app.get(item.id));
        }
        previews.sort(Comparator.comparingLong((Item i) -> i.score).reversed());
    }

    private static Item read(Cursor c) {
        return new Item(number(c, "_id"), text(c, "package_name"), text(c, "title"), text(c, "intent_uri"),
                text(c, "poster_art_uri"), number(c, "last_playback_position_millis"),
                number(c, "duration_millis"), number(c, "last_engagement_time_utc_millis"),
                integer(c, "watch_next_type", -1), integer(c, "type", -1), text(c, "release_date"),
                text(c, "content_id"), text(c, TvContract.WatchNextPrograms.COLUMN_AUTHOR));
    }

    private static boolean usable(Item item) {
        return item.pkg != null && !item.pkg.isEmpty() && item.title != null && !item.title.trim().isEmpty()
                && item.intent != null && !item.intent.isEmpty();
    }

    private static String text(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long number(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private static int integer(Cursor c, String col, int fallback) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? fallback : c.getInt(i);
    }
}
