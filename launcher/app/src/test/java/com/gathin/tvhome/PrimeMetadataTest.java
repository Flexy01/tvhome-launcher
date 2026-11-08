package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

/**
 * The ATV detail method, checked against trimmed row fixtures and a synthetic gzipped detail page fixture.
 */
public class PrimeMetadataTest {

    private static final String SYNTHETIC_GTI = "amzn1.dv.gti.00000000-0000-0000-0000-000000000001";

    private static JSONArray fixtures() throws Exception {
        try (InputStream in = open("/prime/atv_fixtures.json")) {
            return new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
        }
    }

    private static JSONObject fixture(String name) throws Exception {
        JSONArray all = fixtures();
        for (int i = 0; i < all.length(); i++) {
            JSONObject c = all.getJSONObject(i);
            if (name.equals(c.getString("name"))) return c;
        }
        throw new IllegalArgumentException("no fixture " + name);
    }

    /** The page as Prime serves it: the hydration script carries the detail item. */
    private static String page(JSONObject c) {
        try {
            JSONObject state = new JSONObject();
            state.put("pageTitleId", c.getString("pageTitleId"));
            JSONObject header = new JSONObject();
            JSONObject items = c.getJSONObject("headerDetail");
            for (java.util.Iterator<String> it = items.keys(); it.hasNext(); ) {
                String key = it.next();
                header.put(key, items.getJSONObject(key));
            }
            state.put("detail", new JSONObject().put("headerDetail", header));
            JSONObject atf = new JSONObject().put("state", state);
            JSONObject body = new JSONObject().put("atf", atf);
            JSONObject root = new JSONObject().put("init", new JSONObject()
                    .put("preparations", new JSONObject().put("body", body)));
            return "<html><script id=\"dv-web-page-hydration-data\" type=\"application/json\">"
                    + root + "</script></html>";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same item, but only in the below-the-fold block the page also carries. */
    private static String btfPage(JSONObject c) {
        String html = page(c);
        try {
            int open = html.indexOf("application/json\">") + "application/json\">".length();
            JSONObject root = new JSONObject(html.substring(open, html.lastIndexOf("</script>")));
            JSONObject state = root.getJSONObject("init").getJSONObject("preparations")
                    .getJSONObject("body").getJSONObject("atf").getJSONObject("state");
            JSONObject header = state.getJSONObject("detail").getJSONObject("headerDetail");
            JSONObject more = new JSONObject();
            for (java.util.Iterator<String> it = header.keys(); it.hasNext(); ) {
                String key = it.next();
                more.put(key, header.getJSONObject(key));
            }
            JSONObject btfState = new JSONObject().put("detail", new JSONObject().put("btfMoreDetails", more));
            root.getJSONObject("init").getJSONObject("preparations").getJSONObject("body")
                    .put("btf", new JSONObject().put("state", btfState));
            state.getJSONObject("detail").put("headerDetail", new JSONObject());
            return "<html><script id=\"dv-web-page-hydration-data\" type=\"application/json\">"
                    + root + "</script></html>";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test public void everyRealRowResolvesToItsOwnProduction() throws Exception {
        JSONArray all = fixtures();
        int rows = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject c = all.getJSONObject(i);
            String name = c.getString("name");
            if (!name.startsWith("row")) continue;
            JSONObject expect = c.getJSONObject("expect");
            String gti = c.getString("requestedGti");

            PrimeMetadata.Detail d = PrimeMetadata.parse(gti, page(c));

            assertEquals(name + " title", expect.getString("title"), d.title);
            assertEquals(name + " year", expect.getInt("year"), d.year);
            assertEquals(name + " type",
                    "MOVIE".equals(expect.getString("kind"))
                            ? ShelfIdentity.Type.MOVIE : ShelfIdentity.Type.SERIES, d.type);
            assertEquals(name + " canonical gti", gti, d.gti);
            assertEquals(name + " genres", expect.getJSONArray("genres").length(), d.genres.size());
            assertEquals(name + " cast", expect.getJSONArray("cast").length(), d.cast.size());
            for (int k = 0; k < d.cast.size(); k++) {
                assertEquals(expect.getJSONArray("cast").getString(k), d.cast.get(k));
            }
            assertEquals(expect.getString("seriesTitle"), d.seriesTitle);
            assertEquals(expect.getInt("seasonNumber"), d.seasonNumber);
            rows++;
        }
        assertEquals("all twelve captured rows", 12, rows);
    }

    @Test public void aCastEntryWithoutANameIsDropped() throws Exception {
        JSONObject c = fixture("empty-cast-name");
        PrimeMetadata.Detail d = PrimeMetadata.parse(c.getString("requestedGti"), page(c));
        assertEquals(c.getJSONObject("expect").getJSONArray("cast").length(), d.cast.size());
        for (String name : d.cast) assertFalse("no blank credit", name.trim().isEmpty());
    }

    @Test public void seasonRowsCarrySeriesAndSeasonNumber() throws Exception {
        JSONObject c = fixture("row2");
        PrimeMetadata.Detail d = PrimeMetadata.parse(c.getString("requestedGti"), page(c));
        assertEquals(ShelfIdentity.Type.SERIES, d.type);
        assertEquals("The Mentalist", d.seriesTitle);
        assertEquals(1, d.seasonNumber);
        assertEquals(2008, d.year);
    }

    @Test public void detailItemIsAlsoFoundInTheBelowTheFoldBlock() throws Exception {
        JSONObject c = fixture("row0");
        PrimeMetadata.Detail d = PrimeMetadata.parse(c.getString("requestedGti"), btfPage(c));
        assertEquals(ShelfIdentity.Type.MOVIE, d.type);
        assertEquals(2026, d.year);
        assertEquals(c.getJSONObject("expect").getString("title"), d.title);
    }

    @Test public void theSyntheticPageParsesEndToEnd() throws Exception {
        String html;
        try (InputStream in = open("/prime/synthetic_row0.html.gz")) {
            html = new String(readAll(new GZIPInputStream(in)), StandardCharsets.UTF_8);
        }
        PrimeMetadata.Detail d = PrimeMetadata.parse(SYNTHETIC_GTI, html);
        assertEquals("Synthetic Movie: The Test Frontier", d.title);
        assertEquals(2026, d.year);
        assertEquals(ShelfIdentity.Type.MOVIE, d.type);
        assertEquals(SYNTHETIC_GTI, d.gti);
        assertTrue("cast listed", d.cast.size() >= 3);
        assertEquals("Synthetic Actor Alpha", d.cast.get(0));
        assertEquals("", d.seriesTitle);
    }

    @Test public void negativeControlsAllStayUnknown() throws Exception {
        for (String name : new String[]{"neg-page-canonical", "neg-catalogid-mismatch", "neg-item-key-mismatch",
                "neg-trailer-alias", "neg-wrong-production", "neg-no-detail", "neg-missing-year",
                "neg-missing-title", "neg-missing-type"}) {
            JSONObject c = fixture(name);
            PrimeMetadata.Detail d = PrimeMetadata.parse(c.getString("requestedGti"), page(c));
            assertEquals(name + " must be UNKNOWN", ShelfIdentity.Type.UNKNOWN, d.type);
            assertEquals(name + " has no title", "", d.title);
            assertEquals(name + " has no year", 0, d.year);
        }
    }

    @Test public void aTrailerAliasResolvesToTheProductionNotTheTrailer() throws Exception {
        JSONObject c = fixture("neg-trailer-alias");
        String trailer = c.getString("requestedGti");
        // the trailer's own page carries the production's canonical id
        PrimeMetadata.Detail d = PrimeMetadata.parse(trailer, page(c));
        assertEquals(ShelfIdentity.Type.UNKNOWN, d.type);
        assertNotEquals("the trailer must never answer as a production", trailer, d.gti);
    }

    @Test public void malformedInputStaysUnknown() {
        assertEquals(ShelfIdentity.Type.UNKNOWN, PrimeMetadata.parse(null, "<html/>").type);
        assertEquals(ShelfIdentity.Type.UNKNOWN, PrimeMetadata.parse(SYNTHETIC_GTI, "").type);
        assertEquals(ShelfIdentity.Type.UNKNOWN, PrimeMetadata.parse(SYNTHETIC_GTI, "<html>not json</html>").type);
        assertEquals(ShelfIdentity.Type.UNKNOWN, PrimeMetadata.parse(SYNTHETIC_GTI, null).type);
    }

    @Test public void resolveRejectsAMalformedGtiBeforeAnyRequest() {
        final List<String> asked = new ArrayList<>();
        PrimeMetadata.PageSource source = gti -> {
            asked.add(gti);
            return "<html/>";
        };
        assertEquals(ShelfIdentity.Type.UNKNOWN, PrimeMetadata.resolve("not-a-gti", source).type);
        assertEquals(ShelfIdentity.Type.UNKNOWN, PrimeMetadata.resolve("", source).type);
        assertEquals(ShelfIdentity.Type.UNKNOWN, PrimeMetadata.resolve(null, source).type);
        assertEquals("no request for a malformed GTI", 0, asked.size());
    }

    @Test public void resolveKeepsTheCanonicalGtiOfThePage() throws Exception {
        JSONObject c = fixture("row0");
        PrimeMetadata.PageSource source = gti -> page(c);
        PrimeMetadata.Detail d = PrimeMetadata.resolve(c.getString("requestedGti"), source);
        assertEquals(ShelfIdentity.Type.MOVIE, d.type);
        assertEquals(c.getString("requestedGti"), d.gti);
    }

    @Test public void anAccessFailureStaysUnknown() {
        PrimeMetadata.PageSource failing = gti -> {
            throw new IOException("timeout");
        };
        assertEquals(ShelfIdentity.Type.UNKNOWN, PrimeMetadata.resolve(SYNTHETIC_GTI, failing).type);
        PrimeMetadata.PageSource boom = gti -> {
            throw new IllegalStateException("boom");
        };
        assertEquals(ShelfIdentity.Type.UNKNOWN, PrimeMetadata.resolve(SYNTHETIC_GTI, boom).type);
    }

    @Test public void theResponseSizeIsCapped() throws Exception {
        byte[] small = new byte[1024];
        assertEquals(1024, PrimeMetadata.readCapped(new ByteArrayInputStream(small), 4096).length);
        try {
            PrimeMetadata.readCapped(new ByteArrayInputStream(new byte[5000]), 4096);
            fail("an oversized body must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("too large"));
        }
    }

    private static InputStream open(String resource) throws IOException {
        InputStream in = PrimeMetadataTest.class.getResourceAsStream(resource);
        assertNotNull("missing test resource " + resource, in);
        return in;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
