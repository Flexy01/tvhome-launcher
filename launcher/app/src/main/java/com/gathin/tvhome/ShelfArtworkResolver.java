package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadata discovery module for Top Shelf. Resolves TvContract items
 * against accessible public catalog APIs (Apple iTunes / TVmaze) to discover
 * high-resolution, landscape theatrical backdrops without baked-in logos.
 */
final class ShelfArtworkResolver {

    private static final int TIMEOUT_MS = 6000;
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final Pattern ITUNES_THUMB_PATTERN = Pattern.compile("/\\d+x\\d+bb\\.(jpg|png)$");
    private static final Pattern YEAR_PATTERN = Pattern.compile("^\\b(19\\d\\d|20\\d\\d)\\b");

    interface CatalogClient {
        List<ShelfIdentity.Candidate> search(ShelfIdentity.Query query);
    }

    private ShelfArtworkResolver() {}

    static ShelfIdentity.MatchResult resolve(ShelfIdentity.Query query, CatalogClient client) {
        if (query == null) return null;
        if (client == null) {
            return new ShelfIdentity.MatchResult(null, query.fallbackPoster, 0.0f, true);
        }

        List<ShelfIdentity.Candidate> candidates;
        try {
            candidates = client.search(query);
        } catch (Exception e) {
            candidates = Collections.emptyList();
        }

        ShelfIdentity.MatchResult match = ShelfIdentity.match(query, candidates);
        if (!match.isFallback && match.matched != null) {
            String upgraded = upgradeArtworkUrl(match.resolvedArtwork);
            return new ShelfIdentity.MatchResult(match.matched, upgraded, match.confidence, false);
        }
        return match;
    }

    static String upgradeArtworkUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.contains("mzstatic.com")) {
            Matcher m = ITUNES_THUMB_PATTERN.matcher(url);
            if (m.find()) {
                return m.replaceFirst("/1920x1080bb.$1");
            }
        }
        return url;
    }

    static List<ShelfIdentity.Candidate> parseItunesResults(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        List<ShelfIdentity.Candidate> list = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray results = root.optJSONArray("results");
            if (results == null) return list;

            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;

                String kind = item.optString("kind", "");
                String wrapper = item.optString("wrapperType", "");
                ShelfIdentity.Type type = ShelfIdentity.Type.UNKNOWN;

                if ("feature-movie".equalsIgnoreCase(kind)) {
                    type = ShelfIdentity.Type.MOVIE;
                } else if ("tv-episode".equalsIgnoreCase(kind) || "collection".equalsIgnoreCase(wrapper)) {
                    type = ShelfIdentity.Type.SERIES;
                } else {
                    continue; // Skip non-video assets like audiobooks, podcasts, music
                }

                String title = item.optString("trackName", "");
                if (title.isEmpty()) title = item.optString("collectionName", "");
                if (title.isEmpty()) continue;

                int year = 0;
                String releaseDate = item.optString("releaseDate", "");
                Matcher ym = YEAR_PATTERN.matcher(releaseDate);
                if (ym.find()) year = Integer.parseInt(ym.group(1));

                long idNumber = item.optLong("trackId", item.optLong("collectionId", 0));
                String id = idNumber > 0 ? "itunes:" + idNumber : "itunes:" + title.hashCode();

                String artwork = upgradeArtworkUrl(item.optString("artworkUrl100", ""));
                if (artwork.isEmpty()) continue;

                list.add(new ShelfIdentity.Candidate(id, title, year, type, null, artwork));
            }
        } catch (Exception e) {
            // Malformed JSON falls back safely
        }
        return list;
    }

    static List<ShelfIdentity.Candidate> parseTvmazeResults(String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        List<ShelfIdentity.Candidate> list = new ArrayList<>();
        try {
            if (json.trim().startsWith("{")) {
                // Single show with optional _embedded.images
                JSONObject show = new JSONObject(json);
                ShelfIdentity.Candidate c = parseTvmazeShowObject(show);
                if (c != null) list.add(c);
            } else if (json.trim().startsWith("[")) {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.optJSONObject(i);
                    if (obj == null) continue;
                    JSONObject show = obj.optJSONObject("show");
                    if (show == null) continue;
                    ShelfIdentity.Candidate c = parseTvmazeShowObject(show);
                    if (c != null) list.add(c);
                }
            }
        } catch (Exception ignored) {
            // Malformed JSON falls back safely
        }
        return list;
    }

    private static ShelfIdentity.Candidate parseTvmazeShowObject(JSONObject show) {
        String name = show.optString("name", "");
        if (name.isEmpty()) return null;

        int year = 0;
        String premiered = show.optString("premiered", "");
        Matcher ym = YEAR_PATTERN.matcher(premiered);
        if (ym.find()) year = Integer.parseInt(ym.group(1));

        long idNumber = show.optLong("id", 0);
        String id = idNumber > 0 ? "tvmaze:" + idNumber : "tvmaze:" + name.hashCode();

        String imdbId = null;
        JSONObject externals = show.optJSONObject("externals");
        if (externals != null) {
            String imdb = externals.optString("imdb", "");
            if (!imdb.isEmpty() && !"null".equalsIgnoreCase(imdb)) {
                imdbId = "imdb:" + imdb;
            }
        }

        // Prefer horizontal landscape backdrop from _embedded.images if available
        String artwork = "";
        JSONObject embedded = show.optJSONObject("_embedded");
        if (embedded != null) {
            JSONArray images = embedded.optJSONArray("images");
            if (images != null) {
                for (int i = 0; i < images.length(); i++) {
                    JSONObject img = images.optJSONObject(i);
                    if (img != null && "background".equalsIgnoreCase(img.optString("type"))) {
                        JSONObject res = img.optJSONObject("resolutions");
                        if (res != null) {
                            JSONObject orig = res.optJSONObject("original");
                            if (orig != null && !orig.optString("url").isEmpty()) {
                                artwork = orig.optString("url");
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (artwork.isEmpty()) {
            JSONObject image = show.optJSONObject("image");
            if (image != null) {
                artwork = image.optString("original", image.optString("medium", ""));
            }
        }
        if (artwork.isEmpty()) return null;

        return new ShelfIdentity.Candidate(id, name, year, ShelfIdentity.Type.SERIES, imdbId, artwork);
    }

    // ---- Fanart.tv textless 16:9 backdrops. ----

    private static final Pattern IMDB_TT = Pattern.compile("tt\\d+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final String FANART_ASSETS = "https://assets.fanart.tv/";

    /** Path "tvmaze/..." goes to api.tvmaze.com (no key); anything else to webservice.fanart.tv/v3/. Null on any failure. */
    interface FanartClient { String get(String path); }

    /** Backdrop URLs for a verified production; empty on missing/wrong/ambiguous identity or any failure. */
    static List<String> resolveBackdrops(ShelfIdentity.Query q, FanartClient c) {
        try {
            if (q == null || c == null || q.externalId == null) return Collections.emptyList();
            String ext = q.externalId.trim();
            boolean imdb = ext.startsWith("imdb:") && IMDB_TT.matcher(ext.substring(5)).matches();
            boolean tmdb = ext.startsWith("tmdb:") && DIGITS.matcher(ext.substring(5)).matches();
            if (!imdb && !tmdb) return Collections.emptyList();
            String id = ext.substring(5);

            boolean tv;
            String json, name, candidateId;
            if (q.type == ShelfIdentity.Type.MOVIE) {
                tv = false;
                json = c.get("movies/" + id);
                if (json == null) return Collections.emptyList();
                JSONObject o = new JSONObject(json);
                name = o.optString("name", "");
                // identity comes from the response, never echoed from the query
                candidateId = (imdb ? "imdb:" : "tmdb:") + o.optString(imdb ? "imdb_id" : "tmdb_id", "");
            } else if (q.type == ShelfIdentity.Type.SERIES || q.type == ShelfIdentity.Type.EPISODE) {
                tv = true;
                // ponytail: Fanart TV is keyed by TVDB; the bridge needs IMDb. Add tmdb->tvdb when needed.
                if (!imdb) return Collections.emptyList();
                String look = c.get("tvmaze/lookup/shows?imdb=" + id);
                if (look == null) return Collections.emptyList();
                JSONObject ex = new JSONObject(look).optJSONObject("externals");
                if (ex == null) return Collections.emptyList();
                long tvdb = ex.optLong("thetvdb", 0);
                if (tvdb <= 0) return Collections.emptyList();
                json = c.get("tv/" + tvdb);
                if (json == null) return Collections.emptyList();
                JSONObject o = new JSONObject(json);
                String back = o.optString("thetvdb_id", "");
                if (!back.isEmpty() && !back.equals(String.valueOf(tvdb))) return Collections.emptyList();
                name = o.optString("name", "");
                candidateId = "imdb:" + ex.optString("imdb", ""); // Fanart TV payloads carry no IMDb; use the lookup's
            } else {
                return Collections.emptyList();
            }
            if (name.isEmpty()) return Collections.emptyList();

            ShelfIdentity.Candidate cand = new ShelfIdentity.Candidate(
                    "fanart:" + candidateId, name, 0,
                    tv ? ShelfIdentity.Type.SERIES : ShelfIdentity.Type.MOVIE, candidateId, null);
            // only an exact external-ID match (1.0); title/year scoring is not trusted for backgrounds
            ShelfIdentity.MatchResult m = ShelfIdentity.match(q, Collections.singletonList(cand));
            if (m.isFallback || m.confidence < 1.0f) return Collections.emptyList();
            return parseFanartBackdrops(json, tv);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** Textless (lang "00" or "") https://assets.fanart.tv backdrops, most liked first, ties by id. */
    static List<String> parseFanartBackdrops(String json, boolean tv) {
        List<String> out = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return out;
        try {
            JSONArray arr = new JSONObject(json).optJSONArray(tv ? "showbackground" : "moviebackground");
            if (arr == null) return out;
            List<JSONObject> keep = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String lang = o.optString("lang", "");
                if (!lang.isEmpty() && !"00".equals(lang)) continue;
                if (!o.optString("url", "").startsWith(FANART_ASSETS)) continue;
                if (!acceptsFanartDimensions(o)) continue;
                keep.add(o);
            }
            Collections.sort(keep, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    int byLikes = Long.compare(b.optLong("likes", 0), a.optLong("likes", 0));
                    return byLikes != 0 ? byLikes : Long.compare(a.optLong("id", Long.MAX_VALUE), b.optLong("id", Long.MAX_VALUE));
                }
            });
            for (JSONObject o : keep) out.add(o.optString("url"));
        } catch (Exception ignored) {
            out.clear();
        }
        return out;
    }

    /** Scores a candidate; a negative or non-finite result rejects it. */
    interface BackdropScorer { float score(String url, long rank); }

    static final class DecodedImage {
        final int probeWidth;
        final int probeHeight;
        final ShelfArtworkSelector.PixelSource source;
        final int originalWidth;
        final int originalHeight;
        private final Runnable release;

        DecodedImage(int probeWidth, int probeHeight, ShelfArtworkSelector.PixelSource source,
                     int originalWidth, int originalHeight) {
            this(probeWidth, probeHeight, source, originalWidth, originalHeight, () -> { });
        }

        DecodedImage(int probeWidth, int probeHeight, ShelfArtworkSelector.PixelSource source,
                     int originalWidth, int originalHeight, Runnable release) {
            this.probeWidth = probeWidth;
            this.probeHeight = probeHeight;
            this.source = source;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
            this.release = release;
        }

        void release() { release.run(); }
    }

    interface ImageFetcher { byte[] fetch(String url) throws Exception; }
    interface ImageDecoder { DecodedImage decode(byte[] bytes) throws Exception; }

    static BackdropScorer scoringFanartScorer(final ImageFetcher fetcher, final ImageDecoder decoder, final int likes) {
        return (url, rank) -> {
            DecodedImage img = null;
            try {
                if (fetcher == null || decoder == null || url == null || url.isEmpty()) return -1;
                byte[] bytes = fetcher.fetch(url);
                if (bytes == null || bytes.length == 0) return -1;
                img = decoder.decode(bytes);
                if (img == null || img.source == null || img.probeWidth <= 0 || img.probeWidth > 960
                        || img.probeHeight <= 0 || img.probeHeight > 540
                        || img.originalWidth < 1280 || img.originalHeight < 720) return -1;
                ShelfArtworkSelector.Evaluation e = ShelfArtworkSelector.evaluate(
                        img.source, img.originalWidth, img.originalHeight, likes);
                return e.is16x9 && Float.isFinite(e.totalScore) && e.totalScore >= 0 && e.totalScore <= 1
                        ? e.totalScore : -1;
            } catch (Exception | OutOfMemoryError ignored) {
                return -1;
            } finally {
                if (img != null) img.release();
            }
        };
    }

    private static byte[] fetchFanartImage(String raw) throws java.io.IOException {
        URL url = new URL(raw);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !"assets.fanart.tv".equalsIgnoreCase(url.getHost())
                || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) return null;
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "AppleTVLauncher/1.2 (Android TV)");
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > 4 * 1024 * 1024) return null;
            try (InputStream in = connection.getInputStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count = 0, n;
                while ((n = in.read(buffer)) != -1) {
                    count += n;
                    if (count > 4 * 1024 * 1024) return null;
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static DecodedImage decodeFanartImage(byte[] bytes) {
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        int width = options.outWidth, height = options.outHeight;
        if (width < 1280 || height < 720 || width > 8192 || height > 8192
                || width * 9L != height * 16L) return null;
        options.inSampleSize = 1;
        while ((width + options.inSampleSize - 1) / options.inSampleSize > 960
                || (height + options.inSampleSize - 1) / options.inSampleSize > 540) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) return null;
        try {
            if (bitmap.getWidth() > 960 || bitmap.getHeight() > 540) {
                bitmap.recycle();
                return null;
            }
            ShelfArtworkSelector.PixelSource source = new ShelfArtworkSelector.PixelSource() {
                @Override public int getPixel(int x, int y) { return bitmap.getPixel(x, y); }
                @Override public int getWidth() { return bitmap.getWidth(); }
                @Override public int getHeight() { return bitmap.getHeight(); }
            };
            return new DecodedImage(bitmap.getWidth(), bitmap.getHeight(), source, width, height, bitmap::recycle);
        } catch (RuntimeException | OutOfMemoryError failure) {
            bitmap.recycle();
            throw failure;
        }
    }

    private static final java.util.concurrent.ExecutorService FANART_WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "fanart-backdrop");
                thread.setDaemon(true);
                return thread;
            });

    /** Schedules the complete download/selection sequence off the caller's thread. */
    static java.util.concurrent.CompletableFuture<String> selectFanartBackdropAsync(
            ShelfIdentity.Query q, FanartClient c) {
        java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        try {
            FANART_WORKER.execute(() -> {
                try {
                    result.complete(selectFanartBackdrop(q, c,
                            scoringFanartScorer(ShelfArtworkResolver::fetchFanartImage,
                                    ShelfArtworkResolver::decodeFanartImage, 0)));
                } catch (Exception | OutOfMemoryError ignored) {
                    result.complete(q == null ? null : q.fallbackPoster);
                }
            });
        } catch (RuntimeException | OutOfMemoryError ignored) {
            result.complete(q == null ? null : q.fallbackPoster);
        }
        return result;
    }

    /** Best of the first 6 verified backdrops by scorer; provider poster on any failure. */
    static String selectFanartBackdrop(ShelfIdentity.Query q, FanartClient c, BackdropScorer scorer) {
        try {
            if (q == null || c == null || scorer == null) return q == null ? null : q.fallbackPoster;
            List<String> urls = resolveBackdrops(q, c);
            if (urls.isEmpty()) return q.fallbackPoster;
            String best = null;
            float bestScore = Float.NEGATIVE_INFINITY;
            int n = Math.min(urls.size(), 6);
            for (int i = 0; i < n; i++) {
                String url = urls.get(i);
                if (url == null || url.isEmpty()) continue;
                float s;
                try {
                    s = scorer.score(url, i);
                } catch (Exception ignored) {
                    continue;
                }
                if (Float.isFinite(s) && s >= 0 && s <= 1 && (best == null || s > bestScore)) {
                    best = url;
                    bestScore = s;
                }
            }
            return best == null || bestScore < 0 ? q.fallbackPoster : best;
        } catch (Exception e) {
            return q == null ? null : q.fallbackPoster;
        }
    }

    /** v3.2 gives width/height (usually strings): keep only landscape 16:9 >= 1280x720. */
    static boolean acceptsFanartDimensions(JSONObject o) {
        long w = parseFanartInt(o, "width");
        long h = parseFanartInt(o, "height");
        if (w < 1280 || h < 720 || w > 8192 || h > 8192) return false;
        return h != 0 && w <= Long.MAX_VALUE / 9L && h <= Long.MAX_VALUE / 16L && w * 9L == h * 16L;
    }

    static long parseFanartInt(JSONObject o, String key) {
        try {
            long v = o.optLong(key, -1);
            if (v >= 0) return v; // ponytail: optLong already parsed numbers and numeric strings; strings reach Long.parseLong below.
            Object raw = o.opt(key);
            if (raw == null || JSONObject.NULL.equals(raw)) return -1;
            return Long.parseLong(raw.toString().trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** v3.2 request URL; personal key never goes in the URL. Null on null/empty path. */
    static String fanartUrl(String path, String projectKey) {
        if (path == null || path.trim().isEmpty()) return null;
        String base = "https://webservice.fanart.tv/v3.2/" + path.trim();
        if (projectKey == null || projectKey.trim().isEmpty()) return base;
        try {
            return base + "?api_key=" + URLEncoder.encode(projectKey.trim(), StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        }
    }

    /** v3.2 personal-key header map; empty when no usable personal key. */
    static java.util.Map<String, String> fanartHeaders(String personalKey) {
        if (personalKey == null || personalKey.trim().isEmpty()) return Collections.emptyMap();
        return Collections.singletonMap("client-key", personalKey.trim());
    }

    /** Project (?api_key=) and personal (client-key:) keys; never carries values in toString. */
    static final class FanartKeys {
        final String project;
        final String personal;

        FanartKeys(String project, String personal) {
            this.project = project == null ? "" : project.trim();
            this.personal = personal == null ? "" : personal.trim();
        }

        boolean isEmpty() { return project.isEmpty() && personal.isEmpty(); }

        @Override public String toString() { return "FanartKeys"; }
    }

    /** Distinct config fields; legacy `fanart_api_key` held the PERSONAL key, so it maps to the header only. */
    static FanartKeys readFanartKeys(File dir) {
        if (dir == null) return new FanartKeys(null, null);
        File f = new File(dir, "config.json");
        if (!f.isFile() || f.length() > 64 * 1024) return new FanartKeys(null, null);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            JSONObject o = new JSONObject(text);
            String project = o.optString("fanart_project_key", "").trim();
            String personal = o.optString("fanart_personal_key", "").trim();
            if (personal.isEmpty()) personal = o.optString("fanart_api_key", "").trim(); // legacy held the personal key
            if (project.isEmpty() && personal.isEmpty()) return new FanartKeys(null, null);
            return new FanartKeys(project.isEmpty() ? null : project, personal.isEmpty() ? null : personal);
        } catch (Exception e) {
            return new FanartKeys(null, null); // no message: nothing here may echo the file
        }
    }

    /** Ready client from a config dir; no keys means every get() returns null without network. */
    static FanartClient fanartClient(File dir) {
        if (dir == null) return new DefaultFanartClient(null, null);
        FanartKeys k = readFanartKeys(dir);
        return new DefaultFanartClient(k.project.isEmpty() ? null : k.project, k.personal.isEmpty() ? null : k.personal);
    }

    /** `fanart_api_key` from the on-device config.json (adb-pushed; never in APK/repo/logs); null if absent. */
    static String readApiKey(File dir) {
        if (dir == null) return null;
        File f = new File(dir, "config.json");
        if (!f.isFile() || f.length() > 64 * 1024) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            String k = new JSONObject(text).optString("fanart_api_key", "").trim();
            return k.isEmpty() ? null : k;
        } catch (Exception e) {
            return null; // no message: nothing here may echo the file
        }
    }

    static final class DefaultFanartClient implements FanartClient {
        private final String projectKey;
        private final String personalKey;

        DefaultFanartClient(String apiKey) {
            this(apiKey, null);
        }

        DefaultFanartClient(String projectKey, String personalKey) {
            this.projectKey = projectKey == null ? "" : projectKey.trim();
            this.personalKey = personalKey == null ? "" : personalKey.trim();
        }

        @Override
        public String get(String path) {
            if (path == null || path.isEmpty()) return null;
            if (projectKey.isEmpty() && personalKey.isEmpty()) return null; // no key: skip Fanart entirely
            if (path.startsWith("tvmaze/")) return DefaultCatalogClient.httpGet("https://api.tvmaze.com/" + path.substring(7));
            return DefaultCatalogClient.httpGet(fanartUrl(path, projectKey.isEmpty() ? null : projectKey),
                    fanartHeaders(personalKey.isEmpty() ? null : personalKey));
        }

        @Override public String toString() { return "DefaultFanartClient"; }
    }

    static final class DefaultCatalogClient implements CatalogClient {

        @Override
        public List<ShelfIdentity.Candidate> search(ShelfIdentity.Query query) {
            if (query == null || query.title == null || query.title.trim().isEmpty()) {
                return Collections.emptyList();
            }

            List<ShelfIdentity.Candidate> combined = new ArrayList<>();
            java.util.Set<String> seenIds = new java.util.HashSet<>();
            String cleanQuery = cleanSearchTerm(query.title);
            if (cleanQuery.isEmpty()) return Collections.emptyList();

            try {
                String encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.name());

                // 1. If TV series / episode or unknown: query TVmaze API
                if (query.type == ShelfIdentity.Type.SERIES || query.type == ShelfIdentity.Type.EPISODE
                        || query.type == ShelfIdentity.Type.UNKNOWN) {
                    String singleUrl = "https://api.tvmaze.com/singlesearch/shows?q=" + encoded + "&embed=images";
                    String singleJson = httpGet(singleUrl);
                    if (singleJson != null) {
                        for (ShelfIdentity.Candidate c : parseTvmazeResults(singleJson)) {
                            if (c != null && seenIds.add(c.id)) combined.add(c);
                        }
                    }
                    String tvmazeUrl = "https://api.tvmaze.com/search/shows?q=" + encoded;
                    String json = httpGet(tvmazeUrl);
                    if (json != null) {
                        for (ShelfIdentity.Candidate c : parseTvmazeResults(json)) {
                            if (c != null && seenIds.add(c.id)) combined.add(c);
                        }
                    }
                }

                // 2. Query Apple iTunes Store API for cinematic movie/series catalog
                String itunesUrl = "https://itunes.apple.com/search?term=" + encoded + "&country=us&limit=6";
                String itunesJson = httpGet(itunesUrl);
                if (itunesJson != null) {
                    for (ShelfIdentity.Candidate c : parseItunesResults(itunesJson)) {
                        if (c != null && seenIds.add(c.id)) combined.add(c);
                    }
                }
            } catch (Exception ignored) {
                // Network failures fall back cleanly
            }
            return combined;
        }

        private static String cleanSearchTerm(String title) {
            if (title == null || title.trim().isEmpty()) return "";
            // Extract primary title before localized subtitle or season suffix (e.g. "Dune: Çöl Gezegeni" -> "Dune")
            String primary = title.split("(?i)[–—:]|\\s+-\\s+")[0];
            String s = primary.replaceAll("(?i)\\b(season|sezon|episode|bölüm|bolum|part|kısım|kisim|vol|volume|s\\d+|e\\d+)\\s*\\d*\\b", "");
            s = s.replaceAll("[(\\[](19\\d\\d|20\\d\\d)[)\\]]", "");
            s = s.replaceAll("[^a-zA-Z0-9çğıöşüÇĞİÖŞÜ\\s]", " ");
            return s.replaceAll("\\s+", " ").trim();
        }

        static String httpGet(String address) {
            return httpGet(address, Collections.<String, String>emptyMap());
        }

        static String httpGet(String address, java.util.Map<String, String> headers) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(address);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", "AppleTVLauncher/1.2 (Android TV)");
                connection.setRequestProperty("Accept", "application/json");
                if (headers != null) {
                    for (java.util.Map.Entry<String, String> h : headers.entrySet()) {
                        if (h.getKey() != null && h.getValue() != null) connection.setRequestProperty(h.getKey(), h.getValue());
                    }
                }

                int code = connection.getResponseCode();
                if (code != 200) return null;

                try (InputStream in = connection.getInputStream()) {
                    return new String(PrimeMetadata.readCapped(in, MAX_JSON_BYTES), StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
    }
}
