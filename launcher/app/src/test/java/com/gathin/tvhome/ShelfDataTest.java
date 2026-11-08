package com.gathin.tvhome;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ShelfDataTest {
    private ShelfData.Item item(long id, String pkg, String title, long position, long duration, long engaged) {
        return new ShelfData.Item(id, pkg, title, "intent://item", "https://images.example/poster.jpg", position, duration, engaged);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method;
        try { method = ShelfData.class.getDeclaredMethod(name, types); }
        catch (NoSuchMethodException e) { fail("ShelfData must apply " + name + " to provider snapshots"); return null; }
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    @Test public void providerArtworkQueryKeepsTypeReleaseDateAndExternalIdentity() throws Exception {
        for (String projection : new String[]{"WATCH", "PREVIEW"}) {
            java.lang.reflect.Field field = ShelfData.class.getDeclaredField(projection);
            field.setAccessible(true);
            List<String> columns = Arrays.asList((String[]) field.get(null));
            org.junit.Assert.assertTrue(projection + " missing type", columns.contains("type"));
            org.junit.Assert.assertTrue(projection + " missing release_date", columns.contains("release_date"));
            org.junit.Assert.assertTrue(projection + " missing author", columns.contains("author"));
        }
        java.lang.reflect.Constructor<ShelfData.Item> constructor;
        try {
            constructor = ShelfData.Item.class.getDeclaredConstructor(long.class, String.class, String.class,
                    String.class, String.class, long.class, long.class, long.class, int.class, int.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("provider metadata must reach artwork query", e);
        }
        ShelfData.Item movie = constructor.newInstance(1L, "provider", "Dune",
                "https://www.imdb.com/title/tt1160419", "provider-poster", 0L, 0L, 0L, -1, 0, "2021-10-22");
        Method artworkQuery = ShelfData.Item.class.getDeclaredMethod("artworkQuery");
        ShelfIdentity.Query query = (ShelfIdentity.Query) artworkQuery.invoke(movie);
        assertEquals("imdb:tt1160419", query.externalId);
        assertEquals(ShelfIdentity.Type.MOVIE, query.type);
        assertEquals(2021, query.year);
        assertEquals("provider-poster", query.fallbackPoster);
        ShelfData.Item netflix = constructor.newInstance(2L, "com.netflix.ninja", "Dune",
                "nflx://www.netflix.com/title/81198930", "provider-poster", 0L, 0L, 0L, -1, 0, "2021");
        ShelfIdentity.Query netflixQuery = (ShelfIdentity.Query) artworkQuery.invoke(netflix);
        assertEquals("netflix:81198930", netflixQuery.externalId);
        assertEquals("provider-poster", ShelfArtworkResolver.selectFanartBackdrop(netflixQuery, path -> {
            fail("unsupported provider ID must not be sent to Fanart");
            return null;
        }, (url, rank) -> 1f));
    }

    @Test public void changesToPlaybackPositionChangeTheContinueSnapshot() throws Exception {
        ShelfData.Item watched = item(1, "prime", "Episode", 53000, 100000, 20);
        ShelfData.Item updated = item(1, "prime", "Episode", 79000, 100000, 30);
        Class<?>[] types = {List.class};
        List<?> first = (List<?>) call("continueItems", types, Arrays.asList(watched));
        List<?> second = (List<?>) call("continueItems", types, Arrays.asList(updated));
        assertEquals(53000, ((ShelfData.Item) first.get(0)).position);
        assertEquals(79000, ((ShelfData.Item) second.get(0)).position);
    }

    @Test public void summaryInterestUsesBothPackageAndContentIdWithoutAccumulatingOnRerank() throws Exception {
        long now = 20000L * 86400000L;
        ShelfData.Item selected = item(11, "prime", "Selected", 0, 0, 0);
        ShelfData.Item ignored = item(12, "prime", "Ignored", 0, 0, 0);
        ShelfData.Item otherApp = item(11, "other", "Same ID", 0, 0, 0);
        List<ShelfData.Item> previews = new ArrayList<>(Arrays.asList(selected, ignored, otherApp));
        call("rank", new Class<?>[]{List.class, List.class, long.class, Function.class},
                previews, java.util.Collections.emptyList(), now, (Function<String, Long>) pkg -> 0L);
        long selectedBase = selected.score, ignoredBase = ignored.score, otherBase = otherApp.score;
        Class<?> summary;
        try { summary = Class.forName("com.gathin.tvhome.ShelfInteractions$Summary"); }
        catch (ClassNotFoundException e) { throw new AssertionError("rank ignores persistent interaction summaries", e); }
        java.lang.reflect.Constructor<?> constructor = summary.getDeclaredConstructor(long.class, long.class, long.class, long.class);
        constructor.setAccessible(true);
        java.util.Map<Long, Object> appHistory = new java.util.HashMap<>();
        appHistory.put(11L, constructor.newInstance(1L, 1L, now, now));
        appHistory.put(12L, constructor.newInstance(3L, 0L, now, 0L));
        java.util.Map<String, java.util.Map<Long, Object>> history = java.util.Collections.singletonMap("prime", appHistory);
        for (int i = 0; i < 2; i++) {
            call("rank", new Class<?>[]{List.class, List.class, long.class, Function.class, java.util.Map.class},
                    previews, java.util.Collections.emptyList(), now, (Function<String, Long>) pkg -> 0L, history);
            assertEquals(selectedBase + 500, selected.score);
            assertEquals(ignoredBase - 50, ignored.score);
            assertEquals(otherBase, otherApp.score);
        }
    }

    @Test public void recommendationsPrioritizeRecentlyWatchedService() throws Exception {
        long now = 20000L * 86400000L;
        ShelfData.Item prime = item(1, "prime", "Episode", 53000, 100000, now);
        ShelfData.Item other = item(2, "other", "Other", 4000, 100000, now - 86400000L);
        List<ShelfData.Item> previews = new ArrayList<>(Arrays.asList(
                item(10, "other", "Other suggestion", 0, 0, 0),
                item(11, "prime", "Prime suggestion", 0, 0, 0)));
        call("rank", new Class<?>[]{List.class, List.class, long.class, Function.class},
                previews, Arrays.asList(prime, other), now, (Function<String, Long>) pkg -> 0L);
        assertEquals("prime", previews.get(0).pkg);
    }

    private static android.database.Cursor mapCursor(final java.util.Map<String, Object> row) {
        final List<String> keys = new ArrayList<>(row.keySet());
        return (android.database.Cursor) java.lang.reflect.Proxy.newProxyInstance(
                android.database.Cursor.class.getClassLoader(),
                new Class<?>[]{android.database.Cursor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getColumnIndex".equals(name)) {
                        return keys.indexOf((String) args[0]);
                    }
                    if ("isNull".equals(name)) {
                        int idx = (Integer) args[0];
                        return idx < 0 || idx >= keys.size() || row.get(keys.get(idx)) == null;
                    }
                    if ("getString".equals(name)) {
                        Object val = row.get(keys.get((Integer) args[0]));
                        return val == null ? null : String.valueOf(val);
                    }
                    if ("getLong".equals(name)) {
                        Object val = row.get(keys.get((Integer) args[0]));
                        return val instanceof Number ? ((Number) val).longValue() : 0L;
                    }
                    if ("getInt".equals(name)) {
                        Object val = row.get(keys.get((Integer) args[0]));
                        return val instanceof Number ? ((Number) val).intValue() : 0;
                    }
                    return null;
                });
    }

    @Test public void itemConstructorsAndCursorReadPreserveAuthorSafely() throws Exception {
        ShelfData.Item c8 = new ShelfData.Item(1L, "pkg", "Title", "intent:1", "poster", 10L, 100L, 50L);
        assertNull(c8.author);
        assertNull(c8.contentId);
        assertNull(c8.releaseDate);
        assertEquals(-1, c8.watchNextType);
        assertEquals(-1, c8.type);

        ShelfData.Item c9 = new ShelfData.Item(2L, "pkg", "Title", "intent:2", "poster", 10L, 100L, 50L, 0);
        assertNull(c9.author);
        assertEquals(0, c9.watchNextType);

        ShelfData.Item c11 = new ShelfData.Item(3L, "pkg", "Title", "intent:3", "poster", 10L, 100L, 50L, 0, 4, "2025");
        assertNull(c11.author);
        assertNull(c11.contentId);
        assertEquals(4, c11.type);
        assertEquals("2025", c11.releaseDate);

        ShelfData.Item c12 = new ShelfData.Item(4L, "pkg", "Title", "intent:4", "poster", 10L, 100L, 50L, 0, 4, "2025", "cid-4");
        assertNull(c12.author);
        assertEquals("cid-4", c12.contentId);

        ShelfData.Item c13 = new ShelfData.Item(5L, "com.google.android.youtube.tv", "Video", "intent:5", "poster",
                10L, 100L, 50L, 0, 4, "2025", "cid-5", "Orkun Işıtmak");
        assertEquals("Orkun Işıtmak", c13.author);
        assertEquals("cid-5", c13.contentId);

        Method read = ShelfData.class.getDeclaredMethod("read", android.database.Cursor.class);
        read.setAccessible(true);

        java.util.Map<String, Object> populatedRow = new java.util.LinkedHashMap<>();
        populatedRow.put("_id", 579L);
        populatedRow.put("package_name", "com.google.android.youtube.tv");
        populatedRow.put("title", "Test Video");
        populatedRow.put("intent_uri", "https://www.youtube.com/watch?v=CkcGpXnt1uI");
        populatedRow.put("poster_art_uri", "https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg");
        populatedRow.put("last_playback_position_millis", 1778000L);
        populatedRow.put("duration_millis", 2579000L);
        populatedRow.put("last_engagement_time_utc_millis", 1791106262000L);
        populatedRow.put("watch_next_type", 0);
        populatedRow.put("type", 4);
        populatedRow.put("release_date", null);
        populatedRow.put("content_id", "yt-cid");
        populatedRow.put("author", "Orkun Işıtmak");

        ShelfData.Item fromPopulated = (ShelfData.Item) read.invoke(null, mapCursor(populatedRow));
        assertEquals("Orkun Işıtmak", fromPopulated.author);
        assertEquals("yt-cid", fromPopulated.contentId);

        java.util.Map<String, Object> nullAuthorRow = new java.util.LinkedHashMap<>(populatedRow);
        nullAuthorRow.put("author", null);
        ShelfData.Item fromNullAuthor = (ShelfData.Item) read.invoke(null, mapCursor(nullAuthorRow));
        assertNull(fromNullAuthor.author);

        java.util.Map<String, Object> missingAuthorColumnRow = new java.util.LinkedHashMap<>(populatedRow);
        missingAuthorColumnRow.remove("author");
        missingAuthorColumnRow.remove("content_id");
        ShelfData.Item fromMissingColumn = (ShelfData.Item) read.invoke(null, mapCursor(missingAuthorColumnRow));
        assertNull(fromMissingColumn.author);
        assertNull(fromMissingColumn.contentId);
        assertTrue(fromMissingColumn.id == 579L);
    }
}
