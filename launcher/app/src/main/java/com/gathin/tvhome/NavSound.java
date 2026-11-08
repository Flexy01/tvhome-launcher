package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;

import java.io.File;

/**
 * The Home screen's two sounds: a tick as focus moves between icons and buttons (res/raw/nav_tick.wav),
 * and a pop as an app opens (res/raw/open_pop.wav). Both are held in one SoundPool, so a fast run of key
 * presses plays every tick without loading anything. Replaces the stock Android click.
 */
final class NavSound {
    /** WAVs pushed next to config.json (sound.ps1, or content write) replace the built-in sounds. */
    static final String TICK_FILE = "nav_tick.wav";
    static final String POP_FILE = "open_pop.wav";
    /** A focus change this soon after a D-pad press was caused by it; anything later was programmatic. */
    private static final long KEY_WINDOW_MS = 400;
    /** Holding a key repeats focus moves faster than ticks stay pleasant; drop ones closer than this. */
    private static final long MIN_GAP_MS = 45;
    /** Playback level for both sounds, of the samples' full level. */
    private static final float VOLUME = 0.5f;

    private final SoundPool pool;
    // Not final: the load listener reads them, and runs on the main looper after the constructor.
    private int tick;
    private int pop;
    private boolean tickLoaded;
    private boolean popLoaded;
    private long lastKeyAt;
    private long lastTickAt;
    /** The bell button (MainActivity): no tick and no pop while true. Remembered across restarts. */
    /** The Sounds menu and the bell (MainActivity): the navigation tick and the app-open pop, each on or off. */
    private boolean tickOn, popOn;
    private final android.content.SharedPreferences prefs;
    private static final String PREFS = "nav_sound", MUTED = "muted", TICK_ON = "tick_on", POP_ON = "pop_on";

    NavSound(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // "muted" is the older all-or-nothing switch: it still sets both until each is chosen on its own.
        boolean wasMuted = prefs.getBoolean(MUTED, false);
        tickOn = prefs.getBoolean(TICK_ON, !wasMuted);
        popOn = prefs.getBoolean(POP_ON, !wasMuted);
        pool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        pool.setOnLoadCompleteListener((p, id, status) -> {
            if (id == tick) tickLoaded = status == 0;
            else if (id == pop) popLoaded = status == 0;
        });
        tick = load(context, TICK_FILE, R.raw.nav_tick);
        pop = load(context, POP_FILE, R.raw.open_pop);
    }

    private int load(Context context, String name, int fallback) {
        File dir = Config.dir(context);
        File custom = dir == null ? null : new File(dir, name);
        return custom != null && custom.isFile() && custom.length() > 44
                ? pool.load(custom.getPath(), 1) : pool.load(context, fallback, 1);
    }

    /** Every Home sound off: the bell shows its slash. */
    boolean isMuted() {
        return !tickOn && !popOn;
    }

    /** All sounds: both on or both off. */
    void setMuted(boolean m) {
        setTick(!m);
        setPop(!m);
    }

    boolean tickOn() { return tickOn; }
    boolean popOn() { return popOn; }

    void setTick(boolean on) {
        tickOn = on;
        prefs.edit().putBoolean(TICK_ON, on).remove(MUTED).apply();
    }

    void setPop(boolean on) {
        popOn = on;
        prefs.edit().putBoolean(POP_ON, on).remove(MUTED).apply();
    }

    /** Called for every D-pad key down, before focus moves. */
    void noteKey() {
        lastKeyAt = SystemClock.uptimeMillis();
    }

    /** Called when focus lands on a new view; ticks only if a D-pad press just moved it. */
    void onFocusMoved() {
        long now = SystemClock.uptimeMillis();
        if (!tickOn || !tickLoaded || now - lastKeyAt > KEY_WINDOW_MS || now - lastTickAt < MIN_GAP_MS) return;
        lastTickAt = now;
        pool.play(tick, VOLUME, VOLUME, 1, 0, 1f);
    }

    /** Called as an app is launched from a tile. */
    void onAppOpened() {
        if (popOn && popLoaded) pool.play(pop, VOLUME, VOLUME, 2, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
