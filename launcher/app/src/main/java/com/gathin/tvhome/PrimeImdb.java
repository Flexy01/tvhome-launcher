package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The IMDb id of one Prime Video production, verified through Wikidata.
 *
 * The Prime detail page names the production. The IMDb suggestion API proposes candidates; the best one
 * is kept only when Wikidata holds exactly one item for its IMDb id (P345), that item's type (P31) agrees
 * with Prime's and its publication year (P577) does not conflict with Prime's. KESIN means that gate
 * passed. A missing Wikidata year or type is not a verified one, and the caller decides whether that is
 * enough to show. A failed request is NETWORK_ERROR, never a verdict on the title. Only a transient
 * failure is retried, at most twice, after 1 s and then 2 s. Every id comes from a lookup; none is fixed.
 */
final class PrimeImdb {

    static final String KESIN = "KESIN";
    static final String BELIRSIZ = "BELIRSIZ";
    static final String BASARISIZ = "BASARISIZ";
    static final String NETWORK_ERROR = "NETWORK_ERROR";

    private static final String HOST_PRIME = "prime";
    private static final String HOST_SUGGESTIONS = "imdb-sug";
    private static final String HOST_WDSEARCH = "wd-search";
    private static final String HOST_WIKIDATA = "wd-api";
    private static final String SUGGESTIONS = "https://v3.sg.media-imdb.com/suggestion/x/";
    private static final String WIKIDATA_API = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=";
    private static final String WIKIDATA_SEARCH = "https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=";
    private static final String USER_AGENT = "TvHomeLauncher/1.2 (Android TV)";
    /** Wikimedia's policy asks for a contact in the User-Agent; the project's repository is that contact. */
    private static final String WIKIDATA_USER_AGENT = "TvHomeLauncher/1.2 (https://github.com/Flexy01/tvhome-launcher)";
    private static final String TOO_LARGE = "response too large";
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_CANDIDATES = 5;
    private static final int MAX_SUGGESTION_CAST = 4;
    private static final int MAX_WIKIDATA_CAST = 3;
    private static final Pattern GTI = Pattern.compile("amzn1\\.dv\\.gti\\.[0-9a-z-]{36}");
    private static final Pattern IMDB_ID = Pattern.compile("tt\\d{7,9}");
    private static final Pattern NETFLIX_NID = Pattern.compile("\\d{1,12}");
    private static final Pattern YEAR = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b");

    private PrimeImdb() {}

    /** One GET. A status outside 200..399 is an IOException whose message is "http &lt;code&gt;". */
    interface Http {
        String get(String url) throws IOException;
    }

    /** Waits between requests; tests replace it so that they never sleep. */
    interface Sleep {
        void pause(long millis);
    }

    /** One request, which may fail with an IOException. */
    interface Attempt<T> {
        T run() throws IOException;
    }

    /** A status outside 200..399, with the seconds the server asked us to wait (Retry-After) in milliseconds, else 0. */
    static final class HttpStatus extends IOException {
        final long retryAfterMs;

        HttpStatus(int code, long retryAfterMs) {
            super("http " + code);
            this.retryAfterMs = retryAfterMs;
        }
    }

    static boolean isWikidata(String host) {
        return HOST_WDSEARCH.equals(host) || HOST_WIKIDATA.equals(host);
    }

    /** The User-Agent for one request: Wikidata gets the contact-bearing one, every other host keeps the launcher's. */
    static String userAgentFor(String url) {
        return url.startsWith("https://www.wikidata.org/") ? WIKIDATA_USER_AGENT : USER_AGENT;
    }

    /** A Retry-After header in whole seconds, as milliseconds; 0 when absent or not a plain number of seconds. */
    static long retryAfterMs(String header) {
        String t = header == null ? "" : header.trim();
        return t.matches("\\d{1,6}") ? Long.parseLong(t) * 1000 : 0;
    }

    /** A request that failed for good. kind is TIMEOUT, HTTP_&lt;code&gt;, TOO_LARGE or the exception's name. */
    static final class NetworkError extends IOException {
        final String host;
        final String kind;

        NetworkError(String host, String kind, Throwable cause) {
            super(kind + "@" + host, cause);
            this.host = host;
            this.kind = kind;
        }
    }

    /** An answer that arrived but is not the JSON it should be: a data fault, never retried, never a network one. */
    static final class BadAnswer extends RuntimeException {
        BadAnswer(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Paces requests 500 ms apart and retries a transient failure at most twice, after 1 s and then 2 s. Wikidata
     * is one service: a Retry-After from it closes the whole service until that has passed (at most ten minutes),
     * and nothing is sent to it meanwhile, so the background thread never sits out a Wikidata rate limit.
     */
    static final class Net {
        static final long PACE_MS = 500;
        static final int MAX_RETRIES = 2;
        /** The longest Retry-After we sit out on the background thread; a longer one ends the attempt. */
        static final long MAX_RETRY_AFTER_MS = 15_000;
        /** The longest a Wikidata cooldown lasts, whatever Retry-After asks. */
        static final long MAX_COOLDOWN_MS = 600_000;
        private final Http http;
        private final Sleep sleep;
        private final LongSupplier clock;
        private boolean sent;
        private long wikidataCooldownUntil;
        /** Retries and backoff spent so far, for the caller's accounting. */
        int retries;
        long backoffMs;

        Net(Http http, Sleep sleep) {
            this(http, sleep, System::currentTimeMillis);
        }

        Net(Http http, Sleep sleep, LongSupplier clock) {
            this.http = http;
            this.sleep = sleep;
            this.clock = clock;
        }

        String get(String host, String url) throws NetworkError {
            return call(host, () -> http.get(url));
        }

        <T> T call(String host, Attempt<T> attempt) throws NetworkError {
            boolean wikidata = isWikidata(host);
            if (wikidata && clock.getAsLong() < wikidataCooldownUntil) throw new NetworkError(host, "COOLDOWN", null);
            if (sent) sleep.pause(PACE_MS);
            sent = true;
            for (int tries = 0; ; tries++) {
                try {
                    return attempt.run();
                } catch (IOException e) {
                    long asked = e instanceof HttpStatus ? ((HttpStatus) e).retryAfterMs : 0;
                    if (wikidata && asked > 0 && isTransient(e)) {
                        wikidataCooldownUntil = clock.getAsLong() + Math.min(asked, MAX_COOLDOWN_MS);
                        throw new NetworkError(host, kindOf(e), e);
                    }
                    // A server that asks for longer than we may wait is not hammered: stop, the caller retries later.
                    if (tries >= MAX_RETRIES || !isTransient(e) || asked > MAX_RETRY_AFTER_MS) {
                        throw new NetworkError(host, kindOf(e), e);
                    }
                    long wait = Math.max(tries == 0 ? 1000 : 2000, asked);
                    retries++;
                    backoffMs += wait;
                    sleep.pause(wait);
                }
            }
        }
    }

    /** The production GET: 5 s connect and read timeouts, a 4 MB cap and the launcher's own User-Agent. */
    static final class HttpGet implements Http {
        @Override public String get(String address) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
            try {
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", userAgentFor(address));
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code < 200 || code >= 400) {
                    throw new HttpStatus(code, retryAfterMs(c.getHeaderField("Retry-After")));
                }
                if (c.getContentLengthLong() > MAX_BYTES) throw new IOException(TOO_LARGE);
                try (InputStream in = c.getInputStream()) {
                    return new String(PrimeMetadata.readCapped(in, MAX_BYTES), StandardCharsets.UTF_8);
                }
            } finally {
                c.disconnect();
            }
        }
    }

    /** The outcome for one GTI. imdbId is set only for KESIN; netKind names the failed request of a NETWORK_ERROR. */
    static final class Result {
        final String verdict;
        final String imdbId;
        final boolean yearVerified;
        final boolean typeVerified;
        final String title;
        final int year;
        final ShelfIdentity.Type type;
        final String netKind;

        Result(String verdict, String imdbId, boolean yearVerified, boolean typeVerified, String title, int year,
               ShelfIdentity.Type type, String netKind) {
            this.verdict = verdict;
            this.imdbId = imdbId;
            this.yearVerified = yearVerified;
            this.typeVerified = typeVerified;
            this.title = title;
            this.year = year;
            this.type = type;
            this.netKind = netKind;
        }

        static Result of(String verdict) {
            return new Result(verdict, "", false, false, "", 0, ShelfIdentity.Type.UNKNOWN, "");
        }

        static Result network(NetworkError e) {
            return new Result(NETWORK_ERROR, "", false, false, "", 0, ShelfIdentity.Type.UNKNOWN,
                    e.kind + "@" + e.host);
        }

        static Result kesin(String imdbId, boolean yearVerified, boolean typeVerified, PrimeMetadata.Detail d) {
            return new Result(KESIN, imdbId, yearVerified, typeVerified, d.title, d.year, d.type, "");
        }
    }

    /** Verifies one Prime GTI: its detail page first, then its IMDb id. Failures come back as results. */
    static Result verify(String gti, PrimeMetadata.PageSource pages, Net net) {
        if (gti == null || !GTI.matcher(gti).matches()) return Result.of(BASARISIZ);
        PrimeMetadata.Detail d;
        try {
            d = net.call(HOST_PRIME, () -> detailOf(gti, pages));
        } catch (NetworkError e) {
            return Result.network(e);
        }
        return d.type == ShelfIdentity.Type.UNKNOWN ? Result.of(BASARISIZ) : verifyDetail(d, net);
    }

    /** Verifies the IMDb id of one production, given its Prime detail. */
    static Result verifyDetail(PrimeMetadata.Detail d, Net net) {
        String query = d.type == ShelfIdentity.Type.SERIES && !d.seriesTitle.isEmpty()
                ? exactNorm(d.seriesTitle) : normBase(d.title, d.seasonNumber);
        if (!isQueryableNorm(query)) return Result.of(BASARISIZ);
        try {
            return verifyChain(d, query, net);
        } catch (NetworkError e) {
            return Result.network(e);
        } catch (BadAnswer e) {
            return Result.of(BELIRSIZ);
        }
    }

    /**
     * The IMDb id of one Netflix row, verified through Wikidata. The row's Netflix id (P1874) must name exactly one
     * item, whose English title must equal the row's title and whose IMDb id (P345) must lead back to that item
     * alone. P31 gives its kind: a film or a series, never both or neither. An episode, a season or a part of a
     * series is rejected. TvContract's type is no proof for a Netflix row, since Android's TYPE_TV_EPISODE (3) is
     * shared by films and series. A Netflix row carries no year, so none is verified.
     */
    static Result verifyNetflix(String nid, String rowTitle, int typeCode, Net net) {
        if (nid == null || !NETFLIX_NID.matcher(nid).matches()) return Result.of(BASARISIZ);
        String title = exactNorm(rowTitle);
        if (!isQueryableNorm(title)) return Result.of(BASARISIZ);
        try {
            List<String> items = parseSearch(net.get(HOST_WDSEARCH,
                    WIKIDATA_SEARCH + encode("haswbstatement:P1874=" + nid) + "&srlimit=10&format=json"));
            if (items.isEmpty()) return Result.of(BASARISIZ);
            if (items.size() != 1) return Result.of(BELIRSIZ);
            String qid = items.get(0);
            Wiki w = parseEntity(
                    net.get(HOST_WIKIDATA, WIKIDATA_API + encode(qid) + "&props=labels|claims|aliases&format=json"),
                    qid, "");
            if (!titleNamesItem(title, w)) return Result.of(BELIRSIZ);
            if (w.imdbIds.size() != 1 || !IMDB_ID.matcher(w.imdbIds.get(0)).matches()) return Result.of(BELIRSIZ);
            if (w.episode || w.season || w.partOf || w.film == w.series) return Result.of(BELIRSIZ);
            ShelfIdentity.Type kind = w.film ? ShelfIdentity.Type.MOVIE : ShelfIdentity.Type.SERIES;
            if (!netflixTypeFits(typeCode, kind)) return Result.of(BELIRSIZ);
            String tt = w.imdbIds.get(0);
            List<String> back = itemsWithImdb(tt, net);
            if (back.size() != 1 || !qid.equals(back.get(0))) return Result.of(BELIRSIZ);
            return new Result(KESIN, tt, false, true, w.label, 0, kind, "");
        } catch (NetworkError e) {
            return Result.network(e);
        } catch (BadAnswer e) {
            return Result.of(BELIRSIZ);
        }
    }

    /**
     * The Wikidata film of one IMDb id, for a row whose official page names that id. The id (P345) must lead back to
     * exactly one item, whose title must equal the row's title, and that item must be a film alone, never an episode,
     * a season, a part or a series. Its publication year (P577), when known, must agree with the page's year within one.
     */
    static Result verifyImdbFilm(String tt, String rowTitle, int pageYear, Net net) {
        if (tt == null || !IMDB_ID.matcher(tt).matches()) return Result.of(BASARISIZ);
        String title = exactNorm(rowTitle);
        if (!isQueryableNorm(title) || pageYear <= 0) return Result.of(BASARISIZ);
        try {
            List<String> items = itemsWithImdb(tt, net);
            if (items.isEmpty()) return Result.of(BASARISIZ);
            if (items.size() != 1) return Result.of(BELIRSIZ);
            String qid = items.get(0);
            Wiki w = parseEntity(
                    net.get(HOST_WIKIDATA, WIKIDATA_API + encode(qid) + "&props=labels|claims|aliases&format=json"),
                    qid, "");
            if (!titleNamesItem(title, w)) return Result.of(BELIRSIZ);
            if (w.imdbIds.size() != 1 || !tt.equals(w.imdbIds.get(0))) return Result.of(BELIRSIZ);
            if (w.episode || w.season || !w.film || w.series) return Result.of(BASARISIZ);
            if (w.partOf && !partIsFilmSeries(w, net)) return Result.of(BASARISIZ);
            if (w.year > 0 && Math.abs(w.year - pageYear) > 1) return Result.of(BASARISIZ);
            // The page year is verified only when Wikidata gives a year that agrees with it.
            return new Result(KESIN, tt, w.year > 0, true, rowTitle, pageYear, ShelfIdentity.Type.MOVIE, "");
        } catch (NetworkError e) {
            return Result.network(e);
        } catch (BadAnswer e) {
            return Result.of(BELIRSIZ);
        }
    }

    /**
     * Whether the film's one P179 (part of) item is a film series (P31 = Q24856) alone. Only the Turkcell film path asks:
     * a film of a series is a film, while a part of a TV series, a season or an unknown kind still rejects. Its Wikidata
     * request goes through Net, so it is paced and cooled down like the others.
     */
    private static boolean partIsFilmSeries(Wiki w, Net net) throws NetworkError {
        if (w.partQids.size() != 1) return false;
        try {
            Wiki part = parseEntity(
                    net.get(HOST_WIKIDATA, WIKIDATA_API + encode(w.partQids.get(0)) + "&props=labels|claims&format=json"),
                    w.partQids.get(0), "");
            return part.filmSeries && !part.series && !part.episode && !part.season && !part.film;
        } catch (BadAnswer e) {
            return false;
        }
    }

    /**
     * Whether the row's title equals, after exactNorm, the English label, the Turkish label or a Turkish alias of the
     * item. Only exact equality counts: no translation and no fuzzy match, so "Koruyucu (film, 2012)" is not "Koruyucu".
     */
    private static boolean titleNamesItem(String normTitle, Wiki w) {
        if (normTitle.equals(exactNorm(w.label))) return true;
        if (!w.labelTr.isEmpty() && normTitle.equals(exactNorm(w.labelTr))) return true;
        for (String alias : w.aliasesTr) {
            if (normTitle.equals(exactNorm(alias))) return true;
        }
        return false;
    }

    /** TvContract's type against a Wikidata kind: 0 is a film, 1 a series, 3 either (P31 decides); 2, a season, never. */
    private static boolean netflixTypeFits(int typeCode, ShelfIdentity.Type kind) {
        switch (typeCode) {
            case 0: return kind == ShelfIdentity.Type.MOVIE;
            case 1: return kind == ShelfIdentity.Type.SERIES;
            case 3: return true;
            default: return false;
        }
    }

    /** The Wikidata items whose IMDb id (P345) is tt. */
    private static List<String> itemsWithImdb(String tt, Net net) throws NetworkError {
        return parseSearch(net.get(HOST_WDSEARCH,
                WIKIDATA_SEARCH + encode("haswbstatement:P345=" + tt) + "&srlimit=10&format=json"));
    }

    private static Result verifyChain(PrimeMetadata.Detail d, String query, Net net) throws NetworkError {
        List<Suggestion> cands = parseSuggestions(net.get(HOST_SUGGESTIONS, SUGGESTIONS + encode(query) + ".json"));
        if (cands.isEmpty()) return Result.of(BASARISIZ);

        Set<String> dTok = ShelfIdentity.extractTokens(d.title);
        Set<String> castTok = castTokens(d.cast);
        Suggestion best = best(cands, d, dTok, castTok);
        boolean cOk = overlaps(castTok, castTokens(best.cast));

        List<String> items = itemsWithImdb(best.tt, net);
        if (items.size() != 1) return Result.of(BELIRSIZ);
        String qid = items.get(0);
        Wiki w = parseEntity(
                net.get(HOST_WIKIDATA, WIKIDATA_API + encode(qid) + "&props=labels|claims&format=json"),
                qid, best.title);
        if (w.imdbIds.size() != 1 || !best.tt.equals(w.imdbIds.get(0))) return Result.of(BELIRSIZ);

        boolean tOk = overlaps(dTok, ShelfIdentity.extractTokens(w.label))
                || (!w.labelTr.isEmpty() && !w.labelTr.equals(w.label)
                        && overlaps(dTok, ShelfIdentity.extractTokens(w.labelTr)));
        // Wikidata holds only the series' start year for a season, so a later season is consistent, not a conflict.
        boolean yearConflict = yearContradicts(d, w.year);
        boolean wdKnown = w.film || w.series || w.episode;
        boolean typeMatch = d.type == ShelfIdentity.Type.SERIES ? (w.series || w.episode) : w.film;

        boolean castConflict = false;
        if (!d.cast.isEmpty() && !w.castIds.isEmpty()) {
            try {
                Set<String> wdCast = castLabels(w.castIds, net);
                cOk = cOk || overlaps(castTok, wdCast);
                castConflict = !wdCast.isEmpty() && !cOk;
            } catch (NetworkError | BadAnswer e) {
                // The cast lookup only corroborates: when it fails, the verdict does not change.
            }
        }

        if (yearConflict) return Result.of(BASARISIZ);
        if (wdKnown && !typeMatch) return Result.of(BASARISIZ);
        if (!tOk || castConflict) return Result.of(BELIRSIZ);
        boolean yearVerified = w.year > 0 ? !yearConflict : startYearAgrees(d, w, best);
        return Result.kesin(best.tt, yearVerified, wdKnown, d);
    }

    /**
     * Whether a series that Wikidata dates only by its start (P580, no P577) is year-verified: its start must be
     * the start IMDb gives the same matched title, the Prime season must name the series it belongs to, and its
     * title must carry the season's own number, or be the series title itself when Prime leaves the season
     * unlabelled. A "Season N" marker that disagrees with the structured number is a conflict. The season's air
     * year is never compared with the start.
     */
    private static boolean startYearAgrees(PrimeMetadata.Detail d, Wiki w, Suggestion best) {
        if (d.type != ShelfIdentity.Type.SERIES || w.startYear <= 0 || w.startYear != best.year) return false;
        Set<String> parent = ShelfIdentity.extractTokens(d.seriesTitle);
        boolean parentOk = overlaps(parent, ShelfIdentity.extractTokens(w.label))
                || (!w.labelTr.isEmpty() && overlaps(parent, ShelfIdentity.extractTokens(w.labelTr)));
        String title = exactNorm(d.title);
        boolean labelledOk = stripSeasonSuffix(d.title, d.seasonNumber) != null;
        boolean unlabelledOk = d.seasonNumber > 0 && isQueryableNorm(title) && title.equals(exactNorm(d.seriesTitle));
        return parentOk && (labelledOk || unlabelledOk);
    }

    /** The detail page. Where Prime says the page is gone (404/410) that is a data answer, not a network fault. */
    private static PrimeMetadata.Detail detailOf(String gti, PrimeMetadata.PageSource pages) throws IOException {
        String html;
        try {
            html = pages.page(gti);
        } catch (IOException e) {
            int code = httpCode(e);
            if (code == 404 || code == 410) return PrimeMetadata.UNKNOWN;
            throw e;
        }
        return PrimeMetadata.parse(gti, html);
    }

    /** True for a failure worth one more try: a timeout, 429, a 5xx or a connection error without a status. */
    static boolean isTransient(IOException e) {
        if (isTimeout(e)) return true;
        int code = httpCode(e);
        if (code != 0) return code == 429 || (code >= 500 && code <= 599);
        return !TOO_LARGE.equals(e.getMessage());
    }

    static String kindOf(IOException e) {
        if (isTimeout(e)) return "TIMEOUT";
        int code = httpCode(e);
        if (code != 0) return "HTTP_" + code;
        return TOO_LARGE.equals(e.getMessage()) ? "TOO_LARGE" : e.getClass().getSimpleName();
    }

    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) return true;
            String m = t.getMessage();
            if (m != null && m.toLowerCase(Locale.ROOT).contains("timed out")) return true;
        }
        return false;
    }

    static int httpCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.startsWith("http ")) {
                try {
                    return Integer.parseInt(m.substring(5).trim().split("[^0-9]")[0]);
                } catch (NumberFormatException ignored) {
                    // not a status line: keep looking down the cause chain
                }
            }
        }
        return 0;
    }

    static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One IMDb suggestion: the id, the title as IMDb writes it, the year, the type and up to four cast names. */
    static final class Suggestion {
        final String tt;
        final String title;
        final int year;
        final ShelfIdentity.Type type;
        final List<String> cast = new ArrayList<>();

        Suggestion(String tt, String title, int year, ShelfIdentity.Type type) {
            this.tt = tt;
            this.title = title;
            this.year = year;
            this.type = type;
        }
    }

    private static final class Wiki {
        String label = "";
        String labelTr = "";
        List<String> aliasesTr = new ArrayList<>();
        boolean film;
        boolean series;
        boolean episode;
        boolean season;
        boolean partOf;
        boolean filmSeries;
        List<String> partQids = new ArrayList<>();
        int year;
        int startYear;
        List<String> imdbIds = new ArrayList<>();
        List<String> castIds = new ArrayList<>();
    }

    static List<Suggestion> parseSuggestions(String body) {
        List<Suggestion> out = new ArrayList<>();
        JSONArray rows;
        try {
            rows = new JSONObject(body).optJSONArray("d");
        } catch (JSONException e) {
            throw new BadAnswer(e);
        }
        if (rows == null) return out;
        for (int k = 0; k < rows.length() && out.size() < MAX_CANDIDATES; k++) {
            JSONObject row = rows.optJSONObject(k);
            if (row == null) continue;
            String id = row.optString("id", "");
            String name = row.optString("l", "");
            if (!IMDB_ID.matcher(id).matches() || name.isEmpty()) continue;
            Suggestion s = new Suggestion(id, name, row.optInt("y", 0), sugType(row.optString("q", "")));
            for (String part : row.optString("s", "").split(",")) {
                String t = part.trim();
                if (!t.isEmpty() && s.cast.size() < MAX_SUGGESTION_CAST) s.cast.add(t);
            }
            out.add(s);
        }
        return out;
    }

    static ShelfIdentity.Type sugType(String q) {
        String t = q == null ? "" : q.toLowerCase(Locale.ROOT);
        if (t.contains("tv")) return ShelfIdentity.Type.SERIES;
        if (t.contains("feature") || t.contains("movie") || t.contains("video") || t.contains("short")) {
            return ShelfIdentity.Type.MOVIE;
        }
        return ShelfIdentity.Type.UNKNOWN;
    }

    /**
     * Whether Prime's year contradicts the year of the item it is matched to. A movie must agree within one
     * year. A season may air years after its series started, but not before it.
     */
    static boolean yearContradicts(PrimeMetadata.Detail d, int itemYear) {
        if (d.year <= 0 || itemYear <= 0) return false;
        if (d.type == ShelfIdentity.Type.SERIES) return d.year < itemYear - 1;
        return Math.abs(d.year - itemYear) > 1;
    }

    /** Ranks candidates by title overlap, year, type and cast. Ties keep the earlier suggestion. */
    static Suggestion best(List<Suggestion> cands, PrimeMetadata.Detail d, Set<String> titleTok, Set<String> castTok) {
        Suggestion best = cands.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (int k = 0; k < cands.size(); k++) {
            Suggestion c = cands.get(k);
            boolean shared = overlaps(titleTok, ShelfIdentity.extractTokens(c.title));
            boolean yearClash = yearContradicts(d, c.year);
            boolean typeFits = c.type == ShelfIdentity.Type.UNKNOWN
                    || (d.type == ShelfIdentity.Type.SERIES
                            ? c.type == ShelfIdentity.Type.SERIES : c.type == ShelfIdentity.Type.MOVIE);
            int score = (shared ? 2 : -4) + (yearClash ? -4 : 1) + (typeFits ? 1 : -4)
                    + (overlaps(castTok, castTokens(c.cast)) ? 1 : 0) - k;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    static List<String> parseSearch(String body) {
        List<String> out = new ArrayList<>();
        JSONArray hits;
        try {
            JSONObject query = new JSONObject(body).optJSONObject("query");
            hits = query == null ? null : query.optJSONArray("search");
        } catch (JSONException e) {
            throw new BadAnswer(e);
        }
        if (hits == null) return out;
        for (int k = 0; k < hits.length(); k++) {
            JSONObject hit = hits.optJSONObject(k);
            if (hit == null) continue;
            String title = hit.optString("title", "");
            if (!title.isEmpty() && !out.contains(title)) out.add(title);
        }
        return out;
    }

    static Wiki parseEntity(String body, String qid, String fallbackLabel) {
        Wiki w = new Wiki();
        w.label = fallbackLabel;
        try {
            JSONObject entities = new JSONObject(body).optJSONObject("entities");
            JSONObject ent = entities == null ? null : entities.optJSONObject(qid);
            if (ent == null) return w;
            JSONObject labels = ent.optJSONObject("labels");
            if (labels != null) {
                JSONObject en = labels.optJSONObject("en");
                JSONObject tr = labels.optJSONObject("tr");
                if (en != null) w.label = en.optString("value", "");
                if (tr != null) w.labelTr = tr.optString("value", "");
                if (w.label.isEmpty()) w.label = w.labelTr;
                else if (w.labelTr.isEmpty()) w.labelTr = w.label;
            }
            JSONObject aliases = ent.optJSONObject("aliases");
            JSONArray aliasesTr = aliases == null ? null : aliases.optJSONArray("tr");
            if (aliasesTr != null) {
                for (int k = 0; k < aliasesTr.length(); k++) {
                    JSONObject alias = aliasesTr.optJSONObject(k);
                    String value = alias == null ? "" : alias.optString("value", "");
                    if (!value.isEmpty()) w.aliasesTr.add(value);
                }
            }
            JSONObject claims = ent.optJSONObject("claims");
            if (claims != null) {
                w.film = hasId(claims, "P31", "Q11424");
                w.series = hasId(claims, "P31", "Q5398426") || hasId(claims, "P31", "Q15416");
                w.episode = hasId(claims, "P31", "Q21191270");
                w.season = hasId(claims, "P31", "Q3464665");
                w.partQids = ids(claims, "P179", 1);
                w.partOf = !w.partQids.isEmpty();
                w.filmSeries = hasId(claims, "P31", "Q24856");
                w.year = firstYear(claims, "P577");
                w.startYear = firstYear(claims, "P580");
                w.imdbIds = strings(claims, "P345");
                w.castIds = ids(claims, "P161", MAX_WIKIDATA_CAST);
            }
            return w;
        } catch (JSONException e) {
            throw new BadAnswer(e);
        }
    }

    /** Name tokens of the cast members' English (else Turkish, else first) Wikidata labels. */
    static Set<String> parseCastLabels(String body, List<String> ids) {
        Set<String> out = new HashSet<>();
        try {
            JSONObject entities = new JSONObject(body).optJSONObject("entities");
            if (entities == null) return out;
            for (String id : ids) {
                JSONObject ent = entities.optJSONObject(id);
                JSONObject labels = ent == null ? null : ent.optJSONObject("labels");
                if (labels == null) continue;
                JSONObject name = labels.optJSONObject("en");
                if (name == null) name = labels.optJSONObject("tr");
                if (name == null) {
                    Iterator<String> keys = labels.keys();
                    if (keys.hasNext()) name = labels.optJSONObject(keys.next());
                }
                if (name != null) out.addAll(ShelfIdentity.extractTokens(name.optString("value", "")));
            }
            return out;
        } catch (JSONException e) {
            throw new BadAnswer(e);
        }
    }

    private static Set<String> castLabels(List<String> ids, Net net) throws NetworkError {
        StringBuilder param = new StringBuilder();
        for (String id : ids) {
            if (param.length() > 0) param.append('|');
            param.append(id);
        }
        String body = net.get(HOST_WIKIDATA, WIKIDATA_API + encode(param.toString()) + "&props=labels&format=json");
        return parseCastLabels(body, ids);
    }

    private static JSONObject mainValue(JSONObject statement) {
        JSONObject snak = statement == null ? null : statement.optJSONObject("mainsnak");
        JSONObject datavalue = snak == null ? null : snak.optJSONObject("datavalue");
        return datavalue == null ? null : datavalue.optJSONObject("value");
    }

    private static boolean hasId(JSONObject claims, String prop, String qid) {
        JSONArray arr = claims.optJSONArray(prop);
        if (arr == null) return false;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject value = mainValue(arr.optJSONObject(k));
            if (value != null && qid.equals(value.optString("id", ""))) return true;
        }
        return false;
    }

    private static int firstYear(JSONObject claims, String prop) {
        JSONArray arr = claims.optJSONArray(prop);
        if (arr == null) return 0;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject value = mainValue(arr.optJSONObject(k));
            if (value == null) continue;
            Matcher m = YEAR.matcher(value.optString("time", ""));
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private static List<String> strings(JSONObject claims, String prop) {
        List<String> out = new ArrayList<>();
        JSONArray arr = claims.optJSONArray(prop);
        if (arr == null) return out;
        for (int k = 0; k < arr.length(); k++) {
            // mainsnak.datavalue.value is a plain string for P345: keep the exact id, alias it like ids().
            JSONObject snak = arr.optJSONObject(k) == null ? null : arr.optJSONObject(k).optJSONObject("mainsnak");
            JSONObject datavalue = snak == null ? null : snak.optJSONObject("datavalue");
            String id = datavalue == null ? "" : datavalue.optString("value", "");
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
        return out;
    }

    private static List<String> ids(JSONObject claims, String prop, int max) {
        List<String> out = new ArrayList<>();
        JSONArray arr = claims.optJSONArray(prop);
        if (arr == null) return out;
        for (int k = 0; k < arr.length() && out.size() < max; k++) {
            JSONObject value = mainValue(arr.optJSONObject(k));
            String id = value == null ? "" : value.optString("id", "");
            if (!id.isEmpty() && !out.contains(id)) out.add(id);
        }
        return out;
    }

    static Set<String> castTokens(List<String> names) {
        Set<String> out = new HashSet<>();
        for (String name : names) out.addAll(ShelfIdentity.extractTokens(name));
        return out;
    }

    static boolean overlaps(Set<String> a, Set<String> b) {
        for (String t : a) {
            if (b.contains(t)) return true;
        }
        return false;
    }

    static String exactNorm(String s) {
        if (s == null) return "";
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("[\\u2013\\u2014\\u2212]", "-");
        return t.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    static boolean isQueryableNorm(String norm) {
        if (norm == null || norm.isEmpty()) return false;
        String packed = norm.replace(" ", "");
        return packed.length() >= 2 && Pattern.compile("[a-z0-9]").matcher(packed).find();
    }

    /**
     * The series title before a " Season N" suffix, normalized. The match starts at the separator that
     * directly precedes "season", so a title with its own dashes or colons keeps them. Null without that suffix.
     */
    static String stripSeasonSuffix(String title, int seasonNumber) {
        if (title == null || seasonNumber <= 0) return null;
        Matcher m = Pattern.compile("(?i)^(.*?)[\\s\\-:\\u2013\\u2014(|\\[]+(?:sezon|season)\\s*"
                + seasonNumber + "\\b.*$").matcher(title.trim());
        if (!m.matches()) return null;
        String base = m.group(1).trim();
        return base.isEmpty() ? null : exactNorm(base);
    }

    static String normBase(String title, int seasonNumber) {
        String base = stripSeasonSuffix(title, seasonNumber);
        if (isQueryableNorm(base == null ? "" : base)) return base;
        String exact = exactNorm(title);
        return isQueryableNorm(exact) ? exact : "";
    }

    /** The GTI a Top Shelf row's intent carries, after decoding it (rows may be encoded twice); null when none. */
    static String gtiIn(String intentUri) {
        if (intentUri == null || intentUri.isEmpty()) return null;
        String text = intentUri;
        for (int i = 0; i < 2; i++) {
            try {
                text = URLDecoder.decode(text, "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                break;
            }
        }
        Matcher m = GTI.matcher(text);
        if (m.find()) return m.group();
        m = GTI.matcher(intentUri);
        return m.find() ? m.group() : null;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
