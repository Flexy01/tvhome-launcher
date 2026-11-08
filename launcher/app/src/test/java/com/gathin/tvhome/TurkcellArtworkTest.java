package com.gathin.tvhome;

import org.junit.Test;

import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Turkcell TV+ film rows: the official page of the content id (title, year, IMDb link), then Wikidata. Fixture ids are invented. */
public class TurkcellArtworkTest {
    private static final String SITEMAP = "https://tvplus.com.tr/sitemaps/filmler_sitemap.xml";
    private static final String ID = "999000111";
    private static final String TITLE = "Harry Potter ve Test Filmi";
    private static final String TT = "tt9000001";
    private static final PrimeImdb.Sleep NO_WAIT = ms -> { };

    private static String sitemap(String slug, String id) {
        return "<urlset><url><loc>https://tvplus.com.tr/film-izle/" + slug + "--" + id + "</loc></url></urlset>";
    }

    private static String page(String title, String imdb, String year) {
        return "<title>" + title + " İzle | " + year + " | TV+</title>"
                + "<script>{\"extensionInfo\":[{\"value\":\"https://www.imdb.com/title/" + imdb + "/\"}]}</script>";
    }

    private static String search(String qid) {
        return "{\"query\":{\"search\":[{\"title\":\"" + qid + "\"}]}}";
    }

    private static final String SERIES_QID = "Q216930";

    private static String entity(String qid, String labelTr, String type, String year, String imdb) {
        return entityPartOf(qid, labelTr, type, year, imdb, null);
    }

    /** A film entity; partOfQid, when given, is its one P179 (part of the series) value. */
    private static String entityPartOf(String qid, String labelTr, String type, String year, String imdb, String partOfQid) {
        return "{\"entities\":{\"" + qid + "\":{\"labels\":{\"en\":{\"value\":\"Test Film\"},\"tr\":{\"value\":\""
                + labelTr + "\"}},\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"" + type + "\"}}}}],"
                + "\"P577\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"time\":\"+" + year + "-01-01T00:00:00Z\"}}}}],"
                + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"" + imdb + "\",\"type\":\"string\"}}}]"
                + (partOfQid == null ? "" : ",\"P179\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"" + partOfQid + "\"}}}}]")
                + "}}}}";
    }

    /** The series entity a P179 names, with its P31 kind alone. */
    private static String seriesKind(String qid, String kind) {
        return "{\"entities\":{\"" + qid + "\":{\"labels\":{\"en\":{\"value\":\"Test Series\"}},\"claims\":{\"P31\":"
                + "[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"" + kind + "\"}}}}]}}}}";
    }

    /** Answers by request kind: the sitemap (null throws a timeout), any film page, Wikidata search and entity. */
    private static PrimeImdb.Http http(String sitemapXml, String pageHtml, String searchJson, String entityJson) {
        return http(sitemapXml, pageHtml, searchJson, entityJson, null);
    }

    /** As above; partJson answers the request for SERIES_QID, the series a film is part of. */
    private static PrimeImdb.Http http(String sitemapXml, String pageHtml, String searchJson, String entityJson,
                                       String partJson) {
        return url -> {
            if (url.equals(SITEMAP)) {
                if (sitemapXml == null) throw new SocketTimeoutException("sitemap");
                return sitemapXml;
            }
            if (url.startsWith("https://tvplus.com.tr/film-izle/")) return pageHtml;
            if (url.contains("list=search")) return searchJson;
            if (url.contains("wbgetentities")) {
                if (url.contains("ids=" + SERIES_QID)) {
                    if (partJson == null) throw new AssertionError("series requested without a part answer");
                    return partJson;
                }
                return entityJson;
            }
            throw new AssertionError("unexpected request kind");
        };
    }

    private static PrimeImdb.Result verify(String contentId, String rowTitle, PrimeImdb.Http http) {
        return TurkcellArtwork.verify(contentId, rowTitle, new PrimeImdb.Net(http, NO_WAIT));
    }

    private static PrimeImdb.Http goodFilm() {
        return http(sitemap("harry-potter-ve-test-filmi", ID), page(TITLE, TT, "2001"),
                search("Q9000"), entity("Q9000", TITLE, "Q11424", "2001", TT));
    }

    @Test
    public void officialPageAndWikidataVerifyTheFilm() {
        PrimeImdb.Result r = verify(ID, TITLE, goodFilm());
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertEquals(TT, r.imdbId);
        assertTrue(r.yearVerified);
        assertTrue(r.typeVerified);
        assertEquals(ShelfIdentity.Type.MOVIE, r.type);
    }

    @Test
    public void wikidataWithoutAYearLeavesThePageYearUnverified() {
        String noYear = "{\"entities\":{\"Q9000\":{\"labels\":{\"en\":{\"value\":\"Test Film\"},\"tr\":{\"value\":\"" + TITLE
                + "\"}},\"claims\":{\"P31\":[{\"mainsnak\":{\"datavalue\":{\"value\":{\"id\":\"Q11424\"}}}}],"
                + "\"P345\":[{\"mainsnak\":{\"datavalue\":{\"value\":\"" + TT + "\",\"type\":\"string\"}}}]}}}}";
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID), page(TITLE, TT, "2001"),
                search("Q9000"), noYear));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertFalse(r.yearVerified);
    }

    @Test
    public void idMissingFromTheOfficialSitemapIsNotVerified() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", "111222333"),
                page(TITLE, TT, "2001"), search("Q9000"), entity("Q9000", TITLE, "Q11424", "2001", TT)));
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
    }

    @Test
    public void officialPageOfAnotherFilmWithTheSameIdIsNotVerified() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID),
                page("Harry Potter ve Sırlar Odası", TT, "2002"),
                search("Q9000"), entity("Q9000", TITLE, "Q11424", "2001", TT)));
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
    }

    @Test
    public void wikidataYearFarFromTheOfficialYearIsNotVerified() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID), page(TITLE, TT, "2001"),
                search("Q9000"), entity("Q9000", TITLE, "Q11424", "2010", TT)));
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
    }

    @Test
    public void wikidataSeriesItemIsNotAFilm() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID), page(TITLE, TT, "2001"),
                search("Q9000"), entity("Q9000", TITLE, "Q5398426", "2001", TT)));
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
    }

    @Test
    public void filmOfAFilmSeriesIsVerified() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID), page(TITLE, TT, "2001"),
                search("Q9000"), entityPartOf("Q9000", TITLE, "Q11424", "2001", TT, SERIES_QID),
                seriesKind(SERIES_QID, "Q24856")));
        assertEquals(PrimeImdb.KESIN, r.verdict);
        assertEquals(TT, r.imdbId);
        assertTrue(r.yearVerified);
        assertTrue(r.typeVerified);
    }

    @Test
    public void filmOfATvSeriesIsNotVerified() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID), page(TITLE, TT, "2001"),
                search("Q9000"), entityPartOf("Q9000", TITLE, "Q11424", "2001", TT, SERIES_QID),
                seriesKind(SERIES_QID, "Q5398426")));
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
    }

    @Test
    public void filmOfAnItemOfUnknownKindIsNotVerified() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID), page(TITLE, TT, "2001"),
                search("Q9000"), entityPartOf("Q9000", TITLE, "Q11424", "2001", TT, SERIES_QID),
                seriesKind(SERIES_QID, "Q9999999")));
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
    }

    @Test
    public void filmOfASeasonIsNotVerified() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID), page(TITLE, TT, "2001"),
                search("Q9000"), entityPartOf("Q9000", TITLE, "Q11424", "2001", TT, SERIES_QID),
                seriesKind(SERIES_QID, "Q3464665")));
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
    }

    @Test
    public void wikidataTitleThatDiffersIsNotVerified() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID), page(TITLE, TT, "2001"),
                search("Q9000"), entity("Q9000", "Başka Bir Film", "Q11424", "2001", TT)));
        assertEquals(PrimeImdb.BELIRSIZ, r.verdict);
    }

    @Test
    public void officialPageWithoutAnImdbLinkIsNotVerified() {
        PrimeImdb.Result r = verify(ID, TITLE, http(sitemap("harry-potter-ve-test-filmi", ID),
                "<title>" + TITLE + " İzle | 2001 | TV+</title>", search("Q9000"),
                entity("Q9000", TITLE, "Q11424", "2001", TT)));
        assertEquals(PrimeImdb.BELIRSIZ, r.verdict);
    }

    @Test
    public void sitemapTimeoutIsANetworkError() {
        PrimeImdb.Result r = verify(ID, TITLE, http(null, page(TITLE, TT, "2001"),
                search("Q9000"), entity("Q9000", TITLE, "Q11424", "2001", TT)));
        assertEquals(PrimeImdb.NETWORK_ERROR, r.verdict);
    }

    @Test
    public void nonDigitContentIdIsRejectedBeforeAnyRequest() {
        PrimeImdb.Result r = verify("abc", TITLE, url -> { throw new AssertionError("no request"); });
        assertEquals(PrimeImdb.BASARISIZ, r.verdict);
    }

    @Test
    public void turkcellRowKeyIsItsContentIdOnly() {
        assertEquals("turkcell:" + ID, PrimeArtwork.keyOf(PrimeArtwork.TURKCELL_PACKAGE, "content://x", ID));
        assertNull(PrimeArtwork.keyOf(PrimeArtwork.TURKCELL_PACKAGE, "content://x", "abc"));
        assertNull(PrimeArtwork.keyOf(PrimeArtwork.TURKCELL_PACKAGE, "content://x", null));
        assertNull(PrimeArtwork.keyOf("com.example.other", "content://x", ID));
    }

    @Test
    public void turkcellIsAVerifiedPackageAndTheOtherTvPackagesAreNot() {
        assertTrue(PrimeArtwork.isVerifiedPackage(PrimeArtwork.TURKCELL_PACKAGE));
        assertFalse(PrimeArtwork.isVerifiedPackage("com.apple.atve.androidtv.appletv"));
        assertFalse(PrimeArtwork.isVerifiedPackage("com.mitv.tvhome.mitvplus"));
    }
}
