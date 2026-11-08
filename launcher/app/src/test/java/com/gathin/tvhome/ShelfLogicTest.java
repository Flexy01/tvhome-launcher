package com.gathin.tvhome;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class ShelfLogicTest {
    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> logic;
        try {
            logic = Class.forName("com.gathin.tvhome.ShelfLogic");
        } catch (ClassNotFoundException e) {
            fail("ShelfLogic must filter real viewing data");
            return null;
        }
        Method method = logic.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    @Test public void onlyNonLiveFilmsEpisodesAndClipsBecomeRecommendations() throws Exception {
        Class<?>[] types = {int.class, int.class};
        assertEquals(true, call("previewEligible", types, 0, 0));
        assertEquals(true, call("previewEligible", types, 3, 0));
        assertEquals(false, call("previewEligible", types, 3, 1));
        assertEquals(false, call("previewEligible", types, 3, -1));
        assertEquals(false, call("previewEligible", types, 6, 0));
        assertEquals(false, call("previewEligible", types, 5, 0));
        assertEquals(false, call("previewEligible", types, -1, 0));
    }

    @Test public void continueRequiresActualPositivePlaybackPosition() throws Exception {
        Class<?>[] types = {int.class, long.class, long.class};
        assertEquals(true, call("continueEligible", types, 0, 53000L, 2820000L));
        assertEquals(false, call("continueEligible", types, 0, 0L, 2820000L));
        assertEquals(false, call("continueEligible", types, 1, 53000L, 2820000L));
        assertEquals(false, call("continueEligible", types, 0, 2820000L, 2820000L));
        assertEquals(true, call("continueEligible", types, 0, 53000L, 0L));
    }

    @Test public void shelfMovesUpWhenPermissionGrantExpandsRows() throws Exception {
        Class<?>[] types = {int.class, int.class, int.class, int.class};
        assertEquals(150, call("shelfTop", types, 390, 210, 70, 30));
        assertEquals(270, call("shelfTop", types, 390, 120, 70, 0));
        assertEquals(70, call("shelfTop", types, 250, 210, 70, 30));
    }

    @Test public void posterAndIntentUrisNeverLeakCredentialsOrCrossPackages() throws Exception {
        Class<?>[] imageTypes = {String.class};
        assertEquals(true, call("httpsImage", imageTypes, "https://images.example/poster.jpg"));
        assertEquals(true, call("httpsImage", imageTypes, "https://occ-0-7329-778.1.nflxso.net/poster.jpg?client=det&deviceTypeId=3001&platform=sbn"));
        assertEquals(false, call("httpsImage", imageTypes, "http://images.example/poster.jpg"));
        assertEquals(false, call("httpsImage", imageTypes, "https://user:password@images.example/poster.jpg"));
        Class<?>[] packageTypes = {String.class, String.class};
        assertEquals(true, call("samePackage", packageTypes, "com.example.tv", "com.example.tv"));
        assertEquals(false, call("samePackage", packageTypes, "com.example.tv", "com.other.app"));
        assertEquals("https://images-amazon.com/test_SX1920_.jpg",
                call("upgradeImageUrl", imageTypes, "https://images-amazon.com/test_SX300_.jpg"));
    }

    @Test public void recommendationScoresUseRealViewingAndDailyRotation() throws Exception {
        Class<?>[] types = {long.class, long.class, long.class, long.class, long.class};
        long day = 20000L, now = day * 86400000L;
        long watched = (long) call("score", types, 11L, day, now, now, 0L);
        long unseen = (long) call("score", types, 11L, day, now, 0L, 0L);
        long tomorrow = (long) call("score", types, 11L, day + 1, now, 0L, 0L);
        assertTrue(watched > unseen);
        assertNotEquals(unseen, tomorrow);
    }

    @Test public void differentContentsFromSameAppReceiveDifferentScores() throws Exception {
        long day = 20000L;
        long now = day * 86400000L;
        String pkg = "com.amazon.amazonvideo.livingroom";

        // Watching history has "The Boys" in-progress (50% progress, watch_next_type = 0 CONTINUE)
        ShelfData.Item watchedBoys = new ShelfData.Item(1, pkg, "The Boys Season 3", "intent:boys", null,
                1800000L, 3600000L, now - 3600000L, 0);
        java.util.List<ShelfData.Item> watching = java.util.Collections.singletonList(watchedBoys);

        // Preview items from the SAME app:
        // Item A: "The Boys" (matches watched series)
        ShelfData.Item itemA = new ShelfData.Item(101, pkg, "The Boys Season 3 Episode 4", "intent:boys4", null, 0, 0, 0);
        // Item B: "Rings of Power" (unwatched series in same app)
        ShelfData.Item itemB = new ShelfData.Item(102, pkg, "The Lord of the Rings: The Rings of Power", "intent:rings", null, 0, 0, 0);

        long scoreA = ShelfLogic.scoreItem(itemA, watching, day, now, 0L);
        long scoreB = ShelfLogic.scoreItem(itemB, watching, day, now, 0L);
        assertTrue("Watched series preview should score higher than unwatched series in same app", scoreA > scoreB);
        assertNotEquals("Different contents in same app must not receive identical scores", scoreA, scoreB);
    }

    @Test public void appUsageDoesNotEquateToSeriesWatching() throws Exception {
        long day = 20000L;
        long now = day * 86400000L;
        String pkg = "com.netflix.ninja";

        // User watched "Stranger Things", but NOT "The Witcher"
        ShelfData.Item watchedStranger = new ShelfData.Item(1, pkg, "Stranger Things", "intent:st", null,
                1200000L, 3000000L, now, 0);
        java.util.List<ShelfData.Item> watching = java.util.Collections.singletonList(watchedStranger);

        // "The Witcher" from Netflix: app was opened today, but "The Witcher" itself was NOT watched
        ShelfData.Item witcher = new ShelfData.Item(201, pkg, "The Witcher", "intent:w", null, 0, 0, 0);
        ShelfData.Item strangerNext = new ShelfData.Item(202, pkg, "Stranger Things S4", "intent:st2", null, 0, 0, 0);

        long scoreWitcher = ShelfLogic.scoreItem(witcher, watching, day, now, now);
        long scoreStranger = ShelfLogic.scoreItem(strangerNext, watching, day, now, now);

        // App launch does not elevate an unwatched series to the level of an actually watched series
        assertTrue("Actually watched show must score higher than unrelated show from recently launched app",
                scoreStranger > scoreWitcher);
    }

    @Test public void discoveryBonusAndRepetitionPenaltyDifferentiateUnwatchedItems() throws Exception {
        long day = 20000L;
        long now = day * 86400000L;
        String pkg = "com.other.service";

        // No watching history
        java.util.List<ShelfData.Item> emptyWatching = java.util.Collections.emptyList();

        // Find two IDs in different cycle phases:
        // Math.floorMod(id ^ (day * 31L), 7L):
        long idDiscovery = -1, idRepetition = -1;
        for (long id = 1; id < 100; id++) {
            long phase = Math.floorMod(id ^ (day * 31L), 7L);
            if (phase == 0 && idDiscovery == -1) idDiscovery = id;
            if (phase == 6 && idRepetition == -1) idRepetition = id;
            if (idDiscovery != -1 && idRepetition != -1) break;
        }

        ShelfData.Item freshItem = new ShelfData.Item(idDiscovery, pkg, "Fresh Show", "intent:fresh", null, 0, 0, 0);
        ShelfData.Item staleItem = new ShelfData.Item(idRepetition, pkg, "Stale Show", "intent:stale", null, 0, 0, 0);

        long scoreFresh = ShelfLogic.scoreItem(freshItem, emptyWatching, day, now, 0L);
        long scoreStale = ShelfLogic.scoreItem(staleItem, emptyWatching, day, now, 0L);

        assertTrue("Fresh item in discovery window should outscore stale item subject to repetition penalty",
                scoreFresh > scoreStale);
    }

    @Test public void unmatchedContentCeilingNeverExceeds6499() throws Exception {
        long day = 20000L;
        long now = day * 86400000L;
        String pkg = "com.prime.video";

        // Watching history has a different show in the same package (same-day watch)
        ShelfData.Item watchedShow = new ShelfData.Item(1, pkg, "Watched Show", "intent:w", null, 1000, 2000, now, 0);
        java.util.List<ShelfData.Item> watching = java.util.Collections.singletonList(watchedShow);

        // Test 1000 different IDs and days for unmatched content
        for (long id = 1; id <= 1000; id++) {
            ShelfData.Item unmatched = new ShelfData.Item(id, pkg, "Completely Unmatched Title " + id, "intent:u" + id, null, 0, 0, 0);
            long score = ShelfLogic.scoreItem(unmatched, watching, day, now, now);
            assertTrue("Unmatched content score must never exceed 6499, was: " + score, score <= 6499L);
        }
    }

    @Test public void realContinueContentScoresAbove15000() throws Exception {
        long day = 20000L;
        long now = day * 86400000L;
        String pkg = "com.prime.video";

        // Real CONTINUE: watch_next_type = 0, mid-progress 50%, engaged today
        ShelfData.Item continueItem = new ShelfData.Item(1, pkg, "Fallout", "intent:fo", null,
                1500000L, 3000000L, now, 0);
        java.util.List<ShelfData.Item> watching = java.util.Collections.singletonList(continueItem);

        ShelfData.Item preview = new ShelfData.Item(50, pkg, "Fallout Season 1", "intent:fo1", null, 0, 0, 0);
        long score = ShelfLogic.scoreItem(preview, watching, day, now, 0L);

        assertTrue("Real CONTINUE item must score well above 15000, was: " + score, score >= 15000L);
    }

    @Test public void selectionsAddBoundedInterestWithoutClaimingPlayback() throws Exception {
        long now = 20000L * 86400000L;
        ShelfData.Item item = new ShelfData.Item(11, "test.alpha", "Unwatched", "intent:item", null, 0, 0, 0);
        long base = ShelfLogic.scoreItem(item, java.util.Collections.emptyList(), 20000, now, 0);
        assertEquals(base + 500, scoreWithSummary(item, java.util.Collections.emptyList(), now, 1, 1, now, now));
        assertEquals(base + 1000, scoreWithSummary(item, java.util.Collections.emptyList(), now, 2, 2, now, now));
        assertEquals(base + 1500, scoreWithSummary(item, java.util.Collections.emptyList(), now, Long.MAX_VALUE, Long.MAX_VALUE, now, now));
        assertEquals(0, item.position);
        assertEquals(-1, item.watchNextType);
    }

    @Test public void onlyRepeatedUnselectedImpressionsGetAMildBoundedPenalty() throws Exception {
        long now = 20000L * 86400000L;
        ShelfData.Item item = new ShelfData.Item(11, "test.alpha", "Unwatched", "intent:item", null, 0, 0, 0);
        long base = ShelfLogic.scoreItem(item, java.util.Collections.emptyList(), 20000, now, 0);
        assertEquals(base, scoreWithSummary(item, java.util.Collections.emptyList(), now, 0, 0, 0, 0));
        assertEquals(base, scoreWithSummary(item, java.util.Collections.emptyList(), now, 1, 0, now, 0));
        assertEquals(base, scoreWithSummary(item, java.util.Collections.emptyList(), now, 2, 0, now, 0));
        assertEquals(base - 50, scoreWithSummary(item, java.util.Collections.emptyList(), now, 3, 0, now, 0));
        assertEquals(base - 300, scoreWithSummary(item, java.util.Collections.emptyList(), now, Long.MAX_VALUE, 0, now, 0));
        assertEquals(base + 500, scoreWithSummary(item, java.util.Collections.emptyList(), now, Long.MAX_VALUE, 1, now, now));
    }

    @Test public void interactionInterestDecaysWithoutBeingRefreshedByAnImpression() throws Exception {
        long now = 20000L * 86400000L, week = 7L * 86400000L;
        ShelfData.Item item = new ShelfData.Item(11, "test.alpha", "Unwatched", "intent:item", null, 0, 0, 0);
        long base = ShelfLogic.scoreItem(item, java.util.Collections.emptyList(), 20000, now, 0);
        assertEquals(base + 250, scoreWithSummary(item, java.util.Collections.emptyList(), now, 20, 1, now, now - week));
        assertEquals(base + 50, scoreWithSummary(item, java.util.Collections.emptyList(), now, 20, 1, now, now - 9 * week));
        assertEquals(base - 150, scoreWithSummary(item, java.util.Collections.emptyList(), now, 8, 0, now - week, 0));
        assertEquals(base - 30, scoreWithSummary(item, java.util.Collections.emptyList(), now, 8, 0, now - 9 * week, 0));
        assertEquals(base, scoreWithSummary(item, java.util.Collections.emptyList(), now, 1, 1, now, 0));
        assertEquals(base + 1500, scoreWithSummary(item, java.util.Collections.emptyList(), now, 20, 20, now, now + week));
    }

    @Test public void strongestPreferenceCannotOvertakeEvenAnOldRealContinue() throws Exception {
        long now = 20000L * 86400000L;
        ShelfData.Item watched = new ShelfData.Item(1, "test.alpha", "Real Continue", "intent:watch", null,
                1, 0, now - 365L * 86400000L, 0);
        java.util.List<ShelfData.Item> watching = java.util.Collections.singletonList(watched);
        ShelfData.Item preview = new ShelfData.Item(50, "test.alpha", "Real Continue", "intent:preview", null, 0, 0, 0);
        long continuing = scoreWithSummary(preview, watching, now, Long.MAX_VALUE, 0, now, 0);
        for (long id = 1; id <= 100; id++) {
            ShelfData.Item unrelated = new ShelfData.Item(id, "test.alpha", "Unrelated Selection", "intent:other", null, 0, 0, 0);
            assertTrue(continuing > scoreWithSummary(unrelated, watching, now, 100, 100, now, now));
        }
    }

    private long scoreWithSummary(ShelfData.Item item, java.util.List<ShelfData.Item> watching, long now,
                                  long impressions, long selections, long lastImpression, long lastSelection) throws Exception {
        Class<?> summary;
        try { summary = Class.forName("com.gathin.tvhome.ShelfInteractions$Summary"); }
        catch (ClassNotFoundException e) { throw new AssertionError("scoreItem ignores persistent interaction summaries", e); }
        java.lang.reflect.Constructor<?> constructor = summary.getDeclaredConstructor(long.class, long.class, long.class, long.class);
        constructor.setAccessible(true);
        Object history = constructor.newInstance(impressions, selections, lastImpression, lastSelection);
        return (long) call("scoreItem", new Class<?>[]{ShelfData.Item.class, java.util.List.class, long.class, long.class, long.class, summary},
                item, watching, 20000L, now, 0L, history);
    }

    @Test public void watchlistContentScoresAround12000() throws Exception {
        long day = 20000L;
        long now = day * 86400000L;
        String pkg = "com.netflix.ninja";

        // WATCHLIST: watch_next_type = 3, engaged today, no progress
        ShelfData.Item watchlistItem = new ShelfData.Item(2, pkg, "Wednesday", "intent:wed", null,
                0L, 0L, now, 3);
        java.util.List<ShelfData.Item> watching = java.util.Collections.singletonList(watchlistItem);

        ShelfData.Item preview = new ShelfData.Item(60, pkg, "Wednesday", "intent:wed_prev", null, 0, 0, 0);
        long score = ShelfLogic.scoreItem(preview, watching, day, now, 0L);

        assertTrue("Watchlist item should score around 12000 (11000-13000), was: " + score,
                score >= 11000L && score <= 13000L);
    }
}
