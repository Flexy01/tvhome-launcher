package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Presentation only: provider reading and ranking remain in ShelfData. */
final class ShelfPresentation {
    static final long ROTATION_MS = 12_000L; // Project choice; Apple specifies no interval.
    private ShelfPresentation() { }

    static List<ShelfData.Item> bannerItems(List<ShelfData.Item> watching, List<ShelfData.Item> recommended) {
        List<ShelfData.Item> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<ShelfData.Item> source : java.util.Arrays.asList(watching, recommended)) {
            for (ShelfData.Item item : source) {
                if (seen.add(item.pkg + '\n' + item.intent)) result.add(item);
                if (result.size() == 8) return result;
            }
        }
        return result;
    }

    static List<ShelfData.Item> packageItems(String pkg, List<ShelfData.Item> watching, List<ShelfData.Item> recommended) {
        if (pkg == null || pkg.isEmpty()) return java.util.Collections.emptyList();
        List<ShelfData.Item> appItems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<ShelfData.Item> source : java.util.Arrays.asList(watching, recommended)) {
            for (ShelfData.Item item : source) {
                if (pkg.equals(item.pkg) && seen.add(item.pkg + '\n' + item.intent)) {
                    appItems.add(item);
                }
            }
        }
        return appItems;
    }

    static List<ShelfData.Item> bannerItemsForPackage(String pkg, List<ShelfData.Item> watching, List<ShelfData.Item> recommended) {
        if (pkg != null && !pkg.isEmpty()) {
            List<ShelfData.Item> appItems = packageItems(pkg, watching, recommended);
            return appItems.size() <= 8 ? appItems : new ArrayList<>(appItems.subList(0, 8));
        }
        return bannerItems(watching, recommended);
    }

    static List<ShelfData.Item> bannerItemsForPackage(String pkg, List<ShelfData.Item> watching,
            List<ShelfData.Item> recommended, PrimeArtwork primeArtwork) {
        return bannerItemsForPackage(pkg, watching, recommended, item -> {
            String key = PrimeArtwork.keyOf(item.pkg, item.intent, item.contentId);
            return key == null ? null : primeArtwork.backdropFor(key, item.title, item.type);
        });
    }

    static List<ShelfData.Item> bannerItemsForPackage(String pkg, List<ShelfData.Item> watching,
            List<ShelfData.Item> recommended, java.util.function.Function<ShelfData.Item, String> backdropFor) {
        List<ShelfData.Item> out = new ArrayList<>();
        for (ShelfData.Item item : packageItems(pkg, watching, recommended)) {
            String backdrop = backdropFor.apply(item);
            if (backdrop == null) continue;
            out.add(new ShelfData.Item(item.id, item.pkg, item.title, item.intent, backdrop, item.position,
                    item.duration, item.engaged, item.watchNextType, item.type, item.releaseDate, item.contentId, item.author));
            if (out.size() == 8) return out;
        }
        return out;
    }

    static int compareDock(List<String> packages, java.util.Map<String, Integer> saved,
                           String keyA, String pkgA, String keyB, String pkgB) {
        int manual = Order.compare(saved, keyA, keyB);
        if (manual != 0) return manual;
        int a = packages.indexOf(pkgA), b = packages.indexOf(pkgB);
        return Integer.compare(a < 0 ? Integer.MAX_VALUE : a, b < 0 ? Integer.MAX_VALUE : b);
    }

    static boolean sameArtworkTarget(ShelfData.Item requested, String currentPackage, ShelfData.Item active) {
        return requested != null && requested.id > 0 && active != null && requested.id == active.id
                && requested.pkg != null && (currentPackage == null || requested.pkg.equals(currentPackage))
                && requested.pkg.equals(active.pkg) && java.util.Objects.equals(requested.intent, active.intent);
    }

    static boolean canRotate(boolean started, boolean windowFocused, boolean visible, boolean bannerFocused, int count) {
        return started && windowFocused && visible && !bannerFocused && count > 1;
    }

    static String resolveTitle(String pkg, String label, java.util.Map<String, String> packageLabels) {
        if (label != null && !label.isEmpty()) {
            if (packageLabels != null && pkg != null && !pkg.isEmpty()) packageLabels.put(pkg, label);
            return label;
        }
        if (packageLabels != null && pkg != null) {
            String cached = packageLabels.get(pkg);
            if (cached != null && !cached.isEmpty()) return cached;
        }
        return pkg;
    }

    static final class TargetSnapshot {
        final String pkg;
        final String label;

        TargetSnapshot(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        boolean isCurrent(String currentPkg) {
            if (pkg == null) return currentPkg == null;
            return pkg.equals(currentPkg);
        }
    }

    /** The package a load was requested for, with the label that belongs to it. */
    static TargetSnapshot captureTarget(String pkg, java.util.Map<String, String> packageLabels) {
        if (pkg == null) return new TargetSnapshot(null, null);
        String label = packageLabels != null ? packageLabels.get(pkg) : null;
        return new TargetSnapshot(pkg, label);
    }

    /**
     * The target a finished load may paint. The focus can move while the load runs, so the label always
     * comes from the package that is focused now; the captured label is only a fallback for that same package.
     */
    static TargetSnapshot resolveActiveTarget(TargetSnapshot target, String currentPkg, java.util.Map<String, String> packageLabels) {
        if (currentPkg == null) return new TargetSnapshot(null, null);
        String label = packageLabels != null ? packageLabels.get(currentPkg) : null;
        if (label == null && target != null && target.isCurrent(currentPkg)) label = target.label;
        return new TargetSnapshot(currentPkg, label);
    }

    static boolean isYoutubePackage(String pkg) {
        return pkg != null && pkg.toLowerCase(java.util.Locale.ROOT).contains("youtube");
    }

    /**
     * Asynchronous, bounded LRU/TTL oEmbed channel resolver for YouTube Top Shelf rows whose TvContract author is empty.
     * Queries youtube.com/oembed off the UI thread with a validated 11-character video id and accepts author_name only
     * when the returned video title matches the row's TvContract title under PrimeImdb.exactNorm.
     */
    static final class YoutubeOembed {
        static final long SUCCESS_TTL_MS = 6 * 3600 * 1000L;
        static final long FAILURE_TTL_MS = 2 * 60 * 1000L;
        static final int MAX_ENTRIES = 64;
        private static final int TIMEOUT_MS = 4000;
        private static final int MAX_OEMBED_BYTES = 16 * 1024;
        private static final java.util.regex.Pattern VIDEO_ID = java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{11}$");

        interface Callback {
            void onAuthor(ShelfData.Item item, int generation, String author);
        }

        private static final class CacheEntry {
            final String normTitle;
            final String author;
            final long expiresAt;

            CacheEntry(String normTitle, String author, long expiresAt) {
                this.normTitle = normTitle;
                this.author = author;
                this.expiresAt = expiresAt;
            }
        }

        private static final class Pending {
            ShelfData.Item item;
            int generation;

            Pending(ShelfData.Item item, int generation) {
                this.item = item;
                this.generation = generation;
            }
        }

        private final PrimeImdb.Http http;
        private final java.util.function.LongSupplier clock;
        private final java.util.concurrent.Executor executor;
        private final Callback callback;
        private final java.util.Map<String, CacheEntry> cache;
        private final java.util.Map<String, Pending> pending = new java.util.HashMap<>();
        private boolean closed;

        YoutubeOembed(PrimeImdb.Http http, java.util.function.LongSupplier clock,
                      java.util.concurrent.Executor executor, Callback callback, final int maxEntries) {
            this.http = http;
            this.clock = clock;
            this.executor = executor;
            this.callback = callback;
            this.cache = new java.util.LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, CacheEntry> eldest) {
                    return size() > maxEntries;
                }
            };
        }

        static YoutubeOembed forPanel(Callback callback) {
            java.util.concurrent.Executor executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "TvHome.YtOembed");
                thread.setDaemon(true);
                return thread;
            });
            return new YoutubeOembed(YoutubeOembed::httpGet, System::currentTimeMillis, executor, callback, MAX_ENTRIES);
        }

        static String oembedUrl(String videoId) {
            return videoId != null && VIDEO_ID.matcher(videoId).matches()
                    ? "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" + videoId + "&format=json"
                    : null;
        }

        synchronized String cachedAuthor(String intentUri, String title) {
            String videoId = ShelfIdentity.youtubeIdIn(intentUri);
            if (videoId == null) return null;
            CacheEntry entry = cache.get(videoId);
            if (entry == null || entry.expiresAt <= clock.getAsLong()) return null;
            String expected = PrimeImdb.exactNorm(title);
            return entry.author != null && !expected.isEmpty() && expected.equals(entry.normTitle) ? entry.author : null;
        }

        List<ShelfData.Item> applyCachedAuthors(List<ShelfData.Item> items) {
            List<ShelfData.Item> out = new ArrayList<>(items.size());
            for (ShelfData.Item item : items) {
                if (item != null && isYoutubePackage(item.pkg)
                        && (item.author == null || item.author.trim().isEmpty())) {
                    String cached = cachedAuthor(item.intent, item.title);
                    if (cached != null) {
                        out.add(item.withAuthor(cached));
                        continue;
                    }
                }
                out.add(item);
            }
            return out;
        }

        synchronized String authorFor(ShelfData.Item item, int generation) {
            if (item == null || !isYoutubePackage(item.pkg)) return null;
            if (item.author != null && !item.author.trim().isEmpty()) return item.author.trim();
            String videoId = ShelfIdentity.youtubeIdIn(item.intent);
            String url = oembedUrl(videoId);
            String expectedNorm = PrimeImdb.exactNorm(item.title);
            if (url == null || expectedNorm.isEmpty()) return null;
            CacheEntry entry = cache.get(videoId);
            if (entry != null && entry.expiresAt > clock.getAsLong()) {
                return entry.author != null && expectedNorm.equals(entry.normTitle) ? entry.author : null;
            }
            if (closed) return null;
            Pending inFlight = pending.get(videoId);
            if (inFlight != null) {
                inFlight.item = item;
                inFlight.generation = generation;
                return null;
            }
            pending.put(videoId, new Pending(item, generation));
            try {
                executor.execute(() -> resolve(videoId, url));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                pending.remove(videoId);
            }
            return null;
        }

        private void resolve(String videoId, String url) {
            String parsedTitle = "";
            String parsedAuthor = "";
            try {
                String body = http.get(url);
                if (body != null && !body.isEmpty()) {
                    org.json.JSONObject json = new org.json.JSONObject(body);
                    parsedTitle = json.optString("title", "").trim();
                    parsedAuthor = json.optString("author_name", "").trim();
                }
            } catch (Exception ignored) {
                // Network/timeout/JSON failure falls back to existing subtitle format without logging URIs or video IDs
            }
            Pending completed = null;
            String verifiedAuthor = null;
            synchronized (this) {
                Pending req = pending.remove(videoId);
                if (closed) return;
                String normTitle = PrimeImdb.exactNorm(parsedTitle);
                String reqNorm = req == null || req.item == null ? "" : PrimeImdb.exactNorm(req.item.title);
                if (!parsedAuthor.isEmpty() && !normTitle.isEmpty() && normTitle.equals(reqNorm)) {
                    cache.put(videoId, new CacheEntry(normTitle, parsedAuthor, clock.getAsLong() + SUCCESS_TTL_MS));
                    completed = req;
                    verifiedAuthor = parsedAuthor;
                } else {
                    cache.put(videoId, new CacheEntry("", null, clock.getAsLong() + FAILURE_TTL_MS));
                }
            }
            if (completed != null && callback != null) {
                callback.onAuthor(completed.item, completed.generation, verifiedAuthor);
            }
        }

        void close() {
            synchronized (this) {
                closed = true;
                pending.clear();
            }
            if (executor instanceof java.util.concurrent.ExecutorService) {
                ((java.util.concurrent.ExecutorService) executor).shutdownNow();
            }
        }

        private static String httpGet(String address) throws java.io.IOException {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(address).openConnection();
            try {
                c.setRequestMethod("GET");
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("User-Agent", "AppleTVLauncher/1.2 (Android TV)");
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code != 200) throw new java.io.IOException("http " + code);
                if (c.getContentLengthLong() > MAX_OEMBED_BYTES) throw new java.io.IOException("too large");
                try (java.io.InputStream in = c.getInputStream()) {
                    return new String(PrimeMetadata.readCapped(in, MAX_OEMBED_BYTES), java.nio.charset.StandardCharsets.UTF_8);
                }
            } finally {
                c.disconnect();
            }
        }
    }
}
