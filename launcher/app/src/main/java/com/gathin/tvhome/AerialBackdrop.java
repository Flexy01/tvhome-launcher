package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;

/**
 * An Apple aerial looping behind Home, as on tvOS. The video goes to a SurfaceView, which the TV composes
 * as a hardware overlay: no GPU work per frame for the launcher. Above it lie a cover that fades out once
 * the first frame is up (no flash of black or a frozen frame) and two scrims that keep the clock and the
 * icons readable; the lower one deepens while focus is in the app grid, where tvOS blurs the shelf away.
 *
 * The decoder is released whenever Home is not in front: TVs have one or two hardware decoders, and an app
 * opened from Home must never find them taken. The position is kept, so the flight picks up where it left.
 */
final class AerialBackdrop implements SurfaceHolder.Callback {
    private static final String TAG = "TvHome.Aerial";

    /** Not final: cycling swaps in the next aerial. */
    private File file;
    private final View cover;
    private final View gridDim;
    private final SharedPreferences prefs;
    private MediaPlayer player;
    private SurfaceHolder surface;
    private boolean resumed;
    private boolean dimmed;

    private static final String PREFS = "aerial_backdrop", CHOSEN = "file", POSITION = "position";

    /**
     * The aerials on the TV, as {file, title}: the titles from aerials.json next to the videos (tools put it
     * there with them), then any other .mov or .mp4 in the folder under its file name. For the Background menu.
     */
    static java.util.List<String[]> catalog(Context context) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        File dir = context.getExternalFilesDir("aerials");
        if (dir == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        File json = new File(dir, "aerials.json");
        if (json.isFile()) {
            try {
                String text = new String(java.nio.file.Files.readAllBytes(json.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) text = text.substring(1);   // written by PowerShell, with a BOM
                org.json.JSONArray a = new org.json.JSONArray(text);
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject o = a.getJSONObject(i);
                    String f = o.optString("file");
                    if (f.isEmpty() || !new File(dir, f).canRead() || !seen.add(f)) continue;
                    out.add(new String[]{f, o.optString("title", f)});
                }
            } catch (Exception e) {
                Log.w(TAG, "cannot read aerials.json", e);
            }
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".mov") || n.endsWith(".mp4"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                if (seen.add(f.getName())) out.add(new String[]{f.getName(), f.getName().replaceAll("\\.\\w+$", "").replace('_', ' ')});
            }
        }
        return out;
    }

    /** The Background menu's "Cycle through all": each aerial plays once, then the next, round the catalog. */
    static final String CYCLE = "*cycle*";
    /** While cycling: the aerial playing now, so Home picks up the rotation where it left it. */
    private static final String CYCLE_FILE = "cycle_file";

    /**
     * The aerial picked in the Background menu while it is still on the TV, CYCLE when cycling, else the
     * configured one.
     */
    static String chosen(Context context, String configured) {
        String f = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHOSEN, null);
        if (CYCLE.equals(f)) return CYCLE;
        File dir = context.getExternalFilesDir("aerials");
        return f != null && dir != null && new File(dir, f).canRead() ? f : configured;
    }

    /** Remembers the Background menu's pick (a file, or CYCLE); the new flight starts from its beginning. */
    static void choose(Context context, String file) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHOSEN, file).remove(POSITION).apply();
    }

    /**
     * Null when the configured aerial is not on the TV: Home then keeps its wallpaper or plain colour. fileName
     * CYCLE starts the rotation at the aerial it last reached.
     */
    static AerialBackdrop attach(Context context, FrameLayout root, int index, String fileName, int coverColor) {
        if (fileName == null || fileName.isEmpty()) return null;
        File dir = context.getExternalFilesDir("aerials");
        if (dir == null) return null;
        boolean cycle = CYCLE.equals(fileName);
        if (cycle) {
            java.util.List<String[]> all = catalog(context);
            if (all.isEmpty()) return null;
            String at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CYCLE_FILE, null);
            fileName = at != null && new File(dir, at).canRead() ? at : all.get(0)[0];
        }
        File f = new File(dir, fileName);
        if (!f.canRead()) return null;
        return new AerialBackdrop(context, root, index, f, coverColor, cycle);
    }

    private final Context context;
    private final boolean cycle;

    /** Cycling: the aerial after the one playing, in catalog order, wrapping round. */
    private File nextInCycle() {
        java.util.List<String[]> all = catalog(context);
        if (all.isEmpty()) return file;
        int i = 0;
        for (int k = 0; k < all.size(); k++) if (all.get(k)[0].equals(file.getName())) i = k + 1;
        return new File(file.getParentFile(), all.get(i % all.size())[0]);
    }

    /** One aerial finished while cycling: fade to black, swap in the next, and the first frame fades it back in. */
    private void advance() {
        File next = nextInCycle();
        Log.i(TAG, "cycle: " + file.getName() + " -> " + next.getName());
        release();
        prefs.edit().putString(CYCLE_FILE, next.getName()).remove(POSITION).apply();
        cover.animate().cancel();
        cover.animate().alpha(1f).setDuration(300).withEndAction(() -> {
            file = next;
            start();
        }).start();
    }

    private AerialBackdrop(Context context, FrameLayout root, int index, File file, int coverColor, boolean cycle) {
        this.file = file;
        this.context = context.getApplicationContext();
        this.cycle = cycle;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        SurfaceView video = new SurfaceView(context);
        video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        video.getHolder().addCallback(this);
        root.addView(video, index, full);

        // Top: a light shade under the clock and pills. Bottom: deeper, under the dock and the grid.
        View top = new View(context);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x26000000, 0x00000000}));
        root.addView(top, index + 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (context.getResources().getDisplayMetrics().heightPixels * 0.42f)));
        View bottom = new View(context);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x40000000, 0x00000000}));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (context.getResources().getDisplayMetrics().heightPixels * 0.62f));
        lp.gravity = android.view.Gravity.BOTTOM;
        root.addView(bottom, index + 2, lp);
        for (View v : new View[]{top, bottom}) {
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            v.forceHasOverlappingRendering(false);
        }

        gridDim = new View(context);
        gridDim.setBackgroundColor(0x9E05070C);
        gridDim.setAlpha(0f);
        gridDim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(gridDim, index + 3, full);

        cover = new View(context);
        cover.setBackgroundColor(coverColor);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(cover, index + 4, full);
    }

    /** Focus in the grid below the dock: dim the aerial so the icons own the screen, as tvOS does. */
    void setGridFocused(boolean inGrid) {
        if (inGrid == dimmed) return;
        dimmed = inGrid;
        gridDim.animate().cancel();
        gridDim.animate().alpha(inGrid ? 1f : 0f).setDuration(inGrid ? 380 : 300).start();
    }

    void resume() {
        resumed = true;
        start();
    }

    void pause() {
        resumed = false;
        release();
        cover.animate().cancel();
        cover.setAlpha(1f);
    }

    private void start() {
        if (!resumed || surface == null || player != null) return;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(file.getPath());
            mp.setDisplay(surface);
            mp.setVolume(0f, 0f);
            // One aerial loops; cycling plays each through once and then moves on.
            mp.setLooping(!cycle);
            if (cycle) mp.setOnCompletionListener(p -> {
                if (player == p) advance();
            });
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                int at = prefs.getInt("position", 0);
                if (at > 0 && at < p.getDuration() - 2000) p.seekTo(at, MediaPlayer.SEEK_CLOSEST_SYNC);
                p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player == p) {
                    cover.animate().alpha(0f).setDuration(700).start();
                }
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "playback error " + what + "/" + extra + " for " + file.getName());
                release();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "cannot play " + file, e);
            release();
        }
    }

    private void release() {
        MediaPlayer mp = player;
        player = null;
        if (mp == null) return;
        try {
            if (mp.isPlaying()) prefs.edit().putInt("position", mp.getCurrentPosition()).apply();
        } catch (IllegalStateException ignored) {
            // still preparing: keep the previous position
        }
        mp.release();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surface = holder;
        start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
        release();
    }
}
