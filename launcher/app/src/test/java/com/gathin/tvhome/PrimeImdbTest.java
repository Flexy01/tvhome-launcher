package com.gathin.tvhome;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PrimeImdbTest {
    private static final String GTI = "amzn1.dv.gti.8a132dd0-3df2-4bab-93ab-4e64bd720a97";
    private static final String SUG = "https://v3.sg.media-imdb.com/suggestion/x/";
    private static final PrimeImdb.Sleep NO_WAIT = ms -> { };

    // Fixture answers. The ids are invented for the tests; production ids come only from lookups.
    private static final String MOVIE_SUG = "{\"d\":[{\"id\":\"tt1000001\",\"l\":\"Tenet\",\"y\":2020,\"q\":\"feature\","
            + "\"s\":\"John David Washington, Robert Pattinson\"}]}";
    private static final String ONE_SEARCH = "{\"query\":{\"search\":[{\"title\":\"Q1234\"}]}}";
    private static final String TWO_SEARCH = "{\"query\":{\"search\":[{\"title\":\"Q1234\"},{\"title\":\"Q5678\"}]}}";
    private static final String NO_SEARCH = "{\"query\":{\"search\":[]}}";
    private static final String ONE_SEARCH_DARK = "{\"query\":{\"search\":[{\"title\":\"Q9\"}]}}";
    private static final String ONE_SEARCH_SUPERNATURAL = "{\"query\":{\"search\":[{\"title\":\"Q7777\"}]}}";
    private static final String MOVIE_ENTITY = "{\"entities\":{\"Q1234\":{\"labels\":{\"en\":{\"value\":\"Tenet\"}},"
            + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q11424\"}}}}],"
            + "\"P577\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"time\":\"+2020-08-26T00:00:00Z\"}}}}],"
            + "\"P161\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q1001\"}}}}],"
            + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt1000001\",\"type\":\"string\"}}}]}}}}";
    private static final String CAST = "{\"entities\":{\"Q1001\":{\"labels\":{\"en\":{\"value\":\"Robert Pattinson\"}}}}}";
    private static final String CAST_OTHER = "{\"entities\":{\"Q1001\":{\"labels\":{\"en\":{\"value\":\"Someone Else\"}}}}}";
    private static final String DARK_SUG = "{\"d\":[{\"id\":\"tt1000002\",\"l\":\"Dark\",\"y\":2017,\"q\":\"TV Series\",\"s\":\"\"}]}";
    private static final String SUPERNATURAL_SUG = "{\"d\":[{\"id\":\"tt0460627\",\"l\":\"Supernatural\",\"y\":2005,\"q\":\"TV Series\",\"s\":\"\"}]}";
    private static final String SUPERNATURAL_ENTITY = "{\"entities\":{\"Q7777\":{\"labels\":{\"en\":{\"value\":\"Supernatural\"}},"
            + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q5398426\"}}}}],"
            + "\"P577\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"time\":\"+2005-09-13T00:00:00Z\"}}}}],"
            + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt0460627\",\"type\":\"string\"}}}]}}}}";
    private static final String DARK_ENTITY = "{\"entities\":{\"Q9\":{\"labels\":{\"en\":{\"value\":\"Dark\"}},"
            + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q5398426\"}}}}],"
            + "\"P577\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"time\":\"+2017-12-01T00:00:00Z\"}}}}],"
            + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt1000002\",\"type\":\"string\"}}}]}}}}";

    // Series whose Wikidata item carries a start year (P580) and no publication year (P577).
    private static final String MENTALIST_SUG = "{\"d\":[{\"id\":\"tt1000004\",\"l\":\"The Mentalist\",\"y\":2008,\"q\":\"TV series\",\"s\":\"\"}]}";
    private static final String SPIDER_SUG = "{\"d\":[{\"id\":\"tt1000005\",\"l\":\"Spider-Noir\",\"y\":2026,\"q\":\"TV series\",\"s\":\"\"}]}";
    private static final String REACHER_SUG = "{\"d\":[{\"id\":\"tt1000006\",\"l\":\"Reacher\",\"y\":2022,\"q\":\"TV series\",\"s\":\"\"}]}";

    private static String startOnlySeries(String label, String qid, String imdbId, String start) {
        return "{\"entities\":{\"" + qid + "\":{\"labels\":{\"en\":{\"value\":\"" + label + "\"}},"
                + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q5398426\"}}}}],"
                + "\"P580\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"time\":\"+" + start + "T00:00:00Z\"}}}}],"
                + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"" + imdbId + "\",\"type\":\"string\"}}}]}}}}";
    }

    private static String oneHit(String qid) {
        return "{\"query\":{\"search\":[{\"title\":\"" + qid + "\"}]}}";
    }

    /** Canned answers per endpoint, used in order; the last answer repeats. An IOException answer is thrown. */
    static final class Fake implements PrimeImdb.Http {
        final Map<String, ArrayDeque<Object>> routes = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        Fake on(String endpoint, Object... answers) {
            routes.put(endpoint, new ArrayDeque<>(Arrays.asList(answers)));
            return this;
        }

        int hits(String endpoint) {
            int n = 0;
            for (String url : calls) if (endpoint(url).equals(endpoint)) n++;
            return n;
        }

        @Override public String get(String url) throws IOException {
            calls.add(url);
            ArrayDeque<Object> queue = routes.get(endpoint(url));
            if (queue == null) throw new AssertionError("unexpected request: " + url);
            Object answer = queue.size() > 1 ? queue.poll() : queue.peek();
            if (answer instanceof IOException) throw (IOException) answer;
            return (String) answer;
        }

        static String endpoint(String url) {
            if (url.startsWith(SUG)) return "sug";
            if (url.contains("list=search")) return "search";
            return url.contains("props=labels|claims") ? "entity" : "cast";
        }
    }

    private static Fake happyMovie() {
        return new Fake().on("sug", MOVIE_SUG).on("search", ONE_SEARCH).on("entity", MOVIE_ENTITY).on("cast", CAST);
    }

    private static PrimeMetadata.Detail movie(String title, int year) {
        return new PrimeMetadata.Detail(GTI, title, year, ShelfIdentity.Type.MOVIE,
                Collections.<String>emptyList(), Arrays.asList("Robert Pattinson"), "", 0);
    }

    private static PrimeImdb.Result verify(Fake http, PrimeMetadata.Detail d) {
        return PrimeImdb.verifyDetail(d, new PrimeImdb.Net(http, NO_WAIT));
    }

    @Test public void verifiedMovieIsKesinWithItsImdbId() {
        Fake http = happyMovie();
        PrimeImdb.Result r = verify(http, movie("Tenet", 2020));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertEquals("tt1000001", r.imdbId);
        assertTrue(r.yearVerified);
        assertTrue(r.typeVerified);
        assertEquals("", r.netKind);
        assertEquals(SUG + "tenet.json", http.calls.get(0));
    }

    @Test public void missingWikidataYearIsKesinButNotYearVerified() {
        Fake http = happyMovie().on("entity", MOVIE_ENTITY.replace("\"P577\"", "\"P9999\""));
        PrimeImdb.Result r = verify(http, movie("Tenet", 2020));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertFalse(r.yearVerified);
        assertTrue(r.typeVerified);
    }

    @Test public void yearMoreThanOneYearApartIsBasarisizAndOneYearIsTolerated() {
        assertEquals(PrimeImdb.BASARISIZ, verify(happyMovie(), movie("Tenet", 2017)).verdict);
        assertEquals(PrimeImdb.KESIN, verify(happyMovie(), movie("Tenet", 2021)).verdict);
    }

    @Test public void wikidataTypeThatDisagreesWithPrimeIsBasarisiz() {
        Fake http = happyMovie().on("entity", MOVIE_ENTITY.replace("Q11424", "Q5398426"));
        assertEquals(PrimeImdb.BASARISIZ, verify(http, movie("Tenet", 2020)).verdict);
    }

    @Test public void titleThatDoesNotMatchWikidataIsBelirsiz() {
        Fake http = happyMovie().on("entity", MOVIE_ENTITY.replace("\"value\":\"Tenet\"", "\"value\":\"Another Film\""));
        assertEquals(PrimeImdb.BELIRSIZ, verify(http, movie("Tenet", 2020)).verdict);
    }

    @Test public void ambiguousOrMissingWikidataItemIsBelirsiz() {
        assertEquals(PrimeImdb.BELIRSIZ, verify(happyMovie().on("search", TWO_SEARCH), movie("Tenet", 2020)).verdict);
        assertEquals(PrimeImdb.BELIRSIZ, verify(happyMovie().on("search", NO_SEARCH), movie("Tenet", 2020)).verdict);
    }

    @Test public void mismatchedP345IsBelirsiz() {
        Fake http = happyMovie().on("entity", MOVIE_ENTITY.replace("tt1000001", "tt9999999"));
        assertEquals(PrimeImdb.BELIRSIZ, verify(http, movie("Tenet", 2020)).verdict);
    }

    @Test public void noSuggestionIsBasarisiz() {
        assertEquals(PrimeImdb.BASARISIZ, verify(happyMovie().on("sug", "{\"d\":[]}"), movie("Tenet", 2020)).verdict);
    }

    @Test public void seasonIsSearchedByItsSeriesTitle() {
        Fake http = new Fake().on("sug", DARK_SUG).on("search", ONE_SEARCH_DARK).on("entity", DARK_ENTITY);
        PrimeMetadata.Detail season = new PrimeMetadata.Detail(GTI, "Dark: Season 2", 2017,
                ShelfIdentity.Type.SERIES, Collections.<String>emptyList(), Collections.<String>emptyList(), "Dark", 2);
        PrimeImdb.Result r = verify(http, season);
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertEquals("tt1000002", r.imdbId);
        assertEquals(SUG + "dark.json", http.calls.get(0));
    }

    @Test public void persistentServerErrorIsNetworkErrorAfterTwoRetries() {
        Fake http = happyMovie().on("sug", new IOException("http 503"));
        PrimeImdb.Net net = new PrimeImdb.Net(http, NO_WAIT);
        PrimeImdb.Result r = PrimeImdb.verifyDetail(movie("Tenet", 2020), net);
        assertEquals(PrimeImdb.NETWORK_ERROR, r.verdict);
        assertEquals("HTTP_503@imdb-sug", r.netKind);
        assertEquals(3, http.hits("sug"));
        assertEquals(2, net.retries);
        assertEquals(3000, net.backoffMs);
    }

    @Test public void timeoutIsClassifiedAsTimeout() {
        Fake http = happyMovie().on("sug", new SocketTimeoutException("timed out"));
        assertEquals("TIMEOUT@imdb-sug", verify(http, movie("Tenet", 2020)).netKind);
    }

    @Test public void transientFailureThenSuccessIsKesinAfterOneRetry() {
        Fake http = happyMovie().on("sug", new IOException("http 429"), MOVIE_SUG);
        PrimeImdb.Net net = new PrimeImdb.Net(http, NO_WAIT);
        assertEquals(PrimeImdb.KESIN, PrimeImdb.verifyDetail(movie("Tenet", 2020), net).verdict);
        assertEquals(1, net.retries);
        assertEquals(1000, net.backoffMs);
    }

    @Test public void rateLimitedAnswerWaitsItsRetryAfterInsteadOfTheFixedBackoff() {
        Fake http = happyMovie().on("sug", new PrimeImdb.HttpStatus(429, 4000), MOVIE_SUG);
        List<Long> pauses = new ArrayList<>();
        PrimeImdb.Net net = new PrimeImdb.Net(http, pauses::add);
        assertEquals(PrimeImdb.KESIN, PrimeImdb.verifyDetail(movie("Tenet", 2020), net).verdict);
        assertEquals(1, net.retries);
        assertEquals(4000, net.backoffMs);
        assertTrue(pauses.contains(4000L));
        assertFalse(pauses.contains(1000L));
    }

    @Test public void retryAfterThatIsTooLongStopsWithoutHammeringTheServer() {
        Fake http = happyMovie().on("sug", new PrimeImdb.HttpStatus(429, 49000));
        List<Long> pauses = new ArrayList<>();
        PrimeImdb.Net net = new PrimeImdb.Net(http, pauses::add);
        PrimeImdb.Result r = PrimeImdb.verifyDetail(movie("Tenet", 2020), net);
        assertEquals(PrimeImdb.NETWORK_ERROR, r.verdict);
        assertEquals("HTTP_429@imdb-sug", r.netKind);
        assertEquals(1, http.hits("sug"));
        assertEquals(0, net.retries);
        assertTrue(pauses.isEmpty());
    }

    @Test public void retryAfterShorterThanTheBackoffKeepsTheBackoff() {
        Fake http = happyMovie().on("sug", new PrimeImdb.HttpStatus(503, 200), MOVIE_SUG);
        PrimeImdb.Net net = new PrimeImdb.Net(http, NO_WAIT);
        PrimeImdb.verifyDetail(movie("Tenet", 2020), net);
        assertEquals(1000, net.backoffMs);
    }

    @Test public void retryAfterHeaderIsReadAsWholeSecondsOnly() {
        assertEquals(3000, PrimeImdb.retryAfterMs("3"));
        assertEquals(14000, PrimeImdb.retryAfterMs(" 14 "));
        assertEquals(0, PrimeImdb.retryAfterMs(null));
        assertEquals(0, PrimeImdb.retryAfterMs(""));
        assertEquals(0, PrimeImdb.retryAfterMs("-5"));
        assertEquals(0, PrimeImdb.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
    }

    @Test public void wikidataRateLimitStopsItsRequestsUntilRetryAfterHasPassed() {
        Fake http = happyMovie().on("search", new PrimeImdb.HttpStatus(429, 30000), ONE_SEARCH);
        long[] now = {0};
        PrimeImdb.Net net = new PrimeImdb.Net(http, NO_WAIT, () -> now[0]);
        assertEquals("HTTP_429@wd-search", PrimeImdb.verifyDetail(movie("Tenet", 2020), net).netKind);
        assertEquals("COOLDOWN@wd-search", PrimeImdb.verifyDetail(movie("Tenet", 2020), net).netKind);
        assertEquals(1, http.hits("search"));
        now[0] = 30_000;
        assertEquals(PrimeImdb.KESIN, PrimeImdb.verifyDetail(movie("Tenet", 2020), net).verdict);
        assertEquals(2, http.hits("search"));
    }

    @Test public void rateLimitOnTheEntityLookupAlsoStopsTheWikidataSearch() {
        Fake http = happyMovie().on("entity", new PrimeImdb.HttpStatus(429, 30000));
        PrimeImdb.Net net = new PrimeImdb.Net(http, NO_WAIT, () -> 0L);
        assertEquals("HTTP_429@wd-api", PrimeImdb.verifyDetail(movie("Tenet", 2020), net).netKind);
        assertEquals("COOLDOWN@wd-search", PrimeImdb.verifyDetail(movie("Tenet", 2020), net).netKind);
        assertEquals(1, http.hits("entity"));
        assertEquals(1, http.hits("search"));
    }

    @Test public void cooldownSleepsNothingLongAndLeavesOtherServicesAlone() {
        Fake http = happyMovie().on("search", new PrimeImdb.HttpStatus(429, 30000));
        List<Long> pauses = new ArrayList<>();
        PrimeImdb.Net net = new PrimeImdb.Net(http, pauses::add, () -> 0L);
        PrimeImdb.verifyDetail(movie("Tenet", 2020), net);
        PrimeImdb.verifyDetail(movie("Tenet", 2020), net);
        assertEquals(1, http.hits("search"));
        assertEquals(2, http.hits("sug"));
        assertTrue(pauses.stream().allMatch(p -> p == PrimeImdb.Net.PACE_MS));
    }

    @Test public void wikidataRateLimitWithoutRetryAfterKeepsTheFixedBackoff() {
        Fake http = happyMovie().on("search", new IOException("http 429"), ONE_SEARCH);
        PrimeImdb.Net net = new PrimeImdb.Net(http, NO_WAIT, () -> 0L);
        assertEquals(PrimeImdb.KESIN, PrimeImdb.verifyDetail(movie("Tenet", 2020), net).verdict);
        assertEquals(1, net.retries);
        assertEquals(1000, net.backoffMs);
    }

    @Test public void wikidataRetryAfterIsCappedAtTenMinutes() {
        Fake http = happyMovie().on("search", new PrimeImdb.HttpStatus(429, 999_999_000), ONE_SEARCH);
        long[] now = {0};
        PrimeImdb.Net net = new PrimeImdb.Net(http, NO_WAIT, () -> now[0]);
        assertEquals(PrimeImdb.NETWORK_ERROR, PrimeImdb.verifyDetail(movie("Tenet", 2020), net).verdict);
        now[0] = 599_999;
        assertEquals("COOLDOWN@wd-search", PrimeImdb.verifyDetail(movie("Tenet", 2020), net).netKind);
        now[0] = 600_000;
        assertEquals(PrimeImdb.KESIN, PrimeImdb.verifyDetail(movie("Tenet", 2020), net).verdict);
    }

    @Test public void wikidataRequestsCarryAContactAndImdbKeepsItsUserAgent() {
        assertTrue(PrimeImdb.userAgentFor("https://www.wikidata.org/w/api.php?action=query")
                .contains("https://github.com/Flexy01/tvhome-launcher"));
        assertEquals("TvHomeLauncher/1.2 (Android TV)", PrimeImdb.userAgentFor(SUG + "tenet.json"));
    }

    @Test public void notFoundIsNetworkErrorWithoutRetry() {
        Fake http = happyMovie().on("sug", new IOException("http 404"));
        assertEquals("HTTP_404@imdb-sug", verify(http, movie("Tenet", 2020)).netKind);
        assertEquals(1, http.hits("sug"));
    }

    @Test public void unreadableAnswerIsBelirsizAndNotRetried() {
        Fake http = happyMovie().on("sug", "<html>maintenance</html>");
        PrimeImdb.Result r = verify(http, movie("Tenet", 2020));
        assertEquals(PrimeImdb.BELIRSIZ, r.verdict);
        assertEquals("", r.netKind);
        assertEquals(1, http.hits("sug"));
    }

    @Test public void wikidataOutageIsNetworkError() {
        Fake http = happyMovie().on("search", new IOException("http 500"));
        assertEquals("HTTP_500@wd-search", verify(http, movie("Tenet", 2020)).netKind);
    }

    @Test public void failedCastLookupDoesNotBlockAVerifiedMatch() {
        Fake http = happyMovie().on("cast", new IOException("http 500"));
        assertEquals(PrimeImdb.KESIN, verify(http, movie("Tenet", 2020)).verdict);
    }

    @Test public void suggestionCastOverlapCorroboratesTheMatchLikeTheProbe() {
        // The verified probe seeds its cast gate from the best suggestion's cast as well; this keeps that parity.
        Fake http = happyMovie().on("cast", CAST_OTHER);
        assertEquals(PrimeImdb.KESIN, verify(http, movie("Tenet", 2020)).verdict);
    }

    @Test public void castWithNoOverlapInEitherSourceIsBelirsiz() {
        PrimeMetadata.Detail d = new PrimeMetadata.Detail(GTI, "Tenet", 2020, ShelfIdentity.Type.MOVIE,
                Collections.<String>emptyList(), Arrays.asList("Nobody Known"), "", 0);
        Fake http = happyMovie().on("cast", CAST_OTHER);
        assertEquals(PrimeImdb.BELIRSIZ, verify(http, d).verdict);
    }

    private static PrimeMetadata.Detail season(String title, int year, String series) {
        return new PrimeMetadata.Detail(GTI, title, year, ShelfIdentity.Type.SERIES,
                Collections.<String>emptyList(), Collections.<String>emptyList(), series, 3);
    }

    @Test public void laterSeasonOfASeriesIsVerifiedWhenItAiredAfterTheSeriesStart() {
        Fake http = new Fake().on("sug", SUPERNATURAL_SUG).on("search", ONE_SEARCH_SUPERNATURAL).on("entity", SUPERNATURAL_ENTITY);
        PrimeImdb.Result r = verify(http, season("Supernatural - Season 3", 2007, "Supernatural"));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertEquals("tt0460627", r.imdbId);
        assertTrue(r.yearVerified);
    }

    @Test public void seasonThatAiredBeforeItsSeriesStartIsBasarisiz() {
        Fake http = new Fake().on("sug", SUPERNATURAL_SUG).on("search", ONE_SEARCH_SUPERNATURAL).on("entity", SUPERNATURAL_ENTITY);
        assertEquals(PrimeImdb.BASARISIZ, verify(http, season("Supernatural - Season 1", 2003, "Supernatural")).verdict);
    }

    @Test public void bestCandidateKeepsTheSeriesThatStartedBeforeALaterSeason() {
        PrimeImdb.Suggestion later = new PrimeImdb.Suggestion("tt9999999", "Supernatural", 2022, ShelfIdentity.Type.SERIES);
        PrimeImdb.Suggestion own = new PrimeImdb.Suggestion("tt0460627", "Supernatural", 2005, ShelfIdentity.Type.SERIES);
        PrimeImdb.Suggestion best = PrimeImdb.best(Arrays.asList(later, own),
                season("Supernatural - Season 3", 2007, "Supernatural"),
                ShelfIdentity.extractTokens("Supernatural"), Collections.<String>emptySet());
        assertEquals("tt0460627", best.tt);
    }

    private static PrimeMetadata.Detail seasonOf(String title, int year, String series, int number) {
        return new PrimeMetadata.Detail(GTI, title, year, ShelfIdentity.Type.SERIES,
                Collections.<String>emptyList(), Collections.<String>emptyList(), series, number);
    }

    @Test public void seasonOfAStartOnlySeriesIsYearVerifiedWhenItsStartYearAgreesWithImdb() {
        Fake http = new Fake().on("sug", MENTALIST_SUG).on("search", oneHit("Q500"))
                .on("entity", startOnlySeries("The Mentalist", "Q500", "tt1000004", "2008-09-23"));
        PrimeImdb.Result r = verify(http, seasonOf("The Mentalist - Season 1", 2008, "The Mentalist", 1));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertEquals("tt1000004", r.imdbId);
        assertTrue(r.yearVerified);
        assertTrue(r.typeVerified);
    }

    @Test public void laterSeasonIsYearVerifiedByTheSeriesStartNotByItsOwnAirYear() {
        // The season aired in 2025; the series started in 2022. Comparing P580 with the season year would reject it.
        Fake http = new Fake().on("sug", REACHER_SUG).on("search", oneHit("Q501"))
                .on("entity", startOnlySeries("Reacher", "Q501", "tt1000006", "2022-02-04"));
        PrimeImdb.Result r = verify(http, seasonOf("Reacher - Season 3", 2025, "Reacher", 3));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertTrue(r.yearVerified);
    }

    @Test public void newSeriesWithOnlyAStartYearIsYearVerified() {
        Fake http = new Fake().on("sug", SPIDER_SUG).on("search", oneHit("Q502"))
                .on("entity", startOnlySeries("Spider-Noir", "Q502", "tt1000005", "2026-05-25"));
        PrimeImdb.Result r = verify(http, seasonOf("Spider-Noir - Season 1", 2026, "Spider-Noir", 1));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertTrue(r.yearVerified);
    }

    @Test public void startYearThatDisagreesWithTheImdbSeriesYearIsKesinButNotYearVerified() {
        Fake http = new Fake().on("sug", REACHER_SUG).on("search", oneHit("Q501"))
                .on("entity", startOnlySeries("Reacher", "Q501", "tt1000006", "2010-01-01"));
        PrimeImdb.Result r = verify(http, seasonOf("Reacher - Season 1", 2022, "Reacher", 1));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertFalse(r.yearVerified);
    }

    @Test public void seasonNumberThatDisagreesWithItsTitleIsNotYearVerified() {
        Fake http = new Fake().on("sug", MENTALIST_SUG).on("search", oneHit("Q500"))
                .on("entity", startOnlySeries("The Mentalist", "Q500", "tt1000004", "2008-09-23"));
        PrimeImdb.Result r = verify(http, seasonOf("The Mentalist - Season 2", 2008, "The Mentalist", 1));
        assertFalse(r.yearVerified);
    }

    @Test public void seasonOfADifferentSeriesIsNotYearVerifiedByAStartYear() {
        Fake http = new Fake().on("sug", REACHER_SUG).on("search", oneHit("Q501"))
                .on("entity", startOnlySeries("Reacher", "Q501", "tt1000006", "2022-02-04"));
        assertFalse(verify(http, seasonOf("Other Show - Season 1", 2022, "Other Show", 1)).yearVerified);
    }

    @Test public void unlabelledSingleSeasonWithAStartYearIsYearVerified() {
        // Prime titles some seasons without a "Season N" marker; the structured seasonNumber still names the season.
        Fake http = new Fake().on("sug", SPIDER_SUG).on("search", oneHit("Q502"))
                .on("entity", startOnlySeries("Spider-Noir", "Q502", "tt1000005", "2026-05-25"));
        PrimeImdb.Result r = verify(http, seasonOf("Spider-Noir", 2026, "Spider-Noir", 1));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertTrue(r.yearVerified);
    }

    @Test public void labelledSeasonWhoseMarkerDisagreesWithItsNumberIsNotYearVerified() {
        Fake http = new Fake().on("sug", REACHER_SUG).on("search", oneHit("Q501"))
                .on("entity", startOnlySeries("Reacher", "Q501", "tt1000006", "2022-02-04"));
        assertFalse(verify(http, seasonOf("Reacher - Season 2", 2022, "Reacher", 4)).yearVerified);
    }

    @Test public void startOnlySeriesIsNotYearVerifiedForAMovieDetail() {
        Fake http = happyMovie().on("entity", MOVIE_ENTITY.replace("\"P577\"", "\"P580\""));
        PrimeImdb.Result r = verify(http, movie("Tenet", 2020));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertFalse(r.yearVerified);
    }

    @Test public void goneDetailPageIsBasarisizWithoutAnyLookup() {
        PrimeMetadata.PageSource gone = gti -> { throw new IOException("http 404"); };
        PrimeImdb.Result r = PrimeImdb.verify(GTI, gone, new PrimeImdb.Net(new Fake(), NO_WAIT));
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
    }

    @Test public void pageServerErrorIsNetworkErrorAfterRetries() {
        PrimeMetadata.PageSource broken = gti -> { throw new IOException("http 503"); };
        PrimeImdb.Net net = new PrimeImdb.Net(new Fake(), NO_WAIT);
        PrimeImdb.Result r = PrimeImdb.verify(GTI, broken, net);
        assertEquals("HTTP_503@prime", r.netKind);
        assertEquals(2, net.retries);
    }

    @Test public void malformedGtiIsBasarisizWithoutFetching() {
        List<String> fetched = new ArrayList<>();
        PrimeMetadata.PageSource pages = gti -> { fetched.add(gti); return ""; };
        PrimeImdb.Result r = PrimeImdb.verify("amzn1.dv.gti.not-a-uuid", pages, new PrimeImdb.Net(new Fake(), NO_WAIT));
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
        assertTrue(fetched.isEmpty());
    }

    @Test public void bestCandidatePrefersTitleAndYearAgreement() {
        List<PrimeImdb.Suggestion> cands = Arrays.asList(
                new PrimeImdb.Suggestion("tt1000003", "Other Story", 1990, ShelfIdentity.Type.MOVIE),
                new PrimeImdb.Suggestion("tt1000001", "Tenet", 2020, ShelfIdentity.Type.MOVIE));
        PrimeImdb.Suggestion best = PrimeImdb.best(cands, movie("Tenet", 2020),
                ShelfIdentity.extractTokens("Tenet"), Collections.<String>emptySet());
        assertEquals("tt1000001", best.tt);
    }

    @Test public void titleAndIntentHelpersNormalizeAndFindTheGti() {
        assertEquals("unlu dizi", PrimeImdb.exactNorm("Ünlü: Dizi!"));
        assertFalse(PrimeImdb.isQueryableNorm("a"));
        assertTrue(PrimeImdb.isQueryableNorm("ab"));
        assertEquals("dark", PrimeImdb.stripSeasonSuffix("Dark: Season 2", 2));
        assertEquals("mr robot", PrimeImdb.stripSeasonSuffix("Mr. Robot - Sezon 2", 2));
        assertNull(PrimeImdb.stripSeasonSuffix("Show: Season 12", 2));
        assertNull(PrimeImdb.stripSeasonSuffix("Tenet", 2));
        assertEquals(GTI, PrimeImdb.gtiIn("content://x?g=" + GTI));
        assertEquals(GTI, PrimeImdb.gtiIn("content://x?g%253D" + GTI));
        assertNull(PrimeImdb.gtiIn("content://x?g=not-a-gti"));
        assertNull(PrimeImdb.gtiIn(null));
    }

    // ---- Netflix: the Netflix id (P1874) must name one item, and that item's IMDb id (P345) must lead back to it. ----
    private static final String LUPIN_QID = "Q104082429";
    private static final String LUPIN = "{\"entities\":{\"" + LUPIN_QID + "\":{\"labels\":{\"en\":{\"value\":\"Lupin\"}},"
            + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q5398426\"}}}}],"
            + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt2531336\",\"type\":\"string\"}}}]}}}}";
    private static final String LUPIN_EPISODE = "{\"entities\":{\"" + LUPIN_QID + "\":{\"labels\":{\"en\":{\"value\":\"Lupin\"}},"
            + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q21191270\"}}}}],"
            + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt2531337\",\"type\":\"string\"}}}]}}}}";
    private static final String LUPIN_PART = "{\"entities\":{\"" + LUPIN_QID + "\":{\"labels\":{\"en\":{\"value\":\"Lupin\"}},"
            + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q5398426\"}}}}],"
            + "\"P179\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q1\"}}}}],"
            + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt2531336\",\"type\":\"string\"}}}]}}}}";
    private static final String UMBRELLA = "{\"entities\":{\"Q44535025\":{\"labels\":{\"en\":{\"value\":\"The Umbrella Academy\"}},"
            + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q5398426\"}}}}],"
            + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt1312171\",\"type\":\"string\"}}}]}}}}";
    private static final String TENET_FILM = "{\"entities\":{\"Q1234\":{\"labels\":{\"en\":{\"value\":\"Tenet\"}},"
            + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q11424\"}}}}],"
            + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt1000001\",\"type\":\"string\"}}}]}}}}";

    private static PrimeImdb.Result netflix(String nid, String title, int typeCode, Fake http) {
        return PrimeImdb.verifyNetflix(nid, title, typeCode, new PrimeImdb.Net(http, NO_WAIT));
    }

    @Test public void netflixSeriesRowType3IsKesinAsASeriesNotAnEpisode() {
        Fake http = new Fake().on("search", oneHit(LUPIN_QID)).on("entity", LUPIN);
        PrimeImdb.Result r = netflix("80994082", "Lupin", 3, http);
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertEquals("tt2531336", r.imdbId);
        assertEquals(ShelfIdentity.Type.SERIES, r.type);
        assertTrue(r.typeVerified);
        assertFalse(r.yearVerified);
        assertEquals("", r.netKind);
    }

    @Test public void netflixFilmRowType3IsKesinAsAMovie() {
        Fake http = new Fake().on("search", oneHit("Q1234")).on("entity", TENET_FILM);
        PrimeImdb.Result r = netflix("70000001", "Tenet", 3, http);
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertEquals("tt1000001", r.imdbId);
        assertEquals(ShelfIdentity.Type.MOVIE, r.type);
    }

    @Test public void netflixRowTypeZeroIsOnlyAMovie() {
        Fake http = new Fake().on("search", oneHit(LUPIN_QID)).on("entity", LUPIN);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("80994082", "Lupin", 0, http).verdict);
    }

    @Test public void netflixRowTypeOneIsOnlyASeries() {
        Fake http = new Fake().on("search", oneHit("Q1234")).on("entity", TENET_FILM);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("70000001", "Tenet", 1, http).verdict);
    }

    /** Regression: Netflix 80186863 is "Ghosts" in the row, but Wikidata's item for that id is The Umbrella Academy. */
    @Test public void netflixTitleThatDoesNotMatchWikidataIsRejected() {
        Fake http = new Fake().on("search", oneHit("Q44535025")).on("entity", UMBRELLA);
        PrimeImdb.Result r = netflix("80186863", "Ghosts", 3, http);
        assertEquals(PrimeImdb.BELIRSIZ, r.verdict);
        assertEquals("", r.imdbId);
    }

    @Test public void netflixEpisodeOrPartOfASeriesIsRejected() {
        Fake episode = new Fake().on("search", oneHit(LUPIN_QID)).on("entity", LUPIN_EPISODE);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("80994082", "Lupin", 3, episode).verdict);
        Fake part = new Fake().on("search", oneHit(LUPIN_QID)).on("entity", LUPIN_PART);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("80994082", "Lupin", 3, part).verdict);
    }

    @Test public void netflixSeasonMarkerInTheRowTitleIsRejected() {
        Fake http = new Fake().on("search", oneHit(LUPIN_QID)).on("entity", LUPIN);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("80994082", "Lupin: Season 3", 3, http).verdict);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("80994082", "Lupin Sezon 3", 3, http).verdict);
    }

    @Test public void netflixIdThatMatchesSeveralItemsIsRejected() {
        Fake http = new Fake().on("search", TWO_SEARCH);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("80994082", "Lupin", 3, http).verdict);
    }

    @Test public void netflixImdbLookupMustLeadBackToTheSameItem() {
        Fake http = new Fake().on("search", oneHit(LUPIN_QID), oneHit("Q999")).on("entity", LUPIN);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("80994082", "Lupin", 3, http).verdict);
    }

    @Test public void netflixIdThatIsNotNumericIsNeverLookedUp() {
        Fake http = new Fake();
        assertEquals(PrimeImdb.BASARISIZ, netflix("80994082x", "Lupin", 3, http).verdict);
        assertTrue(http.calls.isEmpty());
    }

    @Test public void netflixWikidataFailureIsANetworkErrorNotAVerdict() {
        Fake http = new Fake().on("search", new PrimeImdb.HttpStatus(503, 0));
        PrimeImdb.Result r = netflix("80994082", "Lupin", 3, http);
        assertEquals(PrimeImdb.NETWORK_ERROR, r.verdict);
        assertEquals("", r.imdbId);
    }

    @Test public void netflixSeasonRowIsRejectedEvenWithTheSeriesTitle() {
        Fake http = new Fake().on("search", oneHit(LUPIN_QID)).on("entity", LUPIN);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("80994082", "Lupin", 2, http).verdict);
    }

    @Test public void netflixSeasonItemIsRejectedEvenWhenItIsAlsoASeries() {
        String season = "{\"entities\":{\"" + LUPIN_QID + "\":{\"labels\":{\"en\":{\"value\":\"Lupin\"}},"
                + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q3464665\"}}}},"
                + "{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q5398426\"}}}}],"
                + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt2531336\",\"type\":\"string\"}}}]}}}}";
        Fake http = new Fake().on("search", oneHit(LUPIN_QID)).on("entity", season);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("80994082", "Lupin", 3, http).verdict);
    }

    @Test public void netflixTurkishLabelMatchesWhenEnglishLabelDiffers() {
        String sonundaSen = "{\"entities\":{\"Q138819733\":{\"labels\":{\"en\":{\"value\":\"Love at Last\"},"
                + "\"tr\":{\"value\":\"Sonunda Sen\"}},"
                + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q11424\"}}}}],"
                + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt39130911\",\"type\":\"string\"}}}]}}}}";
        Fake http1 = new Fake().on("search", oneHit("Q138819733")).on("entity", sonundaSen);
        PrimeImdb.Result r1 = netflix("82639874", "Sonunda Sen", 3, http1);
        assertEquals(PrimeImdb.KESIN, r1.verdict);
        assertEquals("tt39130911", r1.imdbId);
        assertEquals(ShelfIdentity.Type.MOVIE, r1.type);

        String besiktenMezara = "{\"entities\":{\"Q837536\":{\"labels\":{\"en\":{\"value\":\"Cradle 2 the Grave\"},"
                + "\"tr\":{\"value\":\"Beşikten Mezara\"}},"
                + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q11424\"}}}}],"
                + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt0306685\",\"type\":\"string\"}}}]}}}}";
        Fake http2 = new Fake().on("search", oneHit("Q837536")).on("entity", besiktenMezara);
        PrimeImdb.Result r2 = netflix("60026133", "Beşikten Mezara", 3, http2);
        assertEquals(PrimeImdb.KESIN, r2.verdict);
        assertEquals("tt0306685", r2.imdbId);
        assertEquals(ShelfIdentity.Type.MOVIE, r2.type);
    }

    @Test public void netflixTurkishAliasMatchesWhenEnglishAndTurkishLabelsDiffer() {
        String entityWithTrAlias = "{\"entities\":{\"Q919945\":{\"labels\":{\"en\":{\"value\":\"Safe\"},"
                + "\"tr\":{\"value\":\"Koruyucu (film, 2012)\"}},"
                + "\"aliases\":{\"tr\":[{\"value\":\"Koruyucu\"}]},"
                + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q11424\"}}}}],"
                + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt1656190\",\"type\":\"string\"}}}]}}}}";
        Fake http = new Fake().on("search", oneHit("Q919945")).on("entity", entityWithTrAlias);
        PrimeImdb.Result r = netflix("70202134", "Koruyucu", 3, http);
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertEquals("tt1656190", r.imdbId);
        assertEquals(ShelfIdentity.Type.MOVIE, r.type);
        assertTrue(http.calls.get(1).contains("aliases"));
    }

    @Test public void netflixTurkishNameThatOnlyPartlyMatchesOrIsAFilmSeriesIsRejected() {
        // A parenthetical in the Turkish label ("Koruyucu (film, 2012)") is not an exact match for "Koruyucu".
        String safeDisambiguated = "{\"entities\":{\"Q919945\":{\"labels\":{\"en\":{\"value\":\"Safe\"},"
                + "\"tr\":{\"value\":\"Koruyucu (film, 2012)\"}},"
                + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q11424\"}}}}],"
                + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt1656190\",\"type\":\"string\"}}}]}}}}";
        Fake httpSafe = new Fake().on("search", oneHit("Q919945")).on("entity", safeDisambiguated);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("70202134", "Koruyucu", 3, httpSafe).verdict);

        // Exact tr label match ("Mezarlık") with P31=Q24856 (film series, neither film Q11424 nor TV series Q5398426) is still rejected
        String mezarlik = "{\"entities\":{\"Q113028954\":{\"labels\":{\"en\":{\"value\":\"The Graveyard\"},"
                + "\"tr\":{\"value\":\"Mezarlık\"}},"
                + "\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q24856\"}}}}],"
                + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"tt16420408\",\"type\":\"string\"}}}]}}}}";
        Fake httpMezarlik = new Fake().on("search", oneHit("Q113028954")).on("entity", mezarlik);
        assertEquals(PrimeImdb.BELIRSIZ, netflix("81573856", "Mezarlık", 3, httpMezarlik).verdict);
    }
}
