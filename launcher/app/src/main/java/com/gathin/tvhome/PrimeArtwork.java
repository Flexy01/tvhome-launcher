package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/**
 * The textless 16:9 backdrop of a Prime Video or Netflix row, found through the row's verified IMDb id.
 *
 * A Prime row is shown only with such a backdrop, and only when its year and type are verified in Wikidata.
 * A Netflix row has no provider year: its Netflix id, title and type are verified instead. The provider's
 * poster is never used. Verification runs on one background thread, one production at a time. Each outcome
 * is kept in a bounded memory cache: a backdrop for six hours (a stale one stays on screen while it is
 * verified again), a non-match for one hour and a network failure for two minutes. Verified backdrops are
 * also kept in a small private file, so after a restart they show at once and are verified again in the
 * background. The cache holds GTIs, Netflix ids, IMDb ids and image URLs only, never titles.
 */
final class PrimeArtwork {

    static final String PRIME_PACKAGE = "com.amazon.amazonvideo.livingroom";
    static final String NETFLIX_PACKAGE = "com.netflix.ninja";
    static final String TURKCELL_PACKAGE = "com.turkcell.ott";
    private static final String NETFLIX_KEY = "netflix:";
    private static final String TURKCELL_KEY = "turkcell:";
    static final long BACKDROP_MS = 6 * 3600 * 1000L;
    static final long NO_MATCH_MS = 3600 * 1000L;
    static final long NETWORK_RETRY_MS = 2 * 60 * 1000L;
    static final int MAX_ENTRIES = 64;
    private static final String TAG = "TvHome.PrimeArt";

    /**
     * Verifies one cache key: a Prime GTI, or "netflix:" and a Netflix id. The row's title and TvContract type come
     * along for the Netflix check; the Prime check ignores them. In production this is PrimeImdb.verify over the
     * Prime page, or PrimeImdb.verifyNetflix, both over the network.
     */
    interface Verifier {
        PrimeImdb.Result verify(String key, String title, int typeCode);
    }

    /** Picks the backdrop of a verified production; the future completes with null when none qualifies. */
    interface Backdrops {
        CompletableFuture<String> select(ShelfIdentity.Query query);
    }

    private static final class CacheEntry {
        final String imdbId;
        final String backdrop;
        final long expiresAt;

        CacheEntry(String imdbId, String backdrop, long expiresAt) {
            this.imdbId = imdbId;
            this.backdrop = backdrop;
            this.expiresAt = expiresAt;
        }
    }

    private final Verifier verifier;
    private final Backdrops backdrops;
    private final LongSupplier clock;
    private final Executor executor;
    private final Runnable onChanged;
    private final PrimeBackdropStore store;
    private final Object writeLock = new Object();
    private final Map<String, CacheEntry> cache = new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final Set<String> pending = new HashSet<>();
    private boolean closed;

    PrimeArtwork(Verifier verifier, Backdrops backdrops, LongSupplier clock, Executor executor, Runnable onChanged,
                 PrimeBackdropStore store) {
        this.verifier = verifier;
        this.backdrops = backdrops;
        this.clock = clock;
        this.executor = executor;
        this.onChanged = onChanged;
        this.store = store;
        for (PrimeBackdropStore.Entry e : store.load()) {
            cache.put(e.gti, new CacheEntry(e.imdbId, e.backdrop, e.expiresAt));
        }
    }

    /** The production instance: the Prime page, IMDb and Wikidata over the network, and Fanart backdrops. */
    static PrimeArtwork forPanel(File configDir, File storeFile, Runnable onChanged) {
        PrimeImdb.Net net = new PrimeImdb.Net(new PrimeImdb.HttpGet(), PrimeImdb::sleepQuietly);
        Verifier verifier = (key, title, typeCode) -> {
            PrimeImdb.Result result = isNetflixKey(key)
                    ? PrimeImdb.verifyNetflix(key.substring(NETFLIX_KEY.length()), title, typeCode, net)
                    : isTurkcellKey(key)
                    ? TurkcellArtwork.verify(key.substring(TURKCELL_KEY.length()), title, net)
                    : PrimeImdb.verify(key, new PrimeMetadata.HttpPageSource(PrimeMetadata.listingLanguage()), net);
            android.util.Log.i(TAG, "verdict=" + result.verdict
                    + (result.netKind.isEmpty() ? "" : " net=" + result.netKind));
            return result;
        };
        Backdrops backdrops = query -> ShelfArtworkResolver.selectFanartBackdropAsync(
                query, ShelfArtworkResolver.fanartClient(configDir));
        Executor executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, TAG);
            thread.setDaemon(true);
            return thread;
        });
        return new PrimeArtwork(verifier, backdrops, System::currentTimeMillis, executor, onChanged,
                new PrimeBackdropStore(storeFile));
    }

    /** Whether rows of this app show only their verified backdrop; their provider poster never does. */
    static boolean isVerifiedPackage(String pkg) {
        return PRIME_PACKAGE.equals(pkg) || NETFLIX_PACKAGE.equals(pkg) || TURKCELL_PACKAGE.equals(pkg);
    }

    /**
     * The cache key of a row of a verified app: a Prime GTI, "netflix:" and the Netflix id, or "turkcell:" and the
     * TV+ content id; null otherwise.
     */
    static String keyOf(String pkg, String intentUri, String contentId) {
        if (PRIME_PACKAGE.equals(pkg)) return PrimeImdb.gtiIn(intentUri);
        if (NETFLIX_PACKAGE.equals(pkg)) {
            String nid = ShelfIdentity.netflixIdIn(intentUri);
            return nid == null ? null : NETFLIX_KEY + nid;
        }
        if (TURKCELL_PACKAGE.equals(pkg)) {
            return TurkcellArtwork.isContentId(contentId) ? TURKCELL_KEY + contentId : null;
        }
        return null;
    }

    private static boolean isNetflixKey(String key) {
        return key.startsWith(NETFLIX_KEY);
    }

    private static boolean isTurkcellKey(String key) {
        return key.startsWith(TURKCELL_KEY);
    }

    /**
     * The backdrop of a cache key when one is known, else null. A key that is unknown, or whose entry has expired,
     * is verified in the background with the row's title and type; onChanged runs when its backdrop changes.
     */
    synchronized String backdropFor(String key, String title, int typeCode) {
        CacheEntry known = cache.get(key);
        boolean fresh = known != null && known.expiresAt > clock.getAsLong();
        if (!fresh && !closed && pending.add(key)) {
            try {
                executor.execute(() -> resolve(key, title, typeCode));
            } catch (RejectedExecutionException e) {
                pending.remove(key);
            }
        }
        known = cache.get(key);
        return known == null ? null : known.backdrop;
    }

    /** The entry cached for a key, whether or not it has expired; null when none. */
    private synchronized CacheEntry cachedEntry(String key) {
        return cache.get(key);
    }

    private void resolve(String key, String title, int typeCode) {
        try {
            PrimeImdb.Result result = verifier.verify(key, title, typeCode);
            // A Netflix row has no provider year, so its verified id, title and type are the whole gate.
            boolean yearOk = result.yearVerified || isNetflixKey(key);
            if (PrimeImdb.KESIN.equals(result.verdict) && yearOk && result.typeVerified) {
                dropIfIdentityChanged(key, result.imdbId);
                ShelfIdentity.Query query = new ShelfIdentity.Query(result.title, result.year, result.type,
                        "imdb:" + result.imdbId, null);
                backdrops.select(query).whenComplete((url, error) -> {
                    if (error != null || url == null) finish(key, null, "", NO_MATCH_MS);
                    else finish(key, url, result.imdbId, BACKDROP_MS);
                });
            } else if (PrimeImdb.NETWORK_ERROR.equals(result.verdict)) {
                CacheEntry kept = cachedEntry(key);
                finish(key, kept == null ? null : kept.backdrop, kept == null ? "" : kept.imdbId, NETWORK_RETRY_MS);
            } else {
                finish(key, null, "", NO_MATCH_MS);
            }
        } catch (RuntimeException e) {
            finish(key, null, "", NO_MATCH_MS);
        }
    }

    /** A stored backdrop was picked for another production than the one now verified: it is dropped. */
    private void dropIfIdentityChanged(String key, String imdbId) {
        boolean shown;
        synchronized (this) {
            CacheEntry known = cache.get(key);
            if (known == null || known.imdbId.isEmpty() || known.imdbId.equals(imdbId)) return;
            cache.remove(key);
            shown = known.backdrop != null;
        }
        persist();
        if (shown) onChanged.run();
    }

    private void finish(String key, String backdrop, String imdbId, long ttlMs) {
        boolean changed;
        synchronized (this) {
            CacheEntry previous = cache.put(key, new CacheEntry(imdbId, backdrop, clock.getAsLong() + ttlMs));
            pending.remove(key);
            changed = !closed && (previous == null
                    ? backdrop != null : !Objects.equals(previous.backdrop, backdrop));
        }
        persist();
        if (changed) onChanged.run();
    }

    /** Writes the verified backdrops to the store; one write at a time, and none once closed. */
    private void persist() {
        synchronized (writeLock) {
            List<PrimeBackdropStore.Entry> entries = new ArrayList<>();
            synchronized (this) {
                if (closed) return;
                for (Map.Entry<String, CacheEntry> e : cache.entrySet()) {
                    CacheEntry c = e.getValue();
                    if (c.backdrop != null) {
                        entries.add(new PrimeBackdropStore.Entry(e.getKey(), c.imdbId, c.backdrop, c.expiresAt));
                    }
                }
            }
            store.save(entries);
        }
    }

    /** Stops accepting work; a verification already running finishes into the cache and is then ignored. */
    void close() {
        synchronized (this) {
            closed = true;
            pending.clear();
        }
        if (executor instanceof ExecutorService) ((ExecutorService) executor).shutdownNow();
    }
}
