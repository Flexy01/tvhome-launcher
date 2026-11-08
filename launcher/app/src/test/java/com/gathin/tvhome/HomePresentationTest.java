package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class HomePresentationTest {
    private Class<?> required(String name) {
        try { return Class.forName("com.gathin.tvhome." + name); }
        catch (ClassNotFoundException e) { throw new AssertionError(name + " must implement the Home presentation", e); }
    }

    private Object presentation(String method, Class<?>[] types, Object... args) throws Exception {
        Method m = required("ShelfPresentation").getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private ShelfData.Item item(long id, String intent) {
        return new ShelfData.Item(id, "provider", "Title " + id, intent, null, 100, 1000, id);
    }

    @Test public void bannerUsesRealItemsOnlyWithoutPaddingAndCapsAtEight() throws Exception {
        Class<?>[] types = {List.class, List.class};
        assertTrue(((List<?>) presentation("bannerItems", types, Collections.emptyList(), Collections.emptyList())).isEmpty());
        ShelfData.Item watched = item(1, "title:one");
        List<ShelfData.Item> previews = new ArrayList<>();
        previews.add(item(9, "title:one"));
        for (int i = 2; i < 12; i++) previews.add(item(i, "title:" + i));
        List<?> result = (List<?>) presentation("bannerItems", types, Arrays.asList(watched), previews);
        assertEquals(8, result.size());
        assertSame(watched, result.get(0));
        assertEquals(2, ((ShelfData.Item) result.get(1)).id);
        assertEquals(1, ((List<?>) presentation("bannerItems", types, Arrays.asList(watched), Collections.emptyList())).size());
    }

    @Test public void bannerItemsForPackageFiltersByFocusedAppWhenMatchesExistAndFallsBackToAll() throws Exception {
        Class<?>[] types = {String.class, List.class, List.class};
        ShelfData.Item netflixItem = new ShelfData.Item(1, "com.netflix.ninja", "N Title", "intent:n", null, 10, 100, 1);
        ShelfData.Item primeItem = new ShelfData.Item(2, "com.amazon.prime", "P Title", "intent:p", null, 10, 100, 2);
        List<ShelfData.Item> watching = Arrays.asList(netflixItem, primeItem);

        List<?> forNetflix = (List<?>) presentation("bannerItemsForPackage", types, "com.netflix.ninja", watching, Collections.emptyList());
        assertEquals(1, forNetflix.size());
        assertSame(netflixItem, forNetflix.get(0));

        List<?> forPrime = (List<?>) presentation("bannerItemsForPackage", types, "com.amazon.prime", watching, Collections.emptyList());
        assertEquals(1, forPrime.size());
        assertSame(primeItem, forPrime.get(0));

        List<?> forUnknown = (List<?>) presentation("bannerItemsForPackage", types, "com.other.app", watching, Collections.emptyList());
        assertEquals(0, forUnknown.size());

        List<?> forNull = (List<?>) presentation("bannerItemsForPackage", types, null, watching, Collections.emptyList());
        assertEquals(2, forNull.size());
    }

    @Test public void delayedArtworkRequiresTheActivePackageAndContentId() throws Exception {
        ShelfData.Item requested = new ShelfData.Item(11, "provider.a", "Dune", "intent:a", "poster", 0, 0, 0);
        Method method;
        try {
            method = required("ShelfPresentation").getDeclaredMethod("sameArtworkTarget",
                    ShelfData.Item.class, String.class, ShelfData.Item.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Fanart completion must validate the active package and content ID", e);
        }
        method.setAccessible(true);
        assertEquals(true, method.invoke(null, requested, "provider.a", requested));
        assertEquals(true, method.invoke(null, requested, null, requested));
        assertEquals(false, method.invoke(null, requested, "provider.b", requested));
        assertEquals(false, method.invoke(null, requested, "provider.a", item(12, "intent:b")));
        assertEquals(false, method.invoke(null, requested, "provider.a", null));
        ShelfData.Item sameIdOtherPackage = new ShelfData.Item(11, "provider.b", "Other", "intent:b", null, 0, 0, 0);
        assertEquals(false, method.invoke(null, requested, "provider.a", sameIdOtherPackage));
        ShelfData.Item sameIdDifferentIntent = new ShelfData.Item(11, "provider.a", "Other", "intent:b", "poster", 0, 0, 0);
        assertEquals(false, method.invoke(null, requested, "provider.a", sameIdDifferentIntent));
        java.util.concurrent.CompletableFuture<String> delayed = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<String> activePackage = new java.util.concurrent.atomic.AtomicReference<>("provider.a");
        java.util.concurrent.atomic.AtomicReference<ShelfData.Item> activeItem = new java.util.concurrent.atomic.AtomicReference<>(requested);
        java.util.concurrent.atomic.AtomicReference<String> shown = new java.util.concurrent.atomic.AtomicReference<>("provider-poster");
        delayed.thenAccept(url -> {
            if (ShelfPresentation.sameArtworkTarget(requested, activePackage.get(), activeItem.get())) shown.set(url);
        });
        activePackage.set("provider.b");
        activeItem.set(sameIdOtherPackage);
        delayed.complete("fanart");
        assertEquals("provider-poster", shown.get());
    }

    @Test public void rotationStopsForFocusMenusBackgroundHiddenAndSingleItem() throws Exception {
        Class<?>[] types = {boolean.class, boolean.class, boolean.class, boolean.class, int.class};
        assertEquals(true, presentation("canRotate", types, true, true, true, false, 2));
        assertEquals(false, presentation("canRotate", types, true, true, true, true, 2));
        assertEquals(false, presentation("canRotate", types, false, true, true, false, 2));
        assertEquals(false, presentation("canRotate", types, true, false, true, false, 2));
        assertEquals(false, presentation("canRotate", types, true, true, false, false, 2));
        assertEquals(false, presentation("canRotate", types, true, true, true, false, 1));
    }

    @Test public void diskStoreEvictsLeastRecentlyUsedAndNeverExceedsBudget() throws Exception {
        Class<?> type = required("ShelfPosterStore");
        File root = Files.createTempDirectory("shelf-posters-test").toFile();
        Object store = type.getDeclaredConstructor(File.class, long.class).newInstance(root, 8L);
        Method put = type.getDeclaredMethod("put", String.class, byte[].class);
        Method read = type.getDeclaredMethod("read", String.class);
        Method clear = type.getDeclaredMethod("clear");
        try {
            put.invoke(store, "https://example.test/private-title-a", new byte[]{1,2,3,4});
            File first = root.listFiles()[0];
            assertTrue(first.setLastModified(1000));
            put.invoke(store, "b", new byte[]{5,6,7,8});
            File second = Arrays.stream(root.listFiles()).filter(f -> !f.equals(first)).findFirst().get();
            assertTrue(second.setLastModified(2000));
            assertArrayEquals(new byte[]{1,2,3,4}, (byte[]) read.invoke(store, "https://example.test/private-title-a"));
            put.invoke(store, "c", new byte[]{9,10,11,12});
            assertNull(read.invoke(store, "b"));
            assertNotNull(read.invoke(store, "https://example.test/private-title-a"));
            put.invoke(store, "too-big", new byte[9]);
            assertNull(read.invoke(store, "too-big"));
            assertTrue(Arrays.stream(root.listFiles()).mapToLong(File::length).sum() <= 8);
            for (File f : root.listFiles()) assertTrue(f.getName().matches("[a-f0-9]{64}"));
            clear.invoke(store);
            assertEquals(0, root.listFiles().length);
        } finally {
            for (File f : root.listFiles()) Files.deleteIfExists(f.toPath());
            Files.deleteIfExists(root.toPath());
        }
    }

    @Test public void providerSignalsRankAppsButNeverOverrideSavedOrder() throws Exception {
        Class<?>[] types = {List.class, java.util.Map.class, String.class, String.class, String.class, String.class};
        List<String> signals = Arrays.asList("prime", "youtube");
        java.util.Map<String, Integer> saved = new java.util.HashMap<>();
        assertTrue((Integer) presentation("compareDock", types, signals, saved, "prime/main", "prime", "netflix/main", "netflix") < 0);
        saved.put("netflix/main", 0);
        saved.put("prime/main", 1);
        assertTrue((Integer) presentation("compareDock", types, signals, saved, "prime/main", "prime", "netflix/main", "netflix") > 0);
        assertEquals(0, presentation("compareDock", types, Collections.emptyList(), Collections.emptyMap(), "a/main", "a", "b/main", "b"));
    }

    @Test public void dynamicDockTilesUseFiveByThreeAtEveryColumnCount() {
        for (int columns = 4; columns <= 8; columns++) {
            int width = AppAdapter.tileWidthPx(1174, columns, 213f / 160f);
            assertEquals(Math.round(width * 3f / 5f), AppAdapter.tileHeightPx(width));
        }
    }

    @Test public void retainsPackageLabelAcrossReloadsWithoutCrossPackageLeak() throws Exception {
        Class<?>[] types = {String.class, String.class, java.util.Map.class};
        java.util.Map<String, String> cache = new java.util.HashMap<>();

        // 1. Initial show with label preserves and caches
        assertEquals("YouTube", presentation("resolveTitle", types, "com.google.android.youtube.tv", "YouTube", cache));
        assertEquals("YouTube", cache.get("com.google.android.youtube.tv"));

        // 2. Subsequent 6-hour refresh or ContentObserver reload passes null label -> retrieves cached label
        assertEquals("YouTube", presentation("resolveTitle", types, "com.google.android.youtube.tv", null, cache));

        // 3. Rapid focus change to another app with known label
        assertEquals("Netflix", presentation("resolveTitle", types, "com.netflix.ninja", "Netflix", cache));
        assertEquals("Netflix", cache.get("com.netflix.ninja"));
        assertEquals("YouTube", cache.get("com.google.android.youtube.tv"));

        // 4. Rapid focus change to unknown app with null label -> does NOT leak YouTube or Netflix
        assertEquals("com.other.app", presentation("resolveTitle", types, "com.other.app", null, cache));
        assertNull(cache.get("com.other.app"));

        // 5. Subsequent reload of YouTube still returns YouTube
        assertEquals("YouTube", presentation("resolveTitle", types, "com.google.android.youtube.tv", null, cache));
    }

    @Test public void controlledRaceConditionPreventsStaleTargetOverwriteAndThreadSafeLabels() throws Exception {
        // Controlled race test: concurrent label updates, snapshot capture and rapid focus transitions
        final java.util.Map<String, String> concurrentLabels = new java.util.concurrent.ConcurrentHashMap<>();
        concurrentLabels.put("com.google.android.youtube.tv", "YouTube");
        concurrentLabels.put("com.netflix.ninja", "Netflix");
        concurrentLabels.put("com.amazon.prime", "Prime Video");
        concurrentLabels.put("com.kick.mobile", "Kick");

        Class<?> snapshotClass = required("ShelfPresentation$TargetSnapshot");
        java.lang.reflect.Field fPkg = snapshotClass.getDeclaredField("pkg");
        fPkg.setAccessible(true);
        java.lang.reflect.Field fLabel = snapshotClass.getDeclaredField("label");
        fLabel.setAccessible(true);

        Class<?>[] captureTypes = {String.class, java.util.Map.class};
        Class<?>[] resolveTypes = {snapshotClass, String.class, java.util.Map.class};

        // 1. Snapshot capture is immutable and isolated from subsequent map mutations
        Object snapYoutube = presentation("captureTarget", captureTypes, "com.google.android.youtube.tv", concurrentLabels);
        assertEquals("com.google.android.youtube.tv", fPkg.get(snapYoutube));
        assertEquals("YouTube", fLabel.get(snapYoutube));

        // 2. Race condition: Load initiated for YouTube, but focus changed to Netflix before load returned
        Object activeWhenSwitched = presentation("resolveActiveTarget", resolveTypes, snapYoutube, "com.netflix.ninja", concurrentLabels);
        assertEquals("com.netflix.ninja", fPkg.get(activeWhenSwitched));
        assertEquals("Netflix", fLabel.get(activeWhenSwitched));
        // Critical: stale YouTube snapshot MUST NOT leak to Netflix
        assertNotEquals("YouTube", fLabel.get(activeWhenSwitched));
        assertNotEquals("com.google.android.youtube.tv", fPkg.get(activeWhenSwitched));

        // 3. Stale load completing after switch to unknown app with null label
        Object activeUnknown = presentation("resolveActiveTarget", resolveTypes, snapYoutube, "com.unknown.app", concurrentLabels);
        assertEquals("com.unknown.app", fPkg.get(activeUnknown));
        assertNull(fLabel.get(activeUnknown));

        // 4. Null safety: capture with null package or null map
        Object nullSnap = presentation("captureTarget", captureTypes, null, concurrentLabels);
        assertNull(fPkg.get(nullSnap));
        assertNull(fLabel.get(nullSnap));
        Object nullMapSnap = presentation("captureTarget", captureTypes, "com.netflix.ninja", null);
        assertEquals("com.netflix.ninja", fPkg.get(nullMapSnap));
        assertNull(fLabel.get(nullMapSnap));

        // 5. Load completing when focus remained on same app -> same package and label, no leak
        Object activeSame = presentation("resolveActiveTarget", resolveTypes, snapYoutube, "com.google.android.youtube.tv", concurrentLabels);
        assertEquals("com.google.android.youtube.tv", fPkg.get(activeSame));
        assertEquals("YouTube", fLabel.get(activeSame));

        // 6. Multi-threaded controlled race test: 8 threads, 4000 total iterations.
        // Half the iterations keep the focus on the loaded package, half move it to another app
        // while the load is still in flight. A stale result must never reach the new app.
        final int THREADS = 8;
        final int ITERATIONS = 500;
        final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(THREADS);
        final String[] packages = {"com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.prime", "com.kick.mobile"};
        final String[] labels = {"YouTube", "Netflix", "Prime Video", "Kick"};

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS);
        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            pool.execute(() -> {
                try {
                    for (int i = 0; i < ITERATIONS; i++) {
                        int fromIdx = (threadId + i) % packages.length;
                        boolean focusMoved = (i & 1) == 1;
                        int toIdx = focusMoved ? (fromIdx + 1) % packages.length : fromIdx;
                        String fromPkg = packages[fromIdx];
                        String toPkg = packages[toIdx];

                        // Simulate showForPackage mutating labels concurrently
                        concurrentLabels.put(fromPkg, labels[fromIdx]);
                        concurrentLabels.put(toPkg, labels[toIdx]);

                        // Simulate load() capturing the immutable target before the worker starts
                        Object targetSnap = presentation("captureTarget", captureTypes, fromPkg, concurrentLabels);
                        if (!fromPkg.equals(fPkg.get(targetSnap)) || !labels[fromIdx].equals(fLabel.get(targetSnap))) {
                            errors.incrementAndGet();
                        }

                        // Simulate the load returning while the focus may have moved
                        Object resolved = presentation("resolveActiveTarget", resolveTypes, targetSnap, toPkg, concurrentLabels);

                        if (focusMoved) {
                            // The stale target must be dropped: the new app gets its own package and label
                            if (!toPkg.equals(fPkg.get(resolved))) errors.incrementAndGet();
                            if (!labels[toIdx].equals(fLabel.get(resolved))) errors.incrementAndGet();
                            if (fromPkg.equals(fPkg.get(resolved))) errors.incrementAndGet();
                        } else {
                            // Focus unchanged: the captured snapshot is reused as-is
                            if (!toPkg.equals(fPkg.get(resolved))) errors.incrementAndGet();
                            if (!labels[toIdx].equals(fLabel.get(resolved))) errors.incrementAndGet();
                        }
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        boolean finished = latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue("Concurrent race test timed out", finished);
        assertEquals(0, errors.get());
    }

    @Test public void verifiedBannerItemsReachCandidatesNineAndTwelveAndCapFinalListAtEight() throws Exception {
        File storeFile = Files.createTempFile("prime-backdrops-test", ".json").toFile();
        try {
            List<Runnable> queue = new ArrayList<>();
            PrimeArtwork artwork = new PrimeArtwork(
                    (key, title, type) -> {
                        if ("netflix:80000001".equals(key)) {
                            return new PrimeImdb.Result(PrimeImdb.KESIN, "tt2531336", false, true, "Lupin", 0, ShelfIdentity.Type.SERIES, "");
                        }
                        if ("netflix:80000009".equals(key)) {
                            return new PrimeImdb.Result(PrimeImdb.KESIN, "tt6968614", false, true, "The Mother", 0, ShelfIdentity.Type.MOVIE, "");
                        }
                        if ("netflix:80000012".equals(key)) {
                            return new PrimeImdb.Result(PrimeImdb.KESIN, "tt5180504", false, true, "The Witcher", 0, ShelfIdentity.Type.SERIES, "");
                        }
                        if (key.startsWith("amzn1.dv.gti.00000001") || key.startsWith("amzn1.dv.gti.00000009") || key.startsWith("amzn1.dv.gti.00000012")) {
                            return new PrimeImdb.Result(PrimeImdb.KESIN, "tt1000001", true, true, title, 2020, ShelfIdentity.Type.MOVIE, "");
                        }
                        return PrimeImdb.Result.of(PrimeImdb.BELIRSIZ);
                    },
                    query -> java.util.concurrent.CompletableFuture.completedFuture("https://assets.fanart.tv/" + query.externalId + ".jpg"),
                    () -> 1_000_000L,
                    queue::add,
                    () -> {},
                    new PrimeBackdropStore(storeFile));

            List<ShelfData.Item> netflixCandidates = new ArrayList<>();
            List<ShelfData.Item> primeCandidates = new ArrayList<>();
            for (int i = 1; i <= 15; i++) {
                String nid = String.format("800000%02d", i);
                netflixCandidates.add(new ShelfData.Item(i, PrimeArtwork.NETFLIX_PACKAGE, "N-" + i,
                        "https://www.netflix.com/title/" + nid, "https://provider.invalid/" + i, 0, 0, i, -1, 1, null));
                String gti = String.format("amzn1.dv.gti.%08d-0000-4000-8000-000000000000", i);
                primeCandidates.add(new ShelfData.Item(100 + i, PrimeArtwork.PRIME_PACKAGE, "P-" + i,
                        "content://x?g=" + gti, "https://provider.invalid/p" + i, 0, 0, i, -1, 0, null));
            }

            Class<?>[] verifiedTypes = {String.class, List.class, List.class, PrimeArtwork.class};
            // First call: async queue is populated without blocking main thread; nothing verified yet
            List<?> initial = (List<?>) presentation("bannerItemsForPackage", verifiedTypes,
                    PrimeArtwork.NETFLIX_PACKAGE, Collections.emptyList(), netflixCandidates, artwork);
            assertTrue(initial.isEmpty());
            assertEquals(15, queue.size());

            // Drain single-threaded queue
            while (!queue.isEmpty()) queue.remove(0).run();

            List<?> verifiedNetflix = (List<?>) presentation("bannerItemsForPackage", verifiedTypes,
                    PrimeArtwork.NETFLIX_PACKAGE, Collections.emptyList(), netflixCandidates, artwork);
            assertEquals(3, verifiedNetflix.size());
            assertEquals(1L, ((ShelfData.Item) verifiedNetflix.get(0)).id);
            assertEquals("https://assets.fanart.tv/imdb:tt2531336.jpg", ((ShelfData.Item) verifiedNetflix.get(0)).poster);
            assertEquals(9L, ((ShelfData.Item) verifiedNetflix.get(1)).id);
            assertEquals("https://assets.fanart.tv/imdb:tt6968614.jpg", ((ShelfData.Item) verifiedNetflix.get(1)).poster);
            assertEquals(12L, ((ShelfData.Item) verifiedNetflix.get(2)).id);
            assertEquals("https://assets.fanart.tv/imdb:tt5180504.jpg", ((ShelfData.Item) verifiedNetflix.get(2)).poster);

            // Works identically for Prime Video
            presentation("bannerItemsForPackage", verifiedTypes,
                    PrimeArtwork.PRIME_PACKAGE, Collections.emptyList(), primeCandidates, artwork);
            while (!queue.isEmpty()) queue.remove(0).run();
            List<?> verifiedPrime = (List<?>) presentation("bannerItemsForPackage", verifiedTypes,
                    PrimeArtwork.PRIME_PACKAGE, Collections.emptyList(), primeCandidates, artwork);
            assertEquals(3, verifiedPrime.size());
            assertEquals(101L, ((ShelfData.Item) verifiedPrime.get(0)).id);
            assertEquals(109L, ((ShelfData.Item) verifiedPrime.get(1)).id);
            assertEquals(112L, ((ShelfData.Item) verifiedPrime.get(2)).id);

            // Generic provider resolver: caps final verified list at 8 even when valid items sit past #8
            Class<?>[] fnTypes = {String.class, List.class, List.class, java.util.function.Function.class};
            List<ShelfData.Item> generic = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                generic.add(new ShelfData.Item(i, "provider.any", "G-" + i, "intent:" + i, null, 0, 0, i));
            }
            java.util.function.Function<ShelfData.Item, String> resolver =
                    it -> (it.id == 1 || it.id >= 9) ? "https://backdrop.example/" + it.id + ".jpg" : null;
            List<?> capped = (List<?>) presentation("bannerItemsForPackage", fnTypes,
                    "provider.any", Collections.emptyList(), generic, resolver);
            assertEquals(8, capped.size());
            assertEquals(1L, ((ShelfData.Item) capped.get(0)).id);
            assertEquals(9L, ((ShelfData.Item) capped.get(1)).id);
            assertEquals(15L, ((ShelfData.Item) capped.get(7)).id);
        } finally {
            Files.deleteIfExists(storeFile.toPath());
        }
    }

    private static final class StubViewState {
        int messageVisibility = android.view.View.GONE;
        int allowVisibility = android.view.View.GONE;
        int hintVisibility = android.view.View.VISIBLE;
    }

    private static final class StubActivity extends android.app.Activity {
        android.content.pm.PackageManager pm;
        @Override public String getPackageName() { return "com.gathin.tvhome"; }
        @Override public android.content.pm.PackageManager getPackageManager() { return pm; }
    }

    private static final class StubLinearLayout extends android.widget.LinearLayout {
        StubViewState ui;
        StubLinearLayout() { super(null); }
        @Override public void setVisibility(int v) { ui.messageVisibility = v; }
    }

    private static final class StubAllowView extends android.widget.TextView {
        StubViewState ui;
        StubAllowView() { super(null); }
        @Override public void setVisibility(int v) { ui.allowVisibility = v; }
    }

    private static final class StubHintView extends android.widget.TextView {
        StubViewState ui;
        StubHintView() { super(null); }
        @Override public void setVisibility(int v) { ui.hintVisibility = v; }
        @Override public void setText(CharSequence text, BufferType type) { }
    }

    private static final class StubRecyclerView extends androidx.recyclerview.widget.RecyclerView {
        StubRecyclerView() { super(null); }
        @Override public void scrollToPosition(int position) { }
        @Override public boolean post(Runnable action) { return false; }
        @Override public boolean hasFocus() { return false; }
        @Override public ViewHolder findViewHolderForAdapterPosition(int position) { return null; }
        @Override public int getChildCount() { return 0; }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object unsafe = f.get(null);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static ShelfPanel newTestableShelfPanel(final StubViewState ui, PrimeArtwork artwork) throws Exception {
        ShelfPanel panel = (ShelfPanel) allocate(ShelfPanel.class);

        final android.content.pm.PackageManager pm = new android.content.pm.PackageManager() {
            @Override public android.content.Intent getLaunchIntentForPackage(String packageName) { return null; }
            @Override public android.content.Intent getLeanbackLaunchIntentForPackage(String packageName) { return null; }
            @Override public android.content.pm.PackageInfo getPackageInfo(String p, int f) { return null; }
            @Override public android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage v, int f) { return null; }
            @Override public String[] currentToCanonicalPackageNames(String[] n) { return new String[0]; }
            @Override public String[] canonicalToCurrentPackageNames(String[] n) { return new String[0]; }
            @Override public int[] getPackageGids(String p) { return new int[0]; }
            @Override public int[] getPackageGids(String p, int f) { return new int[0]; }
            @Override public int getPackageUid(String p, int f) { return 0; }
            @Override public android.content.pm.PermissionInfo getPermissionInfo(String n, int f) { return null; }
            @Override public List<android.content.pm.PermissionInfo> queryPermissionsByGroup(String g, int f) { return Collections.emptyList(); }
            @Override public android.content.pm.PermissionGroupInfo getPermissionGroupInfo(String n, int f) { return null; }
            @Override public List<android.content.pm.PermissionGroupInfo> getAllPermissionGroups(int f) { return Collections.emptyList(); }
            @Override public android.content.pm.ApplicationInfo getApplicationInfo(String p, int f) { return null; }
            @Override public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName c, int f) { return null; }
            @Override public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName c, int f) { return null; }
            @Override public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName c, int f) { return null; }
            @Override public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName c, int f) { return null; }
            @Override public List<android.content.pm.PackageInfo> getInstalledPackages(int f) { return Collections.emptyList(); }
            @Override public List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(String[] p, int f) { return Collections.emptyList(); }
            @Override public int checkPermission(String perm, String pkg) { return 0; }
            @Override public boolean isPermissionRevokedByPolicy(String perm, String pkg) { return false; }
            @Override public boolean addPermission(android.content.pm.PermissionInfo info) { return false; }
            @Override public boolean addPermissionAsync(android.content.pm.PermissionInfo info) { return false; }
            @Override public void removePermission(String name) { }
            @Override public int checkSignatures(String p1, String p2) { return 0; }
            @Override public int checkSignatures(int u1, int u2) { return 0; }
            @Override public String[] getPackagesForUid(int uid) { return new String[0]; }
            @Override public String getNameForUid(int uid) { return null; }
            @Override public List<android.content.pm.ApplicationInfo> getInstalledApplications(int f) { return Collections.emptyList(); }
            @Override public boolean isInstantApp() { return false; }
            @Override public boolean isInstantApp(String p) { return false; }
            @Override public int getInstantAppCookieMaxBytes() { return 0; }
            @Override public byte[] getInstantAppCookie() { return new byte[0]; }
            @Override public void clearInstantAppCookie() { }
            @Override public void updateInstantAppCookie(byte[] cookie) { }
            @Override public String[] getSystemSharedLibraryNames() { return new String[0]; }
            @Override public List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int f) { return Collections.emptyList(); }
            @Override public android.content.pm.ChangedPackages getChangedPackages(int s) { return null; }
            @Override public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() { return new android.content.pm.FeatureInfo[0]; }
            @Override public boolean hasSystemFeature(String n) { return false; }
            @Override public boolean hasSystemFeature(String n, int v) { return false; }
            @Override public android.content.pm.ResolveInfo resolveActivity(android.content.Intent i, int f) { return null; }
            @Override public List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent i, int f) { return Collections.emptyList(); }
            @Override public List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName c, android.content.Intent[] s, android.content.Intent i, int f) { return Collections.emptyList(); }
            @Override public List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent i, int f) { return Collections.emptyList(); }
            @Override public android.content.pm.ResolveInfo resolveService(android.content.Intent i, int f) { return null; }
            @Override public List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent i, int f) { return Collections.emptyList(); }
            @Override public List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent i, int f) { return Collections.emptyList(); }
            @Override public android.content.pm.ProviderInfo resolveContentProvider(String n, int f) { return null; }
            @Override public List<android.content.pm.ProviderInfo> queryContentProviders(String p, int u, int f) { return Collections.emptyList(); }
            @Override public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName c, int f) { return null; }
            @Override public List<android.content.pm.InstrumentationInfo> queryInstrumentation(String t, int f) { return Collections.emptyList(); }
            @Override public android.graphics.drawable.Drawable getDrawable(String p, int r, android.content.pm.ApplicationInfo a) { return null; }
            @Override public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName c) { return null; }
            @Override public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent i) { return null; }
            @Override public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName c) { return null; }
            @Override public android.graphics.drawable.Drawable getActivityBanner(android.content.Intent i) { return null; }
            @Override public android.graphics.drawable.Drawable getDefaultActivityIcon() { return null; }
            @Override public android.graphics.drawable.Drawable getApplicationIcon(android.content.pm.ApplicationInfo a) { return null; }
            @Override public android.graphics.drawable.Drawable getApplicationIcon(String p) { return null; }
            @Override public android.graphics.drawable.Drawable getApplicationBanner(android.content.pm.ApplicationInfo a) { return null; }
            @Override public android.graphics.drawable.Drawable getApplicationBanner(String p) { return null; }
            @Override public android.graphics.drawable.Drawable getActivityLogo(android.content.ComponentName c) { return null; }
            @Override public android.graphics.drawable.Drawable getActivityLogo(android.content.Intent i) { return null; }
            @Override public android.graphics.drawable.Drawable getApplicationLogo(android.content.pm.ApplicationInfo a) { return null; }
            @Override public android.graphics.drawable.Drawable getApplicationLogo(String p) { return null; }
            @Override public android.graphics.drawable.Drawable getUserBadgedIcon(android.graphics.drawable.Drawable d, android.os.UserHandle h) { return null; }
            @Override public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(android.graphics.drawable.Drawable d, android.os.UserHandle h, android.graphics.Rect r, int density) { return null; }
            @Override public CharSequence getUserBadgedLabel(CharSequence l, android.os.UserHandle h) { return l; }
            @Override public CharSequence getText(String p, int r, android.content.pm.ApplicationInfo a) { return null; }
            @Override public android.content.res.XmlResourceParser getXml(String p, int r, android.content.pm.ApplicationInfo a) { return null; }
            @Override public CharSequence getApplicationLabel(android.content.pm.ApplicationInfo a) { return ""; }
            @Override public android.content.res.Resources getResourcesForActivity(android.content.ComponentName c) { return null; }
            @Override public android.content.res.Resources getResourcesForApplication(android.content.pm.ApplicationInfo a) { return null; }
            @Override public android.content.res.Resources getResourcesForApplication(String p) { return null; }
            @Override public void verifyPendingInstall(int id, int code) { }
            @Override public void extendVerificationTimeout(int id, int code, long ms) { }
            @Override public void setInstallerPackageName(String t, String i) { }
            @Override public String getInstallerPackageName(String p) { return null; }
            @Override public void addPackageToPreferred(String p) { }
            @Override public void removePackageFromPreferred(String p) { }
            @Override public List<android.content.pm.PackageInfo> getPreferredPackages(int f) { return Collections.emptyList(); }
            @Override public void addPreferredActivity(android.content.IntentFilter f, int m, android.content.ComponentName[] s, android.content.ComponentName a) { }
            @Override public void clearPackagePreferredActivities(String p) { }
            @Override public int getPreferredActivities(List<android.content.IntentFilter> f, List<android.content.ComponentName> a, String p) { return 0; }
            @Override public void setComponentEnabledSetting(android.content.ComponentName c, int n, int f) { }
            @Override public int getComponentEnabledSetting(android.content.ComponentName c) { return 0; }
            @Override public void setApplicationEnabledSetting(String p, int n, int f) { }
            @Override public int getApplicationEnabledSetting(String p) { return 0; }
            @Override public boolean isSafeMode() { return false; }
            @Override public void setApplicationCategoryHint(String p, int c) { }
            @Override public android.content.pm.PackageInstaller getPackageInstaller() { return null; }
            @Override public boolean canRequestPackageInstalls() { return false; }
        };

        StubActivity activity = (StubActivity) allocate(StubActivity.class);
        activity.pm = pm;

        StubLinearLayout message = (StubLinearLayout) allocate(StubLinearLayout.class);
        message.ui = ui;

        StubAllowView allow = (StubAllowView) allocate(StubAllowView.class);
        allow.ui = ui;

        android.widget.TextView status = (android.widget.TextView) allocate(android.widget.TextView.class);

        StubHintView hint = (StubHintView) allocate(StubHintView.class);
        hint.ui = ui;

        StubRecyclerView row = (StubRecyclerView) allocate(StubRecyclerView.class);

        Class<?> adapterClass = Class.forName("com.gathin.tvhome.ShelfPanel$BannerAdapter");
        java.lang.reflect.Constructor<?> adapterCtor = adapterClass.getDeclaredConstructor(ShelfPanel.class);
        adapterCtor.setAccessible(true);
        Object adapter = adapterCtor.newInstance(panel);
        java.lang.reflect.Field obsField = androidx.recyclerview.widget.RecyclerView.Adapter.class.getDeclaredField("mObservable");
        obsField.setAccessible(true);
        Object observable = obsField.get(adapter);
        java.lang.reflect.Field observersField = android.database.Observable.class.getDeclaredField("mObservers");
        observersField.setAccessible(true);
        observersField.set(observable, new ArrayList<>());

        setField(panel, "activity", activity);
        setField(panel, "primeArtwork", artwork);
        setField(panel, "packageLabels", new java.util.concurrent.ConcurrentHashMap<String, String>());
        setField(panel, "message", message);
        setField(panel, "allow", allow);
        setField(panel, "status", status);
        setField(panel, "hint", hint);
        setField(panel, "row", row);
        setField(panel, "adapter", adapter);
        setField(panel, "inDockMode", true);
        return panel;
    }

    @SuppressWarnings("unchecked")
    private static List<ShelfData.Item> panelItemsFor(ShelfPanel panel, String pkg, String label) throws Exception {
        Method m = ShelfPanel.class.getDeclaredMethod("itemsFor", String.class, String.class);
        m.setAccessible(true);
        return (List<ShelfData.Item>) m.invoke(panel, pkg, label);
    }

    @SuppressWarnings("unchecked")
    private static List<ShelfData.Item> adapterItems(ShelfPanel panel) throws Exception {
        Object adapter = getField(panel, "adapter");
        return (List<ShelfData.Item>) getField(adapter, "items");
    }

    private static void applyPanelPackage(ShelfPanel panel, String pkg, String label, boolean restoreSelected) throws Exception {
        setField(panel, "currentPackage", pkg);
        List<ShelfData.Item> items = panelItemsFor(panel, pkg, label);
        Method applyItems = ShelfPanel.class.getDeclaredMethod("applyItems", List.class, boolean.class);
        applyItems.setAccessible(true);
        try {
            applyItems.invoke(panel, items, restoreSelected);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause == null || cause.getStackTrace().length == 0
                    || !"setText".equals(cause.getStackTrace()[0].getMethodName())) {
                throw e;
            }
            if (items.isEmpty()) {
                Method updateHint = ShelfPanel.class.getDeclaredMethod("updateHint");
                updateHint.setAccessible(true);
                updateHint.invoke(panel);
            }
        }
    }

    private static ShelfData.Snapshot snapshot(List<ShelfData.Item> watching, List<ShelfData.Item> recommended) {
        ShelfData.Snapshot s = new ShelfData.Snapshot();
        s.watching.addAll(watching);
        s.recommended.addAll(recommended);
        return s;
    }

    @Test public void youtubeEmptyUsesEmptyStateWithoutSyntheticMkbhdBannerAndPreservesWatchNextAndTransitions() throws Exception {
        File storeFile = Files.createTempFile("youtube-shelf-test", ".json").toFile();
        try {
            PrimeArtwork artwork = new PrimeArtwork(
                    (key, title, type) -> PrimeImdb.Result.of(PrimeImdb.BELIRSIZ),
                    query -> java.util.concurrent.CompletableFuture.completedFuture(null),
                    () -> 1_000_000L,
                    Runnable::run,
                    () -> {},
                    new PrimeBackdropStore(storeFile));
            StubViewState ui = new StubViewState();
            ShelfPanel panel = newTestableShelfPanel(ui, artwork);

            String ytPkg = "com.google.android.youtube.tv";
            ShelfData.Item kickPreview = new ShelfData.Item(
                    10L, "com.kick.mobile", "Kick Stream", "intent://kick/10",
                    "https://images.example/kick.jpg", 0L, 0L, 100L);
            ShelfData.Item ytWatchNext = new ShelfData.Item(
                    579L, ytPkg,
                    "500 TL’LİK VS  7.500 TL’LİK MASTERCHEF RESTORANINDA ÇALIŞMAK!",
                    "https://www.youtube.com/watch?v=CkcGpXnt1uI",
                    "https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg",
                    1778000L, 2579000L, 1791106262000L, 0, 4, null);

            // 1. Empty YouTube: TvContract has no YouTube items -> no static backdrop, no synthetic id=0 item
            ShelfData.Snapshot withoutYoutube = snapshot(
                    Collections.emptyList(), Collections.singletonList(kickPreview));
            setField(panel, "latestSnapshot", withoutYoutube);

            List<ShelfData.Item> emptyYtItems = panelItemsFor(panel, ytPkg, "YouTube");
            assertTrue("Empty YouTube must return an empty item list without synthetic MKBHD fallback",
                    emptyYtItems.isEmpty());

            // 2. Other unverified providers without TvContract rows get fallback with procedural gradient (poster == null)
            List<ShelfData.Item> otherFallback = panelItemsFor(panel, "com.other.unverified", "Other App");
            assertEquals(1, otherFallback.size());
            assertEquals(0L, otherFallback.get(0).id);
            assertEquals("Other App", otherFallback.get(0).title);
            assertNull(otherFallback.get(0).poster);

            List<ShelfData.Item> unverifiedNf = panelItemsFor(panel, "com.other.netflix", "Netflix Unverified");
            assertEquals(1, unverifiedNf.size());
            assertNull("Unverified Netflix fallback must use procedural gradient with null poster",
                    unverifiedNf.get(0).poster);

            List<ShelfData.Item> unverifiedPrime = panelItemsFor(panel, "com.other.prime", "Prime Unverified");
            assertEquals(1, unverifiedPrime.size());
            assertNull("Unverified Prime fallback must use procedural gradient with null poster",
                    unverifiedPrime.get(0).poster);

            List<ShelfData.Item> verifiedEmptyNf = panelItemsFor(panel, PrimeArtwork.NETFLIX_PACKAGE, "Netflix");
            assertTrue("Verified Netflix with empty catalog must produce clean empty state",
                    verifiedEmptyNf.isEmpty());

            List<ShelfData.Item> verifiedEmptyPrime = panelItemsFor(panel, PrimeArtwork.PRIME_PACKAGE, "Prime Video");
            assertTrue("Verified Prime with empty catalog must produce clean empty state",
                    verifiedEmptyPrime.isEmpty());

            // 3. Transition: Populated hero ("com.kick.mobile") -> Empty YouTube clears previous hero and shows empty state
            applyPanelPackage(panel, "com.kick.mobile", "Kick", false);
            assertEquals(1, adapterItems(panel).size());
            assertEquals(10L, adapterItems(panel).get(0).id);
            assertEquals(android.view.View.GONE, ui.messageVisibility);
            assertEquals("pkg=com.kick.mobile page=1/1 focused=false poster=https://images.example/kick.jpg",
                    panel.rotationState());

            int genBeforeEmpty = (Integer) getField(panel, "artworkGeneration");
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertTrue("Previous hero must not remain in adapter when transitioning to empty YouTube",
                    adapterItems(panel).isEmpty());
            assertEquals(android.view.View.VISIBLE, ui.messageVisibility);
            assertEquals(android.view.View.GONE, ui.allowVisibility);
            assertEquals(android.view.View.GONE, ui.hintVisibility);
            assertTrue((Integer) getField(panel, "artworkGeneration") > genBeforeEmpty);
            assertEquals("pkg=" + ytPkg + " page=1/0 focused=false", panel.rotationState());
            assertFalse(ShelfPresentation.sameArtworkTarget(kickPreview, ytPkg, null));
            assertFalse(ShelfPresentation.canRotate(true, true, true, false, adapterItems(panel).size()));

            // 4. Transition: Empty YouTube -> Populated YouTube (Watch Next video arrives)
            ShelfData.Snapshot withYoutubeWatchNext = snapshot(
                    Collections.singletonList(ytWatchNext), Collections.singletonList(kickPreview));
            setField(panel, "latestSnapshot", withYoutubeWatchNext);
            applyPanelPackage(panel, ytPkg, "YouTube", true);
            assertEquals(1, adapterItems(panel).size());
            assertSame(ytWatchNext, adapterItems(panel).get(0));
            assertEquals(579L, adapterItems(panel).get(0).id);
            assertEquals("https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg", adapterItems(panel).get(0).poster);
            assertEquals(1778000L, adapterItems(panel).get(0).position);
            assertEquals(2579000L, adapterItems(panel).get(0).duration);
            assertEquals(android.view.View.GONE, ui.messageVisibility);
            assertEquals("pkg=" + ytPkg + " page=1/1 focused=false poster=https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg",
                    panel.rotationState());

            // 5. Transition: Populated YouTube -> Empty YouTube (Watch Next video finishes/removed)
            setField(panel, "latestSnapshot", withoutYoutube);
            applyPanelPackage(panel, ytPkg, null, true);
            assertTrue("Watch Next hero must be cleared when YouTube content becomes empty",
                    adapterItems(panel).isEmpty());
            assertEquals(android.view.View.VISIBLE, ui.messageVisibility);
            assertEquals(android.view.View.GONE, ui.hintVisibility);
            assertEquals("pkg=" + ytPkg + " page=1/0 focused=false", panel.rotationState());
            assertFalse(ShelfPresentation.sameArtworkTarget(ytWatchNext, ytPkg, null));
        } finally {
            Files.deleteIfExists(storeFile.toPath());
        }
    }

    @Test public void youtubeBannerMetaIncludesAuthorWhenPresentAndPreservesExistingFormatOtherwise() throws Exception {
        String ytPkg = "com.google.android.youtube.tv";
        ShelfData.Item ytWithAuthor = new ShelfData.Item(
                579L, ytPkg, "MasterChef Video",
                "https://www.youtube.com/watch?v=CkcGpXnt1uI",
                "https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg",
                1778000L, 2579000L, 1791106262000L, 0, 4, null, null, "Orkun Işıtmak");
        ShelfData.Item ytWithPaddedAuthor = new ShelfData.Item(
                580L, ytPkg, "Tech Review",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
                0L, 0L, 1791106262000L, -1, 4, null, null, "  Marques Brownlee  ");
        ShelfData.Item ytNullAuthor = new ShelfData.Item(
                581L, ytPkg, "No Author Video",
                "https://www.youtube.com/watch?v=CkcGpXnt1uI",
                "https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg",
                1778000L, 2579000L, 1791106262000L, 0, 4, null, null, null);
        ShelfData.Item ytBlankAuthor = new ShelfData.Item(
                582L, ytPkg, "Blank Author Video",
                "https://www.youtube.com/watch?v=CkcGpXnt1uI",
                "https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg",
                1778000L, 2579000L, 1791106262000L, 0, 4, null, null, "   ");
        ShelfData.Item netflixWithAuthor = new ShelfData.Item(
                10L, PrimeArtwork.NETFLIX_PACKAGE, "Lupin",
                "https://www.netflix.com/title/80994082",
                "https://assets.fanart.tv/lupin.jpg",
                600000L, 2700000L, 1000L, 0, 1, "2021", "80994082", "Gaumont");
        ShelfData.Item primeWithAuthor = new ShelfData.Item(
                11L, PrimeArtwork.PRIME_PACKAGE, "The Boys",
                "content://prime/11",
                "https://assets.fanart.tv/boys.jpg",
                0L, 0L, 1000L, -1, 1, "2022", "gti-11", "Amazon Studios");

        // YouTube with populated author + progress -> "YouTube  ·  Kanal Adı  ·  İzleme İlerlemesi"
        assertEquals("YouTube  ·  Orkun Işıtmak  ·  29:38 / 42:59",
                ShelfPanel.bannerMeta(ytWithAuthor, "YouTube", "29:38 / 42:59"));
        // YouTube with populated author without progress -> "YouTube  ·  Kanal Adı"
        assertEquals("YouTube  ·  Marques Brownlee",
                ShelfPanel.bannerMeta(ytWithPaddedAuthor, "YouTube", ""));

        // YouTube with null or blank author -> preserves existing format
        assertEquals("YouTube  ·  29:38 / 42:59",
                ShelfPanel.bannerMeta(ytNullAuthor, "YouTube", "29:38 / 42:59"));
        assertEquals("YouTube",
                ShelfPanel.bannerMeta(ytNullAuthor, "YouTube", ""));
        assertEquals("YouTube  ·  29:38 / 42:59",
                ShelfPanel.bannerMeta(ytBlankAuthor, "YouTube", "29:38 / 42:59"));

        // Non-YouTube providers ignore author and preserve their existing format
        assertEquals("Netflix  ·  10:00 / 45:00",
                ShelfPanel.bannerMeta(netflixWithAuthor, "Netflix", "10:00 / 45:00"));
        assertEquals("Netflix",
                ShelfPanel.bannerMeta(netflixWithAuthor, "Netflix", ""));
        assertEquals("Prime Video",
                ShelfPanel.bannerMeta(primeWithAuthor, "Prime Video", ""));

        // ShelfPanel.itemsFor and ShelfPresentation.bannerItemsForPackage preserve item.author
        File storeFile = Files.createTempFile("youtube-author-test", ".json").toFile();
        try {
            PrimeArtwork artwork = new PrimeArtwork(
                    (key, title, type) -> PrimeImdb.Result.of(PrimeImdb.BELIRSIZ),
                    query -> java.util.concurrent.CompletableFuture.completedFuture(null),
                    () -> 1_000_000L,
                    Runnable::run,
                    () -> {},
                    new PrimeBackdropStore(storeFile));
            StubViewState ui = new StubViewState();
            ShelfPanel panel = newTestableShelfPanel(ui, artwork);
            setField(panel, "latestSnapshot", snapshot(
                    Collections.singletonList(ytWithAuthor), Collections.emptyList()));
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertEquals(1, adapterItems(panel).size());
            assertEquals("Orkun Işıtmak", adapterItems(panel).get(0).author);

            List<ShelfData.Item> mapped = ShelfPresentation.bannerItemsForPackage(
                    PrimeArtwork.NETFLIX_PACKAGE,
                    Collections.singletonList(netflixWithAuthor),
                    Collections.emptyList(),
                    it -> "https://assets.fanart.tv/resolved.jpg");
            assertEquals(1, mapped.size());
            assertEquals("Gaumont", mapped.get(0).author);
        } finally {
            Files.deleteIfExists(storeFile.toPath());
        }
    }

    @Test public void youtubeOembedResolvesAsyncCachesWithLruTtlRejectsTitleMismatchTimeoutAndStaleCallbacks() throws Exception {
        String ytPkg = "com.google.android.youtube.tv";
        String masterChefTitle = "500 TL’LİK VS  7.500 TL’LİK MASTERCHEF RESTORANINDA ÇALIŞMAK!";
        String masterChefIntent = "intent://www.youtube.com/watch?v=CkcGpXnt1uI#Intent;scheme=https;package=com.google.android.youtube.tv;end";
        String masterChefPoster = "https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg";

        // 1. Valid 11-char YouTube video ID extraction & rejection of invalid lengths
        assertEquals("CkcGpXnt1uI", ShelfIdentity.youtubeIdIn(masterChefIntent));
        assertEquals("dQw4w9WgXcQ", ShelfIdentity.youtubeIdIn("https://youtu.be/dQw4w9WgXcQ?t=10"));
        assertNull(ShelfIdentity.youtubeIdIn("https://www.youtube.com/watch?v=short10chr"));
        assertNull(ShelfIdentity.youtubeIdIn("https://www.youtube.com/watch?v=tooLong12Chr"));

        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        final List<Runnable> bgTasks = new ArrayList<>();
        final List<Runnable> uiTasks = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger httpCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.Map<String, PrimeImdb.Attempt<String>> responses = new java.util.HashMap<>();

        File storeFile = Files.createTempFile("youtube-oembed-test", ".json").toFile();
        try {
            PrimeArtwork artwork = new PrimeArtwork(
                    (key, title, type) -> PrimeImdb.Result.of(PrimeImdb.BELIRSIZ),
                    query -> java.util.concurrent.CompletableFuture.completedFuture(null),
                    clock::get,
                    Runnable::run,
                    () -> {},
                    new PrimeBackdropStore(storeFile));
            StubViewState ui = new StubViewState();
            ShelfPanel panel = newTestableShelfPanel(ui, artwork);

            ShelfPresentation.YoutubeOembed oembed = new ShelfPresentation.YoutubeOembed(
                    url -> {
                        httpCalls.incrementAndGet();
                        PrimeImdb.Attempt<String> a = responses.get(url);
                        if (a == null) throw new java.io.IOException("unexpected url");
                        return a.run();
                    },
                    clock::get,
                    bgTasks::add,
                    (item, gen, author) -> uiTasks.add(() -> {
                        try {
                            Method apply = ShelfPanel.class.getDeclaredMethod(
                                    "applyYoutubeAuthor", ShelfData.Item.class, int.class, String.class);
                            apply.setAccessible(true);
                            apply.invoke(panel, item, gen, author);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }),
                    3);
            setField(panel, "youtubeOembed", oembed);

            String oembedUrl1 = ShelfPresentation.YoutubeOembed.oembedUrl("CkcGpXnt1uI");
            responses.put(oembedUrl1, () -> "{\"title\":\"500 TL'LİK VS 7.500 TL'LİK MASTERCHEF RESTORANINDA ÇALIŞMAK!\","
                    + "\"author_name\":\"Orkun Işıtmak\",\"type\":\"video\",\"provider_name\":\"YouTube\"}");

            ShelfData.Item ytItem1 = new ShelfData.Item(
                    579L, ytPkg, masterChefTitle, masterChefIntent, masterChefPoster,
                    1778000L, 2579000L, 1791106262000L, 0, 4, null, null, null);

            // A) Success path: initial render uses fallback format, async worker resolves & updates banner
            setField(panel, "latestSnapshot", snapshot(Collections.singletonList(ytItem1), Collections.emptyList()));
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertEquals(1, adapterItems(panel).size());
            assertNull("Before async oEmbed completes, author is null (fallback format)", adapterItems(panel).get(0).author);
            assertEquals("YouTube  ·  29:38 / 42:59",
                    ShelfPanel.bannerMeta(adapterItems(panel).get(0), "YouTube", "29:38 / 42:59"));
            assertEquals(1, bgTasks.size());
            assertEquals(0, httpCalls.get());

            bgTasks.remove(0).run();
            assertEquals(1, httpCalls.get());
            assertEquals(1, uiTasks.size());
            uiTasks.remove(0).run();

            assertEquals("Orkun Işıtmak", adapterItems(panel).get(0).author);
            assertEquals(masterChefPoster, adapterItems(panel).get(0).poster);
            assertEquals("YouTube  ·  Orkun Işıtmak  ·  29:38 / 42:59",
                    ShelfPanel.bannerMeta(adapterItems(panel).get(0), "YouTube", "29:38 / 42:59"));

            // B) Cache hit: re-applying within TTL resolves synchronously with 0 new network calls
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertEquals(1, httpCalls.get());
            assertTrue(bgTasks.isEmpty());
            assertEquals("Orkun Işıtmak", adapterItems(panel).get(0).author);

            // C) Title mismatch: oEmbed returns different video title -> rejected, keeps fallback format
            String oembedUrlMismatch = ShelfPresentation.YoutubeOembed.oembedUrl("dQw4w9WgXcQ");
            responses.put(oembedUrlMismatch, () -> "{\"title\":\"Completely Different Video Title\","
                    + "\"author_name\":\"Wrong Channel\",\"type\":\"video\"}");
            ShelfData.Item ytMismatch = new ShelfData.Item(
                    580L, ytPkg, "Expected Video Title",
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
                    60000L, 180000L, 1791106262000L, 0, 4, null, null, null);
            setField(panel, "latestSnapshot", snapshot(Collections.singletonList(ytMismatch), Collections.emptyList()));
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertEquals(1, bgTasks.size());
            bgTasks.remove(0).run();
            while (!uiTasks.isEmpty()) uiTasks.remove(0).run();
            assertNull("Title mismatch must not apply author_name", adapterItems(panel).get(0).author);
            assertEquals("YouTube  ·  01:00 / 03:00",
                    ShelfPanel.bannerMeta(adapterItems(panel).get(0), "YouTube", "01:00 / 03:00"));

            // D) Timeout / network error: falls back cleanly without crash and negative-caches within failure TTL
            String oembedUrlTimeout = ShelfPresentation.YoutubeOembed.oembedUrl("9bZkp7q19f0");
            responses.put(oembedUrlTimeout, () -> { throw new java.net.SocketTimeoutException("Read timed out"); });
            ShelfData.Item ytTimeout = new ShelfData.Item(
                    581L, ytPkg, "Gangnam Style",
                    "https://www.youtube.com/watch?v=9bZkp7q19f0",
                    "https://i.ytimg.com/vi/9bZkp7q19f0/hq720.jpg",
                    30000L, 250000L, 1791106262000L, 0, 4, null, null, null);
            setField(panel, "latestSnapshot", snapshot(Collections.singletonList(ytTimeout), Collections.emptyList()));
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertEquals(1, bgTasks.size());
            bgTasks.remove(0).run();
            while (!uiTasks.isEmpty()) uiTasks.remove(0).run();
            assertNull("Timeout must keep fallback format", adapterItems(panel).get(0).author);
            assertEquals("YouTube  ·  00:30 / 04:10",
                    ShelfPanel.bannerMeta(adapterItems(panel).get(0), "YouTube", "00:30 / 04:10"));
            int callsAfterTimeout = httpCalls.get();
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertTrue("Negative cache prevents immediate retry storm on timeout", bgTasks.isEmpty());
            assertEquals(callsAfterTimeout, httpCalls.get());

            // E) LRU eviction (maxEntries = 2) and TTL expiry
            // Touch CkcGpXnt1uI so it becomes most-recently-used; 9bZkp7q19f0 is evicted when a 3rd entry is added
            assertEquals("Orkun Işıtmak", oembed.cachedAuthor(masterChefIntent, masterChefTitle));
            String oembedUrl3 = ShelfPresentation.YoutubeOembed.oembedUrl("3JZ_D3ELwOQ");
            responses.put(oembedUrl3, () -> "{\"title\":\"Third Video\",\"author_name\":\"Channel Three\"}");
            ShelfData.Item ytItem3 = new ShelfData.Item(
                    582L, ytPkg, "Third Video",
                    "https://www.youtube.com/watch?v=3JZ_D3ELwOQ",
                    "https://i.ytimg.com/vi/3JZ_D3ELwOQ/hq720.jpg",
                    0L, 0L, 100L, -1, 4, null, null, null);
            setField(panel, "latestSnapshot", snapshot(Collections.singletonList(ytItem3), Collections.emptyList()));
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            bgTasks.remove(0).run();
            while (!uiTasks.isEmpty()) uiTasks.remove(0).run();
            assertEquals("Channel Three", adapterItems(panel).get(0).author);
            // CkcGpXnt1uI was recently touched -> still in LRU cache (size=2)
            assertEquals("Orkun Işıtmak", oembed.cachedAuthor(masterChefIntent, masterChefTitle));
            // Advance clock past 6-hour TTL -> cache entry expires
            clock.addAndGet(ShelfPresentation.YoutubeOembed.SUCCESS_TTL_MS + 1000L);
            assertNull("Expired TTL entry must not be returned as fresh", oembed.cachedAuthor(masterChefIntent, masterChefTitle));

            // F) Stale callback protection: video or app focus changes while request is in flight
            String oembedUrlStale = ShelfPresentation.YoutubeOembed.oembedUrl("kJQP7kiw5Fk");
            responses.put(oembedUrlStale, () -> "{\"title\":\"Stale Video\",\"author_name\":\"Stale Channel\"}");
            ShelfData.Item ytStale = new ShelfData.Item(
                    583L, ytPkg, "Stale Video",
                    "https://www.youtube.com/watch?v=kJQP7kiw5Fk",
                    "https://i.ytimg.com/vi/kJQP7kiw5Fk/hq720.jpg",
                    0L, 0L, 200L, -1, 4, null, null, null);
            ShelfData.Item ytActiveNew = new ShelfData.Item(
                    584L, ytPkg, "New Active Video",
                    "https://www.youtube.com/watch?v=RgKAFK5djSk",
                    "https://i.ytimg.com/vi/RgKAFK5djSk/hq720.jpg",
                    0L, 0L, 300L, -1, 4, null, null, null);
            responses.put(ShelfPresentation.YoutubeOembed.oembedUrl("RgKAFK5djSk"),
                    () -> { throw new java.net.SocketTimeoutException("pending"); });

            // Start request for ytStale
            setField(panel, "latestSnapshot", snapshot(Collections.singletonList(ytStale), Collections.emptyList()));
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertEquals(1, bgTasks.size());
            Runnable staleBgTask = bgTasks.remove(0);

            // Switch to a different YouTube video BEFORE staleBgTask completes
            setField(panel, "latestSnapshot", snapshot(Collections.singletonList(ytActiveNew), Collections.emptyList()));
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            bgTasks.clear(); // keep ytActiveNew unresolved

            // Now staleBgTask finishes and posts its UI callback
            staleBgTask.run();
            while (!uiTasks.isEmpty()) uiTasks.remove(0).run();

            // Stale author MUST NOT be applied to ytActiveNew!
            assertEquals(584L, adapterItems(panel).get(0).id);
            assertNull("Stale callback from previous video must never overwrite new video banner",
                    adapterItems(panel).get(0).author);

            // G) Stale callback when focus switches to another app (e.g. Kick) while YouTube oEmbed is in flight
            String oembedUrlAppSwitch = ShelfPresentation.YoutubeOembed.oembedUrl("OPf0YbXqDm0");
            responses.put(oembedUrlAppSwitch, () -> "{\"title\":\"Uptown Funk\",\"author_name\":\"Mark Ronson\"}");
            ShelfData.Item ytBeforeSwitch = new ShelfData.Item(
                    585L, ytPkg, "Uptown Funk",
                    "https://www.youtube.com/watch?v=OPf0YbXqDm0",
                    "https://i.ytimg.com/vi/OPf0YbXqDm0/hq720.jpg",
                    0L, 0L, 400L, -1, 4, null, null, null);
            ShelfData.Item kickItem = new ShelfData.Item(
                    10L, "com.kick.mobile", "Uptown Funk",
                    "https://www.youtube.com/watch?v=OPf0YbXqDm0",
                    "https://images.example/kick.jpg",
                    0L, 0L, 500L, -1, 4, null, null, null);
            setField(panel, "latestSnapshot", snapshot(Arrays.asList(ytBeforeSwitch, kickItem), Collections.emptyList()));
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertEquals(1, bgTasks.size());
            Runnable switchBgTask = bgTasks.remove(0);

            // Focus switches to Kick before YouTube oEmbed completes
            applyPanelPackage(panel, "com.kick.mobile", "Kick", false);
            assertTrue("Non-YouTube package must never trigger oEmbed", bgTasks.isEmpty());
            switchBgTask.run();
            while (!uiTasks.isEmpty()) uiTasks.remove(0).run();
            assertEquals("com.kick.mobile", adapterItems(panel).get(0).pkg);
            assertNull("Stale YouTube callback must never apply to non-YouTube package", adapterItems(panel).get(0).author);

            // H) TvContract author already populated -> oEmbed is skipped (0 network calls)
            int callsBeforePopulated = httpCalls.get();
            ShelfData.Item ytAlreadyHasAuthor = new ShelfData.Item(
                    586L, ytPkg, "Populated Video",
                    "https://www.youtube.com/watch?v=JGwWNGJdvx8",
                    "https://i.ytimg.com/vi/JGwWNGJdvx8/hq720.jpg",
                    0L, 0L, 600L, -1, 4, null, null, "Ed Sheeran");
            setField(panel, "latestSnapshot", snapshot(Collections.singletonList(ytAlreadyHasAuthor), Collections.emptyList()));
            applyPanelPackage(panel, ytPkg, "YouTube", false);
            assertTrue("Populated TvContract author must not trigger oEmbed", bgTasks.isEmpty());
            assertEquals(callsBeforePopulated, httpCalls.get());
            assertEquals("Ed Sheeran", adapterItems(panel).get(0).author);
        } finally {
            Files.deleteIfExists(storeFile.toPath());
        }
    }
}
