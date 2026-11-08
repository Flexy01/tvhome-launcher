package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ShelfArtworkResolverTest {

    @Test
    public void resolverMatchesCandidateAndUpgradesArtworkUrl() {
        ShelfArtworkResolver.CatalogClient fakeClient = query -> {
            ShelfIdentity.Candidate candidate = new ShelfIdentity.Candidate(
                    "sn1", "Supernatural", 2005, ShelfIdentity.Type.SERIES,
                    "imdb:tt0460681", "https://is1-ssl.mzstatic.com/image/thumb/abc/100x100bb.jpg");
            return Collections.singletonList(candidate);
        };

        ShelfIdentity.Query query = ShelfIdentity.parse(
                "Supernatural - 3. Sezon", "https://app.primevideo.com/watch?g=amzn1.dv.gti.123",
                "https://images.amazon/sn_fallback.jpg", 2, "2007");

        ShelfIdentity.MatchResult result = ShelfArtworkResolver.resolve(query, fakeClient);
        assertFalse("Should be a confident match", result.isFallback);
        assertEquals("sn1", result.matched.id);
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/abc/1920x1080bb.jpg", result.resolvedArtwork);
        assertTrue(result.confidence >= 0.70f);
    }

    @Test
    public void resolverDoesNotAssumeServiceIdsMatchAcrossDifferentCatalogs() {
        // Netflix query with internal Netflix ID 80057281
        ShelfIdentity.Query query = ShelfIdentity.parse(
                "Stranger Things", "nflx://www.netflix.com/title/80057281",
                "https://cdn.netflix/st_fallback.jpg", 1, "2016");

        // Third-party catalog candidate has an unrelated internal iTunes/TMDb numeric ID "80057281"
        // It must NOT falsely match by ID equality across different namespaces!
        ShelfArtworkResolver.CatalogClient fakeClient = q -> {
            ShelfIdentity.Candidate falseCandidate = new ShelfIdentity.Candidate(
                    "other", "Unrelated German Show", 2020, ShelfIdentity.Type.SERIES,
                    "itunes:80057281", "https://is1-ssl.mzstatic.com/image/thumb/other/100x100bb.jpg");
            ShelfIdentity.Candidate trueCandidate = new ShelfIdentity.Candidate(
                    "st", "Stranger Things", 2016, ShelfIdentity.Type.SERIES,
                    "imdb:tt4574334", "https://is1-ssl.mzstatic.com/image/thumb/st/100x100bb.jpg");
            return Arrays.asList(falseCandidate, trueCandidate);
        };

        ShelfIdentity.MatchResult result = ShelfArtworkResolver.resolve(query, fakeClient);
        assertFalse(result.isFallback);
        assertEquals("st", result.matched.id);
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/st/1920x1080bb.jpg", result.resolvedArtwork);
    }

    @Test
    public void resolverFallsBackWhenMatchIsAmbiguous() {
        ShelfArtworkResolver.CatalogClient ambiguousClient = query -> Arrays.asList(
                new ShelfIdentity.Candidate("c1", "Ambiguous Title", 2020, ShelfIdentity.Type.MOVIE, null, "https://art/1.jpg"),
                new ShelfIdentity.Candidate("c2", "Ambiguous Title", 2020, ShelfIdentity.Type.MOVIE, null, "https://art/2.jpg")
        );

        ShelfIdentity.Query query = ShelfIdentity.parse(
                "Ambiguous Title", "intent://amb",
                "https://provider/fallback.jpg", 0, null);

        ShelfIdentity.MatchResult result = ShelfArtworkResolver.resolve(query, ambiguousClient);
        assertTrue("Ambiguous candidates must trigger fallback", result.isFallback);
        assertEquals("https://provider/fallback.jpg", result.resolvedArtwork);
    }

    @Test
    public void resolverFallsBackWhenClientFindsNoCandidates() {
        ShelfArtworkResolver.CatalogClient emptyClient = query -> Collections.emptyList();

        ShelfIdentity.Query query = ShelfIdentity.parse(
                "Nadir Türk Dizisi", "intent://rare",
                "https://provider/rare.jpg", 1, null);

        ShelfIdentity.MatchResult result = ShelfArtworkResolver.resolve(query, emptyClient);
        assertTrue(result.isFallback);
        assertEquals("https://provider/rare.jpg", result.resolvedArtwork);
    }

    @Test
    public void parsesItunesJsonToCandidates() {
        String json = "{\n" +
                "  \"resultCount\": 1,\n" +
                "  \"results\": [\n" +
                "    {\n" +
                "      \"wrapperType\": \"track\",\n" +
                "      \"kind\": \"feature-movie\",\n" +
                "      \"trackId\": 1587841108,\n" +
                "      \"trackName\": \"Dune\",\n" +
                "      \"releaseDate\": \"2021-10-22T07:00:00Z\",\n" +
                "      \"artworkUrl100\": \"https://is1-ssl.mzstatic.com/image/thumb/Video116/v4/dune/100x100bb.jpg\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        List<ShelfIdentity.Candidate> candidates = ShelfArtworkResolver.parseItunesResults(json);
        assertEquals(1, candidates.size());
        ShelfIdentity.Candidate c = candidates.get(0);
        assertEquals("itunes:1587841108", c.id);
        assertEquals("Dune", c.title);
        assertEquals(2021, c.year);
        assertEquals(ShelfIdentity.Type.MOVIE, c.type);
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Video116/v4/dune/1920x1080bb.jpg", c.artworkUri);
    }

    @Test
    public void parsesTvmazeJsonToCandidates() {
        String json = "[\n" +
                "  {\n" +
                "    \"score\": 0.9,\n" +
                "    \"show\": {\n" +
                "      \"id\": 82459,\n" +
                "      \"name\": \"The Mentalist\",\n" +
                "      \"type\": \"Scripted\",\n" +
                "      \"premiered\": \"2008-09-23\",\n" +
                "      \"image\": {\n" +
                "        \"original\": \"https://static.tvmaze.com/uploads/images/original_untouched/0/1239.jpg\"\n" +
                "      },\n" +
                "      \"externals\": {\n" +
                "        \"imdb\": \"tt1196946\"\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "]";

        List<ShelfIdentity.Candidate> candidates = ShelfArtworkResolver.parseTvmazeResults(json);
        assertEquals(1, candidates.size());
        ShelfIdentity.Candidate c = candidates.get(0);
        assertEquals("tvmaze:82459", c.id);
        assertEquals("The Mentalist", c.title);
        assertEquals(2008, c.year);
        assertEquals(ShelfIdentity.Type.SERIES, c.type);
        assertEquals("imdb:tt1196946", c.externalId);
        assertEquals("https://static.tvmaze.com/uploads/images/original_untouched/0/1239.jpg", c.artworkUri);
    }

    // ---- Fanart backdrops ----

    private static final String FA = "https://assets.fanart.tv/fanart/x/";

    private static String bg(String key, String... items) {
        return "{\"name\":\"Dune\",\"imdb_id\":\"tt1160419\",\"tmdb_id\":\"438631\",\"thetvdb_id\":\"81189\",\"" + key + "\":[" + String.join(",", items) + "]}";
    }

    private static String img(long id, String url, String lang, int likes) {
        return "{\"id\":\"" + id + "\",\"url\":\"" + url + "\",\"lang\":\"" + lang + "\",\"likes\":\"" + likes + "\",\"width\":\"1920\",\"height\":\"1080\"}";
    }

    private static ShelfIdentity.Query movieQ(String ext) {
        return new ShelfIdentity.Query("Dune", 2021, ShelfIdentity.Type.MOVIE, ext, "fb");
    }

    private static String dim(String w, String h) {
        StringBuilder sb = new StringBuilder();
        if (w != null) sb.append(",\"width\":\"").append(w).append("\"");
        if (h != null) sb.append(",\"height\":\"").append(h).append("\"");
        return sb.toString();
    }

    private static String entry(long id, String url, String lang, String likes, String w, String h) {
        return "{\"id\":\"" + id + "\",\"url\":\"" + url + "\",\"lang\":\"" + lang + "\",\"likes\":\"" + likes + "\"" + dim(w, h) + "}";
    }

    @Test
    public void fanartParseEnforcesLandscape16x9MinimumFromRealSchema() {
        // RED: parseFanartBackdrops ignores width/height; must keep only 16:9 landscape >= 1280x720.
        String json = bg("moviebackground",
                entry(10, FA + "keep1.jpg", "", "5", "1920", "1080"),
                entry(11, FA + "keep2.jpg", "00", "9", "1280", "720"),
                entry(20, FA + "portrait.jpg", "", "99", "1080", "1920"),
                entry(21, FA + "wide.jpg", "", "99", "1920", "800"),
                entry(22, FA + "small.jpg", "", "99", "640", "360"),
                "{\"id\":\"23\",\"url\":\"" + FA + "nowidth.jpg" + "\",\"lang\":\"\",\"likes\":\"99\",\"height\":\"1080\"}",
                entry(24, FA + "badheight.jpg", "", "99", "1920", "abc"),
                entry(25, FA + "zero.jpg", "", "99", "0", "1080"),
                entry(26, FA + "en.jpg", "en", "99", "1920", "1080"),
                "{\"id\":\"27\",\"url\":\"http://assets.fanart.tv/fanart/x/http.jpg\",\"lang\":\"\",\"likes\":\"99\",\"width\":\"1920\",\"height\":\"1080\"}",
                entry(28, "https://evil.example/x.jpg", "", "99", "1920", "1080"));
        assertEquals(Arrays.asList(FA + "keep2.jpg", FA + "keep1.jpg"),
                ShelfArtworkResolver.parseFanartBackdrops(json, false));
    }

    @Test
    public void fanartParseAcceptsNumericDimensionsAndRejectsCorruptSafely() {
        // RED: string-only or missing dimension handling; numeric 1920/1080 must pass, corrupt must not throw.
        String json = bg("moviebackground",
                "{\"id\":\"30\",\"url\":\"" + FA + "num.jpg" + "\",\"lang\":\"\",\"likes\":3,\"width\":1920,\"height\":1080}",
                "{\"id\":\"31\",\"url\":\"" + FA + "nullw.jpg" + "\",\"lang\":\"\",\"likes\":\"3\",\"width\":null,\"height\":\"1080\"}",
                entry(32, FA + "empty.jpg", "", "3", "", ""),
                entry(33, FA + "blank.jpg", "", "3", " ", "1080"));
        assertEquals(Collections.singletonList(FA + "num.jpg"),
                ShelfArtworkResolver.parseFanartBackdrops(json, false));
        assertTrue(ShelfArtworkResolver.parseFanartBackdrops(null, false).isEmpty());
        assertTrue(ShelfArtworkResolver.parseFanartBackdrops("  ", true).isEmpty());
    }

    @Test
    public void fanartParseKeepsTextlessHttpsAssetsSortedByLikes() {
        String json = bg("moviebackground",
                img(1, FA + "en.jpg", "en", 99),
                img(2, FA + "low.jpg", "00", 1),
                img(3, "http://assets.fanart.tv/x.jpg", "00", 50),
                img(4, "https://evil.example/x.jpg", "00", 50),
                img(6, FA + "tie-b.jpg", "", 7),
                img(5, FA + "tie-a.jpg", "00", 7));
        assertEquals(Arrays.asList(FA + "tie-a.jpg", FA + "tie-b.jpg", FA + "low.jpg"),
                ShelfArtworkResolver.parseFanartBackdrops(json, false));
        assertTrue(ShelfArtworkResolver.parseFanartBackdrops(json, true).isEmpty());
        assertTrue(ShelfArtworkResolver.parseFanartBackdrops("{bad", false).isEmpty());
    }

    @Test
    public void fanartMovieImdbMatchReturnsUrls() {
        ShelfArtworkResolver.FanartClient c = path ->
                path.equals("movies/tt1160419") ? bg("moviebackground", img(1, FA + "a.jpg", "00", 3)) : null;
        assertEquals(Collections.singletonList(FA + "a.jpg"),
                ShelfArtworkResolver.resolveBackdrops(movieQ("imdb:tt1160419"), c));
    }

    @Test
    public void fanartMovieTmdbMatchReturnsUrls() {
        ShelfArtworkResolver.FanartClient c = path ->
                path.equals("movies/438631") ? bg("moviebackground", img(1, FA + "a.jpg", "00", 3)) : null;
        assertEquals(1, ShelfArtworkResolver.resolveBackdrops(movieQ("tmdb:438631"), c).size());
    }

    @Test
    public void fanartRejectsMismatchedMissingOrWrongTypeIdentity() {
        // response is for another production than the requested ID
        ShelfArtworkResolver.FanartClient other = path -> bg("moviebackground", img(1, FA + "a.jpg", "00", 3))
                .replace("tt1160419", "tt9999999");
        assertTrue(ShelfArtworkResolver.resolveBackdrops(movieQ("imdb:tt1160419"), other).isEmpty());
        ShelfArtworkResolver.FanartClient ok = path -> bg("moviebackground", img(1, FA + "a.jpg", "00", 3));
        assertTrue(ShelfArtworkResolver.resolveBackdrops(movieQ(null), ok).isEmpty());
        assertTrue(ShelfArtworkResolver.resolveBackdrops(movieQ("netflix:80057281"), ok).isEmpty());
        ShelfIdentity.Query unknown = new ShelfIdentity.Query("Dune", 2021, ShelfIdentity.Type.UNKNOWN, "imdb:tt1160419", "fb");
        assertTrue(ShelfArtworkResolver.resolveBackdrops(unknown, ok).isEmpty());
        // movie ID asked for a series query: lookup has no matching show
        ShelfIdentity.Query series = new ShelfIdentity.Query("Dune", 2021, ShelfIdentity.Type.SERIES, "imdb:tt1160419", "fb");
        assertTrue(ShelfArtworkResolver.resolveBackdrops(series, ok).isEmpty());
    }

    @Test
    public void fanartSeriesGoesThroughTvmazeLookup() {
        List<String> calls = new ArrayList<>();
        ShelfArtworkResolver.FanartClient c = path -> {
            calls.add(path);
            if (path.equals("tvmaze/lookup/shows?imdb=tt0460681"))
                return "{\"externals\":{\"thetvdb\":78901,\"imdb\":\"tt0460681\"}}";
            if (path.equals("tv/78901"))
                return "{\"name\":\"Supernatural\",\"thetvdb_id\":\"78901\",\"showbackground\":["
                        + img(1, FA + "s.jpg", "00", 2) + "]}";
            return null;
        };
        ShelfIdentity.Query q = new ShelfIdentity.Query("Supernatural", 2005, ShelfIdentity.Type.SERIES, "imdb:tt0460681", "fb");
        assertEquals(Collections.singletonList(FA + "s.jpg"), ShelfArtworkResolver.resolveBackdrops(q, c));
        // lookup returned a different show's IMDb: reject
        ShelfArtworkResolver.FanartClient wrong = path -> path.startsWith("tvmaze/")
                ? "{\"externals\":{\"thetvdb\":1,\"imdb\":\"tt0000001\"}}"
                : "{\"name\":\"X\",\"showbackground\":[" + img(1, FA + "s.jpg", "00", 2) + "]}";
        assertTrue(ShelfArtworkResolver.resolveBackdrops(q, wrong).isEmpty());
    }

    @Test
    public void fanartEmptyKeyNeverCallsNetworkAndClientErrorsGiveEmpty() {
        ShelfArtworkResolver.DefaultFanartClient none = new ShelfArtworkResolver.DefaultFanartClient("  ");
        assertNull(none.get("movies/tt1160419"));
        assertNull(new ShelfArtworkResolver.DefaultFanartClient(null).get("tvmaze/lookup/shows?imdb=tt1"));
        ShelfArtworkResolver.FanartClient boom = path -> { throw new IllegalStateException("x"); };
        assertTrue(ShelfArtworkResolver.resolveBackdrops(movieQ("imdb:tt1160419"), boom).isEmpty());
        assertTrue(ShelfArtworkResolver.resolveBackdrops(movieQ("imdb:tt1160419"), null).isEmpty());
    }

    @Test
    public void fanartPersonalKeyGoesInClientKeyHeaderNeverInUrl() {
        // RED: fanartHeaders/fanartUrl do not exist yet; fails until GREEN.
        java.util.Map<String, String> headers = ShelfArtworkResolver.fanartHeaders("PERSONAL_Y");
        assertEquals("PERSONAL_Y", headers.get("client-key"));
        assertEquals(1, headers.size());
        assertTrue(ShelfArtworkResolver.fanartHeaders(null).isEmpty());
        assertTrue(ShelfArtworkResolver.fanartHeaders("  ").isEmpty());

        String bare = ShelfArtworkResolver.fanartUrl("movies/tt1160419", null);
        assertTrue(bare.startsWith("https://webservice.fanart.tv/v3.2/movies/tt1160419"));
        assertFalse("no query string without a project key", bare.contains("?"));
        assertFalse(bare.contains("PERSONAL_Y"));

        String withProject = ShelfArtworkResolver.fanartUrl("movies/tt1160419", "PROJECT_X");
        assertTrue(withProject.contains("?api_key=PROJECT_X"));
        assertFalse("personal key must never be placed in the URL", withProject.contains("PERSONAL_Y"));
    }

    @Test
    public void fanartMissingKeysFallBackWithoutNetwork() {
        // RED: two-arg constructor does not exist yet; fails until GREEN.
        assertNull(new ShelfArtworkResolver.DefaultFanartClient(null, null).get("movies/tt1160419"));
        assertNull(new ShelfArtworkResolver.DefaultFanartClient(" ", " ").get("tv/1"));
        assertTrue(ShelfArtworkResolver.resolveBackdrops(movieQ("imdb:tt1160419"),
                new ShelfArtworkResolver.DefaultFanartClient(null, null)).isEmpty());
    }

    @Test
    public void fanartKeysSeparateProjectAndPersonalWithLegacyFallback() {
        // RED: readFanartKeys/fanartClient do not exist yet; fails until GREEN.
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("fakeys").toFile();
            java.io.File f = new java.io.File(dir, "config.json");
            java.nio.file.Files.write(f.toPath(),
                    "{\"fanart_project_key\":\"PROJ_X\",\"fanart_personal_key\":\"PERS_Y\"}".getBytes("UTF-8"));
            ShelfArtworkResolver.FanartKeys k = ShelfArtworkResolver.readFanartKeys(dir);
            assertEquals("PROJ_X", k.project);
            assertEquals("PERS_Y", k.personal);
            assertFalse(k.isEmpty());
            assertFalse(k.toString().contains("PROJ_X"));
            assertFalse(k.toString().contains("PERS_Y"));
            // routing through the real reader: project only in URL query, personal only in header
            String url = ShelfArtworkResolver.fanartUrl("movies/tt1160419", k.project);
            assertTrue(url.contains("api_key=PROJ_X"));
            assertFalse(url.contains("PERS_Y"));
            java.util.Map<String, String> h = ShelfArtworkResolver.fanartHeaders(k.personal);
            assertEquals("PERS_Y", h.get("client-key"));
            assertFalse(h.values().contains("PROJ_X"));
            // legacy single field held the PERSONAL key: must route to header, never to ?api_key=
            java.nio.file.Files.write(f.toPath(), "{\"fanart_api_key\":\"LEGACY\"}".getBytes("UTF-8"));
            ShelfArtworkResolver.FanartKeys l = ShelfArtworkResolver.readFanartKeys(dir);
            assertTrue("legacy key must never become the project key", l.project.isEmpty());
            assertEquals("LEGACY", l.personal);
            assertEquals("LEGACY", ShelfArtworkResolver.readApiKey(dir));
            String legacyUrl = ShelfArtworkResolver.fanartUrl("movies/tt1160419", l.project.isEmpty() ? null : l.project);
            assertFalse("no query string for legacy personal key", legacyUrl.contains("?"));
            assertFalse(legacyUrl.contains("LEGACY"));
            assertEquals("LEGACY", ShelfArtworkResolver.fanartHeaders(l.personal).get("client-key"));
            // explicit new fields win; legacy fills personal only when personal absent
            java.nio.file.Files.write(f.toPath(), "{\"fanart_project_key\":\"PROJ_X\",\"fanart_api_key\":\"LEGACY\"}".getBytes("UTF-8"));
            ShelfArtworkResolver.FanartKeys m = ShelfArtworkResolver.readFanartKeys(dir);
            assertEquals("PROJ_X", m.project);
            assertEquals("LEGACY", m.personal);
            java.nio.file.Files.write(f.toPath(), "{\"fanart_personal_key\":\"PERS_Y\",\"fanart_api_key\":\"LEGACY\"}".getBytes("UTF-8"));
            ShelfArtworkResolver.FanartKeys n = ShelfArtworkResolver.readFanartKeys(dir);
            assertTrue(n.project.isEmpty());
            assertEquals("PERS_Y", n.personal);
            // no keys: empty holder, factory client never touches the network
            java.nio.file.Files.write(f.toPath(), "{}".getBytes("UTF-8"));
            assertTrue(ShelfArtworkResolver.readFanartKeys(dir).isEmpty());
            assertNull(ShelfArtworkResolver.readApiKey(dir));
            assertNull(ShelfArtworkResolver.fanartClient(dir).get("movies/tt1160419"));
            assertTrue(ShelfArtworkResolver.fanartClient(null).get("movies/tt1160419") == null);
            f.delete();
            dir.delete();
        } catch (java.io.IOException e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void fanartDimensionsRejectAbsurdValuesWithoutOverflow() {
        // RED: no upper bound; 200000x112500 (valid 16:9) must be rejected before cross-multiplying.
        String json = bg("moviebackground",
                entry(40, FA + "8k.jpg", "", "9", "7680", "4320"),
                entry(41, FA + "huge.jpg", "", "99", "200000", "112500"),
                "{\"id\":\"42\",\"url\":\"" + FA + "of.jpg" + "\",\"lang\":\"\",\"likes\":\"99\",\"width\":\"99999999999999999999\",\"height\":\"1080\"}");
        assertEquals(Collections.singletonList(FA + "8k.jpg"),
                ShelfArtworkResolver.parseFanartBackdrops(json, false));
    }

    @Test
    public void fanartSelectionCapsCandidatesAndFallsBackToProviderPoster() {
        // RED: selectFanartBackdrop does not exist yet; fails until GREEN.
        String json = bg("moviebackground",
                entry(1, FA + "a.jpg", "00", "1", "1920", "1080"),
                entry(2, FA + "b.jpg", "00", "2", "1920", "1080"),
                entry(3, FA + "c.jpg", "00", "3", "1920", "1080"),
                entry(4, FA + "d.jpg", "00", "4", "1920", "1080"),
                entry(5, FA + "e.jpg", "00", "5", "1920", "1080"),
                entry(6, FA + "f.jpg", "00", "6", "1920", "1080"),
                entry(7, FA + "g.jpg", "00", "7", "1920", "1080"),
                entry(8, FA + "h.jpg", "00", "8", "1920", "1080"));
        ShelfArtworkResolver.FanartClient c = path -> path.equals("movies/tt1160419") ? json : null;
        ShelfIdentity.Query q = movieQ("imdb:tt1160419");
        java.util.List<String> evaluated = new java.util.ArrayList<>();
        String winner = ShelfArtworkResolver.selectFanartBackdrop(q, c,
                (url, likes) -> { evaluated.add(url); return 0.5f; });
        assertEquals(FA + "h.jpg", winner); // likes already sorted desc; metadata tie-break by score
        assertEquals(6, evaluated.size()); // capped at 6 candidates
        assertEquals(FA + "h.jpg", evaluated.get(0));
        // all candidates fail: provider poster fallback
        assertEquals("fb", ShelfArtworkResolver.selectFanartBackdrop(q, c, (url, likes) -> -1.0f));
        assertEquals("fb", ShelfArtworkResolver.selectFanartBackdrop(q, path -> null, (url, likes) -> 0.9f));
        assertNull(ShelfArtworkResolver.selectFanartBackdrop(null, c, (url, likes) -> 0.9f));
    }

    @Test
    public void fanartScorerRejectsCorruptEmptyAndTimedOutImages() {
        ShelfArtworkResolver.ImageDecoder ok = bytes -> new ShelfArtworkResolver.DecodedImage(64, 36,
                new ShelfArtworkSelectorTest.MockPixelSource(64, 36, 0xFF142030), 1920, 1080);
        assertTrue(ShelfArtworkResolver.scoringFanartScorer(url -> new byte[]{1}, ok, 4)
                .score(FA + "x.jpg", 0) > 0.5f);
        assertTrue(ShelfArtworkResolver.scoringFanartScorer(url -> new byte[]{9}, bytes -> null, 4)
                .score(FA + "x.jpg", 0) < 0);
        assertTrue(ShelfArtworkResolver.scoringFanartScorer(url -> null, ok, 4)
                .score(FA + "x.jpg", 0) < 0);
        assertTrue(ShelfArtworkResolver.scoringFanartScorer(url -> { throw new java.net.SocketTimeoutException(); }, ok, 4)
                .score(FA + "x.jpg", 0) < 0);
        assertTrue(ShelfArtworkResolver.scoringFanartScorer(url -> new byte[]{1},
                bytes -> new ShelfArtworkResolver.DecodedImage(0, 0, null, 0, 0), 4)
                .score(FA + "x.jpg", 0) < 0);
        assertTrue(ShelfArtworkResolver.scoringFanartScorer(url -> new byte[]{1},
                bytes -> new ShelfArtworkResolver.DecodedImage(961, 540,
                        new ShelfArtworkSelectorTest.MockPixelSource(64, 36, 0xFF142030), 1920, 1080), 4)
                .score(FA + "x.jpg", 0) < 0);
    }

    @Test
    public void fanartAsyncSelectionRunsOffCallerAndSerializesRequests() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch secondEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> worker = new java.util.concurrent.atomic.AtomicReference<>();
        ShelfArtworkResolver.FanartClient first = path -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        };
        java.util.concurrent.CompletableFuture<String> one = ShelfArtworkResolver.selectFanartBackdropAsync(
                movieQ("imdb:tt1160419"), first);
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), worker.get());
            java.util.concurrent.CompletableFuture<String> two = ShelfArtworkResolver.selectFanartBackdropAsync(
                    movieQ("imdb:tt1160419"), path -> { secondEntered.countDown(); return null; });
            try {
                assertFalse("another request must not download concurrently",
                        secondEntered.await(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertEquals("fb", one.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("fb", two.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void fanartScorerReleasesDecodedImagesOnSuccessAndFailure() {
        java.util.concurrent.atomic.AtomicInteger released = new java.util.concurrent.atomic.AtomicInteger();
        ShelfArtworkResolver.ImageDecoder good = bytes -> new ShelfArtworkResolver.DecodedImage(64, 36,
                new ShelfArtworkSelectorTest.MockPixelSource(64, 36, 0xFF142030), 1920, 1080,
                released::incrementAndGet);
        assertTrue(ShelfArtworkResolver.scoringFanartScorer(url -> new byte[]{1}, good, 0)
                .score(FA + "x.jpg", 0) >= 0);
        assertEquals(1, released.get());
        ShelfArtworkResolver.ImageDecoder bad = bytes -> new ShelfArtworkResolver.DecodedImage(64, 36,
                new ShelfArtworkSelector.PixelSource() {
                    @Override public int getWidth() { return 64; }
                    @Override public int getHeight() { return 36; }
                    @Override public int getPixel(int x, int y) { throw new IllegalStateException(); }
                }, 1920, 1080, released::incrementAndGet);
        assertTrue(ShelfArtworkResolver.scoringFanartScorer(url -> new byte[]{1}, bad, 0)
                .score(FA + "x.jpg", 0) < 0);
        assertEquals(2, released.get());
    }

    @Test
    public void fanartSelectionSkipsInvalidScoresAndContinuesAfterCandidateFailure() {
        String json = bg("moviebackground",
                entry(1, FA + "nan.jpg", "", "5", "1920", "1080"),
                entry(2, FA + "infinite.jpg", "", "4", "1920", "1080"),
                entry(3, FA + "throws.jpg", "", "3", "1920", "1080"),
                entry(4, FA + "negative.jpg", "", "2", "1920", "1080"),
                entry(5, FA + "valid.jpg", "", "1", "1920", "1080"));
        ShelfArtworkResolver.FanartClient client = path -> json;
        assertEquals(FA + "valid.jpg", ShelfArtworkResolver.selectFanartBackdrop(movieQ("imdb:tt1160419"), client,
                (url, likes) -> {
                    if (url.contains("nan")) return Float.NaN;
                    if (url.contains("infinite")) return Float.POSITIVE_INFINITY;
                    if (url.contains("throws")) throw new IllegalStateException();
                    if (url.contains("negative")) return -1;
                    return 0.1f;
                }));
        assertEquals("fb", ShelfArtworkResolver.selectFanartBackdrop(movieQ("imdb:tt1160419"), client,
                (url, likes) -> Float.NaN));
    }

    @Test
    public void fanartKeyNeverLeaksViaToStringOrExceptionMessage() {
        ShelfArtworkResolver.DefaultFanartClient c = new ShelfArtworkResolver.DefaultFanartClient("SECRET123");
        assertFalse(c.toString().contains("SECRET123"));
        assertNull(ShelfArtworkResolver.readApiKey(null));
        try {
            java.io.File dir = java.nio.file.Files.createTempDirectory("fa").toFile();
            assertNull(ShelfArtworkResolver.readApiKey(dir));
            java.io.File f = new java.io.File(dir, "config.json");
            java.nio.file.Files.write(f.toPath(), "{\"fanart_api_key\":\" SECRET123 \"}".getBytes("UTF-8"));
            assertEquals("SECRET123", ShelfArtworkResolver.readApiKey(dir));
            java.nio.file.Files.write(f.toPath(), "{not json SECRET123".getBytes("UTF-8"));
            assertNull(ShelfArtworkResolver.readApiKey(dir));
            f.delete();
            dir.delete();
        } catch (java.io.IOException e) {
            fail(e.getMessage());
        }
    }
}
