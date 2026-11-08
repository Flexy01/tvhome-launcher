package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata for one Prime Video title, read from the ATV detail page the TV app itself renders.
 *
 * The page is fetched with the Fire TV user agent, which is what makes Prime serve the TV (ATV)
 * template instead of the mobile one. Identity comes from the page, never from the request: the
 * canonical catalog id of the detail item must be exactly the GTI that was asked for. A trailer's
 * GTI resolves to its parent production instead, and a page that carries no such canonical id
 * holds no production at all; both come back as UNKNOWN rather than as a wrong title.
 *
 * The original title is not on this page (only IMDb has it), and nothing here searches IMDb,
 * caches results or touches the UI.
 */
final class PrimeMetadata {

    private static final String DETAIL_URL = "https://www.primevideo.com/detail/";
    /** The Fire TV app's user agent: only it gets the ATV (TV) template with the detail payload. */
    private static final String FIRE_TV_UA = "Dalvik/2.1.0 (Linux; U; Android 9; AFTM Build/PS7233; wv)";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** The detail page carries the whole hydration payload; a bigger body is not the page we expect. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private static final Pattern GTI = Pattern.compile("amzn1\\.dv\\.gti\\.[0-9a-z-]{36}");
    private static final Pattern HYDRATION = Pattern.compile(
            "<script id=\"dv-web-page-hydration-data\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    private PrimeMetadata() {}

    /** One production, as the ATV detail page describes it. */
    static final class Detail {
        /** The canonical catalog id of the page; equal to the requested GTI when the page matched. */
        final String gti;
        final String title;
        final int year;
        /** Movie or series; a season counts as its series. */
        final ShelfIdentity.Type type;
        /** Genre labels, in page order; empty when the page lists none. */
        final List<String> genres;
        /** Cast names, in page order; empty when the page lists none. */
        final List<String> cast;
        /** Series title for a season, empty for a movie. */
        final String seriesTitle;
        /** Season number for a season, 0 for a movie. */
        final int seasonNumber;

        Detail(String gti, String title, int year, ShelfIdentity.Type type, List<String> genres,
               List<String> cast, String seriesTitle, int seasonNumber) {
            this.gti = gti;
            this.title = title;
            this.year = year;
            this.type = type;
            this.genres = genres;
            this.cast = cast;
            this.seriesTitle = seriesTitle;
            this.seasonNumber = seasonNumber;
        }
    }

    /** The safe answer for a GTI that does not resolve to a production. */
    static final Detail UNKNOWN = new Detail("", "", 0, ShelfIdentity.Type.UNKNOWN,
            Collections.emptyList(), Collections.emptyList(), "", 0);

    /** Fetches the ATV detail page for one GTI. */
    interface PageSource {
        String page(String gti) throws IOException;
    }

    /**
     * Resolves one GTI to its production.
     *
     * Returns {@link #UNKNOWN} for a malformed GTI, a page that does not carry the requested GTI
     * as its canonical id (a trailer alias, a redirect to another production, an error page) and
     * for any access failure.
     */
    static Detail resolve(String gti, PageSource source) {
        if (gti == null || source == null) return UNKNOWN;
        String id = gti.trim();
        if (!GTI.matcher(id).matches()) return UNKNOWN;
        try {
            return parse(id, source.page(id));
        } catch (IOException | RuntimeException e) {
            // A page must never crash the launcher, only be UNKNOWN.
            return UNKNOWN;
        }
    }

    /** Resolves one GTI over the network, in the listing language of the TV. */
    static Detail resolve(String gti) {
        return resolve(gti, new HttpPageSource(listingLanguage()));
    }

    /** Prime names its languages "tr_TR", "de_DE": the device language, as Amazon writes it. */
    static String listingLanguage() {
        java.util.Locale locale = java.util.Locale.getDefault();
        String language = locale.getLanguage();
        String country = locale.getCountry();
        if (language.isEmpty()) return "en_US";
        return country.isEmpty() ? language : language + "_" + country;
    }

    /**
     * Reads the production out of an ATV detail page.
     *
     * The page names one canonical production; that id must be the GTI that was asked for, and the
     * detail item must carry the same id. A trailer's page names its production instead, so it can
     * never answer as the trailer. A missing title, year or type leaves the production
     * unidentified, so it is UNKNOWN.
     */
    static Detail parse(String gti, String html) {
        if (gti == null || html == null || html.isEmpty()) return UNKNOWN;
        Matcher m = HYDRATION.matcher(html);
        if (!m.find()) return UNKNOWN;
        JSONObject root;
        try {
            root = new JSONObject(m.group(1));
        } catch (Exception e) {
            // Exception, not JSONException: the app's org.json and the test jar's differ on
            // whether JSONException is checked, and a broken page must only ever be UNKNOWN.
            return UNKNOWN;
        }
        JSONObject body = child(root, "init", "preparations", "body");
        if (body == null) return UNKNOWN;
        JSONObject atf = child(body, "atf", "state");
        JSONObject btf = child(body, "btf", "state");

        // The page's own canonical id: the trailer of a production opens the production's page.
        String canonical = optString(atf, "pageTitleId");
        if (canonical.isEmpty()) canonical = optString(btf, "pageTitleId");
        if (canonical.isEmpty()) canonical = optString(child(atf, "pageContext"), "pageTitleId");
        if (canonical.isEmpty() || !gti.equals(canonical)) return UNKNOWN;

        JSONObject item = optDetail(atf, "headerDetail", gti);
        if (item == null) item = optDetail(btf, "btfMoreDetails", gti);
        if (item == null) item = optDetail(btf, "headerDetail", gti);
        if (item == null) return UNKNOWN;
        if (!gti.equals(optString(item, "catalogId"))) return UNKNOWN;

        String title = optString(item, "title").trim();
        if (title.isEmpty()) return UNKNOWN;
        int year = item.optInt("releaseYear", 0);
        if (year <= 0) return UNKNOWN;

        String titleType = optString(item, "titleType");
        String entityType = optString(item, "entityType");
        ShelfIdentity.Type type;
        String seriesTitle = "";
        int seasonNumber = 0;
        if ("movie".equals(titleType) && "Movie".equals(entityType)) {
            type = ShelfIdentity.Type.MOVIE;
        } else if ("season".equals(titleType) && "TV Show".equals(entityType)) {
            type = ShelfIdentity.Type.SERIES;
            seriesTitle = optString(item, "parentTitle").trim();
            seasonNumber = item.optInt("seasonNumber", 0);
            if (seriesTitle.isEmpty() || seasonNumber <= 0) return UNKNOWN;
        } else {
            return UNKNOWN; // a trailer, a promotion or an episode: not a production
        }

        return new Detail(gti, title, year, type, genreNames(item), castNames(item), seriesTitle, seasonNumber);
    }

    /** Reads at most {@code max} bytes, so a huge or endless body cannot exhaust the memory. */
    static byte[] readCapped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > max) throw new IOException("response too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static JSONObject child(JSONObject parent, String... path) {
        JSONObject current = parent;
        for (String key : path) {
            if (current == null) return null;
            current = current.optJSONObject(key);
        }
        return current;
    }

    private static String optString(JSONObject o, String key) {
        return o == null ? "" : o.optString(key, "");
    }

    private static JSONObject optDetail(JSONObject state, String map, String gti) {
        JSONObject details = child(state, "detail", map);
        return details == null ? null : details.optJSONObject(gti);
    }

    private static List<String> genreNames(JSONObject item) {
        List<String> names = new ArrayList<>();
        JSONArray genres = item.optJSONArray("genres");
        if (genres == null) return names;
        for (int i = 0; i < genres.length(); i++) {
            JSONObject entry = genres.optJSONObject(i);
            if (entry == null) continue;
            String name = entry.optString("text", "").trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    private static List<String> castNames(JSONObject item) {
        List<String> names = new ArrayList<>();
        JSONObject contributors = item.optJSONObject("contributors");
        if (contributors == null) return names;
        JSONArray cast = contributors.optJSONArray("cast");
        if (cast == null) return names;
        for (int i = 0; i < cast.length(); i++) {
            JSONObject entry = cast.optJSONObject(i);
            if (entry == null) continue;
            String name = entry.optString("name", "").trim();
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    /** The ATV detail page over HTTPS, with a timeout and a size cap. */
    static final class HttpPageSource implements PageSource {
        private final String language;

        HttpPageSource(String language) {
            this.language = language;
        }

        @Override public String page(String gti) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(
                    DETAIL_URL + gti + "?language=" + language).openConnection();
            try {
                c.setRequestProperty("User-Agent", FIRE_TV_UA);
                c.setConnectTimeout(CONNECT_TIMEOUT_MS);
                c.setReadTimeout(READ_TIMEOUT_MS);
                if (c.getResponseCode() != 200) throw new IOException("http " + c.getResponseCode());
                if (c.getContentLengthLong() > MAX_BYTES) throw new IOException("response too large");
                try (InputStream in = c.getInputStream()) {
                    return new String(readCapped(in, MAX_BYTES), StandardCharsets.UTF_8);
                }
            } finally {
                c.disconnect();
            }
        }
    }
}
