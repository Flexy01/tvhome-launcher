package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PrimeArtworkTest {
    private static final String GTI = "amzn1.dv.gti.8a132dd0-3df2-4bab-93ab-4e64bd720a97";
    private static final String BACKDROP = "https://assets.fanart.tv/fanart/movies/1/moviebackground/1.jpg";
    private static final String OTHER_BACKDROP = "https://assets.fanart.tv/fanart/movies/2/moviebackground/2.jpg";
    private static final Executor DIRECT = Runnable::run;

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private long now = 1_000_000L;
    private PrimeImdb.Result next;
    private String backdrop = BACKDROP;
    private boolean holdSelect;
    private final List<String> verified = new ArrayList<>();
    private final List<String> rowTitles = new ArrayList<>();
    private final List<ShelfIdentity.Query> queries = new ArrayList<>();
    private final List<CompletableFuture<String>> pendingSelects = new ArrayList<>();
    private int changes;

    /** A new instance over the same store file: building one again simulates an app restart. */
    private PrimeArtwork artwork(Executor executor) {
        return new PrimeArtwork(
                (key, title, type) -> { verified.add(key); rowTitles.add(title); return next; },
                query -> {
                    queries.add(query);
                    if (!holdSelect) return CompletableFuture.completedFuture(backdrop);
                    CompletableFuture<String> pending = new CompletableFuture<>();
                    pendingSelects.add(pending);
                    return pending;
                },
                () -> now, executor, () -> changes++, store());
    }

    private File storeFile() {
        return new File(folder.getRoot(), "prime-backdrops.json");
    }

    private PrimeBackdropStore store() {
        return new PrimeBackdropStore(storeFile());
    }

    private JSONArray stored() throws Exception {
        if (!storeFile().isFile()) return new JSONArray();
        return new JSONArray(new String(Files.readAllBytes(storeFile().toPath()), StandardCharsets.UTF_8));
    }

    private static PrimeImdb.Result kesin(boolean yearVerified, boolean typeVerified) {
        return kesin("tt1000001", yearVerified, typeVerified);
    }

    private static PrimeImdb.Result kesin(String imdbId, boolean yearVerified, boolean typeVerified) {
        return new PrimeImdb.Result(PrimeImdb.KESIN, imdbId, yearVerified, typeVerified,
                "Tenet", 2020, ShelfIdentity.Type.MOVIE, "");
    }

    private static PrimeImdb.Result failed(String verdict, String netKind) {
        return new PrimeImdb.Result(verdict, "", false, false, "", 0, ShelfIdentity.Type.UNKNOWN, netKind);
    }

    private static String gti(int i) {
        return String.format("amzn1.dv.gti.%08d-0000-4000-8000-000000000000", i);
    }

    private static void writeAll(File file, String text) {
        try {
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test public void backdropIsShownOnlyWithAVerifiedYearAndType() {
        next = kesin(true, true);
        PrimeArtwork art = artwork(DIRECT);
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        assertEquals(1, changes);
        assertEquals("imdb:tt1000001", queries.get(0).externalId);
        assertNull(queries.get(0).fallbackPoster);
    }

    @Test public void unverifiedYearOrTypeNeverReachesFanart() {
        next = kesin(false, true);
        PrimeArtwork unverifiedYear = artwork(DIRECT);
        assertNull(unverifiedYear.backdropFor(GTI, "", 0));
        next = kesin(true, false);
        PrimeArtwork unverifiedType = artwork(DIRECT);
        assertNull(unverifiedType.backdropFor(GTI, "", 0));
        assertTrue(queries.isEmpty());
        assertEquals(0, changes);
    }

    @Test public void networkErrorIsRetriedAfterTwoMinutes() {
        next = failed(PrimeImdb.NETWORK_ERROR, "HTTP_500@wd-api");
        assertRetriedAfter(PrimeArtwork.NETWORK_RETRY_MS);
    }

    @Test public void noMatchIsRetriedAfterAnHour() {
        next = failed(PrimeImdb.BASARISIZ, "");
        assertRetriedAfter(PrimeArtwork.NO_MATCH_MS);
    }

    private void assertRetriedAfter(long ttl) {
        PrimeArtwork art = artwork(DIRECT);
        art.backdropFor(GTI, "", 0);
        now += ttl - 1;
        art.backdropFor(GTI, "", 0);
        assertEquals(1, verified.size());
        now += 1;
        art.backdropFor(GTI, "", 0);
        assertEquals(2, verified.size());
    }

    @Test public void expiredBackdropStaysOnScreenWhileItIsVerifiedAgain() {
        next = kesin(true, true);
        List<Runnable> tasks = new ArrayList<>();
        PrimeArtwork art = artwork(tasks::add);
        assertNull(art.backdropFor(GTI, "", 0));
        tasks.remove(0).run();
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        now += PrimeArtwork.BACKDROP_MS;
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        assertEquals(1, tasks.size());
    }

    @Test public void transientNetworkErrorKeepsAVerifiedBackdropAndVerifiesItAgainAfterTwoMinutes() {
        next = kesin(true, true);
        PrimeArtwork art = artwork(DIRECT);
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        now += PrimeArtwork.BACKDROP_MS;
        next = failed(PrimeImdb.NETWORK_ERROR, "HTTP_429@wd-api");
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        assertEquals(1, changes);
        now += PrimeArtwork.NETWORK_RETRY_MS - 1;
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        assertEquals(2, verified.size());
        now += 1;
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        assertEquals(3, verified.size());
    }

    @Test public void verifiedMismatchClearsAnExpiredBackdrop() {
        next = kesin(true, true);
        PrimeArtwork art = artwork(DIRECT);
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        now += PrimeArtwork.BACKDROP_MS;
        next = failed(PrimeImdb.BASARISIZ, "");
        assertNull(art.backdropFor(GTI, "", 0));
        assertEquals(2, changes);
    }

    @Test public void backdropThatDoesNotResolveClearsAnExpiredBackdrop() {
        next = kesin(true, true);
        PrimeArtwork art = artwork(DIRECT);
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        now += PrimeArtwork.BACKDROP_MS;
        backdrop = null;
        assertNull(art.backdropFor(GTI, "", 0));
        assertEquals(2, changes);
    }

    @Test public void rowsFocusedQuicklyAreQueuedNotVerifiedInline() {
        List<Runnable> tasks = new ArrayList<>();
        PrimeArtwork art = artwork(tasks::add);
        for (int i = 0; i < 12; i++) assertNull(art.backdropFor(gti(i), "", 0));
        assertEquals(12, tasks.size());
        assertTrue(verified.isEmpty());
    }

    @Test public void repeatedRequestsForOneGtiQueueOneVerification() {
        List<Runnable> tasks = new ArrayList<>();
        PrimeArtwork art = artwork(tasks::add);
        art.backdropFor(GTI, "", 0);
        art.backdropFor(GTI, "", 0);
        assertEquals(1, tasks.size());
    }

    @Test public void unchangedBackdropDoesNotRebindTheRow() {
        next = kesin(true, true);
        PrimeArtwork art = artwork(DIRECT);
        art.backdropFor(GTI, "", 0);
        now += PrimeArtwork.BACKDROP_MS;
        art.backdropFor(GTI, "", 0);
        assertEquals(1, changes);
    }

    @Test public void cacheStaysBoundedAndEvictsTheOldestEntry() {
        next = failed(PrimeImdb.BASARISIZ, "");
        PrimeArtwork art = artwork(DIRECT);
        for (int i = 0; i <= PrimeArtwork.MAX_ENTRIES; i++) art.backdropFor(gti(i), "", 0);
        assertEquals(PrimeArtwork.MAX_ENTRIES + 1, verified.size());
        art.backdropFor(gti(PrimeArtwork.MAX_ENTRIES), "", 0);
        assertEquals(PrimeArtwork.MAX_ENTRIES + 1, verified.size());
        art.backdropFor(gti(0), "", 0);
        assertEquals(PrimeArtwork.MAX_ENTRIES + 2, verified.size());
    }

    @Test public void closedArtworkStartsNoWork() {
        PrimeArtwork art = artwork(DIRECT);
        art.close();
        assertNull(art.backdropFor(GTI, "", 0));
        assertTrue(verified.isEmpty());
    }

    @Test public void onlyPrimeRowsCarryAGti() {
        assertEquals(GTI, PrimeArtwork.keyOf(PrimeArtwork.PRIME_PACKAGE, "content://x?g=" + GTI, null));
        assertNull(PrimeArtwork.keyOf("com.netflix.ninja", "content://x?g=" + GTI, null));
    }

    @Test public void freshStoredBackdropIsShownAfterARestartWithoutVerifyingAgain() {
        next = kesin(true, true);
        artwork(DIRECT).backdropFor(GTI, "", 0);
        verified.clear();
        List<Runnable> tasks = new ArrayList<>();
        assertEquals(BACKDROP, artwork(tasks::add).backdropFor(GTI, "", 0));
        assertTrue(tasks.isEmpty());
        assertTrue(verified.isEmpty());
    }

    @Test public void expiredStoredBackdropIsShownAfterARestartAndVerifiedInTheBackground() {
        next = kesin(true, true);
        artwork(DIRECT).backdropFor(GTI, "", 0);
        now += PrimeArtwork.BACKDROP_MS;
        verified.clear();
        changes = 0;
        List<Runnable> tasks = new ArrayList<>();
        PrimeArtwork restarted = artwork(tasks::add);
        assertEquals(BACKDROP, restarted.backdropFor(GTI, "", 0));
        assertEquals(1, tasks.size());
        assertTrue(verified.isEmpty());
        tasks.remove(0).run();
        assertEquals(1, verified.size());
        assertEquals(BACKDROP, restarted.backdropFor(GTI, "", 0));
        assertEquals(0, changes);
    }

    @Test public void identityMismatchAfterRestartDropsTheStoredBackdrop() {
        next = kesin("tt1000001", true, true);
        artwork(DIRECT).backdropFor(GTI, "", 0);
        now += PrimeArtwork.BACKDROP_MS;
        next = kesin("tt1000009", true, true);
        holdSelect = true;
        List<Runnable> tasks = new ArrayList<>();
        PrimeArtwork restarted = artwork(tasks::add);
        assertEquals(BACKDROP, restarted.backdropFor(GTI, "", 0));
        tasks.remove(0).run();
        assertNull(restarted.backdropFor(GTI, "", 0));
        assertEquals("imdb:tt1000009", queries.get(queries.size() - 1).externalId);
        pendingSelects.get(0).complete(OTHER_BACKDROP);
        assertEquals(OTHER_BACKDROP, restarted.backdropFor(GTI, "", 0));
    }

    @Test public void transientNetworkErrorAfterRestartKeepsTheStoredBackdrop() throws Exception {
        next = kesin(true, true);
        artwork(DIRECT).backdropFor(GTI, "", 0);
        now += PrimeArtwork.BACKDROP_MS;
        next = failed(PrimeImdb.NETWORK_ERROR, "HTTP_429@wd-api");
        assertEquals(BACKDROP, artwork(DIRECT).backdropFor(GTI, "", 0));
        int verifiedBefore = verified.size();
        assertEquals(BACKDROP, artwork(DIRECT).backdropFor(GTI, "", 0));
        assertEquals(verifiedBefore, verified.size());
        assertEquals(1, changes);
        assertEquals(BACKDROP, stored().getJSONObject(0).getString("backdrop"));
    }

    @Test public void noMatchIsNeverPersisted() throws Exception {
        next = kesin(true, true);
        backdrop = null;
        assertNull(artwork(DIRECT).backdropFor(GTI, "", 0));
        assertEquals(0, stored().length());
    }

    @Test public void storedEntryHoldsOnlyTheGtiImdbIdBackdropAndExpiry() throws Exception {
        next = kesin(true, true);
        artwork(DIRECT).backdropFor(GTI, "", 0);
        JSONObject entry = stored().getJSONObject(0);
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = entry.keys(); it.hasNext(); ) keys.add(it.next());
        assertEquals(4, keys.size());
        assertTrue(keys.containsAll(java.util.Arrays.asList("gti", "imdbId", "backdrop", "expiresAt")));
        assertEquals(GTI, entry.getString("gti"));
        assertEquals("tt1000001", entry.getString("imdbId"));
    }

    @Test public void storedEntriesAreBoundedAndTheOldestIsEvicted() throws Exception {
        next = kesin(true, true);
        PrimeArtwork art = artwork(DIRECT);
        for (int i = 0; i <= PrimeArtwork.MAX_ENTRIES; i++) {
            backdrop = "https://assets.fanart.tv/fanart/movies/" + i + "/moviebackground/" + i + ".jpg";
            art.backdropFor(gti(i), "", 0);
        }
        assertEquals(PrimeArtwork.MAX_ENTRIES, stored().length());
        List<Runnable> tasks = new ArrayList<>();
        PrimeArtwork restarted = artwork(tasks::add);
        assertNull(restarted.backdropFor(gti(0), "", 0));
        assertEquals(1, tasks.size());
        assertNotNull(restarted.backdropFor(gti(PrimeArtwork.MAX_ENTRIES), "", 0));
    }

    @Test public void storedBackdropThatIsNotHttpsIsIgnored() {
        JSONObject entry = new JSONObject();
        try {
            entry.put("gti", GTI).put("imdbId", "tt1000001")
                    .put("backdrop", "http://assets.example.test/x.jpg").put("expiresAt", now + 1000);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        writeAll(storeFile(), new JSONArray().put(entry).toString());
        List<Runnable> tasks = new ArrayList<>();
        assertNull(artwork(tasks::add).backdropFor(GTI, "", 0));
        assertEquals(1, tasks.size());
    }

    @Test public void corruptStoreFileIsIgnoredWithoutThrowing() {
        writeAll(storeFile(), "{not json");
        next = kesin(true, true);
        assertEquals(BACKDROP, artwork(DIRECT).backdropFor(GTI, "", 0));
    }

    @Test public void storeWriteFailureKeepsTheInMemoryBackdrop() {
        File blocker = new File(folder.getRoot(), "blocker");
        writeAll(blocker, "a file where the store directory should be");
        next = kesin(true, true);
        PrimeArtwork art = new PrimeArtwork(
                (key, title, type) -> { verified.add(key); rowTitles.add(title); return next; },
                query -> CompletableFuture.completedFuture(backdrop),
                () -> now, DIRECT, () -> changes++,
                new PrimeBackdropStore(new File(blocker, "prime-backdrops.json")));
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        assertEquals(BACKDROP, art.backdropFor(GTI, "", 0));
        assertEquals(1, changes);
    }

    @Test public void selectionFinishedAfterCloseIsNotWritten() {
        next = kesin(true, true);
        holdSelect = true;
        PrimeArtwork art = artwork(DIRECT);
        assertNull(art.backdropFor(GTI, "", 0));
        art.close();
        pendingSelects.get(0).complete(BACKDROP);
        assertFalse(storeFile().exists());
    }

    private static final String NFX_KEY = "netflix:80994082";

    @Test public void aNetflixRowIsKeyedByItsNetflixId() {
        assertEquals(NFX_KEY, PrimeArtwork.keyOf(PrimeArtwork.NETFLIX_PACKAGE, "https://www.netflix.com/title/80994082", null));
        assertEquals(GTI, PrimeArtwork.keyOf(PrimeArtwork.PRIME_PACKAGE, "content://x?g=" + GTI, null));
        assertNull(PrimeArtwork.keyOf("com.example.other", "https://www.netflix.com/title/80994082", null));
    }

    @Test public void netflixBackdropNeedsNoProviderYear() {
        next = kesin(false, true);
        PrimeArtwork art = artwork(DIRECT);
        assertEquals(BACKDROP, art.backdropFor(NFX_KEY, "Lupin", 3));
        assertEquals("Lupin", rowTitles.get(0));
        assertEquals("imdb:tt1000001", queries.get(0).externalId);
    }

    @Test public void primeBackdropStillNeedsAVerifiedYear() {
        next = kesin(false, true);
        assertNull(artwork(DIRECT).backdropFor(GTI, "Tenet", 3));
        assertTrue(queries.isEmpty());
    }

    @Test public void rejectedNetflixRowNeverReachesFanart() {
        next = PrimeImdb.Result.of(PrimeImdb.BELIRSIZ);
        assertNull(artwork(DIRECT).backdropFor(NFX_KEY, "Ghosts", 3));
        assertTrue(queries.isEmpty());
    }
}
