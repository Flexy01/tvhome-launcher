package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One content-first Top Shelf. Reading and ranking stay in ShelfData. */
final class ShelfPanel {
    static final int PERMISSION_REQUEST = 204;
    private static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private final Activity activity;
    private final Runnable toDock, toHeader;
    private final java.util.function.Consumer<List<String>> dockSignals;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ShelfPosters posters;
    private final ShelfInteractions interactions;
    private final PrimeArtwork primeArtwork;
    private ShelfPresentation.YoutubeOembed youtubeOembed;
    private final java.util.concurrent.Semaphore eventSlots = new java.util.concurrent.Semaphore(16);
    private String shownPackage;
    private long shownId;
    private final Runnable impression = () -> {
        ShelfData.Item item = visibleItem();
        if (item != null && item.id == shownId && item.pkg.equals(shownPackage)) recordInteraction(item, "impression");
    };
    private final FrameLayout root;
    private final Drawable shade;
    private final LinearLayout message;
    private final TextView status, allow, hint;
    private final RecyclerView row;
    private final BannerAdapter adapter = new BannerAdapter();
    private boolean started, closed, windowFocused;
    private final boolean motion;
    private int generation, selected, focusAfterScroll = -1;
    private volatile int artworkGeneration;
    private ShelfData.Snapshot latestSnapshot;
    private String currentPackage;
    private final java.util.Map<String, String> packageLabels = new java.util.concurrent.ConcurrentHashMap<>();
    static final long REFRESH_INTERVAL_MS = 6 * 3600 * 1000L; // 6 hours
    private final Runnable periodicRefresh = new Runnable() {
        @Override public void run() {
            if (!started || closed) return;
            load();
            main.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };
    private final Runnable reload = this::load;
    private final Runnable rotate = () -> {
        if (!canRotate()) return;
        moveTo((selected + 1) % adapter.items.size(), false);
    };
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            main.removeCallbacks(reload);
            main.postDelayed(reload, 350);
        }
    };

    ShelfPanel(Activity activity, ViewGroup parent, Runnable toDock, Runnable toHeader,
               java.util.function.Consumer<List<String>> dockSignals, boolean motion) {
        this.activity = activity;
        this.toDock = toDock;
        this.toHeader = toHeader;
        this.dockSignals = dockSignals;
        this.motion = motion;
        posters = new ShelfPosters(activity);
        interactions = new ShelfInteractions(activity.getApplicationContext());
        primeArtwork = PrimeArtwork.forPanel(Config.dir(activity),
                new java.io.File(activity.getFilesDir(), "prime-backdrops.json"),
                () -> main.post(this::refreshPrimeRows));
        youtubeOembed = ShelfPresentation.YoutubeOembed.forPanel(
                (item, requestGen, author) -> main.post(() -> applyYoutubeAuthor(item, requestGen, author)));
        root = new FrameLayout(activity);
        root.setVisibility(View.GONE);
        // Under the existing header/grid, above wallpaper and the obsolete hero wash.
        parent.addView(root, parent.indexOfChild(parent.findViewById(R.id.ambient)) + 1,
                new FrameLayout.LayoutParams(-1, -1));
        row = new RecyclerView(activity);
        row.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override public boolean requestChildRectangleOnScreen(RecyclerView parent, View child,
                    android.graphics.Rect rect, boolean immediate, boolean focusedChildVisible) {
                return false; // Whole-screen pages are positioned by moveTo, not Android's focus rectangle scroll.
            }
        });
        row.setItemAnimator(null);
        row.setHasFixedSize(true);
        row.setOverScrollMode(View.OVER_SCROLL_NEVER);
        row.setHorizontalScrollBarEnabled(false);
        row.setAdapter(adapter);
        row.setItemViewCacheSize(0);
        row.setClipChildren(false);
        new PagerSnapHelper().attachToRecyclerView(row);
        root.addView(row, new FrameLayout.LayoutParams(-1, -1));
        row.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView view, int dx, int dy) { observeImpression(); }
            @Override public void onScrollStateChanged(RecyclerView view, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) { observeImpression(); return; }
                View snap = ((LinearLayoutManager) row.getLayoutManager()).findViewByPosition(selected);
                if (snap == null || Math.abs(snap.getLeft()) > row.getWidth() / 2) {
                    int first = ((LinearLayoutManager) row.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
                    if (first >= 0 && selected != first) { selected = first; artworkGeneration++; }
                }
                if (focusAfterScroll >= 0) {
                    int target = focusAfterScroll;
                    focusAfterScroll = -1;
                    focusItem(target);
                }
                updateHint();
                scheduleRotation();
            }
        });
        row.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> observeImpression());
        hint = label(11f, 0x8AFFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setLetterSpacing(0.02f);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1, dp(20));
        root.addView(hint, hintParams);
        shade = scrim();
        message = new LinearLayout(activity);
        message.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams messageParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        messageParams.setMargins(dp(40), dp(70), dp(40), dp(140));
        root.addView(message, messageParams);
        status = label(14.5f, Color.WHITE);
        status.setMaxWidth(dp(600));
        message.addView(status);
        allow = label(15.5f, Color.WHITE);
        allow.setText(R.string.shelf_allow);
        allow.setPadding(dp(16), dp(10), dp(16), dp(10));
        allow.setBackground(new LiquidGlassDrawable());
        allow.setFocusable(true);
        allow.setOnFocusChangeListener((v, focused) -> { if (motion) TvFocusMotion.applyButton(v, focused, density()); });
        allow.setOnClickListener(v -> activity.requestPermissions(new String[]{PERMISSION}, PERMISSION_REQUEST));
        allow.setOnKeyListener((v, key, event) -> verticalKey(key, event));
        message.addView(allow);
    }

    void refresh() { load(); }
    int posterMemoryBytes() { return posters.memoryBytes(); }
    String rotationState() { return "pkg=" + currentPackage + " page=" + (selected + 1) + "/" + adapter.items.size() + " focused=" + row.hasFocus() + (adapter.items.isEmpty() ? "" : " poster=" + adapter.items.get(0).poster); }

    private boolean inDockMode = true;

    void showAbove(float dockTop) {
        showForPackage(currentPackage, null, dockTop, true);
    }

    void showForPackage(String pkg, String label, float dockTop) {
        showForPackage(pkg, label, dockTop, true);
    }

    void showForPackage(String pkg, String label, float dockTop, boolean inDock) {
        root.setVisibility(View.VISIBLE);
        hint.setY(Math.max(dp(80), dockTop - dp(54)));
        if (this.inDockMode != inDock) {
            this.inDockMode = inDock;
            for (int i = 0; i < row.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = row.getChildViewHolder(row.getChildAt(i));
                if (vh instanceof BannerHolder) {
                    BannerHolder bh = (BannerHolder) vh;
                    bh.words.setVisibility(inDock ? View.VISIBLE : View.GONE);
                    bh.words.setAlpha(inDock ? 1f : 0f);
                }
            }
            adapter.notifyDataSetChanged();
        }
        updateHint();
        if (pkg != null && label != null && !label.isEmpty()) {
            packageLabels.put(pkg, label);
        }
        if (pkg != null && pkg.equals(currentPackage)) {
            scheduleRotation();
            return;
        }
        artworkGeneration++;
        currentPackage = pkg;
        applyItems(itemsFor(pkg, label), false);
        scheduleRotation();
    }

    private List<ShelfData.Item> itemsFor(String pkg, String label) {
        if (latestSnapshot == null) return Collections.emptyList();
        if (PrimeArtwork.isVerifiedPackage(pkg)) return verifiedItems(pkg);
        List<ShelfData.Item> items = ShelfPresentation.bannerItemsForPackage(pkg, latestSnapshot.watching, latestSnapshot.recommended);
        if (!items.isEmpty()) {
            return ShelfPresentation.isYoutubePackage(pkg) && youtubeOembed != null
                    ? youtubeOembed.applyCachedAuthors(items) : items;
        }
        if (pkg != null && !pkg.isEmpty()) {
            // Authentic visual backup for apps without TvContract catalog (zero fake recommendations)
            String title = ShelfPresentation.resolveTitle(pkg, label, packageLabels);
            String lower = pkg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("youtube")) {
                return Collections.emptyList();
            }
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(pkg);
            String intentUri = launch != null ? launch.toUri(Intent.URI_INTENT_SCHEME) : "#";
            return Collections.singletonList(new ShelfData.Item(0, pkg, title, intentUri, null, 0, 0, 0));
        }
        return Collections.emptyList();
    }

    /** Prime and Netflix rows show only with their own verified backdrop; the provider's poster and any fixed image stay off. */
    private List<ShelfData.Item> verifiedItems(String pkg) {
        return ShelfPresentation.bannerItemsForPackage(pkg, latestSnapshot.watching, latestSnapshot.recommended, primeArtwork);
    }

    /** A Prime backdrop arrived or changed: rebuild the row when Prime is the app shown. */
    private void refreshPrimeRows() {
        if (!started || closed || root.getVisibility() != View.VISIBLE
                || !PrimeArtwork.isVerifiedPackage(currentPackage)) return;
        boolean focused = row.hasFocus();
        List<ShelfData.Item> items = itemsFor(currentPackage, packageLabels.get(currentPackage));
        applyItems(items, true);
        if (focused) {
            if (items.isEmpty()) toDock.run(); else row.post(() -> focusItem(selected));
        }
        scheduleRotation();
    }

    private void applyItems(List<ShelfData.Item> items, boolean restoreSelected) {
        artworkGeneration++;
        ShelfData.Item old = adapter.items.isEmpty() ? null : adapter.items.get(Math.min(selected, adapter.items.size() - 1));
        selected = 0;
        if (restoreSelected && old != null) {
            for (int i = 0; i < items.size(); i++) {
                ShelfData.Item item = items.get(i);
                if (old.pkg.equals(item.pkg) && old.intent.equals(item.intent)) { selected = i; break; }
            }
        }
        adapter.items = items;
        adapter.notifyDataSetChanged();
        row.scrollToPosition(selected);
        row.post(this::observeImpression);
        requestSelectedYoutubeAuthor();
        allow.setVisibility(View.GONE);
        message.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        status.setText(latestSnapshot != null && latestSnapshot.error != null ? R.string.shelf_error : R.string.shelf_empty);
        updateHint();
    }

    void hide() {
        artworkGeneration++;
        root.setVisibility(View.GONE);
        row.stopScroll();
        main.removeCallbacks(rotate);
        observeImpression();
    }

    void windowFocus(boolean focused) {
        windowFocused = focused;
        scheduleRotation();
    }

    void start() {
        if (started || closed) return;
        started = true;
        windowFocused = activity.hasWindowFocus();
        if (!hasPermission()) { permissionState(); return; }
        activity.getContentResolver().registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
        activity.getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer);
        main.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS);
        load();
    }

    void stop() {
        started = false;
        generation++;
        artworkGeneration++;
        focusAfterScroll = -1;
        row.stopScroll();
        main.removeCallbacks(reload);
        main.removeCallbacks(rotate);
        main.removeCallbacks(periodicRefresh);
        observeImpression();
        activity.getContentResolver().unregisterContentObserver(observer);
    }

    void close() {
        closed = true;
        stop();
        packageLabels.clear();
        worker.execute(interactions::close);
        worker.shutdown();
        posters.close();
        primeArtwork.close();
        if (youtubeOembed != null) youtubeOembed.close();
    }

    boolean focus() {
        if (root.getVisibility() != View.VISIBLE) return false;
        if (allow.getVisibility() == View.VISIBLE) return allow.requestFocus();
        return !adapter.items.isEmpty() && focusItem(selected);
    }

    private boolean focusItem(int position) {
        RecyclerView.ViewHolder holder = row.findViewHolderForAdapterPosition(position);
        if (holder != null) return holder.itemView.requestFocus();
        row.scrollToPosition(position);
        row.post(() -> {
            RecyclerView.ViewHolder view = row.findViewHolderForAdapterPosition(position);
            if (view != null && started && root.getVisibility() == View.VISIBLE) view.itemView.requestFocus();
        });
        return true;
    }

    private void moveTo(int position, boolean focus) {
        if (selected != position) artworkGeneration++;
        selected = position;
        focusAfterScroll = focus ? position : -1;
        main.removeCallbacks(rotate);
        row.stopScroll();
        View target = ((LinearLayoutManager) row.getLayoutManager()).findViewByPosition(position);
        if (target != null && target.getLeft() == 0) {
            if (focus) focusItem(position);
            updateHint();
            scheduleRotation();
        } else if (motion) {
            row.smoothScrollToPosition(position);
        } else {
            row.scrollToPosition(position);
            if (focus) row.post(() -> focusItem(position));
            updateHint();
            scheduleRotation();
        }
    }

    private void loadSelectedArtwork() {
        if (!started || closed || root.getVisibility() != View.VISIBLE
                || selected < 0 || selected >= adapter.items.size()) return;
        ShelfData.Item item = adapter.items.get(selected);
        ShelfIdentity.Query query = item.artworkQuery();
        if (item.id <= 0 || query.externalId == null
                || !(query.externalId.startsWith("imdb:") || query.externalId.startsWith("tmdb:"))
                || !(query.type == ShelfIdentity.Type.MOVIE || query.type == ShelfIdentity.Type.SERIES
                || query.type == ShelfIdentity.Type.EPISODE)) return;
        RecyclerView.ViewHolder view = row.findViewHolderForAdapterPosition(selected);
        if (!(view instanceof BannerHolder)) return;
        BannerHolder holder = (BannerHolder) view;
        int requestGeneration = artworkGeneration;
        if (holder.artworkItem != item || holder.artworkGeneration == requestGeneration) return;
        holder.artworkGeneration = requestGeneration;
        java.io.File configDir = Config.dir(activity);
        ShelfArtworkResolver.selectFanartBackdropAsync(query, new ShelfArtworkResolver.FanartClient() {
            ShelfArtworkResolver.FanartClient client;
            @Override public String get(String path) {
                if (artworkGeneration != requestGeneration) return null;
                if (client == null) client = ShelfArtworkResolver.fanartClient(configDir);
                return client.get(path);
            }
        })
                .thenAccept(url -> main.post(() -> {
                    if (url == null || url.equals(item.poster)
                            || !artworkTargetIsCurrent(item, holder, requestGeneration)) return;
                    posters.load(holder.image, url, true,
                            () -> artworkTargetIsCurrent(item, holder, requestGeneration));
                }));
    }

    private boolean artworkTargetIsCurrent(ShelfData.Item item, BannerHolder holder, int requestGeneration) {
        return started && !closed && requestGeneration == artworkGeneration && root.getVisibility() == View.VISIBLE
                && selected >= 0 && selected < adapter.items.size()
                && ShelfPresentation.sameArtworkTarget(item, currentPackage, adapter.items.get(selected))
                && holder.artworkItem == item && holder.getBindingAdapterPosition() == selected;
    }

    private boolean canRotate() {
        return ShelfPresentation.canRotate(started, windowFocused, root.getVisibility() == View.VISIBLE,
                row.hasFocus() || allow.hasFocus(), adapter.items.size());
    }

    private void scheduleRotation() {
        main.removeCallbacks(rotate);
        if (canRotate()) main.postDelayed(rotate, ShelfPresentation.ROTATION_MS);
        observeImpression();
        loadSelectedArtwork();
        requestSelectedYoutubeAuthor();
    }

    private void requestSelectedYoutubeAuthor() {
        if (closed || youtubeOembed == null || selected < 0 || selected >= adapter.items.size()) return;
        ShelfData.Item item = adapter.items.get(selected);
        if (item == null || !ShelfPresentation.isYoutubePackage(item.pkg)) return;
        if (item.author != null && !item.author.trim().isEmpty()) return;
        String cached = youtubeOembed.authorFor(item, artworkGeneration);
        if (cached != null) applyYoutubeAuthor(item, artworkGeneration, cached);
    }

    private void applyYoutubeAuthor(ShelfData.Item requested, int requestGeneration, String author) {
        if (closed || author == null || author.trim().isEmpty()
                || requestGeneration != artworkGeneration
                || !ShelfPresentation.isYoutubePackage(currentPackage)
                || selected < 0 || selected >= adapter.items.size()) return;
        ShelfData.Item active = adapter.items.get(selected);
        if (!ShelfPresentation.sameArtworkTarget(requested, currentPackage, active)
                || !PrimeImdb.exactNorm(requested.title).equals(PrimeImdb.exactNorm(active.title))) return;
        String trimmed = author.trim();
        if (trimmed.equals(active.author)) return;
        ShelfData.Item updated = active.withAuthor(trimmed);
        if (!(adapter.items instanceof java.util.ArrayList)) {
            adapter.items = new java.util.ArrayList<>(adapter.items);
        }
        adapter.items.set(selected, updated);
        if (row == null) return;
        RecyclerView.ViewHolder view = row.findViewHolderForAdapterPosition(selected);
        if (!(view instanceof BannerHolder)) {
            for (int i = 0; i < row.getChildCount(); i++) {
                RecyclerView.ViewHolder vh = row.getChildViewHolder(row.getChildAt(i));
                if (vh instanceof BannerHolder && ((BannerHolder) vh).artworkItem == active) {
                    view = vh;
                    break;
                }
            }
        }
        if (view instanceof BannerHolder) {
            BannerHolder holder = (BannerHolder) view;
            if (holder.artworkItem == active) {
                holder.artworkItem = updated;
                String meta = bannerMeta(updated, providerLabel(updated.pkg), progressLabel(updated));
                holder.meta.setText(meta);
                holder.itemView.setContentDescription(updated.title + ", " + meta);
            }
        }
    }

    private String providerLabel(String pkg) {
        try {
            android.content.pm.ApplicationInfo info = activity.getPackageManager().getApplicationInfo(pkg, 0);
            if (info != null) {
                CharSequence label = activity.getPackageManager().getApplicationLabel(info);
                if (label != null && label.length() > 0) return label.toString();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        String cached = pkg == null ? null : packageLabels.get(pkg);
        return cached != null && !cached.isEmpty() ? cached : pkg;
    }

    private String progressLabel(ShelfData.Item item) {
        String progress = item.position > 0 ? android.text.format.DateUtils.formatElapsedTime(item.position / 1000) : "";
        if (item.duration > 0 && !progress.isEmpty()) {
            progress = activity.getString(R.string.shelf_progress, progress,
                    android.text.format.DateUtils.formatElapsedTime(item.duration / 1000));
        }
        return progress;
    }

    private ShelfData.Item visibleItem() {
        if (!started || closed || !windowFocused || !root.isShown()
                || row.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return null;
        int position = ((LinearLayoutManager) row.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
        return position >= 0 && position < adapter.items.size() ? adapter.items.get(position) : null;
    }

    private void observeImpression() {
        ShelfData.Item item = visibleItem();
        if (item != null && item.id == shownId && item.pkg.equals(shownPackage)) return;
        main.removeCallbacks(impression);
        shownPackage = item == null ? null : item.pkg;
        shownId = item == null ? 0 : item.id;
        if (item != null && item.id > 0) main.postDelayed(impression, 1000);
    }

    private void recordInteraction(ShelfData.Item item, String event) {
        if (closed || item.id <= 0) return;
        // ponytail: at most 16 pending events; shed bursts rather than retaining an unbounded history queue.
        if (!eventSlots.tryAcquire()) return;
        String pkg = item.pkg;
        long id = item.id, time = System.currentTimeMillis();
        worker.execute(() -> {
            try { interactions.record(pkg, id, event, time); }
            catch (android.database.sqlite.SQLiteException e) {
                android.util.Log.w("ShelfInteractions", "Local event write failed", e);
            } finally { eventSlots.release(); }
        });
    }

    private void updateHint() {
        if (!inDockMode || adapter.items.isEmpty()) {
            hint.setVisibility(View.GONE);
            return;
        }
        hint.setText(activity.getString(R.string.shelf_banner_hint));
        hint.setVisibility(View.VISIBLE);
    }

    private boolean hasPermission() { return activity.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED; }

    private void load() {
        if (!started || closed) return;
        if (!hasPermission()) { permissionState(); return; }
        final int current = ++generation;
        final ShelfPresentation.TargetSnapshot target = ShelfPresentation.captureTarget(currentPackage, packageLabels);
        worker.execute(() -> {
            ShelfData.Snapshot snapshot = ShelfData.load(activity.getApplicationContext(), interactions);
            main.post(() -> {
                if (!started || closed || current != generation) return;
                latestSnapshot = snapshot;
                if (!hasPermission()) { permissionState(); return; }
                boolean focused = row.hasFocus();
                ShelfPresentation.TargetSnapshot active = ShelfPresentation.resolveActiveTarget(target, currentPackage, packageLabels);
                List<ShelfData.Item> items = itemsFor(active.pkg, active.label);
                applyItems(items, true);
                if (focused) {
                    if (items.isEmpty()) toDock.run(); else row.post(() -> focusItem(selected));
                }
                List<String> packages = new java.util.ArrayList<>();
                for (ShelfData.Item item : snapshot.watching) if (!packages.contains(item.pkg)) packages.add(item.pkg);
                for (ShelfData.Item item : snapshot.recommended) if (!packages.contains(item.pkg)) packages.add(item.pkg);
                dockSignals.accept(packages);
                scheduleRotation();
            });
        });
    }

    private void permissionState() {
        artworkGeneration++;
        activity.getContentResolver().unregisterContentObserver(observer);
        main.removeCallbacks(rotate);
        latestSnapshot = null;
        adapter.items = Collections.emptyList();
        adapter.notifyDataSetChanged();
        dockSignals.accept(Collections.emptyList());
        posters.clear();
        message.setVisibility(View.VISIBLE);
        status.setText(R.string.shelf_permission);
        allow.setVisibility(View.VISIBLE);
        updateHint();
        observeImpression();
    }

    private boolean verticalKey(int key, KeyEvent event) {
        if (key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (key == KeyEvent.KEYCODE_DPAD_UP) toHeader.run(); else toDock.run();
        }
        return true;
    }

    private Drawable scrim() {
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        // Top status vignette: subtle darkening over top 40px for clock/status legibility
        paint.setShader(new LinearGradient(0, 0, 0, 45,
                new int[]{0x66000000, 0x22000000, 0x00000000},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 320, 45, paint);
        // Left text gradient: gentle typography shadow without darkening center artwork
        paint.setShader(new LinearGradient(0, 0, 180, 0,
                new int[]{0x88000000, 0x44000000, 0x14000000, 0x00000000},
                new float[]{0f, 0.35f, 0.70f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 180, 180, paint);
        // Bottom dock vignette: soft fade only behind the dock (last 45px), keeping the poster bright
        paint.setShader(new LinearGradient(0, 135, 0, 180,
                new int[]{0x00000000, 0x33000000, 0x66000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 135, 320, 180, paint);
        BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), bitmap);
        drawable.setGravity(Gravity.FILL);
        return drawable;
    }

    private TextView label(float size, int color) {
        TextView text = new TextView(activity);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(activity.getResources().getFont(R.font.inter_medium));
        text.setIncludeFontPadding(false);
        return text;
    }

    private void open(ShelfData.Item item) {
        recordInteraction(item, "selection");
        try {
            Intent intent = Intent.parseUri(item.intent, Intent.URI_INTENT_SCHEME);
            if (intent.getSelector() != null) throw new SecurityException();
            if (intent.getComponent() != null && !ShelfLogic.samePackage(item.pkg, intent.getComponent().getPackageName())) throw new SecurityException();
            if (intent.getPackage() != null && !ShelfLogic.samePackage(item.pkg, intent.getPackage())) throw new SecurityException();
            intent.setPackage(item.pkg);
            intent.setClipData(null);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ResolveInfo resolved = activity.getPackageManager().resolveActivity(intent, 0);
            if (resolved == null || resolved.activityInfo == null || !resolved.activityInfo.exported
                    || !ShelfLogic.samePackage(item.pkg, resolved.activityInfo.packageName)) throw new ActivityNotFoundException();
            activity.startActivity(intent);
            LastOpened.record(activity, item.pkg);
        } catch (URISyntaxException | SecurityException | ActivityNotFoundException | IllegalArgumentException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, item.title), Toast.LENGTH_SHORT).show();
        }
    }

    private float density() { return activity.getResources().getDisplayMetrics().density; }
    private int dp(float value) { return Math.round(value * density()); }

    static String bannerMeta(ShelfData.Item item, String provider, String progress) {
        String author = item != null && item.pkg != null
                && item.pkg.toLowerCase(java.util.Locale.ROOT).contains("youtube")
                && item.author != null ? item.author.trim() : "";
        String base = author.isEmpty() ? provider : provider + "  ·  " + author;
        return progress == null || progress.isEmpty() ? base : base + "  ·  " + progress;
    }

    private final class BannerAdapter extends RecyclerView.Adapter<BannerHolder> {
        private List<ShelfData.Item> items = Collections.emptyList();
        @Override public int getItemCount() { return items.size(); }
        @Override public BannerHolder onCreateViewHolder(ViewGroup parent, int type) {
            FrameLayout card = new FrameLayout(activity);
            card.setLayoutParams(new RecyclerView.LayoutParams(parent.getWidth(), -1));
            card.setBackgroundColor(0xFF101115);
            card.setFocusable(true);
            card.setClickable(true);
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new FrameLayout.LayoutParams(-1, -1));
            View scrim = new View(activity);
            scrim.setBackground(shade.getConstantState().newDrawable(activity.getResources()));
            scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout words = new LinearLayout(activity);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView title = label(38.1f, Color.WHITE);
            title.setTypeface(activity.getResources().getFont(R.font.inter_bold));
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setLetterSpacing(-0.025f);
            title.setShadowLayer(dp(3), 0, dp(1), 0x88000000);
            TextView meta = label(14.5f, 0xDEFFFFFF);
            meta.setPadding(0, dp(9), 0, 0);
            words.addView(title);
            words.addView(meta);
            FrameLayout.LayoutParams wordsParams = new FrameLayout.LayoutParams(dp(530), -2);
            wordsParams.leftMargin = dp(40);
            wordsParams.topMargin = dp(30);
            card.addView(words, wordsParams);
            return new BannerHolder(card, image, scrim, words, title, meta);
        }
        @Override public void onBindViewHolder(BannerHolder holder, int position) {
            ShelfData.Item item = items.get(position);
            holder.title.setText(item.title);
            String provider = providerLabel(item.pkg);
            String progress = progressLabel(item);
            String meta = bannerMeta(item, provider, progress);
            holder.meta.setText(meta);
            holder.words.setVisibility(inDockMode ? View.VISIBLE : View.GONE);
            holder.words.setAlpha(inDockMode ? 1f : 0f);
            holder.itemView.setContentDescription(item.title + ", " + meta);
            holder.itemView.setOnClickListener(v -> open(item));
            holder.itemView.setOnFocusChangeListener((v, focused) -> {
                if (motion) {
                    holder.image.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                            .setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
                    holder.words.animate().translationY(focused ? -dp(6) : 0f).setDuration(260).start();
                    holder.scrim.animate().alpha(focused ? 0.20f : 1f).setDuration(260).start();
                }
                main.post(ShelfPanel.this::scheduleRotation);
            });
            holder.itemView.setOnKeyListener((v, key, event) -> {
                if (verticalKey(key, event)) return true;
                if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int next = Math.max(0, Math.min(items.size() - 1, selected + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                    if (next != selected) moveTo(next, true);
                    else TvFocusMotion.bump(v, key, density());
                }
                return true;
            });
            holder.artworkItem = item;
            holder.artworkGeneration = -1;
            if (item.poster != null) {
                posters.load(holder.image, item.poster);
            } else {
                holder.image.setTag(null);
                holder.image.setImageBitmap(HeroArt.from(item.pkg, null, null, 0xFF4488EE, false));
            }
            row.post(ShelfPanel.this::loadSelectedArtwork);
            row.post(ShelfPanel.this::requestSelectedYoutubeAuthor);
        }
        @Override public void onViewRecycled(BannerHolder holder) {
            holder.artworkItem = null;
            holder.artworkGeneration = -1;
            holder.image.animate().cancel();
            holder.image.setScaleX(1f);
            holder.image.setScaleY(1f);
            holder.words.animate().cancel();
            holder.words.setTranslationY(0f);
            holder.scrim.animate().cancel();
            holder.scrim.setAlpha(1f);
            holder.image.setTag(null);
            holder.image.setImageDrawable(null);
        }
    }

    private static final class BannerHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final View scrim;
        final LinearLayout words;
        final TextView title, meta;
        ShelfData.Item artworkItem;
        int artworkGeneration = -1;
        BannerHolder(View card, ImageView image, View scrim, LinearLayout words, TextView title, TextView meta) {
            super(card); this.image = image; this.scrim = scrim; this.words = words; this.title = title; this.meta = meta;
        }
    }
}
