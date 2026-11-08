package com.gathin.tvhome;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.service.dreams.DreamService;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tvOS Aerial screensaver: Apple's aerial films from files/aerials, shuffled, one after another, with a
 * fade through black between them, the place's name at the lower left as each one begins and the time at
 * the upper right. Offline: the films were copied to the TV (push-aerials.ps1).
 *
 * Pick it in Settings > System > Ambient mode / Screen saver, or
 * {@code settings put secure screensaver_components com.gathin.tvhome/.AerialDream}.
 */
public class AerialDream extends DreamService implements SurfaceHolder.Callback {
    private static final String TAG = "TvHome.AerialDream";

    private final List<File> films = new ArrayList<>();
    private final Map<String, String> titles = new HashMap<>();
    private int next;
    private MediaPlayer player;
    private SurfaceHolder surface;
    private View fade;
    private TextView place;
    private boolean dreaming;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        float d = getResources().getDisplayMetrics().density;
        Typeface light = getResources().getFont(R.font.inter_display_light);
        Typeface medium = getResources().getFont(R.font.inter_medium);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        SurfaceView video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        root.addView(video, match());

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x80000000, 0x00000000, 0x00000000, 0x4D000000}));
        root.addView(shade, match());

        place = new TextView(this);
        place.setTextColor(0xF2FFFFFF);
        place.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        place.setTypeface(medium);
        place.setShadowLayer(12 * d, 0, 2 * d, 0x80000000);
        place.setAlpha(0f);
        FrameLayout.LayoutParams pl = wrap(Gravity.BOTTOM | Gravity.LEFT);
        pl.setMargins((int) (64 * d), 0, 0, (int) (56 * d));
        root.addView(place, pl);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(0xE6FFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        clock.setTypeface(light);
        clock.setFontFeatureSettings("tnum");   // fixed-width digits: the time never shifts as it changes
        clock.setShadowLayer(12 * d, 0, 2 * d, 0x80000000);
        FrameLayout.LayoutParams cl = wrap(Gravity.TOP | Gravity.RIGHT);
        cl.setMargins(0, (int) (44 * d), (int) (64 * d), 0);
        root.addView(clock, cl);

        fade = new View(this);
        fade.setBackgroundColor(0xFF000000);
        root.addView(fade, match());
        setContentView(root);

        loadFilms();
    }

    private void loadFilms() {
        File dir = getExternalFilesDir("aerials");
        File[] all = dir == null ? null : dir.listFiles();
        if (all != null) {
            for (File f : all) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (f.canRead() && (n.endsWith(".mov") || n.endsWith(".mp4"))) films.add(f);
            }
        }
        Collections.shuffle(films);
        // aerials.json (written next to the films) names each place; a missing entry falls back to the file name.
        try {
            File index = new File(dir, "aerials.json");
            if (index.canRead()) {
                String json = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8)
                        .replace("\uFEFF", "").trim();
                JSONArray list = json.startsWith("[") ? new JSONArray(json) : new JSONArray().put(new JSONObject(json));
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    titles.put(o.optString("file"), o.optString("title"));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "aerials.json unreadable", e);
        }
    }

    private String titleOf(File f) {
        String t = titles.get(f.getName());
        if (t != null && !t.isEmpty()) return t;
        String base = f.getName().replaceFirst("\\.[^.]+$", "").replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : base.split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        playNext();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        dreaming = false;
        release();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }

    private void playNext() {
        if (surface == null || films.isEmpty()) return;
        release();
        File film = films.get(next % films.size());
        next++;
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(film.getPath());
            mp.setDisplay(surface);
            mp.setVolume(0f, 0f);
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player == p) {
                    fade.animate().alpha(0f).setDuration(1200).start();
                    place.setText(titleOf(film));
                    place.animate().cancel();
                    place.animate().alpha(1f).setStartDelay(900).setDuration(900).withEndAction(() ->
                            place.animate().alpha(0f).setStartDelay(6000).setDuration(1200).start()).start();
                    // Start fading to black a little before the end, so films change through black.
                    int lead = Math.max(0, p.getDuration() - 1500);
                    fade.postDelayed(() -> {
                        if (player == p) fade.animate().alpha(1f).setDuration(1200).start();
                    }, lead);
                }
                return false;
            });
            mp.setOnCompletionListener(p -> { if (player == p) playNext(); });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "cannot play " + film.getName() + ": " + what + "/" + extra);
                films.remove(film);
                fade.post(this::playNext);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "cannot play " + film, e);
            films.remove(film);
            fade.post(this::playNext);
        }
    }

    private void release() {
        MediaPlayer mp = player;
        player = null;
        if (mp != null) mp.release();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surface = holder;
        if (player == null && dreaming) playNext();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
        release();
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams wrap(int gravity) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        return lp;
    }
}
