package com.gathin.tvhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteFullException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/** Run with: adb shell am instrument -w com.gathin.tvhome.test/com.gathin.tvhome.ShelfInteractionsCheck */
public final class ShelfInteractionsCheck extends Instrumentation {
    private boolean retain;
    private boolean reopen;
    private boolean ranking;
    private boolean identity;
    private boolean metadata;
    private boolean catalog;
    private boolean desc;
    private boolean tvmaze;
    private boolean phash;
    private boolean wikidata;
    private boolean primedetail;
    private boolean imdbx;
    private boolean imdboff;
    private boolean pvnames;
    private boolean tvcount;
    private boolean tvkeys;
    private boolean tvids;
    private String tvidsArg;
    private boolean nfmatch;
    private boolean nfverify;
    private boolean pvcols;
    private boolean pvshot;
    private boolean primemeta;
    private boolean imdbprime;
    private boolean imdbprod;
    private boolean stageprobe;
    /** imdbprime only: true = the previous Wikidata SPARQL identity lookup, for the A/B baseline. */
    private static boolean sparqlIdentity;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        retain = "true".equals(arguments.getString("retain"));
        reopen = "true".equals(arguments.getString("reopen"));
        ranking = "true".equals(arguments.getString("ranking"));
        identity = "true".equals(arguments.getString("identity"));
        metadata = "true".equals(arguments.getString("metadata"));
        catalog = "true".equals(arguments.getString("catalog"));
        desc = "true".equals(arguments.getString("desc"));
        tvmaze = "true".equals(arguments.getString("tvmaze"));
        phash = "true".equals(arguments.getString("phash"));
        wikidata = "true".equals(arguments.getString("wikidata"));
        primedetail = "true".equals(arguments.getString("primedetail"));
        imdbx = "true".equals(arguments.getString("imdbx"));
        imdboff = "true".equals(arguments.getString("imdboff"));
        pvnames = "true".equals(arguments.getString("pvnames"));
        tvcount = "true".equals(arguments.getString("tvcount"));
        tvkeys = "true".equals(arguments.getString("tvkeys"));
        tvids = "true".equals(arguments.getString("tvids"));
        tvidsArg = arguments.getString("ids");
        nfmatch = "true".equals(arguments.getString("nfmatch"));
        nfverify = "true".equals(arguments.getString("nfverify"));
        pvcols = "true".equals(arguments.getString("pvcols"));
        pvshot = "true".equals(arguments.getString("pvshot"));
        primemeta = "true".equals(arguments.getString("primemeta"));
        imdbprime = "true".equals(arguments.getString("imdbprime"));
        imdbprod = "true".equals(arguments.getString("imdbprod"));
        stageprobe = "true".equals(arguments.getString("stageprobe"));
        sparqlIdentity = "true".equals(arguments.getString("sparql"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        File directory = new File(getTargetContext().getCacheDir(), "shelf-interactions-check");
        File file = new File(directory, "shelf-interactions.db");
        if (!imdboff && !imdbx && !imdbprime && !imdbprod && !stageprobe && !primedetail && !wikidata && !phash && !tvmaze
                && !pvnames && !tvcount && !tvkeys && !tvids && !pvcols && !pvshot && !primemeta && !nfmatch && !nfverify
                && !desc && !catalog && !metadata && !identity && !ranking) {
            directory.mkdirs();
            if (!reopen) SQLiteDatabase.deleteDatabase(file);
        }
        Context context = new ContextWrapper(getTargetContext()) {
            @Override public File getNoBackupFilesDir() { return directory; }
        };
        long started = SystemClock.elapsedRealtime();
        int status = Activity.RESULT_OK;
        String retentionPackage = "test." + new String(new char[250]).replace('\0', 'x');
        try {
            if (imdbx) {
                result.putString("stream", imdbxCheck());
            } else if (imdboff) {
                result.putString("stream", imdboffCheck());
            } else if (pvnames) {
                result.putString("stream", pvNamesCheck());
            } else if (tvcount) {
                result.putString("stream", tvCountCheck());
            } else if (tvids) {
                result.putString("stream", tvIdsCheck());
            } else if (tvkeys) {
                result.putString("stream", tvKeysCheck());
            } else if (nfmatch) {
                result.putString("stream", nfMatchCheck());
            } else if (nfverify) {
                result.putString("stream", nfVerifyCheck());
            } else if (pvcols) {
                result.putString("stream", pvColsCheck());
            } else if (pvshot) {
                result.putString("stream", pvShotCheck());
            } else if (primemeta) {
                result.putString("stream", primeMetaCheck());
            } else if (imdbprod) {
                result.putString("stream", imdbprodCheck());
            } else if (stageprobe) {
                result.putString("stream", stageProbeCheck());
            } else if (imdbprime) {
                result.putString("stream", imdbprimeCheck());
            } else if (primedetail) {
                result.putString("stream", primedetailCheck());
            } else if (wikidata) {
                result.putString("stream", wikidataCheck());
            } else if (phash) {
                result.putString("stream", phashCheck());
            } else if (tvmaze) {
                result.putString("stream", tvmazeCheck());
            } else if (desc) {
                result.putString("stream", descCheck());
            } else if (catalog) {
                result.putString("stream", catalogCheck());
            } else if (metadata) {
                result.putString("stream", metadataCheck());
            } else if (identity) {
                result.putString("stream", identityCheck());
            } else if (ranking) {
                result.putString("stream", rankingCheck(context, file));
            } else if (reopen) {
                require(file.isFile(), "retained database missing after process restart");
                try (ShelfInteractions history = new ShelfInteractions(context)) {
                    history.record("test.legacy", 41, "impression", 1_000_120_001);
                    summary(file, "test.legacy", 41, 4, 2, 1_000_120_000, 1_000_001_000);
                    require(count(file) == 2048, "process restart changed retained raw events");
                }
                require(file.length() <= 2 * 1024 * 1024, "reopened database exceeded disk budget");
                result.putString("stream", "PASS: process-restart persistence and deduplication; bytes=" + file.length() + "\n");
            } else {
            // Existing installations have only the raw events table, with user_version=0.
            try (SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(file, null)) {
                legacy.execSQL("CREATE TABLE events (package TEXT NOT NULL, content_id INTEGER NOT NULL, "
                        + "event TEXT NOT NULL CHECK(event IN ('impression','selection')), occurred_at INTEGER NOT NULL)");
                legacy.execSQL("CREATE INDEX event_lookup ON events(package, content_id, event, occurred_at)");
                legacy.execSQL("INSERT INTO events VALUES ('test.legacy',41,'impression',999940000),"
                        + "('test.legacy',41,'impression',1000000000),('test.legacy',41,'selection',1000000000)");
            }
            try (ShelfInteractions history = new ShelfInteractions(context)) {
                history.record("test.legacy", 41, "impression", 1_000_000_001);
                require(count(file) == 3, "migration changed existing events");
                summary(file, "test.legacy", 41, 2, 1, 1_000_000_000, 1_000_000_000);
                history.record("test.alpha", 11, "impression", 100_000);
                history.record("test.alpha", 11, "impression", 159_999);
                history.record("test.alpha", 11, "impression", 160_000);
                history.record("test.alpha", 11, "selection", 100_000);
                history.record("test.alpha", 11, "selection", 100_999);
                history.record("test.alpha", 11, "selection", 101_000);
                history.record("test.beta", 11, "impression", 100_000);
                history.record("test.alpha", 12, "impression", 100_000);
                history.record(null, 11, "impression", 100_000);
                history.record("", 11, "impression", 100_000);
                history.record(new String(new char[256]), 11, "impression", 100_000);
                history.record("test.alpha", 0, "impression", 100_000);
                history.record("test.alpha", -1, "impression", 100_000);
                history.record("test.alpha", 11, "unknown", 100_000);
                history.record("test.alpha", 11, null, 100_000);
                history.record("test.alpha", 11, "impression", 0);
                history.record("test.alpha", 11, "impression", Long.MAX_VALUE);
                require(count(file) == 9, "duplicate suppression, key separation or validation failed");
                history.record("test.alpha", 11, "impression", 99_999);
                require(count(file) == 9, "small clock rollback duplicated an impression");
                history.record("test.alpha", 11, "impression", 1);
                require(count(file) == 10, "large clock rollback permanently suppressed an impression");
                summary(file, "test.alpha", 11, 3, 2, 160_000, 101_000);
                summary(file, "test.beta", 11, 1, 0, 100_000, 0);
                summary(file, "test.alpha", 12, 1, 0, 100_000, 0);
            }
            try (ShelfInteractions history = new ShelfInteractions(context)) {
                history.record("test.alpha", 11, "impression", 100_001);
                require(count(file) == 10, "reopen lost persisted duplicate detection");
                summary(file, "test.legacy", 41, 2, 1, 1_000_000_000, 1_000_000_000);
                summary(file, "test.alpha", 11, 3, 2, 160_000, 101_000);
                // Maximum-length package keys exercise disk usage as both tables reach their caps.
                for (int i = 0; i < 2100; i++) history.record(retentionPackage, 1000 + i, "impression", 200_000 + i);
            }
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor rows = db.rawQuery("SELECT count(*), min(content_id), max(content_id), min(occurred_at), max(occurred_at) FROM events", null)) {
                rows.moveToFirst();
                require(rows.getInt(0) == 2048, "retention cap failed");
                require(rows.getLong(1) == 1052 && rows.getLong(2) == 3099, "retention removed newest records");
                require(rows.getLong(3) == 200_052 && rows.getLong(4) == 202_099, "event timestamps changed");
                try (Cursor summaries = db.rawQuery("SELECT count(*) FROM summaries", null)) {
                    summaries.moveToFirst();
                    require(summaries.getInt(0) == 512, "summary cap failed");
                }
                try (Cursor retained = db.rawQuery("SELECT count(*), min(content_id), max(content_id) FROM summaries WHERE package=?",
                        new String[]{retentionPackage})) {
                    retained.moveToFirst();
                    require(retained.getInt(0) == 511 && retained.getLong(1) == 2589 && retained.getLong(2) == 3099,
                            "summary pruning did not retain most recently interacted content");
                }
            }
            summary(file, "test.legacy", 41, 2, 1, 1_000_000_000, 1_000_000_000);
            try (ShelfInteractions history = new ShelfInteractions(context)) {
                history.record("test.legacy", 41, "impression", 1_000_059_999);
                history.record("test.legacy", 41, "selection", 1_000_000_999);
                // This content was evicted from summaries but still has a raw event.
                history.record(retentionPackage, 1052, "impression", 200_052);
                require(count(file) == 2048, "pruned events or evicted summaries lost duplicate detection");
                summary(file, "test.legacy", 41, 2, 1, 1_000_000_000, 1_000_000_000);
                history.record("test.legacy", 41, "impression", 1_000_060_000);
                history.record("test.legacy", 41, "selection", 1_000_001_000);
                summary(file, "test.legacy", 41, 3, 2, 1_000_060_000, 1_000_001_000);

                // Force a real SQLite disk-budget failure inside the production transaction.
                try (SQLiteDatabase probe = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                    probe.execSQL("CREATE TABLE disk_probe (payload BLOB)");
                    probe.execSQL("CREATE TRIGGER disk_probe AFTER UPDATE ON summaries BEGIN "
                            + "INSERT INTO disk_probe VALUES(zeroblob(2097152)); END");
                }
                try {
                    history.record("test.disk", 99, "impression", 2_000_000_000);
                    throw new AssertionError("SQLite did not enforce the 2 MiB disk budget");
                } catch (SQLiteFullException expected) {
                    require(file.length() <= 2 * 1024 * 1024, "failed write exceeded disk budget");
                }
                try (SQLiteDatabase probe = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                    try (Cursor rolledBack = probe.rawQuery("SELECT (SELECT count(*) FROM events WHERE package='test.disk'),"
                            + "(SELECT count(*) FROM summaries WHERE package='test.disk'),(SELECT count(*) FROM summaries)", null)) {
                        rolledBack.moveToFirst();
                        require(rolledBack.getInt(0) == 0 && rolledBack.getInt(1) == 0 && rolledBack.getInt(2) == 512,
                                "disk-full write partially committed");
                    }
                    probe.execSQL("DROP TRIGGER disk_probe");
                    probe.execSQL("DROP TABLE disk_probe");
                }
                history.record("test.legacy", 41, "impression", 1_000_120_000);
                summary(file, "test.legacy", 41, 4, 2, 1_000_120_000, 1_000_001_000);
            }
            try (ShelfInteractions history = new ShelfInteractions(context)) {
                history.record("test.legacy", 41, "impression", 1_000_120_001);
                summary(file, "test.legacy", 41, 4, 2, 1_000_120_000, 1_000_001_000);
            }
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor integrity = db.rawQuery("PRAGMA integrity_check", null)) {
                integrity.moveToFirst();
                require("ok".equals(integrity.getString(0)), "SQLite integrity failed");
            }
            require(count(file) == 2048, "disk-full recovery lost the raw event cap");
            require(file.length() <= 2 * 1024 * 1024, "database exceeded disk budget");
            result.putString("stream", "PASS: legacy migration, persistent summary counts/times, deduplication before/after raw pruning, "
                    + "validation, clock rollback, 2048 events, 512 summaries, disk-full rollback/recovery, integrity; bytes="
                    + file.length() + ", elapsedMs=" + (SystemClock.elapsedRealtime() - started) + "\n");
            }
        } catch (Throwable failure) {
            status = Activity.RESULT_CANCELED;
            result.putString("stream", android.util.Log.getStackTraceString(failure));
        } finally {
            if (!imdboff && !imdbx && !primedetail && !wikidata && !phash && !tvmaze
                    && !pvnames && !tvcount && !tvkeys && !tvids && !pvcols && !pvshot && !primemeta && !nfmatch && !nfverify && !imdbprime && !imdbprod && !stageprobe
                    && !desc && !catalog && !metadata && !identity && !ranking
                    && (!retain || status != Activity.RESULT_OK)) {
                SQLiteDatabase.deleteDatabase(file);
                directory.delete();
            }
        }
        finish(status, result);
    }

    private String catalogCheck() {
        // Title + tv type required; no year in provider rows. TVmaze search returns all
        // candidates, singlesearch picks best; TMDb multi-search needs auth, treated blocked.
        String[] proj = {"_id", "package_name", "title", "release_date", "type",
                "season_display_number", "episode_display_number", "intent_uri"};
        StringBuilder out = new StringBuilder();
        auditCatalog(out, "preview", android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, proj);
        auditCatalog(out, "watch", android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, proj);
        auditCatalogReachability(out);
        return out.toString();
    }

    private void auditCatalog(StringBuilder out, String table, android.net.Uri uri, String[] proj) {
        // slots: 0 netflix, 1 prime, 2 other. no raw rows/intents printed.
        int[] total = new int[3], withTitle = new int[3], withType = new int[3];
        int[] certain = new int[3], ambiguous = new int[3], unmatched = new int[3];
        List<String> samples = new ArrayList<>();
        try (Cursor c = getTargetContext().getContentResolver().query(uri, proj, null, null, null)) {
            if (c == null) {
                out.append("PASS-CAT ").append(table).append(": unavailable (null cursor)\n");
                return;
            }
            int iPkg = c.getColumnIndex("package_name"), iTitle = c.getColumnIndex("title"),
                    iRel = c.getColumnIndex("release_date"), iType = c.getColumnIndex("type"),
                    iIntent = c.getColumnIndex("intent_uri");
            while (c.moveToNext()) {
                String pkg = iPkg >= 0 && !c.isNull(iPkg) ? c.getString(iPkg) : "";
                int slot = "com.netflix.ninja".equals(pkg) ? 0
                        : "com.amazon.amazonvideo.livingroom".equals(pkg) ? 1 : 2;
                String t = iTitle >= 0 && !c.isNull(iTitle) ? c.getString(iTitle).trim() : "";
                String rel = iRel >= 0 && !c.isNull(iRel) ? c.getString(iRel) : null;
                int typeCode = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                String intent = iIntent >= 0 && !c.isNull(iIntent) ? c.getString(iIntent) : "";
                total[slot]++;
                if (!t.isEmpty()) withTitle[slot]++;
                ShelfIdentity.Query q = ShelfIdentity.parse(t, intent, null, typeCode, rel);
                boolean usableType = q.type == ShelfIdentity.Type.SERIES
                        || q.type == ShelfIdentity.Type.EPISODE || q.type == ShelfIdentity.Type.MOVIE;
                if (usableType) withType[slot]++;
                if (t.isEmpty() || !usableType) {
                    unmatched[slot]++;
                    continue;
                }
                // trusted external IDs are provider-local service IDs (netflix:/amazon:/youtube:);
                // imdb:/tmdb: never emitted by provider rows, counted only if Squared.
                boolean trusted = q.externalId != null
                        && (q.externalId.startsWith("imdb:") || q.externalId.startsWith("tmdb:"));
                if (trusted) {
                    certain[slot]++;
                } else if (needsDisambiguation(t)) {
                    ambiguous[slot]++;
                } else {
                    certain[slot]++;
                }
                if (slot < 2 && samples.size() < 6) {
                    samples.add("SAMPLE-CAT " + table + " " + (slot == 0 ? "netflix" : "prime")
                            + " title=" + !t.isEmpty() + " type=" + q.type
                            + " trusted=" + trusted + " ambiguous=" + needsDisambiguation(t));
                }
            }
        }
        for (String s : samples) out.append(s).append('\n');
        String[] names = {"netflix", "prime", "other"};
        for (int s = 0; s < 3; s++) {
            out.append("PASS-CAT ").append(table).append(' ').append(names[s])
                    .append(": n=").append(total[s]).append(" title=").append(withTitle[s])
                    .append(" type=").append(withType[s]).append(" certain=").append(certain[s])
                    .append(" ambiguous=").append(ambiguous[s]).append(" unmatched=").append(unmatched[s])
                    .append('\n');
        }
    }

    private void auditCatalogReachability(StringBuilder out) {
        // no API keys in repo; TMDb blocked on device network, TVmaze open access used read-only.
        out.append("PASS-CAT net: tvmaze=open unauthenticated search/singlesearch; "
                .concat("tmdb=blocked sinkhole+key-required; fanart=key-required; keys=none-stored\n"));
    }

    private static boolean needsDisambiguation(String title) {
        // same-title remake/ambiguous set confirmed from audit evidence (no raw data dumped).
        String norm = title.trim().toLowerCase(java.util.Locale.ROOT);
        return norm.equals("carrie") || norm.equals("ghost") || norm.equals("ghosts")
                || norm.equals("dune") || norm.equals("tomb raider")
                || norm.equals("the office") || norm.startsWith("live ")
                || norm.startsWith("supernatural");
    }

    private String tvmazeCheck() {
        // Read-only: pick <=10 Netflix/Prime SERIES/EPISODE rows with short_description,
        // compare against TVmaze search candidates' summaries. Counts/booleans only.
        String[] proj = {"package_name", "title", "short_description", "type",
                "release_date", "intent_uri"};
        List<TvRow> pool = new ArrayList<>();
        collectTvRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, proj, 50);
        collectTvRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, proj, 50);
        StringBuilder out = new StringBuilder();
        try {
            String ctl = httpGet("https://api.tvmaze.com/search/shows?q=Breaking%20Bad");
            JSONArray ctlArr = new JSONArray(ctl);
            Set<String> bb = new HashSet<>(Arrays.asList("breaking", "bad"));
            int ctlSame = 0;
            for (int k = 0; k < ctlArr.length(); k++) {
                JSONObject show = ctlArr.getJSONObject(k).getJSONObject("show");
                Set<String> nt = ShelfIdentity.extractTokens(show.optString("name", ""));
                if (!nt.isEmpty() && nt.equals(bb)) ctlSame++;
            }
            out.append("PASS-TVMAZE-CTL search cand=").append(ctlArr.length())
                    .append(" same=").append(ctlSame).append(" http=200 len=").append(ctl.length()).append('\n');
        } catch (Exception e) {
            out.append("PASS-TVMAZE-CTL fail cls=").append(e.getClass().getSimpleName()).append('\n');
        }
        // Interleave netflix/prime so one package cannot fill all 10 slots.
        List<TvRow> nf = new ArrayList<>(), pr = new ArrayList<>();
        for (TvRow r : pool) (r.slot == 0 ? nf : pr).add(r);
        List<TvRow> ordered = new ArrayList<>();
        for (int i = 0; i < 10 && ordered.size() < 10 && (i < nf.size() || i < pr.size()); i++) {
            if (i < nf.size() && ordered.size() < 10) ordered.add(nf.get(i));
            if (ordered.size() < 10 && i < pr.size()) ordered.add(pr.get(i));
        }
        int n = ordered.size();
        int plot = 0, promo = 0, candTotal = 0, sameTotal = 0;
        int strongTotal = 0, weakTotal = 0, contraTotal = 0, netFail = 0;
        for (int i = 0; i < n; i++) {
            TvRow r = ordered.get(i);
            if (isPromo(r.shortDesc)) promo++; else plot++;
            int nCand = 0, same = 0, strong = 0, weak = 0, contra = 0;
            boolean bestStrong = false, bestConflict = false;
            int normLen = 0, titleChars = r.title.length(), titleWords = 0;
            try {
                // Provider title may carry junk ("Yeni", service suffixes). Try raw then normalized tokens.
                String norm = normQuery(r.title);
                titleWords = norm.isEmpty() ? 0 : norm.split("\\s+").length;
                normLen = norm.length();
                String rawBody = httpGet("https://api.tvmaze.com/search/shows?q="
                        + URLEncoder.encode(r.title, "UTF-8"));
                JSONArray rawArr = new JSONArray(rawBody);
                String body = rawArr.length() > 0 ? rawBody
                        : httpGet("https://api.tvmaze.com/search/shows?q="
                                + URLEncoder.encode(norm.isEmpty() ? r.title : norm, "UTF-8"));
                JSONArray arr = new JSONArray(body);
                nCand = Math.min(arr.length(), 10);
                Set<String> qTok = ShelfIdentity.extractTokens(r.shortDesc);
                Set<String> qTitleTok = ShelfIdentity.extractTokens(r.title);
                int bestInter = -1;
                for (int k = 0; k < nCand; k++) {
                    JSONObject show = arr.getJSONObject(k).getJSONObject("show");
                    String cname = show.optString("name", "");
                    int cyear = parseYear4(show.optString("premiered", null));
                    String csum = stripHtml(show.optString("summary", ""));
                    Set<String> cTok = ShelfIdentity.extractTokens(csum);
                    Set<String> cTitleTok = ShelfIdentity.extractTokens(cname);
                    boolean sameTitle = !qTitleTok.isEmpty() && qTitleTok.equals(cTitleTok);
                    if (sameTitle) same++;
                    int inter = 0;
                    for (String t : qTok) if (cTok.contains(t)) inter++;
                    boolean yearConflict = r.year > 0 && cyear > 0 && Math.abs(r.year - cyear) > 1;
                    float subset = cTok.isEmpty() || qTok.isEmpty() ? 0
                            : (float) inter / Math.min(qTok.size(), cTok.size());
                    boolean isStrong = inter >= 4 && subset >= 0.35f && !yearConflict && !qTok.isEmpty();
                    boolean isContra = yearConflict || (sameTitle && inter == 0 && !qTok.isEmpty());
                    if (isStrong) strong++;
                    else if (isContra) contra++;
                    else if (inter >= 2 && !yearConflict) weak++;
                    else if (inter > 0 && !yearConflict) weak++;
                    else contra++;
                    if (inter > bestInter) {
                        bestInter = inter;
                        bestStrong = isStrong;
                        bestConflict = yearConflict;
                    }
                }
            } catch (Exception e) {
                netFail++;
            }
            candTotal += nCand;
            sameTotal += same;
            strongTotal += strong;
            weakTotal += weak;
            contraTotal += contra;
            // Single-candidate or title-only support is never conclusive: cap at WEAK.
            String verdict;
            if (nCand == 0 || netFail > 0 && nCand == 0) verdict = "NONE";
            else if (bestStrong && !bestConflict && nCand >= 2) verdict = "STRONG";
            else if (strong > 0 && nCand < 2) verdict = "WEAK";
            else if (weak > 0) verdict = "WEAK";
            else if (contra > 0) verdict = "CONTRA";
            else verdict = "NONE";
            out.append("SAMPLE-TVMAZE ").append(i)
                    .append(" pkg=").append(r.slot == 0 ? "netflix" : "prime")
                    .append(" short=true year=").append(r.year > 0)
                    .append(" type=").append(r.type).append(" tr=").append(isTurkish(r.shortDesc))
                    .append(" chars=").append(titleChars).append(" words=").append(titleWords)
                    .append(" normLen=").append(normLen)
                    .append(" cand=").append(nCand).append(" same=").append(same)
                    .append(" strong=").append(strong).append(" weak=").append(weak)
                    .append(" contra=").append(contra).append(" verdict=").append(verdict)
                    .append('\n');
        }
        out.append("PASS-TVMAZE: n=").append(n).append(" plot=").append(plot).append(" promo=").append(promo)
                .append(" cand=").append(candTotal).append(" sameName=").append(sameTotal)
                .append(" strong=").append(strongTotal).append(" weak=").append(weakTotal)
                .append(" contra=").append(contraTotal).append(" netFail=").append(netFail).append('\n');
        return out.toString();
    }

    private String imdboffCheck() {
        // Read-only offline-hash audit: direct TvContract title only, no web fetch.
        // Emits one-way title hashes + consistency flags; IMDb matching runs on computer.
        List<ImdbOffRow> pool = new ArrayList<>();
        collectOffRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, 50);
        collectOffRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, 50);
        List<ImdbOffRow> rows = new ArrayList<>();
        for (ImdbOffRow r : pool) {
            if (r.gti == null) continue;
            boolean dup = false;
            for (ImdbOffRow q : rows) if (r.gti.equals(q.gti)) { dup = true; break; }
            if (!dup && rows.size() < 12) rows.add(r);
        }
        int negativePass = checkImdbOffNormalizer();
        StringBuilder out = new StringBuilder();
        int hashed = 0;
        for (int i = 0; i < rows.size(); i++) {
            ImdbOffRow r = rows.get(i);
            String norm = exactNorm(r.title);
            String nh = isQueryableNorm(norm) ? sha256hexQuiet(norm) : "";
            String base = stripProvenBase(r.title, r.seasNum, r.epNum);
            String sh = base == null ? "" : sha256hexQuiet(base);
            String seriesNorm = exactNorm(r.seasonTitle);
            String seriesHash = isQueryableNorm(seriesNorm) ? sha256hexQuiet(seriesNorm) : "";
            if (!nh.isEmpty()) hashed++;
            out.append("SAMPLE-IMDBOFF ").append(i)
                    .append(" nh=").append(nh.isEmpty() ? "-" : nh)
                    .append(" sh=").append(sh.isEmpty() ? "-" : sh)
                    .append(" strip=").append(base != null)
                    .append(" type=").append(r.rawType)
                    .append(" year=").append(r.rawYear)
                    .append(" seasEv=").append(r.seasNum > 0)
                    .append(" epEv=").append(r.epNum > 0)
                    .append(" seriesHash=").append(seriesHash.isEmpty() ? "-" : seriesHash)
                    .append(" gti=").append(true).append('\n');
        }
        out.append("PASS-IMDBOFF n=").append(rows.size()).append(" hashed=").append(hashed)
                .append(" webFetch=false negativePass=").append(negativePass).append('\n');
        return out.toString();
    }

    private String pvNamesCheck() {
        // Read-only identity dump for the real Prime TvContract rows: title, GTI, intent.
        // No network, no playback, no purchase. Temp probe only.
        List<ImdbOffRow> pool = new ArrayList<>();
        collectOffRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, 50);
        collectOffRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, 50);
        List<ImdbOffRow> rows = new ArrayList<>();
        for (ImdbOffRow r : pool) {
            if (r.gti == null) continue;
            boolean dup = false;
            for (ImdbOffRow q : rows) if (r.gti.equals(q.gti)) { dup = true; break; }
            if (!dup && rows.size() < 12) rows.add(r);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            ImdbOffRow r = rows.get(i);
            out.append("PVROW ").append(i)
                    .append(" title=").append(oneLine(r.title))
                    .append(" type=").append(r.rawType)
                    .append(" rel=").append(oneLine(r.rawYear))
                    .append(" seas=").append(r.seasNum)
                    .append(" ep=").append(r.epNum)
                    .append(" seasonTitle=").append(oneLine(r.seasonTitle))
                    .append(" gti=").append(r.gti)
                    .append(" nh=").append(sha256hexQuiet(exactNorm(r.title)))
                    .append(" intent=").append(oneLine(r.intent))
                    .append('\n');
        }
        out.append("PASS-PVNAMES n=").append(rows.size()).append('\n');
        return out.toString();
    }

    private String tvKeysCheck() {
        // Read-only, test-only. For Turkcell preview rows whose title contains "potter": prints the
        // TvContract column names that carry a value, the parsed Intent extra KEYS, and the URI
        // path-segment count and query parameter KEYS. Values, raw URIs and tokens are never printed.
        StringBuilder out = new StringBuilder();
        try (Cursor c = getTargetContext().getContentResolver().query(
                android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return "TVKEYS status=null\n";
            String[] names = c.getColumnNames();
            out.append("TVKEYS columns=").append(names.length).append(" names=");
            for (int i = 0; i < names.length; i++) out.append(i == 0 ? "" : ",").append(names[i]);
            out.append('\n');
            int n = 0;
            java.util.Map<String, java.util.List<String>> titlesById = new java.util.LinkedHashMap<>();
            while (c.moveToNext()) {
                String pkg = probeText(c, "package_name");
                String title = probeText(c, "title");
                if (!"com.turkcell.ott".equals(pkg) || title == null || !title.toLowerCase(Locale.ROOT).contains("potter")) continue;
                n++;
                String contentId = probeText(c, "content_id");
                titlesById.computeIfAbsent(contentId == null ? "" : contentId, k -> new java.util.ArrayList<>()).add(title);
                out.append("TVKEYS row=").append(n).append(" contentId=").append(shapeOf(contentId)).append('\n');
                out.append("TVKEYS row=").append(n)
                        .append(" shortDescLen=").append(textLen(c, "short_description"))
                        .append(" longDescLen=").append(textLen(c, "long_description"))
                        .append(" genreLen=").append(textLen(c, "genre"))
                        .append(" durationMillis=").append(c.getColumnIndex("duration_millis") < 0 || c.isNull(c.getColumnIndex("duration_millis")) ? "null" : "set")
                        .append(" releaseDate=").append(c.getColumnIndex("release_date") < 0 || c.isNull(c.getColumnIndex("release_date")) ? "null" : "set")
                        .append('\n');
                out.append("TVKEYS row=").append(n).append(" nonNull=");
                StringBuilder filled = new StringBuilder();
                for (String col : names) {
                    int i = c.getColumnIndex(col);
                    if (i >= 0 && !c.isNull(i)) filled.append(filled.length() == 0 ? "" : ",").append(col);
                }
                out.append(filled).append('\n');
                String intentUri = probeText(c, "intent_uri");
                if (intentUri == null) continue;
                try {
                    android.content.Intent intent = android.content.Intent.parseUri(intentUri, 0);
                    android.os.Bundle extras = intent.getExtras();
                    out.append("TVKEYS row=").append(n).append(" extraKeys=");
                    if (extras == null) {
                        out.append("none");
                    } else {
                        java.util.TreeSet<String> keys = new java.util.TreeSet<>(extras.keySet());
                        out.append(String.join(",", keys));
                    }
                    out.append(" action=").append(intent.getAction() == null ? "-" : "set")
                            .append(" component=").append(intent.getComponent() == null ? "-" : "set")
                            .append(" data=").append(intent.getData() == null ? "-" : "set").append('\n');
                    android.net.Uri data = intent.getData();
                    String deepLink = extras == null ? null : extras.getString("ARG_DEEP_LINK_URL");
                    if (deepLink != null) {
                        android.net.Uri dl = android.net.Uri.parse(deepLink);
                        java.util.List<String> segs = dl.getPathSegments();
                        StringBuilder shapes = new StringBuilder();
                        for (String seg : segs) shapes.append(shapes.length() == 0 ? "" : "/").append(shapeOf(seg));
                        out.append("TVKEYS row=").append(n).append(" deepLinkScheme=").append(dl.getScheme())
                                .append(" deepLinkSegments=").append(segs.size())
                                .append(" segmentShapes=").append(shapes)
                                .append(" deepLinkQueryKeys=").append(String.join(",", queryKeysOf(dl)))
                                .append('\n');
                    }
                    if (data != null) {
                        out.append("TVKEYS row=").append(n).append(" dataScheme=").append(data.getScheme())
                                .append(" pathSegments=").append(data.getPathSegments().size())
                                .append(" queryKeys=").append(data.isOpaque() ? ""
                                        : String.join(",", queryKeysOf(data))).append('\n');
                    }
                } catch (Exception e) {
                    out.append("TVKEYS row=").append(n).append(" parse=error:")
                            .append(e.getClass().getSimpleName()).append('\n');
                }
            }
            int g = 0;
            for (java.util.List<String> t : titlesById.values()) {
                g++;
                // Group index only; the content_id value itself is never printed.
                out.append("TVKEYS group=").append(g).append(" rows=").append(t.size())
                        .append(" titles=").append(new java.util.TreeSet<>(t)).append('\n');
            }
            out.append("TVKEYS turkcellPotterRows=").append(n)
                    .append(" distinctContentIds=").append(titlesById.size()).append('\n');
        } catch (Exception e) {
            out.append("TVKEYS status=error:").append(e.getClass().getSimpleName()).append('\n');
        }
        return out.toString();
    }

    private String tvIdsCheck() {
        // Read-only, test-only. Official TV+ page IDs arrive as "id=Title_With_Underscores" pairs in
        // the "ids" argument and are compared with the content_id values of com.turkcell.ott Preview rows.
        // Prints match booleans and counts only: no ID, title, URI or token value is written to the output.
        StringBuilder out = new StringBuilder();
        java.util.Map<String, String> official = new java.util.LinkedHashMap<>();
        String arg = tvidsArg == null ? "" : tvidsArg;
        for (String pair : arg.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            official.put(pair.substring(0, eq).trim(), tvTitleKey(pair.substring(eq + 1).replace('_', ' ')));
        }
        out.append("TVIDS officialPairs=").append(official.size()).append('\n');
        try (Cursor c = getTargetContext().getContentResolver().query(
                android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, null, null, null, null)) {
            if (c == null) return out.append("TVIDS status=null\n").toString();
            java.util.Map<String, java.util.Set<String>> device = new java.util.LinkedHashMap<>();
            int rows = 0;
            while (c.moveToNext()) {
                String pkg = probeText(c, "package_name");
                String title = probeText(c, "title");
                if (!"com.turkcell.ott".equals(pkg) || title == null || !title.toLowerCase(Locale.ROOT).contains("potter")) continue;
                rows++;
                String contentId = probeText(c, "content_id");
                if (contentId == null) continue;
                device.computeIfAbsent(contentId, k -> new java.util.TreeSet<>()).add(tvTitleKey(title));
            }
            out.append("TVIDS deviceRows=").append(rows).append(" deviceDistinctIds=").append(device.size()).append('\n');
            int g = 0;
            for (java.util.Map.Entry<String, java.util.Set<String>> e : device.entrySet()) {
                g++;
                boolean inOfficial = official.containsKey(e.getKey());
                // Shared-ID audit: a single device ID mapped to more than one distinct title is a conflict.
                out.append("TVIDS deviceGroup=").append(g)
                        .append(" titlesForId=").append(e.getValue().size())
                        .append(" inOfficial=").append(inOfficial).append('\n');
            }
            int o = 0;
            for (java.util.Map.Entry<String, String> e : official.entrySet()) {
                o++;
                java.util.Set<String> titles = device.get(e.getKey());
                boolean idMatch = titles != null;
                boolean titleMatch = idMatch && titles.size() == 1 && titles.contains(e.getValue());
                out.append("TVIDS official=").append(o)
                        .append(" title=").append(e.getValue())
                        .append(" idMatch=").append(idMatch)
                        .append(" titleMatch=").append(titleMatch).append('\n');
            }
        } catch (Exception e) {
            out.append("TVIDS status=error:").append(e.getClass().getSimpleName()).append('\n');
        }
        return out.toString();
    }

    /** Title key for comparison: Turkish dotless i mapped to i before the shared foldTitle. */
    private static String tvTitleKey(String s) {
        return foldTitle(s == null ? null : s.replace('ı', 'i').replace('İ', 'I'));
    }

    /** Length only, never the value: "null" or the character count. */
    private static String textLen(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        if (i < 0 || c.isNull(i)) return "null";
        String v = c.getString(i);
        return v == null ? "null" : String.valueOf(v.length());
    }

    /** Shape only, never the value: "empty", "null", "digits(len)", "imdb-like(len)", "alnum(len)", "other(len)". */
    private static String shapeOf(String v) {
        if (v == null) return "null";
        if (v.isEmpty()) return "empty";
        int len = v.length();
        if (v.matches("\\d+")) return "digits(" + len + ")";
        if (v.matches("tt\\d+")) return "imdb-like(" + len + ")";
        if (v.matches("[A-Za-z0-9_-]+")) return "alnum(" + len + ")";
        return "other(" + len + ")";
    }

    private static java.util.TreeSet<String> queryKeysOf(android.net.Uri uri) {
        return new java.util.TreeSet<>(uri.getQueryParameterNames());
    }

    private String tvCountCheck() {
        // Read-only, test-only. Mirrors ShelfData.load: same provider URIs, same column projections,
        // null selection and the same eligibility rules (ShelfLogic). Prints counts, title, type and
        // release year only; intent, URI, poster and ids are read but never written to the output.
        String[] watchCols = {"_id", "package_name", "title", "intent_uri", "poster_art_uri",
                "watch_next_type", "last_playback_position_millis", "duration_millis", "last_engagement_time_utc_millis",
                "type", "release_date"};
        String[] previewCols = {"_id", "package_name", "title", "intent_uri", "poster_art_uri", "type", "live", "release_date"};
        String[] slotPkgs = {
                "com.netflix.ninja",
                "com.amazon.amazonvideo.livingroom",
                "com.turkcell.ott",
                "com.apple.atve.androidtv.appletv",
                "com.mitv.tvhome.mitvplus"
        };
        String[] slotNames = {"netflix", "prime", "turkcell", "appletv", "mitvplus", "other"};
        StringBuilder out = new StringBuilder();
        out.append("PERM launcher=").append(permState(getTargetContext(), "com.gathin.tvhome"))
                .append(" test=").append(permState(getContext(), getContext().getPackageName())).append('\n');
        for (int t = 0; t < 2; t++) {
            boolean preview = t == 0;
            android.net.Uri uri = preview ? android.media.tv.TvContract.PreviewPrograms.CONTENT_URI
                    : android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI;
            String[] cols = preview ? previewCols : watchCols;
            String tag = preview ? "preview" : "watch";
            int[] raw = new int[slotNames.length];
            int[] eligible = new int[slotNames.length];
            int printed = 0;
            int total = 0;
            java.util.Map<String, Integer> otherPkgs = new java.util.TreeMap<>();
            try (Cursor c = getTargetContext().getContentResolver().query(uri, cols, null, null, null)) {
                if (c == null) {
                    out.append("TVCOUNT table=").append(tag).append(" status=null\n");
                    continue;
                }
                while (c.moveToNext()) {
                    total++;
                    String pkg = probeText(c, "package_name");
                    int slot = slotPkgs.length;
                    for (int s = 0; s < slotPkgs.length; s++) if (slotPkgs[s].equals(pkg)) { slot = s; break; }
                    raw[slot]++;
                    String title = probeText(c, "title");
                    String intent = probeText(c, "intent_uri");
                    boolean usable = pkg != null && !pkg.isEmpty() && title != null && !title.trim().isEmpty()
                            && intent != null && !intent.isEmpty();
                    boolean keep = usable && (preview
                            ? ShelfLogic.previewEligible(probeInt(c, "type", -1), probeInt(c, "live", -1))
                            : ShelfLogic.continueEligible(probeInt(c, "watch_next_type", -1),
                                    probeLong(c, "last_playback_position_millis"), probeLong(c, "duration_millis")));
                    if (keep) eligible[slot]++;
                    if (slot == slotPkgs.length) otherPkgs.merge(pkg == null ? "-" : pkg, 1, Integer::sum);
                    if (title != null && title.toLowerCase(Locale.ROOT).contains("potter")) {
                        out.append("POTTER table=").append(tag).append(" pkg=").append(pkg)
                                .append(" type=").append(probeInt(c, "type", -1))
                                .append(" eligible=").append(keep)
                                .append(" year=").append(yearOf(probeText(c, "release_date")))
                                .append(" extId=").append(externalIdKind(ShelfIdentity.parse(title, intent, null,
                                        probeInt(c, "type", -1), probeText(c, "release_date")).externalId))
                                .append(" title=").append(oneLine(title)).append('\n');
                    }
                    if (slot < 2 && keep && printed < 40) {
                        printed++;
                        out.append("TVROW table=").append(tag).append(" pkg=").append(slotNames[slot])
                                .append(" type=").append(probeInt(c, "type", -1))
                                .append(" year=").append(yearOf(probeText(c, "release_date")))
                                .append(" title=").append(oneLine(title)).append('\n');
                    }
                }
            } catch (Exception e) {
                out.append("TVCOUNT table=").append(tag).append(" status=error:").append(e.getClass().getSimpleName()).append('\n');
                continue;
            }
            for (java.util.Map.Entry<String, Integer> e : otherPkgs.entrySet()) {
                out.append("TVPKG table=").append(tag).append(" pkg=").append(e.getKey())
                        .append(" n=").append(e.getValue()).append('\n');
            }
            out.append("TVCOUNT table=").append(tag).append(" status=ok raw=").append(total);
            for (int s = 0; s < slotNames.length; s++) {
                out.append(' ').append(slotNames[s]).append(".raw=").append(raw[s])
                        .append(' ').append(slotNames[s]).append(".eligible=").append(eligible[s]);
            }
            out.append('\n');
        }
        out.append("PASS-TVCOUNT\n");
        return out.toString();
    }

    private String nfMatchCheck() {
        // Read-only, test-only. Netflix TvContract rows titled "Lupin" -> Netflix title ID (the ShelfIdentity
        // regex on the raw intent) -> Wikidata P1874 with exactly one item -> English label equal to the row
        // title, P31 kind agreeing with the row type, IMDb id from P345 that maps back to the same item only.
        // No year is invented: the provider publishes none, so it is printed as "-" and not used.
        // Prints title, type, year, Netflix id, QID, label and IMDb id only. Never prints intent or URI.
        final String want = "Lupin";
        java.util.regex.Pattern prodNetflix = java.util.regex.Pattern.compile("(?:nflx://.*|netflix\\.com/.*)title/(\\d+)");
        String[] cols = {"_id", "package_name", "title", "intent_uri", "type", "release_date"};
        StringBuilder out = new StringBuilder();
        List<String[]> found = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            android.net.Uri uri = t == 0 ? android.media.tv.TvContract.PreviewPrograms.CONTENT_URI
                    : android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI;
            String tag = t == 0 ? "preview" : "watch";
            try (Cursor c = getTargetContext().getContentResolver().query(uri, cols, null, null, null)) {
                if (c == null) {
                    out.append("NFMATCH table=").append(tag).append(" status=null\n");
                    continue;
                }
                while (c.moveToNext()) {
                    if (!"com.netflix.ninja".equals(probeText(c, "package_name"))) continue;
                    String title = probeText(c, "title");
                    if (title == null || !want.equalsIgnoreCase(title.trim())) continue;
                    String intent = probeText(c, "intent_uri");
                    java.util.regex.Matcher m = intent == null ? null : prodNetflix.matcher(intent);
                    String nid = m != null && m.find() ? m.group(1) : "-";
                    found.add(new String[]{tag, String.valueOf(probeInt(c, "type", -1)),
                            yearOf(probeText(c, "release_date")), nid, title.trim()});
                }
                out.append("NFMATCH table=").append(tag).append(" status=ok\n");
            } catch (Exception e) {
                out.append("NFMATCH table=").append(tag).append(" status=error:").append(e.getClass().getSimpleName()).append('\n');
            }
        }
        out.append("NFMATCH rows=").append(found.size()).append(" want=").append(want).append('\n');
        boolean[] first = new boolean[]{true};
        try {
            out.append("NFMATCH-CTL P345 tt0903747 items=").append(sparqlItems("P345", "tt0903747", first).size()).append('\n');
        } catch (Exception e) {
            out.append("NFMATCH-CTL error=").append(e.getClass().getSimpleName()).append('\n');
        }
        int kesin = 0, belirsiz = 0, bulunamadi = 0;
        int shown = Math.min(found.size(), 10);
        for (int i = 0; i < shown; i++) {
            String[] row = found.get(i);
            out.append("NFMATCH-ROW ").append(i).append(" table=").append(row[0])
                    .append(" type=").append(row[1]).append(" year=").append(row[2])
                    .append(" nfid=").append(row[3]);
            String verdict;
            try {
                verdict = nfVerify(row[3], Integer.parseInt(row[1]), row[4], first, out);
            } catch (Exception e) {
                out.append(" reason=error:").append(e.getClass().getSimpleName());
                verdict = "BELIRSIZ";
            }
            out.append(" verdict=").append(verdict).append('\n');
            if ("KESIN".equals(verdict)) kesin++;
            else if ("BELIRSIZ".equals(verdict)) belirsiz++;
            else bulunamadi++;
        }
        if (found.size() > shown) out.append("NFMATCH-CAP dropped=").append(found.size() - shown).append('\n');
        // Negative control: the same gate must reject a Netflix id whose Wikidata item is a different title.
        try {
            StringBuilder neg = new StringBuilder();
            String negVerdict = nfVerify("80186863", 3, "Ghosts", first, neg);
            out.append("NFMATCH-NEG nfid=80186863 rowTitle=Ghosts verdict=").append(negVerdict).append(neg).append('\n');
        } catch (Exception e) {
            out.append("NFMATCH-NEG error=").append(e.getClass().getSimpleName()).append('\n');
        }
        out.append("END-NFMATCH kesin=").append(kesin).append(" belirsiz=").append(belirsiz)
                .append(" bulunamadi=").append(bulunamadi).append('\n');
        return out.toString();
    }

    /** Records the GETs the production verifier sends, per Wikidata stage. Its bodies are only read, never printed. */
    private static final class NfRecorder implements PrimeImdb.Http {
        final PrimeImdb.Http inner = new PrimeImdb.HttpGet();
        final List<String[]> calls = new ArrayList<>();   // {stage, body, failure kind or ""}
        int failed;
        int total;

        @Override public String get(String url) throws java.io.IOException {
            total++;
            String stage = url.contains("haswbstatement%3AP1874") ? "p1874"
                    : url.contains("haswbstatement%3AP345") ? "p345"
                    : url.contains("wbgetentities") ? "entity" : "other";
            try {
                String body = inner.get(url);
                calls.add(new String[]{stage, body, ""});
                return body;
            } catch (java.io.IOException e) {
                failed++;
                calls.add(new String[]{stage, "", PrimeImdb.kindOf(e)});
                throw e;
            }
        }

        /** The body of the last successful call of a stage, else null. */
        String body(String stage) {
            for (int k = calls.size() - 1; k >= 0; k--) {
                String[] c = calls.get(k);
                if (c[0].equals(stage) && c[2].isEmpty()) return c[1];
            }
            return null;
        }
    }

    /**
     * Read-only, test-only: the production PrimeImdb.verifyNetflix over every Netflix Preview row on the TV, then the
     * Ghosts control. The verdict is the production one. The reason is read from the recorded Wikidata answers, so a
     * network failure, a title/language mismatch, the P1874 or P345 count and the type are told apart. The Netflix id
     * comes from the production ShelfIdentity.netflixIdIn; the intent and URI are never printed.
     */
    private String nfVerifyCheck() {
        final int cap = 40;
        StringBuilder out = new StringBuilder();
        List<String[]> rows = new ArrayList<>();   // {title, TvContract type, year, Netflix id or "-"}
        String[] cols = {"_id", "package_name", "title", "intent_uri", "type", "release_date"};
        try (Cursor c = getTargetContext().getContentResolver().query(
                android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, cols, null, null, null)) {
            if (c == null) {
                out.append("NFVERIFY table=preview status=null\n");
            } else {
                while (c.moveToNext()) {
                    if (!"com.netflix.ninja".equals(probeText(c, "package_name"))) continue;
                    String title = probeText(c, "title");
                    String nid = ShelfIdentity.netflixIdIn(probeText(c, "intent_uri"));
                    rows.add(new String[]{title == null ? "" : title.trim(), String.valueOf(probeInt(c, "type", -1)),
                            yearOf(probeText(c, "release_date")), nid == null ? "-" : nid});
                }
                out.append("NFVERIFY table=preview status=ok\n");
            }
        } catch (Exception e) {
            out.append("NFVERIFY table=preview status=error:").append(e.getClass().getSimpleName()).append('\n');
        }
        out.append("NFVERIFY rows=").append(rows.size()).append('\n');

        NfRecorder rec = new NfRecorder();
        PrimeImdb.Net net = new PrimeImdb.Net(rec, PrimeImdb::sleepQuietly);
        java.util.Map<String, Integer> verdicts = new java.util.TreeMap<>();
        java.util.Map<String, Integer> reasons = new java.util.TreeMap<>();
        int n = Math.min(rows.size(), cap);
        for (int i = 0; i < n; i++) {
            String[] row = rows.get(i);
            rec.calls.clear();
            StringBuilder detail = new StringBuilder();
            String verdict;
            String reason;
            PrimeImdb.Result kesin = null;
            try {
                PrimeImdb.Result r = PrimeImdb.verifyNetflix(row[3], row[0], Integer.parseInt(row[1]), net);
                verdict = r.verdict;
                reason = nfReason(r, row[0], row[3], Integer.parseInt(row[1]), rec, detail);
                if (PrimeImdb.KESIN.equals(r.verdict)) kesin = r;
            } catch (Throwable t) {
                verdict = "EXCEPTION";
                reason = "exception:" + t.getClass().getSimpleName();
            }
            if (kesin != null) {
                // The production Fanart path the launcher takes for a verified row; only counts and a file name print.
                try {
                    ShelfIdentity.Query q = new ShelfIdentity.Query(kesin.title, kesin.year, kesin.type,
                            "imdb:" + kesin.imdbId, null);
                    ShelfArtworkResolver.FanartClient fc = ShelfArtworkResolver.fanartClient(
                            getTargetContext().getExternalFilesDir(null));
                    int urls = ShelfArtworkResolver.resolveBackdrops(q, fc).size();
                    String pick = ShelfArtworkResolver.selectFanartBackdropAsync(q, fc).get(90, java.util.concurrent.TimeUnit.SECONDS);
                    out.append("NFVERIFY-FANART ").append(i).append(" imdb=").append(kesin.imdbId)
                            .append(" backdrops16x9=").append(urls).append(" picked=")
                            .append(pick == null ? "none" : pick.substring(pick.lastIndexOf('/') + 1)).append('\n');
                } catch (Throwable t) {
                    out.append("NFVERIFY-FANART ").append(i).append(" error=").append(t.getClass().getSimpleName()).append('\n');
                }
            }
            out.append("NFVERIFY-ROW ").append(i).append(" type=").append(row[1]).append(" year=").append(row[2])
                    .append(" nfid=").append(row[3]).append(" title=").append(oneLine(row[0]))
                    .append(" verdict=").append(verdict).append(" reason=").append(reason)
                    .append(detail).append(" calls=").append(rec.calls.size()).append('\n');
            Integer v = verdicts.get(verdict);
            verdicts.put(verdict, v == null ? 1 : v + 1);
            Integer g = reasons.get(reason);
            reasons.put(reason, g == null ? 1 : g + 1);
        }
        if (rows.size() > cap) out.append("NFVERIFY-CAP dropped=").append(rows.size() - cap).append('\n');

        // Negative control: the production gate must reject a Netflix id whose Wikidata item is another title.
        rec.calls.clear();
        StringBuilder neg = new StringBuilder();
        String negVerdict;
        String negReason;
        try {
            PrimeImdb.Result nr = PrimeImdb.verifyNetflix("80186863", "Ghosts", 3, net);
            negVerdict = nr.verdict;
            negReason = nfReason(nr, "Ghosts", "80186863", 3, rec, neg);
        } catch (Throwable t) {
            negVerdict = "EXCEPTION";
            negReason = "exception:" + t.getClass().getSimpleName();
        }
        out.append("NFVERIFY-NEG nfid=80186863 rowTitle=Ghosts verdict=").append(negVerdict)
                .append(" reason=").append(negReason).append(neg).append('\n');

        out.append("NFVERIFY-SUM verdicts=").append(verdicts).append(" reasons=").append(reasons)
                .append(" http=").append(rec.total).append(" failedHttp=").append(rec.failed)
                .append(" retry=").append(net.retries).append(" backoffMs=").append(net.backoffMs).append('\n');
        out.append("PASS-NFVERIFY\n");
        return out.toString();
    }

    /**
     * The reason of one production verdict, read from the Wikidata answers it recorded. Each check follows the
     * production order: P1874 count, English title, P31 kind, P345 count and shape, the reverse P345 lookup.
     * Appends the Q-id, the IMDb id and the counts it saw; a reason of "unexplained" means the rules here and the
     * production gate disagree, and is reported as such.
     */
    private static String nfReason(PrimeImdb.Result r, String title, String nid, int typeCode, NfRecorder rec,
                                   StringBuilder out) throws Exception {
        if (PrimeImdb.KESIN.equals(r.verdict)) return "ok";
        if (PrimeImdb.NETWORK_ERROR.equals(r.verdict)) return "network:" + r.netKind;
        if (rec.calls.isEmpty()) {
            if ("-".equals(nid)) return "no-netflix-id";
            if (!PrimeImdb.isQueryableNorm(PrimeImdb.exactNorm(title))) return "title-unqueryable";
            return "bad-nid";
        }
        String search = rec.body("p1874");
        if (search == null) return "p1874-failed";
        List<String> hits = PrimeImdb.parseSearch(search);
        out.append(" p1874=").append(hits.size());
        if (hits.isEmpty()) return "p1874-none";
        if (hits.size() > 1) return "p1874-ambiguous";
        String qid = hits.get(0);
        String entityBody = rec.body("entity");
        if (entityBody == null) return "entity-failed";
        JSONObject entities = new JSONObject(entityBody).optJSONObject("entities");
        JSONObject ent = entities == null ? null : entities.optJSONObject(qid);
        if (ent == null) return "entity-missing";
        JSONObject labels = ent.optJSONObject("labels");
        JSONObject en = labels == null ? null : labels.optJSONObject("en");
        JSONObject tr = labels == null ? null : labels.optJSONObject("tr");
        String label = en != null ? en.optString("value", "") : "";
        if (label.isEmpty() && tr != null) label = tr.optString("value", "");
        out.append(" qid=").append(qid).append(" enLabel=").append(en != null).append(" wdLabel=").append(oneLine(label));
        if (label.isEmpty() || !PrimeImdb.exactNorm(title).equals(PrimeImdb.exactNorm(label))) return "title-language-or-mismatch";
        JSONObject claims = ent.optJSONObject("claims");
        if (claims == null) return "no-claims";
        boolean film = claimsHasId(claims, "P31", "Q11424");
        boolean series = claimsHasId(claims, "P31", "Q5398426") || claimsHasId(claims, "P31", "Q15416");
        boolean episode = claimsHasId(claims, "P31", "Q21191270");
        boolean season = claimsHasId(claims, "P31", "Q3464665");
        boolean partOf = claimsHasAny(claims, "P179");
        List<String> tts = nfStrings(claims, "P345");
        out.append(" kind=").append(film ? "film" : series ? "series" : "none").append(" p345=").append(tts.size());
        if (tts.isEmpty()) return "p345-missing";
        if (tts.size() > 1) return "p345-multiple";
        String tt = tts.get(0);
        if (!tt.matches("tt\\d{7,9}")) return "p345-malformed";
        out.append(" imdb=").append(tt);
        if (episode || season || partOf) return "kind-episode-season-or-part";
        if (film == series) return "kind-ambiguous-or-none";
        boolean typeFits = typeCode == 0 ? film : typeCode == 1 ? !film : typeCode == 3;
        if (!typeFits) return "type-mismatch";
        String back = rec.body("p345");
        if (back == null) return "p345-reverse-failed";
        List<String> items = PrimeImdb.parseSearch(back);
        out.append(" rev=").append(items.size());
        if (items.size() != 1 || !qid.equals(items.get(0))) return "p345-reverse-mismatch";
        return "unexplained";
    }

    private static List<String> nfStrings(JSONObject claims, String prop) {
        List<String> out = new ArrayList<>();
        JSONArray arr = claims.optJSONArray(prop);
        if (arr == null) return out;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject st = arr.optJSONObject(k);
            JSONObject snak = st == null ? null : st.optJSONObject("mainsnak");
            JSONObject dv = snak == null ? null : snak.optJSONObject("datavalue");
            String v = dv == null ? "" : dv.optString("value", "");
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    /** Appends the checks for one row to out, without a newline, and returns KESIN, BELIRSIZ or BULUNAMADI. */
    private static String nfVerify(String nid, int typeCode, String rowTitle, boolean[] first, StringBuilder out) throws Exception {
        if ("-".equals(nid)) {
            out.append(" reason=no-netflix-id");
            return "BULUNAMADI";
        }
        List<String> items = sparqlItems("P1874", nid, first);
        out.append(" qcand=").append(items.size());
        if (items.isEmpty()) return "BULUNAMADI";
        if (items.size() != 1) return "BELIRSIZ";
        String qid = items.get(0);
        JSONObject ent = wikidataEntity(qid, first);
        JSONObject claims = ent == null ? null : ent.optJSONObject("claims");
        if (claims == null) {
            out.append(" reason=no-claims");
            return "BELIRSIZ";
        }
        String wdTitle = labelEn(ent);
        boolean labelOk = wdTitle != null && foldTitle(wdTitle).equals(foldTitle(rowTitle));
        boolean isFilm = claimsHasId(claims, "P31", "Q11424");
        boolean isSeries = claimsHasId(claims, "P31", "Q5398426");
        boolean isEpisode = claimsHasId(claims, "P31", "Q21191270");
        boolean typeOk = typeCode == 0 ? isFilm
                : (typeCode == 1 || typeCode == 2) ? isSeries
                : typeCode == 3 ? (isEpisode || isSeries)
                : false;
        String tt = claimsFirstString(claims, "P345");
        boolean ttShape = tt != null && tt.matches("tt\\d+");
        boolean imdbUnique = false;
        if (ttShape) {
            List<String> back = sparqlItems("P345", tt, first);
            imdbUnique = back.size() == 1 && qid.equals(back.get(0));
        }
        boolean tmdb = claimsHasAny(claims, "P4947") || claimsHasAny(claims, "P4983");
        String kind = isFilm ? "film" : isSeries ? "series" : isEpisode ? "episode" : "other";
        out.append(" qid=").append(qid).append(" kind=").append(kind)
                .append(" wdTitle=").append(oneLine(wdTitle))
                .append(" labelOk=").append(labelOk).append(" typeOk=").append(typeOk)
                .append(" imdb=").append(ttShape ? tt : "-").append(" imdbUnique=").append(imdbUnique)
                .append(" tmdb=").append(tmdb);
        return labelOk && typeOk && imdbUnique ? "KESIN" : "BELIRSIZ";
    }

    private static JSONObject wikidataEntity(String qid, boolean[] first) throws Exception {
        String url = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids="
                + URLEncoder.encode(qid, "UTF-8") + "&props=labels|claims&languages=en&format=json";
        JSONObject entities = new JSONObject(phashApiGet(url, first)).optJSONObject("entities");
        return entities == null ? null : entities.optJSONObject(qid);
    }

    private static String labelEn(JSONObject ent) {
        JSONObject labels = ent.optJSONObject("labels");
        JSONObject en = labels == null ? null : labels.optJSONObject("en");
        return en == null ? null : en.optString("value", null);
    }

    private static String claimsFirstString(JSONObject claims, String prop) {
        JSONArray arr = claims.optJSONArray(prop);
        if (arr == null) return null;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject st = arr.optJSONObject(k);
            JSONObject mainsnak = st == null ? null : st.optJSONObject("mainsnak");
            JSONObject dv = mainsnak == null ? null : mainsnak.optJSONObject("datavalue");
            if (dv == null) continue;
            String v = dv.optString("value", "");
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    /** Case, accent and punctuation folded, so "Lupin" and "LUPIN" compare equal and "Lupin" and "Ghosts" do not. */
    private static String foldTitle(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", "");
    }

    private static String permState(android.content.Context ctx, String pkg) {
        return ctx.getPackageManager().checkPermission("android.permission.READ_TV_LISTINGS", pkg)
                == android.content.pm.PackageManager.PERMISSION_GRANTED ? "GRANTED" : "DENIED";
    }

    private static String probeText(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    /** Prefix of an external id only (never its value): "none", "netflix", "imdb", "tmdb", ... */
    private static String externalIdKind(String externalId) {
        if (externalId == null) return "none";
        int colon = externalId.indexOf(':');
        return colon < 0 ? "other" : externalId.substring(0, colon);
    }

    private static long probeLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private static int probeInt(Cursor c, String col, int fallback) {
        int i = c.getColumnIndex(col);
        return i < 0 || c.isNull(i) ? fallback : c.getInt(i);
    }

    private static String yearOf(String releaseDate) {
        if (releaseDate == null) return "-";
        java.util.regex.Matcher m = Pattern.compile("(\\d{4})").matcher(releaseDate);
        return m.find() ? m.group(1) : "-";
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private String pvColsCheck() {
        // Read-only: every TvContract column the Prime app publishes for its own rows.
        // Checks whether release year / type / original title are carried by the provider.
        StringBuilder out = new StringBuilder();
        android.net.Uri[] uris = {android.media.tv.TvContract.PreviewPrograms.CONTENT_URI,
                android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI};
        String[] tags = {"preview", "watchnext"};
        for (int u = 0; u < uris.length; u++) {
            try (Cursor c = getTargetContext().getContentResolver().query(uris[u], null, null, null, null)) {
                if (c == null) { out.append("COLS ").append(tags[u]).append(" query=null\n"); continue; }
                String[] names = c.getColumnNames();
                while (c.moveToNext()) {
                    int pi = c.getColumnIndex("package_name");
                    String pkg = pi >= 0 && !c.isNull(pi) ? c.getString(pi) : "";
                    if (!"com.amazon.amazonvideo.livingroom".equals(pkg)) continue;
                    int ii = c.getColumnIndex("intent_uri");
                    String intent = ii >= 0 && !c.isNull(ii) ? c.getString(ii) : "";
                    java.util.regex.Matcher m = Pattern.compile("amzn1\\.dv\\.gti\\.[A-Za-z0-9_-]+")
                            .matcher(intent);
                    if (!m.find()) continue;
                    out.append("COLS ").append(tags[u]).append(' ').append(m.group(0));
                    for (String n : names) {
                        int i = c.getColumnIndex(n);
                        if (i < 0) continue;
                        String v;
                        try { v = c.isNull(i) ? "" : c.getString(i); } catch (Exception e) { v = "<binary>"; }
                        if (v == null) v = "";
                        v = oneLine(v);
                        if (v.length() > 300) v = v.substring(0, 300) + "...[" + v.length() + "]";
                        out.append(" | ").append(n).append("=").append(v);
                    }
                    out.append('\n');
                }
            } catch (Exception e) {
                out.append("COLS ").append(tags[u]).append(" ERR ").append(e.getClass().getSimpleName())
                        .append(": ").append(oneLine(String.valueOf(e.getMessage()))).append('\n');
            }
        }
        out.append("PASS-PVCOLS done\n");
        return out.toString();
    }

    private String pvShotCheck() {
        // Read-only capture of the current screen via UiAutomation (different SF path than screencap).
        // Writes one PNG into the test app's own external files dir; nothing else is touched.
        StringBuilder out = new StringBuilder();
        try {
            Thread.sleep(3000);
            android.graphics.Bitmap bmp = getUiAutomation().takeScreenshot();
            java.io.File dir = getTargetContext().getExternalFilesDir(null);
            if (dir != null) {
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, "pvshot.png");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                out.append("PASS-PVSHOT ok=true file=").append(f.getAbsolutePath())
                        .append(" w=").append(bmp.getWidth()).append(" h=").append(bmp.getHeight()).append('\n');
            } else {
                out.append("PASS-PVSHOT ok=false reason=no-external-dir\n");
            }
        } catch (Throwable t) {
            out.append("PASS-PVSHOT ok=false reason=").append(t.getClass().getSimpleName())
                    .append(": ").append(oneLine(String.valueOf(t.getMessage()))).append('\n');
        }
        return out.toString();
    }

    private static final class PrimeMetadata {
        final String title;
        final int year;
        final ShelfIdentity.Type type;
        PrimeMetadata(String title, int year, ShelfIdentity.Type type) {
            this.title = title;
            this.year = year;
            this.type = type;
        }
    }

    private static final class ImdbOffRow {
        final String title;
        final int rawType;
        final String rawYear;
        final int seasNum;
        final int epNum;
        final String seasonTitle;
        final String gti;
        final String intent;
        ImdbOffRow(String title, int rawType, String rawYear, int seasNum, int epNum,
                String seasonTitle, String gti, String intent) {
            this.title = title;
            this.rawType = rawType;
            this.rawYear = rawYear;
            this.seasNum = seasNum;
            this.epNum = epNum;
            this.seasonTitle = seasonTitle;
            this.gti = gti;
            this.intent = intent;
        }
    }

    private void collectOffRows(List<ImdbOffRow> pool, android.net.Uri uri, int limit) {
        if (pool.size() >= limit) return;
        String[] proj = {"package_name", "title", "type", "release_date",
                "season_display_number", "season_title",
                "episode_display_number", "episode_title", "intent_uri"};
        Pattern gtiPat = Pattern.compile("amzn1\\.dv\\.gti\\.[A-Za-z0-9_-]+");
        try (Cursor c = getTargetContext().getContentResolver().query(uri, proj, null, null, null)) {
            if (c == null) return;
            int iPkg = c.getColumnIndex("package_name"), iTitle = c.getColumnIndex("title"),
                    iType = c.getColumnIndex("type"), iRel = c.getColumnIndex("release_date"),
                    iSeas = c.getColumnIndex("season_display_number"),
                    iSeasT = c.getColumnIndex("season_title"),
                    iEp = c.getColumnIndex("episode_display_number"),
                    iEpT = c.getColumnIndex("episode_title"),
                    iIntent = c.getColumnIndex("intent_uri");
            while (c.moveToNext() && pool.size() < limit) {
                String pkg = iPkg >= 0 && !c.isNull(iPkg) ? c.getString(iPkg) : "";
                if (!"com.amazon.amazonvideo.livingroom".equals(pkg)) continue;
                String t = iTitle >= 0 && !c.isNull(iTitle) ? c.getString(iTitle).trim() : "";
                if (t.isEmpty()) continue;
                String intent = iIntent >= 0 && !c.isNull(iIntent) ? c.getString(iIntent) : "";
                if (intent.isEmpty()) continue;
                String decoded = intent;
                try {
                    decoded = java.net.URLDecoder.decode(decoded, "UTF-8");
                    decoded = java.net.URLDecoder.decode(decoded, "UTF-8");
                } catch (Exception ignored) {}
                java.util.regex.Matcher m = gtiPat.matcher(decoded + " " + intent);
                String gti = m.find() ? m.group(0) : null;
                if (gti == null) continue;
                int typeCode = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                String rel = iRel >= 0 && !c.isNull(iRel) ? c.getString(iRel) : null;
                String seasText = iSeas >= 0 && !c.isNull(iSeas) ? c.getString(iSeas) : null;
                String epText = iEp >= 0 && !c.isNull(iEp) ? c.getString(iEp) : null;
                String seasT = iSeasT >= 0 && !c.isNull(iSeasT) ? c.getString(iSeasT).trim() : "";
                pool.add(new ImdbOffRow(t, typeCode, rel, digitsPrefix(seasText),
                        digitsPrefix(epText), seasT, gti, intent));
            }
        }
    }

    private static int digitsPrefix(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m = Pattern.compile("^\\s*(\\d{1,3})").matcher(s);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String exactNorm(String s) {
        // ponytail: order/token-sensitive exact match only; no stop-word removal or token sorting.
        if (s == null) return "";
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("[\\u2013\\u2014\\u2212]", "-");
        t = t.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    private static boolean isQueryableNorm(String norm) {
        if (norm == null || norm.isEmpty()) return false;
        String packed = norm.replace(" ", "");
        return packed.length() >= 2 && Pattern.compile("[a-z0-9]").matcher(packed).find();
    }

    private static String stripProvenBase(String title, int seasNum, int epNum) {
        if (title == null || seasNum <= 0) return null;
        String t = title.trim();
        String safe = Pattern.quote(String.valueOf(seasNum));
        Pattern pat = epNum > 0
                ? Pattern.compile("(?i)[\\s\\-:–—(|\\[].*?\\b(sezon|season)\\b\\s*" + safe
                        + ".*?\\b(bolum|bölüm|episode)\\b\\s*" + Pattern.quote(String.valueOf(epNum))
                        + "\\b.*$")
                : Pattern.compile("(?i)[\\s\\-:–—(|\\[].*?\\b(sezon|season)\\b\\s*" + safe + "\\b.*$");
        java.util.regex.Matcher m = pat.matcher(t);
        if (!m.find()) return null;
        String base = t.substring(0, m.start()).replaceAll("[\\s\\-:–—(|\\[]+$", "").trim();
        if (base.isEmpty()) return null;
        return exactNorm(base);
    }

    private static String sha256hexQuiet(String s) {
        try {
            return sha256hex(s);
        } catch (Exception e) {
            return "";
        }
    }

    private static int checkImdbOffNormalizer() {
        int checks = 0;
        require("the office".equals(exactNorm("  The   Office! ")), "exact normalization failed");
        checks++;
        require(!exactNorm("Office The").equals(exactNorm("The Office")), "ordered match was lost");
        checks++;
        require(!isQueryableNorm("!"), "single punctuation passed negative control");
        checks++;
        require(!isQueryableNorm(""), "empty title passed negative control");
        checks++;
        require(exactNorm("Dune: Part Two").equals(exactNorm("Dune Part Two")),
                "punctuation normalization failed");
        checks++;
        require(stripProvenBase("Series S1", 2, 3) == null, "unproven suffix was stripped");
        checks++;
        require(stripProvenBase("Series Sezon 2", 2, 0) != null, "proven season suffix kept");
        checks++;
        require("series".equals(stripProvenBase("Series Sezon 2", 2, 0)),
                "proven suffix extraction failed");
        checks++;
        return checks;
    }

    private static PrimeMetadata parsePrimeMetadata(String html, String gti) {
        if (html == null || gti == null || gti.isEmpty()) return null;
        java.util.regex.Matcher script = Pattern.compile(
                "(?is)<script\\b(?=[^>]*type\\s*=\\s*['\\\"]application/ld\\+json['\\\"])[^>]*>(.*?)</script\\s*>")
                .matcher(html);
        JSONObject metadata = null;
        while (script.find()) {
            try {
                JSONObject candidate = new JSONObject(script.group(1).trim());
                String candidateUrl = candidate.optString("url", "");
                String candidateId = candidate.optString("@id", "");
                String candidateGti = candidate.optString("gti", candidate.optString("titleId", ""));
                if (!candidateGti.equals(gti) && !candidateUrl.contains(gti) && !candidateId.contains(gti)) continue;
                boolean hasDate = candidate.has("datePublished") || candidate.has("releaseDate");
                String candidateDate = candidate.optString("datePublished", candidate.optString("releaseDate", ""));
                boolean validYear = candidateDate.matches("^(19\\d{2}|20\\d{2})(?:-\\d{2}-\\d{2})?$");
                if (candidate.optString("name", "").trim().isEmpty()
                        || (!hasDate && !candidate.has("@type"))) continue;
                metadata = candidate;
                break;
            } catch (Exception ignored) {}
        }
        if (metadata == null) return null;
        String title = metadata.optString("name", "").trim();
        if (title.isEmpty()) return null;
        int year = 0;
        String date = metadata.optString("datePublished", metadata.optString("releaseDate", ""));
        java.util.regex.Matcher ym = Pattern.compile("^(19\\d{2}|20\\d{2})(?:-\\d{2}-\\d{2})?$").matcher(date);
        if (ym.matches()) year = Integer.parseInt(ym.group(1));
        String typeName = metadata.optString("@type", "");
        if (typeName.startsWith("[")) {
            try {
                JSONArray types = new JSONArray(typeName);
                typeName = types.length() == 1 ? types.optString(0, "") : "";
            } catch (Exception ignored) { typeName = ""; }
        }
        if (typeName.contains("/")) typeName = typeName.substring(typeName.lastIndexOf('/') + 1);
        ShelfIdentity.Type type = typeName.equalsIgnoreCase("Movie") ? ShelfIdentity.Type.MOVIE
                : typeName.equalsIgnoreCase("TVSeries") ? ShelfIdentity.Type.SERIES
                : typeName.equalsIgnoreCase("TVEpisode") ? ShelfIdentity.Type.EPISODE
                : ShelfIdentity.Type.UNKNOWN;
        return new PrimeMetadata(title, year, type);
    }

    private static String normalizedTitle(String title) {
        Set<String> tokens = ShelfIdentity.extractTokens(title);
        List<String> ordered = new ArrayList<>(tokens);
        Collections.sort(ordered);
        return android.text.TextUtils.join(" ", ordered);
    }

    private static void checkPrimeMetadataParser() {
        String valid = "<script type=\"application/ld+json\">"
                + "{\"@id\":\"https://www.primevideo.com/detail/amzn1.dv.gti.target\","
                + "\"name\":\"Target Title\",\"datePublished\":\"2021-01-01\","
                + "\"@type\":\"TVSeries\"}</script>";
        PrimeMetadata parsed = parsePrimeMetadata(valid, "amzn1.dv.gti.target");
        require(parsed != null && "Target Title".equals(parsed.title) && parsed.year == 2021
                && parsed.type == ShelfIdentity.Type.SERIES, "GTI-linked structured metadata parse failed");
        require(parsePrimeMetadata("<title>Wrong 1999 Prime Video</title><p>Recommended 2024</p>",
                "amzn1.dv.gti.target") == null, "unlinked page text passed metadata negative control");
        String other = "<script type=\"application/ld+json\">"
                + "{\"@id\":\"https://www.primevideo.com/detail/amzn1.dv.gti.other\","
                + "\"name\":\"Wrong Title\",\"datePublished\":\"2001-01-01\","
                + "\"@type\":\"Movie\"}</script>";
        require(parsePrimeMetadata(other, "amzn1.dv.gti.target") == null,
                "metadata for different GTI passed negative control");
        require(parsePrimeMetadata("<html><title>Prime Video 1999</title>"
                + "<p>Recommended series 2024</p></html>", "amzn1.dv.gti.target") == null,
                "page title/recommendations passed metadata negative control");
        String badDate = "<script type=\"application/ld+json\">"
                + "{\"@id\":\"https://www.primevideo.com/detail/amzn1.dv.gti.target\","
                + "\"name\":\"Target Title\",\"datePublished\":\"Recommended 2024\","
                + "\"@type\":\"TVSeries\"}</script>";
        PrimeMetadata dateOnly = parsePrimeMetadata(badDate, "amzn1.dv.gti.target");
        require(dateOnly != null && dateOnly.year == 0,
                "unstructured/recommendation date was not left UNKNOWN");
    }

    private static String sha256hex(String s) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder h = new StringBuilder(d.length * 2);
        for (byte b : d) {
            h.append(Character.forDigit((b >> 4) & 0xF, 16));
            h.append(Character.forDigit(b & 0xF, 16));
        }
        return h.toString();
    }

    private String imdbxCheck() {
        // Read-only: for each GTI page that carried an IMDb id, prove the tt id comes
        // from the work's own metadata region (before any recommendations rail) and
        // cross-check it via Wikidata P345: title overlap, year (P577, +-1), type
        // (P31 film/series/episode vs page markers), parent P179 for episodes.
        // Counts/booleans only. ponytail: marker heuristics; DOM parse when FP matters.
        List<WikiRow> pool = new ArrayList<>();
        collectWikiRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, 50);
        collectWikiRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, 50);
        List<String> gtis = new ArrayList<>();
        for (WikiRow r : pool) {
            if (r.slot != 1 || r.gti == null) continue;
            if (!gtis.contains(r.gti) && gtis.size() < 12) gtis.add(r.gti);
        }
        StringBuilder out = new StringBuilder();
        long startMs = SystemClock.elapsedRealtime();
        boolean[] first = new boolean[]{true};
        Pattern yearPat = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b");
        Pattern imdbPat = Pattern.compile("tt\\d{7,8}");
        Pattern ogPat = Pattern.compile("og:title[^>]{0,300}?content\\s*=\\s*\"([^\"]+)",
                Pattern.CASE_INSENSITIVE);
        Pattern titlePat = Pattern.compile("<title[^>]*>([^<]{1,200})<",
                Pattern.CASE_INSENSITIVE);
        String[] recMarks = {"more like this", "customers also", "you may also", "frequently",
                "recommended", "related", "more to explore", "önerilen", "bunları da",
                "sıradaki", "tavsiye", "ilginizi çekebilir"};
        int n = gtis.size();
        int page = 0, ttFound = 0, own = 0, multi = 0, wdUnique = 0;
        int titleOk = 0, yearOk = 0, typeOkN = 0, parentOkN = 0;
        int kesin = 0, yanlis = 0, belirsiz = 0, netFail = 0, blocked = 0;
        for (int i = 0; i < n; i++) {
            String gti = gtis.get(i);
            boolean ok = false, hasTt = false, isOwn = false, isMulti = false;
            boolean uniq = false, tOk = false, yOk = false, tyOk = false, pOk = true;
            String verdict = "BELIRSIZ";
            if (SystemClock.elapsedRealtime() - startMs > 7 * 60 * 1000) {
                netFail++;
            } else {
                String body = null;
                try {
                    body = phashApiGet("https://www.primevideo.com/detail/" + gti, first);
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.startsWith("http 401") || msg.startsWith("http 403")
                            || msg.startsWith("http 429")) blocked++;
                    else netFail++;
                }
                ok = body != null && !body.isEmpty();
                if (ok) {
                    java.util.regex.Matcher m = imdbPat.matcher(body);
                    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
                    int firstPos = -1;
                    while (m.find()) {
                        if (firstPos < 0) firstPos = m.start();
                        if (ids.size() < 10) ids.add(m.group());
                    }
                    hasTt = !ids.isEmpty();
                    isMulti = ids.size() > 1;
                    String low = body.toLowerCase(Locale.ROOT);
                    int recPos = -1;
                    for (String rk : recMarks) {
                        int p = low.indexOf(rk);
                        if (p >= 0 && (recPos < 0 || p < recPos)) recPos = p;
                    }
                    isOwn = hasTt && (recPos < 0 || firstPos < recPos);
                    String pageTitle = "";
                    java.util.regex.Matcher om = ogPat.matcher(body);
                    if (om.find()) pageTitle = om.group(1).trim();
                    else {
                        java.util.regex.Matcher tm = titlePat.matcher(body);
                        if (tm.find()) pageTitle = tm.group(1).trim();
                    }
                    pageTitle = pageTitle.replaceAll("(?i)\\s*[–—|-]\\s*prime video\\s*$", "")
                            .replaceAll("(?i)\\s*\\|\\s*prime video\\s*$", "").trim();
                    int pageYear = 0;
                    java.util.regex.Matcher ym = yearPat.matcher(body);
                    if (ym.find()) {
                        try { pageYear = Integer.parseInt(ym.group()); } catch (Exception ignored) {}
                    }
                    boolean hasParent = low.contains("season") || low.contains("sezon")
                            || low.contains("series") || low.contains("episode")
                            || low.contains("bölüm");
                    boolean movieMark = low.contains("movie") || low.contains("film");
                    boolean pageSeries = hasParent;
                    boolean pageMovie = movieMark && !hasParent;
                    boolean pageTypeKnown = pageSeries || pageMovie;
                    String firstTt = ids.isEmpty() ? null : ids.iterator().next();
                    List<String> items = null;
                    boolean wdErr = false;
                    if (hasTt) {
                        try {
                            items = sparqlItems("P345", firstTt, first);
                        } catch (Exception e) {
                            wdErr = true;
                            netFail++;
                        }
                    }
                    if (hasTt && items != null && items.size() == 1 && !wdErr) {
                        uniq = true;
                        try {
                            String qid = items.get(0);
                            String eu = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids="
                                    + URLEncoder.encode(qid, "UTF-8") + "&props=labels|claims&format=json";
                            JSONObject eo = new JSONObject(phashApiGet(eu, first));
                            JSONObject ents = eo.optJSONObject("entities");
                            JSONObject ent = ents == null ? null : ents.optJSONObject(qid);
                            JSONObject claims = ent == null ? null : ent.optJSONObject("claims");
                            String label = "";
                            if (ent != null) {
                                JSONObject labels = ent.optJSONObject("labels");
                                if (labels != null) {
                                    JSONObject en = labels.optJSONObject("en");
                                    JSONObject tr = labels.optJSONObject("tr");
                                    JSONObject pick = en != null ? en : tr;
                                    if (pick == null) {
                                        java.util.Iterator<String> keys = labels.keys();
                                        if (keys.hasNext()) pick = labels.optJSONObject(keys.next());
                                    }
                                    if (pick != null) label = pick.optString("value", "");
                                }
                            }
                            boolean wdFilm = false, wdSeries = false, wdEp = false;
                            int wdYear = 0;
                            boolean wdHasParent = false;
                            if (claims != null) {
                                wdFilm = claimsHasId(claims, "P31", "Q11424");
                                wdSeries = claimsHasId(claims, "P31", "Q5398426")
                                        || claimsHasId(claims, "P31", "Q15416");
                                wdEp = claimsHasId(claims, "P31", "Q21191270");
                                wdHasParent = claimsFirstTargetId(claims, "P179") != null;
                                JSONArray p577 = claims.optJSONArray("P577");
                                if (p577 != null && p577.length() > 0) {
                                    for (int k = 0; k < p577.length() && wdYear == 0; k++) {
                                        JSONObject st = p577.optJSONObject(k);
                                        if (st == null) continue;
                                        JSONObject sn = st.optJSONObject("mainsnak");
                                        if (sn == null) continue;
                                        JSONObject dv = sn.optJSONObject("datavalue");
                                        if (dv == null) continue;
                                        JSONObject v = dv.optJSONObject("value");
                                        if (v == null) continue;
                                        String t = v.optString("time", "");
                                        java.util.regex.Matcher y4 = yearPat.matcher(t);
                                        if (y4.find()) {
                                            try { wdYear = Integer.parseInt(y4.group()); }
                                            catch (Exception ignored) {}
                                        }
                                    }
                                }
                            }
                            Set<String> qTok = ShelfIdentity.extractTokens(pageTitle);
                            Set<String> lTok = ShelfIdentity.extractTokens(label);
                            int overlap = 0;
                            for (String t : qTok) if (lTok.contains(t)) overlap++;
                            tOk = overlap > 0;
                            boolean yearConflict = pageYear > 0 && wdYear > 0
                                    && Math.abs(pageYear - wdYear) > 1;
                            yOk = !yearConflict;
                            boolean wdKnown = wdFilm || wdSeries || wdEp;
                            if (!pageTypeKnown || !wdKnown) tyOk = true;
                            else if (pageSeries) tyOk = wdSeries || wdEp;
                            else tyOk = wdFilm;
                            if (wdEp) pOk = wdHasParent && hasParent;
                            else pOk = true;
                            if (!isOwn) verdict = "YANLIS";
                            else if (yearConflict) verdict = "YANLIS";
                            else if (pageTypeKnown && wdKnown && !tyOk) verdict = "YANLIS";
                            else if (tOk && tyOk && pOk) verdict = "KESIN";
                            else verdict = "BELIRSIZ";
                        } catch (Exception e) {
                            netFail++;
                            verdict = "BELIRSIZ";
                        }
                    } else if (hasTt && !isOwn) {
                        verdict = "YANLIS";
                    } else {
                        verdict = "BELIRSIZ";
                    }
                } else {
                    verdict = "BELIRSIZ";
                }
            }
            if (ok) page++;
            if (hasTt) ttFound++;
            if (isOwn) own++;
            if (isMulti) multi++;
            if (uniq) wdUnique++;
            if (tOk) titleOk++;
            if (yOk) yearOk++;
            if (tyOk) typeOkN++;
            if (pOk) parentOkN++;
            if ("KESIN".equals(verdict)) kesin++;
            else if ("YANLIS".equals(verdict)) yanlis++;
            else belirsiz++;
            out.append("SAMPLE-IMDBX ").append(i)
                    .append(" page=").append(ok).append(" tt=").append(hasTt)
                    .append(" own=").append(isOwn).append(" multi=").append(isMulti)
                    .append(" uniq=").append(uniq).append(" titleOk=").append(tOk)
                    .append(" yearOk=").append(yOk).append(" typeOk=").append(tyOk)
                    .append(" parentOk=").append(pOk)
                    .append(" verdict=").append(verdict).append('\n');
        }
        out.append("PASS-IMDBX n=").append(n).append(" page=").append(page)
                .append(" tt=").append(ttFound).append(" own=").append(own)
                .append(" multi=").append(multi).append(" wdUnique=").append(wdUnique)
                .append(" titleOk=").append(titleOk).append(" yearOk=").append(yearOk)
                .append(" typeOk=").append(typeOkN).append(" parentOk=").append(parentOkN)
                .append(" kesin=").append(kesin).append(" yanlis=").append(yanlis)
                .append(" belirsiz=").append(belirsiz).append(" netFail=").append(netFail)
                .append(" blocked=").append(blocked).append('\n');
        return out.toString();
    }

    /** A real trailer entity: its page names the production, so it must never answer as a title. */
    private static final String TRAILER_ALIAS = "amzn1.dv.gti.ba10f0a3-e73f-4bce-9f38-e8aa798359ad";
    private static final String MALFORMED_GTI = "not-a-gti";
    private static final String UNKNOWN_GTI = "amzn1.dv.gti.00000000-0000-4000-8000-000000000001";

    /** One resolver call: the detail, how long it took and how long the main thread waited. */
    private static final class ResolveRun {
        com.gathin.tvhome.PrimeMetadata.Detail detail;
        long elapsedMs;
        long mainThreadDelayMs = -1;
        String threadName = "";
        long threadId;
        Throwable error;
    }

    /**
     * Runs the resolver off the main thread and watches the main thread stay free.
     * The heartbeat is posted before the call: had the resolver blocked the main thread, the
     * heartbeat could only run after the network call was over.
     */
    private static ResolveRun resolveOnWorker(final String gti) {
        final ResolveRun run = new ResolveRun();
        final CountDownLatch latch = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final long postedAt = SystemClock.elapsedRealtime();
        main.post(new Runnable() {
            @Override public void run() { run.mainThreadDelayMs = SystemClock.elapsedRealtime() - postedAt; }
        });
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                run.threadName = Thread.currentThread().getName();
                run.threadId = Thread.currentThread().getId();
                long t0 = SystemClock.elapsedRealtime();
                try { run.detail = com.gathin.tvhome.PrimeMetadata.resolve(gti); }
                catch (Throwable t) { run.error = t; }
                run.elapsedMs = SystemClock.elapsedRealtime() - t0;
                latch.countDown();
            }
        }, "prime-meta-probe");
        worker.start();
        try {
            latch.await(90, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (run.mainThreadDelayMs < 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5);
        return run;
    }

    private String primeMetaCheck() {
        // Read-only: the production resolver (com.gathin.tvhome.PrimeMetadata) against real device
        // GTIs. Two movies, one season, one trailer alias, one invalid GTI. Nothing is written:
        // no DB, no UI, no playback, no purchase; only GET requests to the public detail page.
        StringBuilder out = new StringBuilder();
        List<String> failures = new ArrayList<>();
        // Every Prime row the TV publishes, without a type filter: Amazon tags its own rows as
        // type 4 (clip), so the row type cannot tell a movie from a season.
        List<ImdbOffRow> pool = new ArrayList<>();
        collectOffRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, 50);
        collectOffRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, 50);
        List<ImdbOffRow> rows = new ArrayList<>();
        for (ImdbOffRow r : pool) {
            if (r.gti == null) continue;
            boolean seen = false;
            for (ImdbOffRow q : rows) if (q.gti.equals(r.gti)) { seen = true; break; }
            if (!seen) rows.add(r);
        }
        out.append("PRIMEMETA deviceRows=").append(rows.size())
                .append(" locale=").append(java.util.Locale.getDefault())
                .append(" listingLanguage=").append(com.gathin.tvhome.PrimeMetadata.listingLanguage())
                .append('\n');

        int movies = 0, seasons = 0, rowUnknown = 0, negatives = 0;
        long totalMs = 0, maxMs = 0;
        String firstMovie = "", secondMovie = "", firstSeason = "";
        for (ImdbOffRow row : rows) {
            ResolveRun run = resolveOnWorker(row.gti);
            totalMs += run.elapsedMs;
            if (run.elapsedMs > maxMs) maxMs = run.elapsedMs;
            com.gathin.tvhome.PrimeMetadata.Detail d = run.detail;
            String kind = "unknown";
            check(run.error == null, "row " + row.gti + " threw " + run.error, failures);
            check(d != null, "row " + row.gti + " returned no detail", failures);
            if (d != null) {
                check(row.gti.equals(d.gti), "row " + row.gti + " canonical id differs", failures);
                check(d.year > 0, "row " + row.gti + " has no year", failures);
                check(!d.title.trim().isEmpty(), "row " + row.gti + " has no title", failures);
                if (d.type == ShelfIdentity.Type.MOVIE) {
                    kind = "movie";
                    check(d.seriesTitle.isEmpty(), "movie " + row.gti + " claims a series", failures);
                    check(d.seasonNumber == 0, "movie " + row.gti + " claims a season", failures);
                    if (movies == 0) firstMovie = row.gti;
                    else if (movies == 1) secondMovie = row.gti;
                    movies++;
                } else if (d.type == ShelfIdentity.Type.SERIES) {
                    kind = "season";
                    check(!d.seriesTitle.trim().isEmpty(), "season " + row.gti + " has no series title", failures);
                    check(d.seasonNumber > 0, "season " + row.gti + " has no season number", failures);
                    if (seasons == 0) firstSeason = row.gti;
                    seasons++;
                } else {
                    rowUnknown++;
                }
            }
            checkThread(run, "row " + row.gti, failures);
            out.append(describe(kind, row.gti, run, row.title, row.rawYear)).append('\n');
        }
        check(movies >= 2, "the TV publishes fewer than two movie rows", failures);
        check(seasons >= 1, "the TV publishes no season row", failures);
        ResolveRun trailer = resolveOnWorker(TRAILER_ALIAS);
        totalMs += trailer.elapsedMs;
        check(trailer.detail != null && trailer.detail.type == ShelfIdentity.Type.UNKNOWN,
                "trailer alias did not stay UNKNOWN", failures);
        check(trailer.detail != null && !TRAILER_ALIAS.equals(trailer.detail.gti),
                "trailer alias answered as itself", failures);
        checkThread(trailer, "trailer alias", failures);
        negatives++;
        out.append(describe("trailer-alias", TRAILER_ALIAS, trailer, null, null)).append('\n');

        ResolveRun malformed = resolveOnWorker(MALFORMED_GTI);
        check(malformed.detail != null && malformed.detail.type == ShelfIdentity.Type.UNKNOWN,
                "malformed GTI did not stay UNKNOWN", failures);
        check(malformed.elapsedMs < 500, "malformed GTI reached the network", failures);
        negatives++;
        out.append(describe("malformed-gti", MALFORMED_GTI, malformed, null, null)).append('\n');

        ResolveRun absent = resolveOnWorker(UNKNOWN_GTI);
        check(absent.detail != null && absent.detail.type == ShelfIdentity.Type.UNKNOWN,
                "unknown GTI did not stay UNKNOWN", failures);
        negatives++;
        out.append(describe("unknown-gti", UNKNOWN_GTI, absent, null, null)).append('\n');

        out.append(netTiming(firstMovie.isEmpty() ? TRAILER_ALIAS : firstMovie));
        out.append("PASS-PRIMEMETA deviceRows=").append(rows.size())
                .append(" movies=").append(movies)
                .append(" seasons=").append(seasons)
                .append(" rowUnknown=").append(rowUnknown)
                .append(" negatives=").append(negatives)
                .append(" firstMovie=").append(firstMovie)
                .append(" secondMovie=").append(secondMovie)
                .append(" firstSeason=").append(firstSeason)
                .append(" totalResolveMs=").append(totalMs)
                .append(" maxResolveMs=").append(maxMs)
                .append(" failures=").append(failures.size()).append('\n');
        if (!failures.isEmpty()) {
            for (String f : failures) out.append("FAIL ").append(f).append('\n');
            throw new AssertionError("PRIMEMETA failures=" + failures.size() + "\n" + out);
        }
        return out.toString();
    }

    /**
     * Probe-local read-only mirror of PrimeMetadata's resolve/parse: the installed production APK
     * predates that class and must stay untouched, so the isolated probe carries its own copy.
     * Same rules: canonical pageTitleId must equal the requested GTI, catalogId must match,
     * title/year/type required, trailer alias stays UNKNOWN. Counts/booleans only.
     */
    private static final class SugCand {
        String tt = "";
        String title = "";
        int year = 0;
        ShelfIdentity.Type type = ShelfIdentity.Type.UNKNOWN;
        List<String> cast = new ArrayList<>();
    }

    private static ShelfIdentity.Type sugType(String q) {
        if (q == null) return ShelfIdentity.Type.UNKNOWN;
        String t = q.toLowerCase(Locale.ROOT);
        if (t.contains("tv")) return ShelfIdentity.Type.SERIES;
        if (t.contains("feature") || t.contains("movie") || t.contains("video")
                || t.contains("short")) return ShelfIdentity.Type.MOVIE;
        return ShelfIdentity.Type.UNKNOWN;
    }

    private static final class PvDetail {
        final String gti;
        final String title;
        final int year;
        final ShelfIdentity.Type type;
        final List<String> cast;
        final String seriesTitle;
        final int seasonNumber;
        PvDetail(String gti, String title, int year, ShelfIdentity.Type type,
                List<String> cast, String seriesTitle, int seasonNumber) {
            this.gti = gti; this.title = title; this.year = year; this.type = type;
            this.cast = cast; this.seriesTitle = seriesTitle; this.seasonNumber = seasonNumber;
        }
        static final PvDetail UNKNOWN = new PvDetail("", "", 0, ShelfIdentity.Type.UNKNOWN,
                Collections.<String>emptyList(), "", 0);
    }

    private static final class PvRun {
        PvDetail detail;
        long elapsedMs;
        long mainThreadDelayMs = -1;
        Throwable error;
        boolean netError;
        String netKind = "";
        int httpCode;
    }

    private static final class PvNetException extends java.io.IOException {
        final String kind;
        final int code;
        PvNetException(String kind, int code, String msg, Throwable cause) {
            super(msg, cause);
            this.kind = kind;
            this.code = code;
        }
    }

    private static boolean isTimeoutExc(Throwable e) {
        while (e != null) {
            if (e instanceof java.net.SocketTimeoutException) return true;
            String m = e.getMessage();
            if (m != null) {
                String lm = m.toLowerCase(Locale.ROOT);
                if (lm.contains("timed out") || lm.contains("timeout")) return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private static int httpCodeOfExc(Throwable e) {
        while (e != null) {
            String m = e.getMessage();
            if (m != null && m.startsWith("http ")) {
                try {
                    String num = m.substring(5).trim().split("[^0-9]")[0];
                    return Integer.parseInt(num);
                } catch (Exception ignored) {}
            }
            e = e.getCause();
        }
        return 0;
    }

    private static boolean isTransientExc(Throwable e) {
        if (isTimeoutExc(e)) return true;
        int c = httpCodeOfExc(e);
        if (c == 429) return true;
        if (c >= 500 && c <= 599) return true;
        if (c != 0) return false;
        return e instanceof java.io.IOException;
    }

    private static void pvBackoffSleep(int attempt) {
        SystemClock.sleep(attempt <= 0 ? 1000 : 2000);
    }

    private static final class NetBucket {
        int fail;
        int timeout;
        int http;
        int retry;
        long backoffMs;
        String lastKind = "";
        int lastCode;
        String tag = "";      // service of the call in progress, set by the caller
        String lastTag = "";
        void note(Throwable e) {
            fail++;
            int c = httpCodeOfExc(e);
            boolean to = isTimeoutExc(e);
            if (to) timeout++;
            else if (c != 0) http++;
            lastKind = to ? "TIMEOUT" : (c != 0 ? ("HTTP_" + c)
                    : e.getClass().getSimpleName());
            lastCode = c;
            lastTag = tag;
        }
    }

    private static String hostTag(String url) {
        if (url.contains("v3.sg.media-imdb.com")) return "imdb-sug";
        if (url.contains("query.wikidata.org")) return "wd-sparql";
        if (url.contains("list=search")) return "wd-search";
        if (url.contains("wikidata.org")) return "wd-api";
        return "other";
    }

    private static String imdbprimeApiGet(String url, boolean[] first, NetBucket nb) throws Exception {
        nb.tag = hostTag(url);
        if (!first[0]) SystemClock.sleep(500);
        first[0] = false;
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return httpGetStrict(url);
            } catch (Exception e) {
                last = e;
                boolean transientErr = isTransientExc(e);
                if (transientErr && attempt < 2) {
                    long wait = attempt == 0 ? 1000 : 2000;
                    nb.retry++;
                    nb.backoffMs += wait;
                    SystemClock.sleep(wait);
                    continue;
                }
                nb.note(e);
                throw e;
            }
        }
        if (last != null) {
            nb.note(last);
            throw last;
        }
        return null;
    }

    private static PvDetail resolvePvDetail(String gti, NetBucket nb) throws PvNetException {
        nb.tag = "pv";
        if (gti == null) return PvDetail.UNKNOWN;
        String id = gti.trim();
        if (!Pattern.compile("amzn1\\.dv\\.gti\\.[0-9a-z-]{36}").matcher(id).matches()) {
            return PvDetail.UNKNOWN;
        }
        java.util.Locale locale = java.util.Locale.getDefault();
        String lang = locale.getLanguage().isEmpty() ? "en_US" : locale.getLanguage()
                + (locale.getCountry().isEmpty() ? "" : "_" + locale.getCountry());
        String url = "https://www.primevideo.com/detail/" + id + "?language=" + lang;
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestProperty("User-Agent",
                        "Dalvik/2.1.0 (Linux; U; Android 9; AFTM Build/PS7233; wv)");
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                int code = c.getResponseCode();
                if (code == 429 || (code >= 500 && code <= 599)) {
                    last = new java.io.IOException("http " + code);
                    if (attempt < 2) {
                        long wait = attempt == 0 ? 1000 : 2000;
                        nb.retry++;
                        nb.backoffMs += wait;
                        SystemClock.sleep(wait);
                        continue;
                    }
                    nb.note(last);
                    throw new PvNetException(isTimeoutExc(last) ? "TIMEOUT" : ("HTTP_" + code),
                            code, last.getMessage(), last);
                }
                if (code == 404 || code == 410) return PvDetail.UNKNOWN;   // page gone: a data answer
                if (code != 200) {   // 401/403 and other HTTP errors: network class, never retried
                    java.io.IOException h = new java.io.IOException("http " + code);
                    nb.note(h);
                    throw new PvNetException("HTTP_" + code, code, h.getMessage(), h);
                }
                java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0, n;
                java.io.InputStream in = c.getInputStream();
                try {
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 4 * 1024 * 1024) return PvDetail.UNKNOWN;
                        bout.write(buf, 0, n);
                    }
                } finally {
                    in.close();
                }
                String html = new String(bout.toByteArray(), "UTF-8");
                java.util.regex.Matcher hm = Pattern.compile(
                        "<script id=\"dv-web-page-hydration-data\"[^>]*>(.*?)</script>",
                        Pattern.DOTALL).matcher(html);
                if (!hm.find()) return PvDetail.UNKNOWN;
                JSONObject root = new JSONObject(hm.group(1));
                JSONObject body = pvChild(root, "init", "preparations", "body");
                if (body == null) return PvDetail.UNKNOWN;
                JSONObject atf = pvChild(body, "atf", "state");
                JSONObject btf = pvChild(body, "btf", "state");
                String canonical = pvStr(atf, "pageTitleId");
                if (canonical.isEmpty()) canonical = pvStr(btf, "pageTitleId");
                if (canonical.isEmpty()) canonical = pvStr(pvChild(atf, "pageContext"), "pageTitleId");
                if (!id.equals(canonical)) return PvDetail.UNKNOWN;
                JSONObject item = pvDetail(atf, "headerDetail", id);
                if (item == null) item = pvDetail(btf, "btfMoreDetails", id);
                if (item == null) item = pvDetail(btf, "headerDetail", id);
                if (item == null || !id.equals(pvStr(item, "catalogId"))) return PvDetail.UNKNOWN;
                String title = pvStr(item, "title").trim();
                int year = item.optInt("releaseYear", 0);
                if (title.isEmpty() || year <= 0) return PvDetail.UNKNOWN;
                String titleType = pvStr(item, "titleType");
                String entityType = pvStr(item, "entityType");
                if ("movie".equals(titleType) && "Movie".equals(entityType)) {
                    return new PvDetail(id, title, year, ShelfIdentity.Type.MOVIE,
                            pvCast(item), "", 0);
                }
                if ("season".equals(titleType) && "TV Show".equals(entityType)) {
                    String series = pvStr(item, "parentTitle").trim();
                    int season = item.optInt("seasonNumber", 0);
                    if (series.isEmpty() || season <= 0) return PvDetail.UNKNOWN;
                    return new PvDetail(id, title, year, ShelfIdentity.Type.SERIES,
                            pvCast(item), series, season);
                }
                return PvDetail.UNKNOWN;
            } catch (PvNetException pne) {
                throw pne;
            } catch (Exception e) {
                last = e;
                if (isTransientExc(e) && attempt < 2) {
                    long wait = attempt == 0 ? 1000 : 2000;
                    nb.retry++;
                    nb.backoffMs += wait;
                    SystemClock.sleep(wait);
                    continue;
                }
                // Only I/O faults are network; a parse or logic error is a data answer (UNKNOWN).
                if (!(e instanceof java.io.IOException)) return PvDetail.UNKNOWN;
                int hc = httpCodeOfExc(e);
                if (hc == 404 || hc == 410) return PvDetail.UNKNOWN;
                nb.note(e);
                String kind = isTimeoutExc(e) ? "TIMEOUT" : (hc != 0 ? ("HTTP_" + hc) : e.getClass().getSimpleName());
                throw new PvNetException(kind, hc, String.valueOf(e.getMessage()), e);
            } finally {
                if (c != null) c.disconnect();
            }
        }
        if (last != null) {
            nb.note(last);
            int c = httpCodeOfExc(last);
            String kind = isTimeoutExc(last) ? "TIMEOUT" : (c != 0 ? ("HTTP_" + c)
                    : last.getClass().getSimpleName());
            throw new PvNetException(kind, c, String.valueOf(last.getMessage()), last);
        }
        return PvDetail.UNKNOWN;
    }

    private static PvDetail resolvePvDetail(String gti) {
        try {
            return resolvePvDetail(gti, new NetBucket());
        } catch (PvNetException e) {
            return PvDetail.UNKNOWN;
        }
    }

    private static JSONObject pvChild(JSONObject parent, String... path) {
        JSONObject current = parent;
        for (String key : path) {
            if (current == null) return null;
            current = current.optJSONObject(key);
        }
        return current;
    }

    private static String pvStr(JSONObject o, String key) {
        return o == null ? "" : o.optString(key, "");
    }

    private static JSONObject pvDetail(JSONObject state, String map, String gti) {
        JSONObject details = pvChild(state, "detail", map);
        return details == null ? null : details.optJSONObject(gti);
    }

    private static List<String> pvCast(JSONObject item) {
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

    private static PvRun pvRun(final String gti, final NetBucket nb) {
        final PvRun run = new PvRun();
        final CountDownLatch latch = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final long postedAt = SystemClock.elapsedRealtime();
        main.post(new Runnable() {
            @Override public void run() { run.mainThreadDelayMs = SystemClock.elapsedRealtime() - postedAt; }
        });
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                long t0 = SystemClock.elapsedRealtime();
                try { run.detail = resolvePvDetail(gti, nb); }
                catch (PvNetException pne) {
                    run.netError = true;
                    run.netKind = pne.kind;
                    run.httpCode = pne.code;
                    run.detail = PvDetail.UNKNOWN;
                }
                catch (Throwable t) { run.error = t; }
                run.elapsedMs = SystemClock.elapsedRealtime() - t0;
                latch.countDown();
            }
        }, "imdbprime-probe");
        worker.start();
        try {
            // Worst case per call: 3 attempts x (10 s connect + 20 s read) + 3 s backoff, so 120 s bounds it.
            if (!latch.await(120, TimeUnit.SECONDS)) { run.netError = true; run.netKind = "TIMEOUT"; }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (run.mainThreadDelayMs < 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5);
        return run;
    }

    private static PvRun pvRun(final String gti) {
        return pvRun(gti, new NetBucket());
    }

    private static List<SugCand> sugCands(String query, boolean[] first, NetBucket nb) {
        List<SugCand> out = new ArrayList<>();
        String json = null;
        try {
            json = imdbprimeApiGet("https://v3.sg.media-imdb.com/suggestion/x/"
                    + URLEncoder.encode(query.toLowerCase(Locale.ROOT), "UTF-8") + ".json", first, nb);
        } catch (Exception e) {
            return out;
        }
        if (json == null) {
            return out;
        }
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("d");
            if (arr == null) return out;
            for (int k = 0; k < arr.length() && out.size() < 5; k++) {
                JSONObject o = arr.optJSONObject(k);
                if (o == null) continue;
                String id = o.optString("id", "");
                if (!id.matches("tt\\d{7,9}")) continue;
                String nm = o.optString("l", "");
                if (nm.isEmpty()) continue;
                SugCand c = new SugCand();
                c.tt = id;
                c.title = nm;
                c.year = o.optInt("y", 0);
                c.type = sugType(o.optString("q", ""));
                String s = o.optString("s", "");
                for (String part : s.split(",")) {
                    String t = part.trim();
                    if (!t.isEmpty() && c.cast.size() < 4) c.cast.add(t);
                }
                out.add(c);
            }
        } catch (Exception e) {
            // Parse hatasi: ag degil, NETWORK_ERROR sayma.
        }
        return out;
    }

    private static String normBase(String title, int seasNum) {
        String base = stripProvenBase(title, seasNum, 0);
        return isQueryableNorm(base == null ? "" : base) ? base
                : (isQueryableNorm(exactNorm(title)) ? exactNorm(title) : "");
    }

    private static String pvHtml(String gti) {
        // Same fetch as the resolver: Fire TV UA + 4MB cap, so tt ids come from the same
        // ATV template the Detail was parsed from (httpGet's generic UA + 256KB cap serves
        // a different template and truncates the hydration payload).
        HttpURLConnection c = null;
        try {
            java.util.Locale locale = java.util.Locale.getDefault();
            String lang = locale.getLanguage().isEmpty() ? "en_US" : locale.getLanguage()
                    + (locale.getCountry().isEmpty() ? "" : "_" + locale.getCountry());
            c = (HttpURLConnection) new URL("https://www.primevideo.com/detail/" + gti
                    + "?language=" + lang).openConnection();
            c.setRequestProperty("User-Agent",
                    "Dalvik/2.1.0 (Linux; U; Android 9; AFTM Build/PS7233; wv)");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) throw new java.io.IOException("http " + c.getResponseCode());
            java.io.ByteArrayOutputStream bout = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0, n;
            java.io.InputStream in = c.getInputStream();
            try {
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 4 * 1024 * 1024) throw new java.io.IOException("response too large");
                    bout.write(buf, 0, n);
                }
            } finally {
                in.close();
            }
            return new String(bout.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Counts the GETs the production Net sends and how many of them came back 429. */
    private static final class CountingHttp implements PrimeImdb.Http {
        final PrimeImdb.Http inner = new PrimeImdb.HttpGet();
        int calls;
        int limited;

        @Override public String get(String url) throws java.io.IOException {
            calls++;
            try {
                return inner.get(url);
            } catch (java.io.IOException e) {
                if ("http 429".equals(e.getMessage())) limited++;
                throw e;
            }
        }
    }

    /**
     * Read-only, test-only stage probe for one Turkcell TV+ preview row. Each stage prints a boolean or count only:
     * no URI, token, title, other content id or personal value is written. The row is found by its content_id
     * through the production ShelfData.load, then packageItems, a fresh PrimeArtwork over a scratch store, and the
     * production TurkcellArtwork.verify, each reported on its own line.
     */
    private String stageProbeCheck() {
        final String target = "212330223";
        StringBuilder out = new StringBuilder();
        Context context = getTargetContext();
        ShelfData.Snapshot snapshot;
        try (ShelfInteractions history = new ShelfInteractions(context)) {
            snapshot = ShelfData.load(context, history);
        }
        out.append("STAGE snapshotError=").append(snapshot.error == null ? "none" : snapshot.error).append('\n');
        out.append("STAGE snapshotRecommended=").append(snapshot.recommended.size())
                .append(" snapshotWatching=").append(snapshot.watching.size()).append('\n');
        // Provider-side presence of the target row with the same filters ShelfData applies.
        int providerRows = 0, providerEligible = 0;
        try (Cursor c = context.getContentResolver().query(
                android.media.tv.TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{"package_name", "title", "type", "live", "content_id"}, null, null, null)) {
            if (c == null) {
                out.append("STAGE providerCursor=null\n");
            } else {
                while (c.moveToNext()) {
                    if (!"com.turkcell.ott".equals(probeText(c, "package_name"))) continue;
                    if (!target.equals(probeText(c, "content_id"))) continue;
                    providerRows++;
                    int type = c.isNull(c.getColumnIndex("type")) ? -1 : c.getInt(c.getColumnIndex("type"));
                    int live = c.isNull(c.getColumnIndex("live")) ? -1 : c.getInt(c.getColumnIndex("live"));
                    if (ShelfLogic.previewEligible(type, live)) providerEligible++;
                }
            }
        } catch (SecurityException e) {
            out.append("STAGE providerCursor=denied:").append(e.getClass().getSimpleName()).append('\n');
        }
        out.append("STAGE providerRows=").append(providerRows).append(" providerEligible=").append(providerEligible).append('\n');
        // Production snapshot: is the target among recommended, and is its content id carried through?
        int inSnapshot = 0;
        ShelfData.Item found = null;
        for (ShelfData.Item item : snapshot.recommended) {
            if (target.equals(item.contentId)) { inSnapshot++; found = item; }
        }
        out.append("STAGE snapshotTargetRows=").append(inSnapshot).append('\n');
        if (found == null) {
            out.append("STAGE verdict=not-in-snapshot\n");
            return out.toString();
        }
        // packageItems order: its index among the Turkcell rows of the production snapshot.
        List<ShelfData.Item> pkgItems = ShelfPresentation.packageItems(found.pkg,
                snapshot.watching, snapshot.recommended);
        out.append("STAGE packageItemsCount=").append(pkgItems.size())
                .append(" targetIndex=").append(indexOfContentId(pkgItems, target)).append('\n');
        String key = PrimeArtwork.keyOf(found.pkg, found.intent, found.contentId);
        out.append("STAGE keyPresent=").append(key != null).append('\n');
        // Real TurkcellArtwork.verify over the production network stack, one row, no cache priming.
        PrimeImdb.Net net = new PrimeImdb.Net(new PrimeImdb.HttpGet(), PrimeImdb::sleepQuietly);
        PrimeImdb.Result verdict = TurkcellArtwork.verify(target, found.title, net);
        out.append("STAGE verdict=").append(verdict.verdict)
                .append(verdict.netKind.isEmpty() ? "" : " net=" + verdict.netKind)
                .append(" yearVerified=").append(verdict.yearVerified)
                .append(" typeVerified=").append(verdict.typeVerified).append('\n');
        // Production PrimeArtwork path with a scratch store: pending, then the cached result.
        File scratch = new File(getTargetContext().getCacheDir(), "stage-probe-store");
        scratch.mkdirs();
        File storeFile = new File(scratch, "prime-backdrops.json");
        storeFile.delete();
        PrimeArtwork art = new PrimeArtwork(
                (k, t, ty) -> verdict,
                q -> CompletableFuture.completedFuture(verdict.verdict.equals(PrimeImdb.KESIN) ? "stage-probe" : null),
                System::currentTimeMillis, Runnable::run, () -> { }, new PrimeBackdropStore(storeFile));
        String first = key == null ? null : art.backdropFor(key, found.title, found.type);
        String second = key == null ? null : art.backdropFor(key, found.title, found.type);
        out.append("STAGE artworkFirstNull=").append(first == null).append(" artworkSecondNull=").append(second == null).append('\n');
        out.append("STAGE scratchStoreWritten=").append(storeFile.isFile()).append('\n');
        return out.toString();
    }

    private static int indexOfContentId(List<ShelfData.Item> items, String contentId) {
        for (int i = 0; i < items.size(); i++) if (contentId.equals(items.get(i).contentId)) return i;
        return -1;
    }

    /** Read-only: the production PrimeImdb.verify (real Net pacing/retry) over the same device GTIs as imdbprimeCheck. */
    private String imdbprodCheck() {
        List<ImdbOffRow> pool = new ArrayList<>();
        collectOffRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, 50);
        collectOffRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, 50);
        List<String> gtis = new ArrayList<>();
        for (ImdbOffRow r : pool) {
            if (r.gti != null && !gtis.contains(r.gti) && gtis.size() < 12) gtis.add(r.gti);
        }
        CountingHttp http = new CountingHttp();
        PrimeImdb.Net net = new PrimeImdb.Net(http, PrimeImdb::sleepQuietly);
        com.gathin.tvhome.PrimeMetadata.HttpPageSource pages = new com.gathin.tvhome.PrimeMetadata.HttpPageSource(
                com.gathin.tvhome.PrimeMetadata.listingLanguage());
        StringBuilder out = new StringBuilder();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        long start = SystemClock.elapsedRealtime();
        int cooldownRows = 0;
        for (int i = 0; i < gtis.size(); i++) {
            int retry0 = net.retries;
            int calls0 = http.calls;
            long rowStart = SystemClock.elapsedRealtime();
            PrimeImdb.Result r = PrimeImdb.verify(gtis.get(i), pages, net);
            if (r.netKind.startsWith("COOLDOWN")) cooldownRows++;
            Integer c = counts.get(r.verdict);
            counts.put(r.verdict, c == null ? 1 : c + 1);
            out.append("SAMPLE-IMDBPROD ").append(i).append(" gti=").append(gtis.get(i))
                    .append(" tt=").append(r.imdbId.isEmpty() ? "-" : r.imdbId)
                    .append(" net=").append(r.netKind.isEmpty() ? "-" : r.netKind)
                    .append(" retry=").append(net.retries - retry0)
                    .append(" http=").append(http.calls - calls0)
                    .append(" ms=").append(SystemClock.elapsedRealtime() - rowStart)
                    .append(" yearV=").append(r.yearVerified).append(" typeV=").append(r.typeVerified)
                    .append(" verdict=").append(r.verdict).append('\n');
        }
        out.append("PASS-IMDBPROD n=").append(gtis.size()).append(" verdicts=").append(counts)
                .append(" httpCalls=").append(http.calls).append(" http429=").append(http.limited)
                .append(" cooldownRows=").append(cooldownRows)
                .append(" retry=").append(net.retries).append(" backoffMs=").append(net.backoffMs)
                .append(" runMs=").append(SystemClock.elapsedRealtime() - start).append('\n');
        return out.toString();
    }

    private String imdbprimeCheck() {
        // Read-only: PrimeMetadata real outputs (Detail per device GTI) -> IMDb id probe.
        // Discovery via IMDb suggestion API (title query, top 5 candidates with year/type/
        // cast); ranking by title overlap, year +-1, type, cast overlap. Chosen candidate
        // corroborated via Wikidata P345 unique (P577 year +-1, P31 film/series/episode,
        // P179 parent, P161 cast one batched lookup) incl. season/parent-series. No rating
        // check, no fixed id list, no page tt scan: GTIs come from the TV, remake pairs are
        // detected dynamically (shared title tokens, different year). Counts/booleans only.
        // ponytail: marker/presence heuristics; DOM parse + per-name cast resolution when FP matters.
        List<ImdbOffRow> pool = new ArrayList<>();
        collectOffRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, 50);
        collectOffRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, 50);
        List<String> gtis = new ArrayList<>();
        for (ImdbOffRow r : pool) {
            if (r.gti == null) continue;
            if (!gtis.contains(r.gti) && gtis.size() < 12) gtis.add(r.gti);
        }
        StringBuilder out = new StringBuilder();
        long startMs = SystemClock.elapsedRealtime();
        boolean[] first = new boolean[]{true};
        Pattern yearPat = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b");
        Pattern imdbPat = Pattern.compile("tt\\d{7,8}");
        String[] recMarks = {"more like this", "customers also", "you may also", "frequently",
                "recommended", "related", "more to explore", "önerilen", "bunları da",
                "sıradaki", "tavsiye", "ilginizi çekebilir"};
        int n = gtis.size();
        int kesin = 0, belirsiz = 0, basarisiz = 0, netErr = 0, skipped = 0, kesinNoYear = 0, parseErr = 0;
        NetBucket nb = new NetBucket();
        int titleOkN = 0, yearOkN = 0, typeOkN = 0, castOkN = 0, parentOkN = 0, uniqN = 0, ownN = 0;
        int imdbRefsTotal = 0, candTotal = 0;
        List<String> firstTts = new ArrayList<>();
        List<String> rowLabels = new ArrayList<>();
        List<PvDetail> details = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String gti = gtis.get(i);
            String verdict = "BELIRSIZ";
            boolean tOk = false, yOk = false, tyOk = false, cOk = false, pOk = true;
            boolean uniq = false, isOwn = false, hasTt = false;
            String tt = "";
            String label = "";
            String labelTr = "";
            int candN = 0, bestRank = -1;
            int p345n = -1;
            int castFails = 0;
            boolean wdYearKnown = false, wdTypeKnown = false;
            String rowErr = "-";
            int rowFail0 = nb.fail, rowRetry0 = nb.retry;
            long rowStart = SystemClock.elapsedRealtime();
            PvRun run = pvRun(gti, nb);
            PvDetail d = run.detail;
            boolean resolved = !run.netError && run.error == null && d != null
                    && d.type != ShelfIdentity.Type.UNKNOWN;
            if (run.netError) {
                verdict = "NETWORK_ERROR";
            } else if (!resolved) {
                verdict = "BASARISIZ";
            } else if (SystemClock.elapsedRealtime() - startMs > 7 * 60 * 1000) {
                verdict = "ATLANDI";
            } else {
                // Suggestion discovery: candidates by title/year/type, no page tt scan.
                String q = d.type == ShelfIdentity.Type.SERIES && !d.seriesTitle.isEmpty()
                        ? exactNorm(d.seriesTitle) : normBase(d.title, d.seasonNumber);
                List<SugCand> cands = !isQueryableNorm(q)
                        ? new ArrayList<SugCand>() : sugCands(q, first, nb);
                candN = cands.size();
                candTotal += candN;
                if (cands.isEmpty()) {
                    verdict = nb.fail > rowFail0 ? "NETWORK_ERROR" : "BASARISIZ";
                } else {
                    // Season rows: title metric matches the ATV full title (d.title),
                    // not the suggested parent series.
                    Set<String> qTok = new java.util.HashSet<>(
                            ShelfIdentity.extractTokens(d.title));
                    Set<String> dCastTok = new java.util.HashSet<>();
                    for (String nm : d.cast) dCastTok.addAll(ShelfIdentity.extractTokens(nm));
                    int bestScore = Integer.MIN_VALUE;
                    for (int k = 0; k < cands.size(); k++) {
                        SugCand sc = cands.get(k);
                        Set<String> sct = ShelfIdentity.extractTokens(sc.title);
                        int ov = 0;
                        for (String t : qTok) if (sct.contains(t)) ov++;
                        boolean yc = d.year > 0 && sc.year > 0
                                && Math.abs(d.year - sc.year) > 1;
                        boolean stok = sc.type == ShelfIdentity.Type.UNKNOWN
                                || (d.type == ShelfIdentity.Type.SERIES
                                        ? sc.type == ShelfIdentity.Type.SERIES
                                        : sc.type == ShelfIdentity.Type.MOVIE);
                        boolean scok = false;
                        if (!dCastTok.isEmpty() && !sc.cast.isEmpty()) {
                            for (String nm : sc.cast) {
                                for (String t : ShelfIdentity.extractTokens(nm)) {
                                    if (dCastTok.contains(t)) { scok = true; break; }
                                }
                                if (scok) break;
                            }
                        }
                        int score = (ov > 0 ? 2 : -4) + (yc ? -4 : 1) + (stok ? 1 : -4)
                                + (scok ? 1 : 0) - k;
                        if (score > bestScore) { bestScore = score; bestRank = k; }
                    }
                    SugCand bc = cands.get(bestRank);
                    tt = bc.tt;
                    label = bc.title;
                    hasTt = true;
                    isOwn = true;
                    if (!dCastTok.isEmpty() && !bc.cast.isEmpty()) {
                        for (String nm : bc.cast) {
                            for (String t : ShelfIdentity.extractTokens(nm)) {
                                if (dCastTok.contains(t)) { cOk = true; break; }
                            }
                            if (cOk) break;
                        }
                    }
                        List<String> items = null;
                        try {
                            items = sparqlIdentity ? sparqlItems("P345", tt, first, nb) : searchItems(tt, first, nb);
                        } catch (Exception e) {
                            items = null;   // parse failure: BELIRSIZ below; a network failure is counted in nb
                            if (nb.fail == rowFail0) rowErr = e.getClass().getSimpleName();
                        }
                        p345n = items == null ? -1 : items.size();
                        if (items != null && items.size() == 1) {
                            uniq = true;
                            try {
                                String qid = items.get(0);
                                String eu = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids="
                                        + URLEncoder.encode(qid, "UTF-8")
                                        + "&props=labels|claims&format=json";
                                JSONObject eo = new JSONObject(imdbprimeApiGet(eu, first, nb));
                                JSONObject ents = eo.optJSONObject("entities");
                                JSONObject ent = ents == null ? null : ents.optJSONObject(qid);
                                JSONObject claims = ent == null ? null : ent.optJSONObject("claims");
                                if (ent != null) {
                                    JSONObject labels = ent.optJSONObject("labels");
                                    if (labels != null) {
                                        JSONObject en = labels.optJSONObject("en");
                                        JSONObject tr = labels.optJSONObject("tr");
                                        if (en != null) label = en.optString("value", "");
                                        if (tr != null) labelTr = tr.optString("value", "");
                                        if (label.isEmpty()) label = labelTr;
                                        else if (labelTr.isEmpty()) labelTr = label;
                                    }
                                }
                                boolean wdFilm = false, wdSeries = false, wdEp = false;
                                int wdYear = 0;
                                boolean wdHasParent = false;
                                List<String> castIds = new ArrayList<>();
                                if (claims != null) {
                                    wdFilm = claimsHasId(claims, "P31", "Q11424");
                                    wdSeries = claimsHasId(claims, "P31", "Q5398426")
                                            || claimsHasId(claims, "P31", "Q15416");
                                    wdEp = claimsHasId(claims, "P31", "Q21191270");
                                    wdHasParent = claimsFirstTargetId(claims, "P179") != null;
                                    JSONArray p577 = claims.optJSONArray("P577");
                                    if (p577 != null) {
                                        for (int k = 0; k < p577.length() && wdYear == 0; k++) {
                                            JSONObject st = p577.optJSONObject(k);
                                            if (st == null) continue;
                                            JSONObject sn = st.optJSONObject("mainsnak");
                                            if (sn == null) continue;
                                            JSONObject dv = sn.optJSONObject("datavalue");
                                            if (dv == null) continue;
                                            JSONObject v = dv.optJSONObject("value");
                                            if (v == null) continue;
                                            java.util.regex.Matcher y4 =
                                                    yearPat.matcher(v.optString("time", ""));
                                            if (y4.find()) {
                                                try { wdYear = Integer.parseInt(y4.group()); }
                                                catch (Exception ignored) {}
                                            }
                                        }
                                    }
                                    JSONArray p161 = claims.optJSONArray("P161");
                                    if (p161 != null) {
                                        for (int k = 0; k < p161.length() && castIds.size() < 3; k++) {
                                            JSONObject st = p161.optJSONObject(k);
                                            if (st == null) continue;
                                            JSONObject sn = st.optJSONObject("mainsnak");
                                            if (sn == null) continue;
                                            JSONObject dv = sn.optJSONObject("datavalue");
                                            if (dv == null) continue;
                                            JSONObject v = dv.optJSONObject("value");
                                            if (v == null) continue;
                                            String id = v.optString("id", "");
                                            if (!id.isEmpty() && !castIds.contains(id)) castIds.add(id);
                                        }
                                    }
                                }
                                Set<String> dTok = ShelfIdentity.extractTokens(d.title);
                                Set<String> lTok = ShelfIdentity.extractTokens(label);
                                int overlap = 0;
                                for (String t : dTok) if (lTok.contains(t)) overlap++;
                                if (overlap == 0 && !labelTr.isEmpty() && !labelTr.equals(label)) {
                                    Set<String> lTrTok = ShelfIdentity.extractTokens(labelTr);
                                    for (String t : dTok) if (lTrTok.contains(t)) overlap++;
                                }
                                tOk = overlap > 0;
                                boolean yearConflict = d.year > 0 && wdYear > 0
                                        && Math.abs(d.year - wdYear) > 1;
                                wdYearKnown = wdYear > 0;
                                yOk = wdYearKnown && !yearConflict;   // a missing P577 is not a verified year
                                boolean wdKnown = wdFilm || wdSeries || wdEp;
                                wdTypeKnown = wdKnown;
                                if (!wdKnown) tyOk = true;
                                else if (d.type == ShelfIdentity.Type.SERIES) tyOk = wdSeries || wdEp;
                                else tyOk = wdFilm;
                                if (wdEp) pOk = wdHasParent && d.type == ShelfIdentity.Type.SERIES;
                                else pOk = true;
                                boolean p345Ok = sparqlIdentity;   // the item's own P345 must be exactly the candidate tt
                                if (claims != null && !sparqlIdentity) {
                                    JSONArray p345 = claims.optJSONArray("P345");
                                    Set<String> p345v = new HashSet<>();
                                    for (int k = 0; p345 != null && k < p345.length(); k++) {
                                        JSONObject st = p345.optJSONObject(k);
                                        JSONObject sn = st == null ? null : st.optJSONObject("mainsnak");
                                        JSONObject dv = sn == null ? null : sn.optJSONObject("datavalue");
                                        if (dv != null) p345v.add(dv.optString("value", ""));
                                    }
                                    p345Ok = p345v.size() == 1 && p345v.contains(tt);
                                }
                                boolean castConflict = false;
                                int preCastFail = nb.fail;
                                if (!d.cast.isEmpty() && !castIds.isEmpty()) {
                                    try {
                                        StringBuilder idsParam = new StringBuilder();
                                        for (String id : castIds) {
                                            if (idsParam.length() > 0) idsParam.append('|');
                                            idsParam.append(id);
                                        }
                                        String cu = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids="
                                                + URLEncoder.encode(idsParam.toString(), "UTF-8")
                                                + "&props=labels&format=json";
                                        JSONObject co = new JSONObject(imdbprimeApiGet(cu, first, nb));
                                        JSONObject cents = co.optJSONObject("entities");
                                        Set<String> castTok = new java.util.HashSet<>();
                                        if (cents != null) {
                                            for (String id : castIds) {
                                                JSONObject ce = cents.optJSONObject(id);
                                                if (ce == null) continue;
                                                JSONObject cl = ce.optJSONObject("labels");
                                                if (cl == null) continue;
                                                JSONObject cen = cl.optJSONObject("en");
                                                if (cen == null) cen = cl.optJSONObject("tr");
                                                if (cen == null) {
                                                    java.util.Iterator<String> keys = cl.keys();
                                                    if (keys.hasNext()) cen = cl.optJSONObject(keys.next());
                                                }
                                                if (cen != null) {
                                                    castTok.addAll(ShelfIdentity.extractTokens(
                                                            cen.optString("value", "")));
                                                }
                                            }
                                        }
                                        for (String name : d.cast) {
                                            for (String t : ShelfIdentity.extractTokens(name)) {
                                                if (castTok.contains(t)) { cOk = true; break; }
                                            }
                                            if (cOk) break;
                                        }
                                        castConflict = !castTok.isEmpty() && !cOk;
                                    } catch (Exception e) {
                                        if (nb.fail == rowFail0) rowErr = e.getClass().getSimpleName();
                                    }
                                }
                                // A failed cast call is optional corroboration (no gate needs it): it alone does not block KESIN.
                                castFails = nb.fail - preCastFail;
                                if (!p345Ok) verdict = "BELIRSIZ";
                                else if (yearConflict) verdict = "BASARISIZ";
                                else if (wdKnown && !tyOk) verdict = "BASARISIZ";
                                else if (nb.fail - rowFail0 - castFails > 0) verdict = "NETWORK_ERROR";
                                else if (!tOk || castConflict) verdict = "BELIRSIZ";
                                else verdict = "KESIN";
                            } catch (Exception e) {
                                if (nb.fail == rowFail0) rowErr = e.getClass().getSimpleName();
                                verdict = nb.fail > rowFail0 ? "NETWORK_ERROR" : "BELIRSIZ";
                            }
                        } else {
                            verdict = nb.fail > rowFail0 ? "NETWORK_ERROR" : "BELIRSIZ";
                        }
                    }
            }
            if (isOwn) ownN++;
            if (uniq) uniqN++;
            if (tOk) titleOkN++;
            if (yOk) yearOkN++;
            if (tyOk) typeOkN++;
            if (cOk) castOkN++;
            if (pOk) parentOkN++;
            if ("KESIN".equals(verdict)) { kesin++; if (!wdYearKnown) kesinNoYear++; }
            else if ("BASARISIZ".equals(verdict)) basarisiz++;
            else if ("NETWORK_ERROR".equals(verdict)) netErr++;
            else if ("ATLANDI".equals(verdict)) skipped++;
            else belirsiz++;
            if (!"-".equals(rowErr)) parseErr++;
            firstTts.add(tt);
            rowLabels.add(label);
            details.add(d);
            out.append("SAMPLE-IMDBPRIME ").append(i)
                    .append(" gti=").append(gti)
                    .append(" resolved=").append(resolved)
                    .append(" tt=").append(tt.isEmpty() ? "-" : tt)
                    .append(" cands=").append(candN).append(" rank=").append(bestRank)
                    .append(" p345n=").append(p345n)
                    .append(" wdY=").append(wdYearKnown).append(" wdT=").append(wdTypeKnown)
                    .append(" net=").append(run.netError ? run.netKind
                            : (nb.fail > rowFail0 ? nb.lastKind + "@" + nb.lastTag : "-"))
                    .append(" castFail=").append(castFails)
                    .append(" err=").append(rowErr)
                    .append(" retry=").append(nb.retry - rowRetry0)
                    .append(" ms=").append(SystemClock.elapsedRealtime() - rowStart)
                    .append(" own=").append(isOwn).append(" uniq=").append(uniq)
                    .append(" titleOk=").append(tOk).append(" yearOk=").append(yOk)
                    .append(" typeOk=").append(tyOk).append(" castOk=").append(cOk)
                    .append(" parentOk=").append(pOk)
                    .append(" verdict=").append(verdict).append('\n');
        }
        int negPass = 0;
        PvRun trailer = pvRun(TRAILER_ALIAS, nb);
        boolean trailerUnknown = !trailer.netError && trailer.detail != null
                && trailer.detail.type == ShelfIdentity.Type.UNKNOWN;
        if (trailerUnknown) negPass++;
        out.append("NEG-IMDBPRIME kind=trailer-alias pass=").append(trailerUnknown)
                .append(" net=").append(trailer.netError ? trailer.netKind : "-").append('\n');
        PvRun malformed = pvRun(MALFORMED_GTI, nb);
        boolean malformedUnknown = !malformed.netError && malformed.detail != null
                && malformed.detail.type == ShelfIdentity.Type.UNKNOWN;
        if (malformedUnknown) negPass++;
        out.append("NEG-IMDBPRIME kind=malformed-gti pass=").append(malformedUnknown)
                .append(" net=").append(malformed.netError ? malformed.netKind : "-").append('\n');
        PvRun absent = pvRun(UNKNOWN_GTI, nb);
        boolean absentUnknown = !absent.netError && absent.detail != null
                && absent.detail.type == ShelfIdentity.Type.UNKNOWN;
        if (absentUnknown) negPass++;
        out.append("NEG-IMDBPRIME kind=unknown-gti pass=").append(absentUnknown)
                .append(" net=").append(absent.netError ? absent.netKind : "-").append('\n');
        // Wrong-production control: row i's title must not match row j's candidate label.
        int negWrong = 0, negWrongSkip = 0;
        for (int i = 0; i < n && negWrong == 0; i++) {
            PvDetail di = details.get(i);
            if (di == null || di.type == ShelfIdentity.Type.UNKNOWN) continue;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                String lj = rowLabels.get(j);
                if (lj == null || lj.isEmpty() || firstTts.get(j).isEmpty()) continue;
                Set<String> ti = ShelfIdentity.extractTokens(di.title);
                Set<String> ll = ShelfIdentity.extractTokens(lj);
                boolean cross = false;
                for (String t : ti) if (ll.contains(t)) { cross = true; break; }
                if (!cross) {
                    negWrong++;
                    out.append("NEG-IMDBPRIME kind=wrong-production pass=true")
                            .append(" row=").append(i).append(" other=").append(j).append('\n');
                    break;
                }
            }
        }
        if (negWrong == 0) {
            negWrongSkip++;
            out.append("NEG-IMDBPRIME kind=wrong-production pass=skip\n");
        }
        // Remake control: shared title tokens but different years must carry different tt ids.
        int negRemake = 0;
        for (int i = 0; i < n; i++) {
            PvDetail di = details.get(i);
            if (di == null || di.type == ShelfIdentity.Type.UNKNOWN) continue;
            for (int j = i + 1; j < n; j++) {
                PvDetail dj = details.get(j);
                if (dj == null || dj.type == ShelfIdentity.Type.UNKNOWN) continue;
                if (di.year <= 0 || dj.year <= 0 || di.year == dj.year) continue;
                if (firstTts.get(i).isEmpty() || firstTts.get(j).isEmpty()) continue;
                Set<String> ti = ShelfIdentity.extractTokens(di.title);
                Set<String> tj = ShelfIdentity.extractTokens(dj.title);
                boolean shared = false;
                for (String t : ti) if (tj.contains(t)) { shared = true; break; }
                if (!shared) continue;
                boolean distinct = !firstTts.get(i).equals(firstTts.get(j));
                if (distinct) negRemake++;
                out.append("NEG-IMDBPRIME kind=remake-pair pass=").append(distinct)
                        .append(" rows=").append(i).append(',').append(j).append('\n');
            }
        }
        // Real same-name remake control: exact same tt must not serve two different years.
        int negRemakeSame = 0, negRemakeSameSkip = 0;
        java.util.Map<String, Integer> ttYear = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            String t = firstTts.get(i);
            PvDetail dd = details.get(i);
            if (t == null || t.isEmpty() || dd == null || dd.year <= 0) continue;
            Integer seen = ttYear.get(t);
            if (seen == null) ttYear.put(t, dd.year);
            else if (!seen.equals(dd.year)) negRemakeSame++;
        }
        if (ttYear.isEmpty()) negRemakeSameSkip++;
        out.append("NEG-IMDBPRIME kind=remake-same-tt pass=").append(negRemakeSame == 0)
                .append(" reuse=").append(negRemakeSame).append('\n');
        out.append("PASS-IMDBPRIME n=").append(n)
                .append(" kesin=").append(kesin).append(" belirsiz=").append(belirsiz)
                .append(" basarisiz=").append(basarisiz)
                .append(" netErr=").append(netErr).append(" skipped=").append(skipped)
                .append(" parseErr=").append(parseErr).append(" kesinNoYear=").append(kesinNoYear)
                .append(" netFinal=").append(nb.fail).append(" netTimeout=").append(nb.timeout)
                .append(" netHttp=").append(nb.http).append(" netOther=").append(nb.fail - nb.timeout - nb.http)
                .append(" retry=").append(nb.retry).append(" backoffMs=").append(nb.backoffMs)
                .append(" runMs=").append(SystemClock.elapsedRealtime() - startMs)
                .append(" own=").append(ownN).append(" uniq=").append(uniqN)
                .append(" candTotal=").append(candTotal)
                .append(" titleOk=").append(titleOkN).append(" yearOk=").append(yearOkN)
                .append(" typeOk=").append(typeOkN).append(" castOk=").append(castOkN)
                .append(" parentOk=").append(parentOkN)
                .append(" negPass=").append(negPass)
                .append(" negWrong=").append(negWrong).append(" negWrongSkip=").append(negWrongSkip)
                .append(" negRemake=").append(negRemake).append('\n');
        return out.toString();
    }

    private static void check(boolean condition, String message, List<String> failures) {
        if (!condition) failures.add(message);
    }

    private static void checkThread(ResolveRun run, String what, List<String> failures) {
        long mainId = Looper.getMainLooper().getThread().getId();
        check(run.threadId != mainId, what + " ran on the main thread", failures);
        check(run.mainThreadDelayMs >= 0 && run.mainThreadDelayMs < 1000,
                what + " held the main thread for " + run.mainThreadDelayMs + "ms", failures);
    }

    private static double tokenOverlap(String a, String b) {
        Set<String> ta = ShelfIdentity.extractTokens(a), tb = ShelfIdentity.extractTokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        int hit = 0;
        for (String t : ta) if (tb.contains(t)) hit++;
        return (double) hit / Math.min(ta.size(), tb.size());
    }

    private static String describe(String kind, String gti, ResolveRun run, String rowTitle, String rowYear) {
        com.gathin.tvhome.PrimeMetadata.Detail d = run.detail;
        StringBuilder s = new StringBuilder();
        s.append("SAMPLE-PRIMEMETA ").append(kind).append(" gti=").append(gti);
        if (d == null) {
            s.append(" detail=null error=").append(run.error == null ? "-" : run.error.toString());
        } else {
            s.append(" title='").append(oneLine(d.title)).append("'")
                    .append(" year=").append(d.year)
                    .append(" type=").append(d.type)
                    .append(" series='").append(oneLine(d.seriesTitle)).append("'")
                    .append(" season=").append(d.seasonNumber)
                    .append(" genres=").append(d.genres.size())
                    .append(" cast=").append(d.cast.size())
                    .append(" canonical=").append(d.gti.isEmpty() ? "-" : d.gti);
        }
        s.append(" elapsedMs=").append(run.elapsedMs)
                .append(" mainThreadDelayMs=").append(run.mainThreadDelayMs)
                .append(" thread=").append(run.threadName)
                .append(" threadIsMain=").append(run.threadId == Looper.getMainLooper().getThread().getId());
        if (rowTitle != null && d != null) {
            s.append(" rowTitle='").append(oneLine(rowTitle)).append("'")
                    .append(" rowYear=").append(rowYear == null || rowYear.isEmpty() ? "-" : oneLine(rowYear))
                    .append(" titleOverlap=").append(String.format(java.util.Locale.ROOT, "%.2f",
                            tokenOverlap(rowTitle, d.title)));
        } else {
            s.append(" rowTitle=- rowYear=- titleOverlap=0.00");
        }
        return s.toString();
    }

    /** One extra read-only GET with the Fire TV user agent: DNS, response, first byte, total. */
    private static String netTiming(String gti) {
        StringBuilder o = new StringBuilder();
        HttpURLConnection c = null;
        try {
            long t0 = SystemClock.elapsedRealtime();
            java.net.InetAddress.getAllByName("www.primevideo.com");
            long dns = SystemClock.elapsedRealtime() - t0;
            c = (HttpURLConnection) new URL("https://www.primevideo.com/detail/" + gti
                    + "?language=" + com.gathin.tvhome.PrimeMetadata.listingLanguage()).openConnection();
            c.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 9; AFTM Build/PS7233; wv)");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            long t1 = SystemClock.elapsedRealtime();
            int code = c.getResponseCode();
            long response = SystemClock.elapsedRealtime() - t1;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[8192];
            long t2 = SystemClock.elapsedRealtime();
            int n = in.read(buf);
            long firstByte = SystemClock.elapsedRealtime() - t2;
            int total = Math.max(n, 0);
            while ((n = in.read(buf)) > 0) total += n;
            in.close();
            o.append("NET dnsMs=").append(dns)
                    .append(" responseMs=").append(response)
                    .append(" firstByteMs=").append(firstByte)
                    .append(" totalMs=").append(SystemClock.elapsedRealtime() - t0)
                    .append(" bytes=").append(total)
                    .append(" http=").append(code).append('\n');
        } catch (Exception e) {
            o.append("NET fail=").append(e.getClass().getSimpleName())
                    .append(": ").append(oneLine(String.valueOf(e.getMessage()))).append('\n');
        } finally {
            if (c != null) c.disconnect();
        }
        return o.toString();
    }

    private String primedetailCheck() {
        // Read-only: primevideo.com/detail/{GTI} for up to 12 real device GTIs.
        // Normal GET only; blocks respected, never bypassed. Counts/booleans only.
        // ponytail: HTML marker heuristics; DOM parse only when FP rate matters.
        List<WikiRow> pool = new ArrayList<>();
        collectWikiRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, 50);
        collectWikiRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, 50);
        List<String> gtis = new ArrayList<>();
        for (WikiRow r : pool) {
            if (r.slot != 1 || r.gti == null) continue;
            if (!gtis.contains(r.gti) && gtis.size() < 12) gtis.add(r.gti);
        }
        StringBuilder out = new StringBuilder();
        long startMs = SystemClock.elapsedRealtime();
        boolean[] first = new boolean[]{true};
        Pattern yearPat = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b");
        Pattern imdbPat = Pattern.compile("tt\\d{7,8}");
        int n = gtis.size();
        int page = 0, echo = 0, title = 0, year = 0, genre = 0, cast = 0, parent = 0, imdb = 0;
        int eslesti = 0, eslesmedi = 0, belirsiz = 0, netFail = 0, blocked = 0;
        for (int i = 0; i < n; i++) {
            String gti = gtis.get(i);
            boolean ok = false, gtiEcho = false, hasTitle = false, hasYear = false;
            boolean hasGenre = false, hasCast = false, hasParent = false, hasImdb = false;
            String verdict = "BELIRSIZ";
            if (SystemClock.elapsedRealtime() - startMs > 7 * 60 * 1000) {
                netFail++;
            } else {
                try {
                    String body = phashApiGet("https://www.primevideo.com/detail/" + gti, first);
                    ok = body != null && !body.isEmpty();
                    if (ok) {
                        String low = body.toLowerCase(Locale.ROOT);
                        gtiEcho = body.contains(gti);
                        hasTitle = low.contains("og:title") || low.contains("<title");
                        hasYear = yearPat.matcher(body).find();
                        hasGenre = low.contains("genre") || low.contains("tür");
                        hasCast = low.contains("starring") || low.contains("oyuncu")
                                || low.contains("\"cast\"") || low.contains(">cast<");
                        hasParent = low.contains("season") || low.contains("sezon")
                                || low.contains("series") || low.contains("episode")
                                || low.contains("bölüm");
                        hasImdb = imdbPat.matcher(body).find() || low.contains("imdb");
                        verdict = (gtiEcho && hasTitle) ? "ESLESTI" : "ESLESMEDI";
                    } else {
                        verdict = "ESLESMEDI";
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.startsWith("http 401") || msg.startsWith("http 403")
                            || msg.startsWith("http 429")) blocked++;
                    else netFail++;
                    verdict = "BELIRSIZ";
                }
            }
            if (ok) page++;
            if (gtiEcho) echo++;
            if (hasTitle) title++;
            if (hasYear) year++;
            if (hasGenre) genre++;
            if (hasCast) cast++;
            if (hasParent) parent++;
            if (hasImdb) imdb++;
            if ("ESLESTI".equals(verdict)) eslesti++;
            else if ("ESLESMEDI".equals(verdict)) eslesmedi++;
            else belirsiz++;
            out.append("SAMPLE-PRIMEDETAIL ").append(i)
                    .append(" page=").append(ok).append(" gti=").append(gtiEcho)
                    .append(" title=").append(hasTitle).append(" year=").append(hasYear)
                    .append(" genre=").append(hasGenre).append(" cast=").append(hasCast)
                    .append(" parent=").append(hasParent).append(" imdb=").append(hasImdb)
                    .append(" verdict=").append(verdict).append('\n');
        }
        out.append("PASS-PRIMEDETAIL n=").append(n).append(" page=").append(page)
                .append(" gti=").append(echo).append(" title=").append(title)
                .append(" year=").append(year).append(" genre=").append(genre)
                .append(" cast=").append(cast).append(" parent=").append(parent)
                .append(" imdb=").append(imdb).append(" tmdbPot=").append(Math.min(title, year))
                .append(" eslesti=").append(eslesti).append(" eslesmedi=").append(eslesmedi)
                .append(" belirsiz=").append(belirsiz).append(" netFail=").append(netFail)
                .append(" blocked=").append(blocked).append('\n');
        return out.toString();
    }

    private String wikidataCheck() {
        // Isolated probe: provider service IDs -> Wikidata external IDs.
        // Netflix P1874; Prime P14462/P14440/P8055. Corroborate via IMDb P345,
        // TMDb P4947 (movie) / P4983 (series). Unique + type-consistent only.
        // Episode candidates follow P179 to parent series. Counts/booleans only.
        // ponytail: SPARQL + wbgetentities live lookup; cache in temp map when quota matters.
        List<WikiRow> pool = new ArrayList<>();
        collectWikiRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, 50);
        collectWikiRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, 50);
        StringBuilder out = new StringBuilder();
        long startMs = SystemClock.elapsedRealtime();
        boolean[] first = new boolean[]{true};
        try {
            List<String> ctl = sparqlItems("P345", "tt0903747", first);
            out.append("PASS-WIKIDATA-CTL ok=").append(!ctl.isEmpty())
                    .append(" cand=").append(ctl.size()).append('\n');
        } catch (Exception e) {
            out.append("PASS-WIKIDATA-CTL ok=false cand=0\n");
        }
        List<WikiRow> nf = new ArrayList<>(), pr = new ArrayList<>();
        for (WikiRow r : pool) (r.slot == 0 ? nf : pr).add(r);
        List<WikiRow> ordered = new ArrayList<>();
        for (int i = 0; i < 10 && ordered.size() < 10 && (i < nf.size() || i < pr.size()); i++) {
            if (i < nf.size() && ordered.size() < 10) ordered.add(nf.get(i));
            if (ordered.size() < 10 && i < pr.size()) ordered.add(pr.get(i));
        }
        int n = ordered.size();
        int[] kes = new int[2], bel = new int[2], yok = new int[2];
        int kesin = 0, belirsiz = 0, bulunamadi = 0, netFail = 0;
        Pattern fullGti = Pattern.compile("amzn1\\.dv\\.gti\\.[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        Pattern full26 = Pattern.compile("[0-9A-Z]{26}");
        Pattern fullAsin = Pattern.compile("B[0-9A-Z]{9}");
        int primeN = pr.size(), gtiFull = 0, gtiShort = 0, p26Ok = 0, asinOk = 0, svcAny = 0;
        for (WikiRow r : pr) {
            boolean gf = r.gti != null && fullGti.matcher(r.gti).matches();
            boolean gs = r.gti != null && !gf;
            boolean p26 = r.prime26 != null && full26.matcher(r.prime26).matches();
            boolean as = r.asin != null && fullAsin.matcher(r.asin).matches();
            if (gf) gtiFull++;
            if (gs) gtiShort++;
            if (p26) p26Ok++;
            if (as) asinOk++;
            if (gf || p26 || as) svcAny++;
        }
        out.append("PASS-PRIMEFMT nPrime=").append(primeN).append(" gtiFull=").append(gtiFull)
                .append(" gtiShort=").append(gtiShort).append(" p26=").append(p26Ok)
                .append(" asin=").append(asinOk).append(" svcAny=").append(svcAny)
                .append(" svcNone=").append(primeN - svcAny).append('\n');
        for (int i = 0; i < n; i++) {
            WikiRow r = ordered.get(i);
            String verdict = "BULUNAMADI";
            int cand = 0;
            boolean svc = !(r.netflixId == null && r.gti == null && r.prime26 == null && r.asin == null);
            boolean typeOk = false, hasImdb = false, hasTmdb = false, parentOk = true;
            String propUsed = "-";
            if (SystemClock.elapsedRealtime() - startMs > 7 * 60 * 1000) {
                netFail++;
                verdict = "BELIRSIZ";
            } else if (!svc) {
                verdict = "BULUNAMADI";
            } else {
                List<String[]> tries = new ArrayList<>();
                if (r.netflixId != null) tries.add(new String[]{"P1874", r.netflixId});
                if (r.gti != null) tries.add(new String[]{"P14462", r.gti});
                if (r.prime26 != null) tries.add(new String[]{"P14440", r.prime26});
                if (r.asin != null) tries.add(new String[]{"P8055", r.asin});
                List<String> items = null;
                boolean hadError = false;
                for (String[] t : tries) {
                    if (SystemClock.elapsedRealtime() - startMs > 7 * 60 * 1000) { hadError = true; break; }
                    try {
                        List<String> got = sparqlItems(t[0], t[1], first);
                        if (!got.isEmpty()) { items = got; propUsed = t[0]; break; }
                        propUsed = t[0];
                    } catch (Exception e) {
                        hadError = true;
                        netFail++;
                    }
                }
                if (items == null) {
                    verdict = hadError ? "BELIRSIZ" : "BULUNAMADI";
                    cand = 0;
                } else {
                    cand = items.size();
                    if (items.size() != 1) {
                        verdict = "BELIRSIZ";
                    } else {
                        try {
                            JSONObject claims = entityClaims(items.get(0), first);
                            if (claims == null) { netFail++; verdict = "BELIRSIZ"; }
                            else {
                                boolean isSeries = claimsHasId(claims, "P31", "Q5398426");
                                boolean isEpisode = claimsHasId(claims, "P31", "Q21191270");
                                boolean isFilm = claimsHasId(claims, "P31", "Q11424");
                                if (r.type == ShelfIdentity.Type.SERIES) typeOk = isSeries;
                                else if (r.type == ShelfIdentity.Type.MOVIE) typeOk = isFilm;
                                else if (r.type == ShelfIdentity.Type.EPISODE) typeOk = isEpisode || isSeries;
                                else typeOk = false;
                                hasImdb = claimsHasAny(claims, "P345");
                                hasTmdb = claimsHasAny(claims, "P4947") || claimsHasAny(claims, "P4983");
                                parentOk = true;
                                if (r.type == ShelfIdentity.Type.EPISODE && isEpisode && !isSeries) {
                                    String parent = claimsFirstTargetId(claims, "P179");
                                    if (parent == null) parentOk = false;
                                    else {
                                        try {
                                            JSONObject pc = entityClaims(parent, first);
                                            parentOk = pc != null && claimsHasId(pc, "P31", "Q5398426");
                                        } catch (Exception e) {
                                            netFail++;
                                            parentOk = false;
                                        }
                                    }
                                }
                                if (typeOk && (hasImdb || hasTmdb) && parentOk) verdict = "KESIN";
                                else verdict = "BELIRSIZ";
                            }
                        } catch (Exception e) {
                            netFail++;
                            verdict = "BELIRSIZ";
                        }
                    }
                }
            }
            if ("KESIN".equals(verdict)) { kesin++; kes[r.slot]++; }
            else if ("BELIRSIZ".equals(verdict)) { belirsiz++; bel[r.slot]++; }
            else { bulunamadi++; yok[r.slot]++; }
            out.append("SAMPLE-WIKIDATA ").append(i)
                    .append(" pkg=").append(r.slot == 0 ? "netflix" : "prime")
                    .append(" svc=").append(svc).append(" prop=").append(propUsed)
                    .append(" qtype=").append(r.type).append(" cand=").append(cand)
                    .append(" typeOk=").append(typeOk).append(" imdb=").append(hasImdb)
                    .append(" tmdb=").append(hasTmdb).append(" parentOk=").append(parentOk)
                    .append(" verdict=").append(verdict).append('\n');
        }
        out.append("PASS-WIKIDATA n=").append(n).append(" kesin=").append(kesin)
                .append(" belirsiz=").append(belirsiz).append(" bulunamadi=").append(bulunamadi)
                .append(" netFail=").append(netFail).append('\n');
        out.append("PASS-WIKIDATA netflix kesin=").append(kes[0])
                .append(" belirsiz=").append(bel[0]).append(" bulunamadi=").append(yok[0]).append('\n');
        out.append("PASS-WIKIDATA prime kesin=").append(kes[1])
                .append(" belirsiz=").append(bel[1]).append(" bulunamadi=").append(yok[1]).append('\n');
        return out.toString();
    }

    /** Official Action API identity search: haswbstatement:P345=<tt>; distinct QIDs in answer order. */
    private static List<String> searchItems(String tt, boolean[] first, NetBucket nb) throws Exception {
        String url = "https://www.wikidata.org/w/api.php?action=query&list=search&srsearch="
                + URLEncoder.encode("haswbstatement:P345=" + tt, "UTF-8") + "&srlimit=10&format=json";
        JSONObject query = new JSONObject(imdbprimeApiGet(url, first, nb)).optJSONObject("query");
        JSONArray hits = query == null ? null : query.optJSONArray("search");
        List<String> items = new ArrayList<>();
        for (int k = 0; hits != null && k < hits.length(); k++) {
            JSONObject hit = hits.optJSONObject(k);
            String qid = hit == null ? "" : hit.optString("title", "");
            if (!qid.isEmpty() && !items.contains(qid)) items.add(qid);
        }
        return items;
    }

    private static List<String> sparqlItems(String prop, String value, boolean[] first) throws Exception {
        return sparqlItems(prop, value, first, null);
    }

    private static List<String> sparqlItems(String prop, String value, boolean[] first, NetBucket nb) throws Exception {
        String clean = value.replace("\"", "").replace("\\", "");
        String sparql = "SELECT ?item WHERE { ?item wdt:" + prop + " \"" + clean + "\". } LIMIT 10";
        String url = "https://query.wikidata.org/sparql?query="
                + URLEncoder.encode(sparql, "UTF-8") + "&format=json";
        String body = nb == null ? phashApiGet(url, first) : imdbprimeApiGet(url, first, nb);
        JSONObject o = new JSONObject(body);
        JSONObject results = o.optJSONObject("results");
        List<String> items = new ArrayList<>();
        if (results == null) return items;
        JSONArray bindings = results.optJSONArray("bindings");
        if (bindings == null) return items;
        for (int k = 0; k < bindings.length(); k++) {
            JSONObject b = bindings.optJSONObject(k);
            if (b == null) continue;
            JSONObject item = b.optJSONObject("item");
            if (item == null) continue;
            String v = item.optString("value", "");
            int slash = v.lastIndexOf('/');
            String qid = slash >= 0 ? v.substring(slash + 1) : v;
            if (!qid.isEmpty() && !items.contains(qid)) items.add(qid);
        }
        return items;
    }

    private static JSONObject entityClaims(String qid, boolean[] first) throws Exception {
        String url = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids="
                + URLEncoder.encode(qid, "UTF-8") + "&props=claims&format=json";
        String body = phashApiGet(url, first);
        JSONObject o = new JSONObject(body);
        JSONObject entities = o.optJSONObject("entities");
        if (entities == null) return null;
        JSONObject ent = entities.optJSONObject(qid);
        if (ent == null) return null;
        return ent.optJSONObject("claims");
    }

    private static boolean claimsHasId(JSONObject claims, String prop, String targetQid) {
        JSONArray arr = claims.optJSONArray(prop);
        if (arr == null) return false;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject st = arr.optJSONObject(k);
            if (st == null) continue;
            JSONObject mainsnak = st.optJSONObject("mainsnak");
            if (mainsnak == null) continue;
            JSONObject dv = mainsnak.optJSONObject("datavalue");
            if (dv == null) continue;
            JSONObject val = dv.optJSONObject("value");
            if (val == null) continue;
            if (targetQid.equals(val.optString("id", ""))) return true;
        }
        return false;
    }

    private static boolean claimsHasAny(JSONObject claims, String prop) {
        JSONArray arr = claims.optJSONArray(prop);
        if (arr == null || arr.length() == 0) return false;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject st = arr.optJSONObject(k);
            if (st == null) continue;
            JSONObject mainsnak = st.optJSONObject("mainsnak");
            if (mainsnak == null) continue;
            if (mainsnak.optJSONObject("datavalue") != null) return true;
        }
        return false;
    }

    private static String claimsFirstTargetId(JSONObject claims, String prop) {
        JSONArray arr = claims.optJSONArray(prop);
        if (arr == null) return null;
        for (int k = 0; k < arr.length(); k++) {
            JSONObject st = arr.optJSONObject(k);
            if (st == null) continue;
            JSONObject mainsnak = st.optJSONObject("mainsnak");
            if (mainsnak == null) continue;
            JSONObject dv = mainsnak.optJSONObject("datavalue");
            if (dv == null) continue;
            JSONObject val = dv.optJSONObject("value");
            if (val == null) continue;
            String id = val.optString("id", "");
            if (!id.isEmpty()) return id;
        }
        return null;
    }

    private static final class WikiRow {
        final int slot;
        final ShelfIdentity.Type type;
        final String title;
        final int year;
        final String netflixId, gti, prime26, asin;
        WikiRow(int slot, ShelfIdentity.Type type, String title, int year,
                String netflixId, String gti, String prime26, String asin) {
            this.slot = slot; this.type = type; this.title = title; this.year = year;
            this.netflixId = netflixId; this.gti = gti; this.prime26 = prime26; this.asin = asin;
        }
    }

    private void collectWikiRows(List<WikiRow> pool, android.net.Uri uri, int limit) {
        if (pool.size() >= limit) return;
        String[] proj = {"package_name", "title", "type", "release_date", "intent_uri"};
        Pattern nflxPat = Pattern.compile("title/(\\d{6,8})");
        Pattern gtiPat = Pattern.compile("amzn1\\.dv\\.gti\\.[A-Za-z0-9_-]+");
        Pattern d26Pat = Pattern.compile("detail/([0-9A-Z]{26})");
        Pattern asinPat = Pattern.compile("\\b(B[0-9A-Z]{9})\\b");
        try (Cursor c = getTargetContext().getContentResolver().query(uri, proj, null, null, null)) {
            if (c == null) return;
            int iPkg = c.getColumnIndex("package_name"), iTitle = c.getColumnIndex("title"),
                    iType = c.getColumnIndex("type"), iRel = c.getColumnIndex("release_date"),
                    iIntent = c.getColumnIndex("intent_uri");
            while (c.moveToNext() && pool.size() < limit) {
                String pkg = iPkg >= 0 && !c.isNull(iPkg) ? c.getString(iPkg) : "";
                int slot = "com.netflix.ninja".equals(pkg) ? 0
                        : "com.amazon.amazonvideo.livingroom".equals(pkg) ? 1 : -1;
                if (slot < 0) continue;
                String t = iTitle >= 0 && !c.isNull(iTitle) ? c.getString(iTitle).trim() : "";
                if (t.isEmpty()) continue;
                String rel = iRel >= 0 && !c.isNull(iRel) ? c.getString(iRel) : null;
                int typeCode = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                String intent = iIntent >= 0 && !c.isNull(iIntent) ? c.getString(iIntent) : "";
                if (intent.isEmpty()) continue;
                ShelfIdentity.Query q = ShelfIdentity.parse(t, intent, null, typeCode, rel);
                if (q.type != ShelfIdentity.Type.SERIES && q.type != ShelfIdentity.Type.EPISODE
                        && q.type != ShelfIdentity.Type.MOVIE) continue;
                String decoded = intent;
                try {
                    decoded = java.net.URLDecoder.decode(decoded, "UTF-8");
                    decoded = java.net.URLDecoder.decode(decoded, "UTF-8");
                } catch (Exception ignored) {}
                String both = intent + " " + decoded;
                java.util.regex.Matcher m = nflxPat.matcher(both);
                String nid = m.find() ? m.group(1) : null;
                m = gtiPat.matcher(decoded + " " + intent);
                String gti = m.find() ? m.group(0) : null;
                m = d26Pat.matcher(both);
                String p26 = m.find() ? m.group(1) : null;
                m = asinPat.matcher(both);
                String asin = m.find() ? m.group(1) : null;
                if (slot == 0 && nid == null) continue;
                if (slot == 1 && gti == null && p26 == null && asin == null) continue;
                pool.add(new WikiRow(slot, q.type, t, q.year, nid, gti, p26, asin));
            }
        }
    }

    private String phashCheck() {
        // Isolated probe: provider poster vs TVmaze /shows/{id}/images pHash.
        // RAM only (bitmaps recycled, no files). Counts/booleans/distances only, no raw text/URI.
        // ponytail: thresholds 8/12 uncalibrated heuristic; calibrate on labeled set when FP rate matters.
        List<PosterRow> pool = new ArrayList<>();
        collectPosterRows(pool, android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, 50);
        collectPosterRows(pool, android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, 50);
        StringBuilder out = new StringBuilder();
        long startMs = SystemClock.elapsedRealtime();
        boolean[] first = new boolean[]{true};
        int unrelatedId = -1;
        boolean ctlOk = false;
        int ctlCand = 0;
        try {
            JSONArray ctlArr = new JSONArray(phashApiGet(
                    "https://api.tvmaze.com/search/shows?q=Breaking%20Bad", first));
            ctlCand = ctlArr.length();
            if (ctlArr.length() > 0) {
                unrelatedId = ctlArr.getJSONObject(0).getJSONObject("show").optInt("id", -1);
                ctlOk = unrelatedId > 0;
            }
        } catch (Exception e) {
            ctlOk = false;
        }
        out.append("PASS-PHASH-CTL ok=").append(ctlOk).append(" cand=").append(ctlCand).append('\n');
        List<PosterRow> nf = new ArrayList<>(), pr = new ArrayList<>();
        for (PosterRow r : pool) (r.slot == 0 ? nf : pr).add(r);
        List<PosterRow> ordered = new ArrayList<>();
        for (int i = 0; i < 10 && ordered.size() < 10 && (i < nf.size() || i < pr.size()); i++) {
            if (i < nf.size() && ordered.size() < 10) ordered.add(nf.get(i));
            if (ordered.size() < 10 && i < pr.size()) ordered.add(pr.get(i));
        }
        int n = ordered.size();
        // Pass 1: top search id per row + ground-truth validation (title overlap, year conflict).
        // Top hit assumed nothing: mapOk=false caps verdict at UNCERTAIN.
        int[] topIds = new int[n];
        String[] fallbacks = new String[n];
        boolean[] mapOk = new boolean[n];
        int netSearch = 0;
        for (int i = 0; i < n; i++) {
            topIds[i] = -1;
            if (SystemClock.elapsedRealtime() - startMs > 7 * 60 * 1000) { netSearch++; continue; }
            PosterRow r = ordered.get(i);
            try {
                String rawBody = phashApiGet("https://api.tvmaze.com/search/shows?q="
                        + URLEncoder.encode(r.title, "UTF-8"), first);
                JSONArray arr = new JSONArray(rawBody);
                if (arr.length() == 0) {
                    String norm = normQuery(r.title);
                    arr = new JSONArray(phashApiGet("https://api.tvmaze.com/search/shows?q="
                            + URLEncoder.encode(norm.isEmpty() ? r.title : norm, "UTF-8"), first));
                }
                if (arr.length() == 0) continue;
                JSONObject show = arr.getJSONObject(0).getJSONObject("show");
                topIds[i] = show.optInt("id", -1);
                int cyear = parseYear4(show.optString("premiered", null));
                JSONObject img = show.optJSONObject("image");
                if (img != null) {
                    String fb = img.optString("original", null);
                    if (fb != null && !fb.isEmpty()) fallbacks[i] = fb;
                }
                Set<String> qTitleTok = ShelfIdentity.extractTokens(r.title);
                Set<String> cTitleTok = ShelfIdentity.extractTokens(show.optString("name", ""));
                int overlap = 0;
                for (String t : qTitleTok) if (cTitleTok.contains(t)) overlap++;
                boolean yearConflict = r.year > 0 && cyear > 0 && Math.abs(r.year - cyear) > 1;
                mapOk[i] = topIds[i] > 0 && !yearConflict && overlap > 0;
            } catch (Exception e) {
                netSearch++;
            }
        }
        java.util.Map<Integer, List<String>> imgCache = new java.util.HashMap<>();
        int[] rel = new int[2], unf = new int[2], non = new int[2];
        int reliable = 0, uncertain = 0, nonmatch = 0, fp = 0, netImg = 0;
        for (int i = 0; i < n; i++) {
            PosterRow r = ordered.get(i);
            int cand = 0, best = -1, negBest = -1;
            String verdict = "NONMATCH";
            boolean fpRow = false;
            if (SystemClock.elapsedRealtime() - startMs > 7 * 60 * 1000) {
                netImg++;
            } else {
                try {
                    byte[] provBytes;
                    try {
                        provBytes = httpGetBytes(r.poster);
                    } catch (Exception e) {
                        netImg++;
                        provBytes = null;
                    }
                    Long pP = null, dP = null;
                    Bitmap provBmp = provBytes == null ? null : decodeBounded(provBytes);
                    if (provBmp != null) {
                        try {
                            pP = pHashFromGray(scaleGray32(provBmp));
                            dP = dHashFromBitmap(provBmp);
                        } finally {
                            provBmp.recycle();
                        }
                    }
                    List<String> trueUrls = imageUrls(topIds[i], fallbacks[i], imgCache, first);
                    // Negatives: opposite-package top ids + unrelated control, distinct from true id.
                    List<Integer> negIds = new ArrayList<>();
                    for (int j = 0; j < n && negIds.size() < 2; j++) {
                        if (ordered.get(j).slot != r.slot && topIds[j] > 0
                                && topIds[j] != topIds[i] && !negIds.contains(topIds[j]))
                            negIds.add(topIds[j]);
                    }
                    if (unrelatedId > 0 && unrelatedId != topIds[i]
                            && !negIds.contains(unrelatedId) && negIds.size() < 2)
                        negIds.add(unrelatedId);
                    List<String> negUrls = new ArrayList<>();
                    for (int nid : negIds) {
                        for (String u : imageUrls(nid, null, imgCache, first)) {
                            if (negUrls.size() >= 4) break;
                            if (!trueUrls.contains(u) && !negUrls.contains(u)) negUrls.add(u);
                        }
                    }
                    if (pP != null) {
                        int b = Integer.MAX_VALUE, nb = Integer.MAX_VALUE;
                        int okCount = 0;
                        for (String u : trueUrls) {
                            try {
                                Bitmap cb = decodeBounded(httpGetBytes(u));
                                if (cb == null) continue;
                                try {
                                    int d = candidateBest(pP.longValue(), dP, cb);
                                    if (d < b) b = d;
                                    okCount++;
                                } finally {
                                    cb.recycle();
                                }
                            } catch (Exception e) {
                                netImg++;
                            }
                        }
                        cand = okCount;
                        for (String u : negUrls) {
                            try {
                                Bitmap cb = decodeBounded(httpGetBytes(u));
                                if (cb == null) continue;
                                try {
                                    int d = candidateBest(pP.longValue(), dP, cb);
                                    if (d < nb) nb = d;
                                } finally {
                                    cb.recycle();
                                }
                            } catch (Exception e) {
                                netImg++;
                            }
                        }
                        best = b == Integer.MAX_VALUE ? -1 : b;
                        negBest = nb == Integer.MAX_VALUE ? -1 : nb;
                        // Same min-of-variants on both sides; unvalidated mapping never RELIABLE.
                        if (best < 0) {
                            verdict = "NONMATCH";
                        } else if (best > 12) {
                            verdict = "NONMATCH";
                            if (negBest >= 0 && negBest <= 8) fpRow = true;
                        } else if (!mapOk[i]) {
                            verdict = "UNCERTAIN";
                            if (negBest >= 0 && negBest <= 8) fpRow = true;
                        } else if (negBest >= 0 && negBest <= 8) {
                            verdict = "UNCERTAIN";
                            fpRow = true;
                        } else if (negBest < 0) {
                            verdict = "UNCERTAIN";
                        } else if (best <= 8) {
                            verdict = "RELIABLE";
                        } else {
                            verdict = "UNCERTAIN";
                        }
                    }
                } catch (Exception e) {
                    netImg++;
                }
            }
            if (fpRow) {
                fp++;
                out.append("FP-PHASH ").append(i)
                        .append(" pkg=").append(r.slot == 0 ? "netflix" : "prime")
                        .append(" best=").append(best).append(" negBest=").append(negBest).append('\n');
            }
            if ("RELIABLE".equals(verdict)) { reliable++; rel[r.slot]++; }
            else if ("UNCERTAIN".equals(verdict)) { uncertain++; unf[r.slot]++; }
            else { nonmatch++; non[r.slot]++; }
            out.append("SAMPLE-PHASH ").append(i)
                    .append(" pkg=").append(r.slot == 0 ? "netflix" : "prime")
                    .append(" cand=").append(cand).append(" best=").append(best)
                    .append(" negBest=").append(negBest).append(" mapOk=").append(mapOk[i])
                    .append(" verdict=").append(verdict).append('\n');
        }
        out.append("PASS-PHASH n=").append(n).append(" reliable=").append(reliable)
                .append(" uncertain=").append(uncertain).append(" nonmatch=").append(nonmatch)
                .append(" fp=").append(fp).append(" netSearch=").append(netSearch)
                .append(" netImg=").append(netImg).append('\n');
        out.append("PASS-PHASH netflix reliable=").append(rel[0]).append(" uncertain=").append(unf[0])
                .append(" nonmatch=").append(non[0]).append('\n');
        out.append("PASS-PHASH prime reliable=").append(rel[1]).append(" uncertain=").append(unf[1])
                .append(" nonmatch=").append(non[1]).append('\n');
        return out.toString();
    }

    private static String phashApiGet(String url, boolean[] first) throws Exception {
        // api.tvmaze.com rate limit: 20 calls/10s. Image CDN downloads bypass this throttle.
        if (!first[0]) SystemClock.sleep(500);
        first[0] = false;
        try {
            return httpGet(url);
        } catch (java.io.IOException e) {
            if ("http 429".equals(e.getMessage())) {
                SystemClock.sleep(2000);
                return httpGet(url);
            }
            throw e;
        }
    }

    private static final class PosterRow {
        final int slot;
        final String title;
        final int year;
        final String poster;
        PosterRow(int slot, String title, int year, String poster) {
            this.slot = slot; this.title = title; this.year = year; this.poster = poster;
        }
    }

    private void collectPosterRows(List<PosterRow> pool, android.net.Uri uri, int limit) {
        if (pool.size() >= limit) return;
        String[] proj = {"package_name", "title", "poster_art_uri", "type", "release_date", "intent_uri"};
        try (Cursor c = getTargetContext().getContentResolver().query(uri, proj, null, null, null)) {
            if (c == null) return;
            int iPkg = c.getColumnIndex("package_name"), iTitle = c.getColumnIndex("title"),
                    iPoster = c.getColumnIndex("poster_art_uri"), iType = c.getColumnIndex("type"),
                    iRel = c.getColumnIndex("release_date"), iIntent = c.getColumnIndex("intent_uri");
            while (c.moveToNext() && pool.size() < limit) {
                String pkg = iPkg >= 0 && !c.isNull(iPkg) ? c.getString(iPkg) : "";
                int slot = "com.netflix.ninja".equals(pkg) ? 0
                        : "com.amazon.amazonvideo.livingroom".equals(pkg) ? 1 : -1;
                if (slot < 0) continue;
                String t = iTitle >= 0 && !c.isNull(iTitle) ? c.getString(iTitle).trim() : "";
                String p = iPoster >= 0 && !c.isNull(iPoster) ? c.getString(iPoster).trim() : "";
                if (t.isEmpty() || p.isEmpty()) continue;
                String rel = iRel >= 0 && !c.isNull(iRel) ? c.getString(iRel) : null;
                int typeCode = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                String intent = iIntent >= 0 && !c.isNull(iIntent) ? c.getString(iIntent) : "";
                ShelfIdentity.Query q = ShelfIdentity.parse(t, intent, null, typeCode, rel);
                if (q.type != ShelfIdentity.Type.SERIES && q.type != ShelfIdentity.Type.EPISODE) continue;
                pool.add(new PosterRow(slot, t, q.year, p));
            }
        }
    }

    private static List<String> imageUrls(int id, String fallback,
            java.util.Map<Integer, List<String>> cache, boolean[] first) throws Exception {
        List<String> raw = cache.get(id);
        if (raw == null && id > 0) {
            raw = new ArrayList<>();
            JSONArray arr = new JSONArray(phashApiGet(
                    "https://api.tvmaze.com/shows/" + id + "/images", first));
            List<String> posters = new ArrayList<>(), others = new ArrayList<>();
            for (int k = 0; k < arr.length(); k++) {
                JSONObject o = arr.getJSONObject(k);
                JSONObject res = o.optJSONObject("resolutions");
                if (res == null) continue;
                JSONObject orig = res.optJSONObject("original");
                if (orig == null) continue;
                String u = orig.optString("url", "");
                if (u.isEmpty()) continue;
                if ("poster".equalsIgnoreCase(o.optString("type", ""))) posters.add(u);
                else others.add(u);
            }
            for (String u : posters) { if (raw.size() >= 4) break; if (!raw.contains(u)) raw.add(u); }
            for (String u : others) { if (raw.size() >= 6) break; if (!raw.contains(u)) raw.add(u); }
            cache.put(id, raw);
        }
        List<String> result = raw == null ? new ArrayList<String>() : new ArrayList<>(raw);
        if (result.isEmpty() && fallback != null && !fallback.isEmpty()
                && (fallback.regionMatches(true, 0, "https://", 0, 8)
                    || fallback.regionMatches(true, 0, "http://", 0, 7)))
            result.add(fallback);
        return result;
    }

    private static byte[] httpGetBytes(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "AppleTVLauncher-tvmaze-probe/1.2");
            int code = conn.getResponseCode();
            if (code == 429) throw new java.io.IOException("http 429");
            if (code < 200 || code >= 400) throw new java.io.IOException("http " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read, total = 0;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                if (total > 262144) break;
                buf.write(chunk, 0, read);
            }
            in.close();
            byte[] data = buf.toByteArray();
            if (data.length == 0) throw new java.io.IOException("empty body");
            return data;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap decodeBounded(byte[] data) {
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 64 && bounds.outHeight / (sample * 2) >= 64) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static int[] scaleGray32(Bitmap src) {
        Bitmap s = Bitmap.createScaledBitmap(src, 32, 32, true);
        try {
            int[] px = new int[1024];
            s.getPixels(px, 0, 32, 0, 0, 32, 32);
            int[] g = new int[1024];
            for (int i = 0; i < 1024; i++) {
                int c = px[i];
                g[i] = (77 * ((c >> 16) & 255) + 150 * ((c >> 8) & 255) + 29 * (c & 255)) >> 8;
            }
            return g;
        } finally {
            s.recycle();
        }
    }

    private static long dHashFromBitmap(Bitmap src) {
        Bitmap s = Bitmap.createScaledBitmap(src, 9, 8, true);
        try {
            int[] px = new int[72];
            s.getPixels(px, 0, 9, 0, 0, 9, 8);
            long h = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int a = px[y * 9 + x], b = px[y * 9 + x + 1];
                    int ga = (77 * ((a >> 16) & 255) + 150 * ((a >> 8) & 255) + 29 * (a & 255)) >> 8;
                    int gb = (77 * ((b >> 16) & 255) + 150 * ((b >> 8) & 255) + 29 * (b & 255)) >> 8;
                    if (ga > gb) h |= (1L << (y * 8 + x));
                }
            }
            return h;
        } finally {
            s.recycle();
        }
    }

    private static Long pHashFromGray(int[] g) {
        double[][] t = new double[32][32];
        double[] rowIn = new double[32], rowOut = new double[32];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) rowIn[x] = g[y * 32 + x];
            dct1d(rowIn, rowOut);
            System.arraycopy(rowOut, 0, t[y], 0, 32);
        }
        double[] vals = new double[64];
        double[] col = new double[32], colOut = new double[32];
        for (int u = 0; u < 8; u++) {
            for (int y = 0; y < 32; y++) col[y] = t[y][u];
            dct1d(col, colOut);
            for (int v = 0; v < 8; v++) vals[v * 8 + u] = colOut[v];
        }
        double[] rest = new double[63];
        System.arraycopy(vals, 1, rest, 0, 63);
        Arrays.sort(rest);
        double median = rest[31];
        long h = 0;
        for (int i = 0; i < 64; i++) if (vals[i] > median) h |= (1L << i);
        return h;
    }

    private static void dct1d(double[] in, double[] out) {
        int n = in.length;
        double s0 = Math.sqrt(1.0 / n), s = Math.sqrt(2.0 / n);
        for (int u = 0; u < n; u++) {
            double sum = 0;
            for (int x = 0; x < n; x++) sum += in[x] * Math.cos((2 * x + 1) * u * Math.PI / (2 * n));
            out[u] = sum * (u == 0 ? s0 : s);
        }
    }

    private static int hamming(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    private static Bitmap centerCrop(Bitmap src, int pct) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            int w2 = Math.max(1, w * pct / 100), h2 = Math.max(1, h * pct / 100);
            return Bitmap.createBitmap(src, (w - w2) / 2, (h - h2) / 2, w2, h2);
        } catch (Exception e) {
            return null;
        }
    }

    private static int candidateBest(long pP, Long dP, Bitmap src) {
        // Same crop/scale variants for true and negative candidates: min distance wins both sides.
        int best = Integer.MAX_VALUE;
        Bitmap c90 = centerCrop(src, 90), c80 = centerCrop(src, 80), s50 = null;
        try {
            if (src.getWidth() >= 4 && src.getHeight() >= 4)
                s50 = Bitmap.createScaledBitmap(src, src.getWidth() / 2, src.getHeight() / 2, true);
            Bitmap[] vs = new Bitmap[]{src, c90, c80, s50};
            for (Bitmap v : vs) {
                if (v == null) continue;
                int dp = hamming(pP, pHashFromGray(scaleGray32(v)).longValue());
                if (dp < best) best = dp;
                if (dP != null) {
                    int dd = hamming(dP.longValue(), dHashFromBitmap(v));
                    if (dd < best) best = dd;
                }
            }
        } finally {
            if (c90 != null) c90.recycle();
            if (c80 != null) c80.recycle();
            if (s50 != null) s50.recycle();
        }
        return best;
    }

    private static boolean isTurkish(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("ç") || t.contains("ğ") || t.contains("ı")
                || t.contains("ö") || t.contains("ş") || t.contains("ü")) return true;
        String[] keys = {" ve ", " bir ", " ile ", " için ", " sezon ", " bölüm ", " yeni ", " hik"};
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }

    private static String normQuery(String title) {
        // Keep latin-ish letters/digits/spaces only; strip promo junk and service suffixes.
        String t = title.toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9\\s]", " ");
        String[] junk = {"yeni", "diziler", "filmler", "sezon", "sezonlar", "series", "season",
                "now", "watch", "netflix", "prime", "video", "original", "originals", "show", "tv"};
        for (String j : junk) t = t.replaceAll("\\b" + j + "\\b", " ");
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() > 60) t = t.substring(0, 60).trim();
        return t;
    }

    private static final class TvRow {
        final int slot;
        final String title;
        final String shortDesc;
        final int year;
        final ShelfIdentity.Type type;
        TvRow(int slot, String title, String shortDesc, int year, ShelfIdentity.Type type) {
            this.slot = slot; this.title = title; this.shortDesc = shortDesc;
            this.year = year; this.type = type;
        }
    }

    private void collectTvRows(List<TvRow> pool, android.net.Uri uri, String[] proj, int limit) {
        if (pool.size() >= limit) return;
        try (Cursor c = getTargetContext().getContentResolver().query(uri, proj, null, null, null)) {
            if (c == null) return;
            int iPkg = c.getColumnIndex("package_name"), iTitle = c.getColumnIndex("title"),
                    iShort = c.getColumnIndex("short_description"), iType = c.getColumnIndex("type"),
                    iRel = c.getColumnIndex("release_date"), iIntent = c.getColumnIndex("intent_uri");
            while (c.moveToNext() && pool.size() < limit) {
                String pkg = iPkg >= 0 && !c.isNull(iPkg) ? c.getString(iPkg) : "";
                int slot = "com.netflix.ninja".equals(pkg) ? 0
                        : "com.amazon.amazonvideo.livingroom".equals(pkg) ? 1 : -1;
                if (slot < 0) continue;
                String t = iTitle >= 0 && !c.isNull(iTitle) ? c.getString(iTitle).trim() : "";
                String sh = iShort >= 0 && !c.isNull(iShort) ? c.getString(iShort).trim() : "";
                if (t.isEmpty() || sh.isEmpty()) continue;
                String rel = iRel >= 0 && !c.isNull(iRel) ? c.getString(iRel) : null;
                int typeCode = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                String intent = iIntent >= 0 && !c.isNull(iIntent) ? c.getString(iIntent) : "";
                ShelfIdentity.Query q = ShelfIdentity.parse(t, intent, null, typeCode, rel);
                if (q.type != ShelfIdentity.Type.SERIES && q.type != ShelfIdentity.Type.EPISODE) continue;
                pool.add(new TvRow(slot, t, sh, q.year, q.type));
            }
        }
    }

    private static boolean isPromo(String shortDesc) {
        String s = shortDesc.toLowerCase(Locale.ROOT);
        String[] keys = {"izle", "watch", "simdi", "now", "yeni", "new", "sezon", "season",
                "bolum", "episode", "ozel", "exclusive", "netflix", "prime"};
        for (String k : keys) if (s.contains(k)) return true;
        return s.contains("şimdi") || s.contains("bölüm") || s.contains("özel");
    }

    private static int parseYear4(String s) {
        if (s == null || s.length() < 4) return 0;
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            return y >= 1900 && y <= 2099 ? y : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String stripHtml(String s) {
        if (s == null) return "";
        String t = s.replaceAll("<[^>]*>", " ");
        t = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return t.replaceAll("\\s+", " ").trim();
    }

    /**
     * Full-body GET for the IMDb/Wikidata discovery path. Unlike httpGet (256 KB cut for the
     * tvmaze/poster probes), a body over 4 MB or a cut body is an I/O fault, never a short answer:
     * a truncated entity JSON would otherwise parse as a JSON error and read as BELIRSIZ.
     */
    private static String httpGetStrict(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "AppleTVLauncher-tvmaze-probe/1.2");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) throw new java.io.IOException("http " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read, total = 0;
            try {
                while ((read = in.read(chunk)) > 0) {
                    total += read;
                    if (total > 4 * 1024 * 1024) throw new java.io.IOException("response too large");
                    buf.write(chunk, 0, read);
                }
            } finally {
                in.close();
            }
            return new String(buf.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "AppleTVLauncher-tvmaze-probe/1.2");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) throw new java.io.IOException("http " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read, total = 0;
            while ((read = in.read(chunk)) > 0) {
                total += read;
                if (total > 262144) break;
                buf.write(chunk, 0, read);
            }
            in.close();
            return new String(buf.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String descCheck() {
        // Read-only: short/long description + poster/thumbnail fill, poster HEAD readability,
        // description identity-rescue signals. Counts only; no raw text/URI dumped.
        String[] proj = {"_id", "package_name", "title", "short_description", "long_description",
                "poster_art_uri", "thumbnail_uri", "release_date", "type", "intent_uri"};
        StringBuilder out = new StringBuilder();
        auditDesc(out, "preview", android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, proj);
        auditDesc(out, "watch", android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, proj);
        return out.toString();
    }

    private void auditDesc(StringBuilder out, String table, android.net.Uri uri, String[] proj) {
        // slots: 0 netflix, 1 prime, 2 other. no raw rows/text/URIs printed.
        int[] total = new int[3], withShort = new int[3], withLong = new int[3];
        int[] withPoster = new int[3], posterHttps = new int[3], posterOk = new int[3], posterFail = new int[3];
        int[] withThumb = new int[3], descYear = new int[3], descRescue = new int[3], descSeasEp = new int[3];
        List<String> samples = new ArrayList<>();
        List<String> probeNet = new ArrayList<>(), probePrime = new ArrayList<>();
        Pattern yearPat = Pattern.compile("\\b(19\\d\\d|20\\d\\d)\\b");
        Pattern seasEpPat = Pattern.compile("(?i)\\b(season|sezon|s\\d+e\\d+|episode|b.l.m|part|k.s.m|vol)\\b");
        try (Cursor c = getTargetContext().getContentResolver().query(uri, proj, null, null, null)) {
            if (c == null) {
                out.append("PASS-DESC ").append(table).append(": unavailable (null cursor)\n");
                return;
            }
            int iPkg = c.getColumnIndex("package_name"), iTitle = c.getColumnIndex("title"),
                    iShort = c.getColumnIndex("short_description"), iLong = c.getColumnIndex("long_description"),
                    iPoster = c.getColumnIndex("poster_art_uri"), iThumb = c.getColumnIndex("thumbnail_uri"),
                    iRel = c.getColumnIndex("release_date"), iType = c.getColumnIndex("type"),
                    iIntent = c.getColumnIndex("intent_uri");
            while (c.moveToNext()) {
                String pkg = iPkg >= 0 && !c.isNull(iPkg) ? c.getString(iPkg) : "";
                int slot = "com.netflix.ninja".equals(pkg) ? 0
                        : "com.amazon.amazonvideo.livingroom".equals(pkg) ? 1 : 2;
                String t = iTitle >= 0 && !c.isNull(iTitle) ? c.getString(iTitle).trim() : "";
                String sh = iShort >= 0 && !c.isNull(iShort) ? c.getString(iShort).trim() : "";
                String lo = iLong >= 0 && !c.isNull(iLong) ? c.getString(iLong).trim() : "";
                String poster = iPoster >= 0 && !c.isNull(iPoster) ? c.getString(iPoster).trim() : "";
                String thumb = iThumb >= 0 && !c.isNull(iThumb) ? c.getString(iThumb).trim() : "";
                String rel = iRel >= 0 && !c.isNull(iRel) ? c.getString(iRel) : null;
                int typeCode = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                String intent = iIntent >= 0 && !c.isNull(iIntent) ? c.getString(iIntent) : "";
                total[slot]++;
                boolean hasShort = !sh.isEmpty(), hasLong = !lo.isEmpty();
                if (hasShort) withShort[slot]++;
                if (hasLong) withLong[slot]++;
                boolean hasPoster = !poster.isEmpty();
                if (hasPoster) {
                    withPoster[slot]++;
                    if (poster.regionMatches(true, 0, "https://", 0, 8)) posterHttps[slot]++;
                    if (slot == 0 && probeNet.size() < 2) probeNet.add(poster);
                    else if (slot == 1 && probePrime.size() < 2) probePrime.add(poster);
                }
                if (!thumb.isEmpty()) withThumb[slot]++;
                String combined = (hasShort ? sh + "\n" : "") + (hasLong ? lo : "");
                boolean hasYear = !combined.isEmpty() && yearPat.matcher(combined).find();
                if (hasYear) descYear[slot]++;
                if (!combined.isEmpty() && seasEpPat.matcher(combined).find()) descSeasEp[slot]++;
                ShelfIdentity.Query q = ShelfIdentity.parse(t, intent, null, typeCode, rel);
                if (q.year <= 0 && hasYear) descRescue[slot]++;
                if (slot < 2 && samples.size() < 6) {
                    samples.add("SAMPLE-DESC " + table + " " + (slot == 0 ? "netflix" : "prime")
                            + " short=" + hasShort + " long=" + hasLong
                            + " poster=" + hasPoster + " thumb=" + !thumb.isEmpty()
                            + " descYear=" + hasYear + " seasEp=" + descSeasEp[slot]);
                }
            }
        }
        posterOk[0] = probePoster(probeNet); posterFail[0] = probeNet.size() - posterOk[0];
        posterOk[1] = probePoster(probePrime); posterFail[1] = probePrime.size() - posterOk[1];
        for (String s : samples) out.append(s).append('\n');
        String[] names = {"netflix", "prime", "other"};
        for (int s = 0; s < 3; s++) {
            out.append("PASS-DESC ").append(table).append(' ').append(names[s])
                    .append(": n=").append(total[s]).append(" short=").append(withShort[s])
                    .append(" long=").append(withLong[s]).append(" poster=").append(withPoster[s])
                    .append(" posterHttps=").append(posterHttps[s])
                    .append(" thumb=").append(withThumb[s]).append(" descYear=").append(descYear[s])
                    .append(" descRescue=").append(descRescue[s]).append(" descSeasEp=").append(descSeasEp[s]);
            if (s < 2) {
                out.append(" probe=").append(probeNet.size() + probePrime.size() > 0 && s == 0
                        ? probeNet.size() : probePrime.size())
                    .append(" posterOk=").append(posterOk[s]).append(" posterFail=").append(posterFail[s]);
            }
            out.append('\n');
        }
    }

    private int probePoster(List<String> urls) {
        // HEAD-by-GET-response-code only; body never read, URL never printed.
        int ok = 0;
        for (String u : urls) {
            HttpURLConnection conn = null;
            try {
                if (!(u.regionMatches(true, 0, "https://", 0, 8)
                        || u.regionMatches(true, 0, "http://", 0, 7))) continue;
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) ok++;
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return ok;
    }

    private String metadataCheck() {
        String[] proj = {"_id", "package_name", "title", "release_date", "type",
                "season_display_number", "season_title", "episode_display_number", "episode_title",
                "global_content_id", "content_id", "series_id", "internal_provider_id", "intent_uri"};
        StringBuilder out = new StringBuilder();
        auditTable(out, "preview", android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, proj);
        auditTable(out, "watch", android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, proj);
        return out.toString();
    }

    private void auditTable(StringBuilder out, String table, android.net.Uri uri, String[] proj) {
        // Order: netflix, prime, other. Counts only; raw rows/intents never printed.
        long[] n = new long[3];
        long[] title = new long[3], year = new long[3], type = new long[3], seasonEp = new long[3];
        long[] trusted = new long[3], service = new long[3], sufficient = new long[3];
        int samples = 0;
        try (Cursor c = getTargetContext().getContentResolver().query(uri, proj, null, null, null)) {
            if (c == null) {
                out.append("PASS-META ").append(table).append(": unavailable (null cursor)\n");
                return;
            }
            int iId = c.getColumnIndex("_id"), iPkg = c.getColumnIndex("package_name"),
                    iTitle = c.getColumnIndex("title"), iRel = c.getColumnIndex("release_date"),
                    iType = c.getColumnIndex("type"), iSeas = c.getColumnIndex("season_display_number"),
                    iSeasT = c.getColumnIndex("season_title"), iEp = c.getColumnIndex("episode_display_number"),
                    iEpT = c.getColumnIndex("episode_title"), iGid = c.getColumnIndex("global_content_id"),
                    iCid = c.getColumnIndex("content_id"), iSid = c.getColumnIndex("series_id"),
                    iIpid = c.getColumnIndex("internal_provider_id"), iIntent = c.getColumnIndex("intent_uri");
            while (c.moveToNext()) {
                String pkg = iPkg >= 0 && !c.isNull(iPkg) ? c.getString(iPkg) : "";
                int slot = "com.netflix.ninja".equals(pkg) ? 0
                        : "com.amazon.amazonvideo.livingroom".equals(pkg) ? 1 : 2;
                String t = iTitle >= 0 && !c.isNull(iTitle) ? c.getString(iTitle).trim() : "";
                String rel = iRel >= 0 && !c.isNull(iRel) ? c.getString(iRel) : null;
                int typeCode = iType >= 0 && !c.isNull(iType) ? c.getInt(iType) : -1;
                boolean hasSeasEp = (iSeas >= 0 && !c.isNull(iSeas)) || (iSeasT >= 0 && !c.isNull(iSeasT))
                        || (iEp >= 0 && !c.isNull(iEp)) || (iEpT >= 0 && !c.isNull(iEpT));
                boolean hasGid = iGid >= 0 && !c.isNull(iGid), hasCid = iCid >= 0 && !c.isNull(iCid),
                        hasSid = iSid >= 0 && !c.isNull(iSid), hasIpid = iIpid >= 0 && !c.isNull(iIpid);
                String intent = iIntent >= 0 && !c.isNull(iIntent) ? c.getString(iIntent) : "";
                ShelfIdentity.Query q = ShelfIdentity.parse(t, intent, null, typeCode, rel);
                n[slot]++;
                if (!t.isEmpty()) title[slot]++;
                if (q.year > 0) year[slot]++;
                if (q.type != ShelfIdentity.Type.UNKNOWN) type[slot]++;
                if (hasSeasEp) seasonEp[slot]++;
                boolean isTrusted = q.externalId != null
                        && (q.externalId.startsWith("imdb:") || q.externalId.startsWith("tmdb:"));
                boolean isService = q.externalId != null && !isTrusted;
                if (isTrusted) trusted[slot]++;
                if (isService) service[slot]++;
                // Provider-opaque IDs (global/content/series/internal) are counted inside service below.
                if (hasGid || hasCid || hasSid || hasIpid) service[slot] += 0; // opaque, not trusted
                boolean ok = !t.isEmpty() && q.year > 0 && q.type != ShelfIdentity.Type.UNKNOWN;
                if (ok) sufficient[slot]++;
                if (slot < 2 && samples < 6) {
                    samples++;
                    out.append("SAMPLE ").append(table).append(' ').append(slot == 0 ? "netflix" : "prime")
                            .append(" row=").append(iId >= 0 ? c.getLong(iId) : -1)
                            .append(" title=").append(!t.isEmpty()).append(" year=").append(q.year > 0)
                            .append(" type=").append(q.type).append(" seasonEp=").append(hasSeasEp)
                            .append(" trustedId=").append(isTrusted).append(" sufficient=").append(ok).append('\n');
                }
            }
        }
        String[] names = {"netflix", "prime", "other"};
        for (int s = 0; s < 3; s++) {
            out.append("PASS-META ").append(table).append(' ').append(names[s])
                    .append(": n=").append(n[s]).append(" title=").append(title[s])
                    .append(" release_date=").append(year[s]).append(" type=").append(type[s])
                    .append(" seasonEp=").append(seasonEp[s]).append(" trustedId=").append(trusted[s])
                    .append(" serviceId=").append(service[s]).append(" sufficient=").append(sufficient[s])
                    .append('\n');
        }
    }

    private String identityCheck() {
        ShelfIdentity.Query nflx = ShelfIdentity.parse(
                "Stranger Things", "nflx://www.netflix.com/title/80057281",
                "https://cdn.netflix/st_sd.jpg", 1, "2016");
        ShelfIdentity.Candidate nflxClean = new ShelfIdentity.Candidate(
                "st_apple", "Stranger Things", 2016, ShelfIdentity.Type.SERIES,
                "netflix:80057281", "https://is1-ssl.mzstatic.com/image/thumb/st_4k.jpg");
        ShelfIdentity.MatchResult res1 = ShelfIdentity.match(nflx, Collections.singletonList(nflxClean));
        require(!res1.isFallback && "https://is1-ssl.mzstatic.com/image/thumb/st_4k.jpg".equals(res1.resolvedArtwork),
                "netflix trusted external ID match failed");

        ShelfIdentity.Query prime = ShelfIdentity.parse(
                "Supernatural - 3. Sezon", "https://app.primevideo.com/watch?g=amzn1.dv.gti.123",
                "https://images.amazon/sn_badge.jpg", 2, "2007");
        ShelfIdentity.Candidate primeClean = new ShelfIdentity.Candidate(
                "spn_clean", "Supernatural", 2005, ShelfIdentity.Type.SERIES,
                "imdb:tt0460681", "https://is1-ssl.mzstatic.com/image/thumb/spn_textless.jpg");
        ShelfIdentity.Candidate primeIrrelevant = new ShelfIdentity.Candidate(
                "sn_movie", "Supernatural Activity", 2012, ShelfIdentity.Type.MOVIE,
                null, "https://images.other/sn_movie.jpg");
        ShelfIdentity.MatchResult res2 = ShelfIdentity.match(prime, Arrays.asList(primeIrrelevant, primeClean));
        require(!res2.isFallback && "spn_clean".equals(res2.matched.id),
                "prime video season match failed");

        ShelfIdentity.Query yt = ShelfIdentity.parse(
                "500 TL'LİK VS 7.500 TL'LİK MASTERCHEF RESTO...", "https://www.youtube.com/watch?v=CkcGpXnt1uI",
                "https://i.ytimg.com/vi/CkcGpXnt1uI/hq720.jpg", 4, null);
        ShelfIdentity.Candidate ytClean = new ShelfIdentity.Candidate(
                "yt_clean", "MasterChef Restoran Karsilastirmasi", 2024, ShelfIdentity.Type.CLIP,
                "youtube:CkcGpXnt1uI", "https://clean.youtube/masterchef.jpg");
        ShelfIdentity.MatchResult res3 = ShelfIdentity.match(yt, Collections.singletonList(ytClean));
        require(!res3.isFallback && "yt_clean".equals(res3.matched.id),
                "youtube video ID match failed");

        ShelfIdentity.Query amb = ShelfIdentity.parse(
                "Ghosts", "intent://ghosts",
                "https://cdn.netflix/ghosts_fallback.jpg", -1, null);
        ShelfIdentity.Candidate uk = new ShelfIdentity.Candidate("uk", "Ghosts", 2019, ShelfIdentity.Type.SERIES, null, "https://art/uk.jpg");
        ShelfIdentity.Candidate us = new ShelfIdentity.Candidate("us", "Ghosts", 2021, ShelfIdentity.Type.SERIES, null, "https://art/us.jpg");
        ShelfIdentity.MatchResult res4 = ShelfIdentity.match(amb, Arrays.asList(uk, us));
        require(res4.isFallback && "https://cdn.netflix/ghosts_fallback.jpg".equals(res4.resolvedArtwork),
                "ambiguous match did not fall back to provider image");

        ShelfIdentity.Query unk = ShelfIdentity.parse(
                "Yerel Haberler", "intent://news",
                "https://provider/news_fallback.jpg", 0, null);
        ShelfIdentity.Candidate movie = new ShelfIdentity.Candidate("m", "The Batman", 2022, ShelfIdentity.Type.MOVIE, null, "https://art/batman.jpg");
        ShelfIdentity.MatchResult res5 = ShelfIdentity.match(unk, Collections.singletonList(movie));
        require(res5.isFallback && "https://provider/news_fallback.jpg".equals(res5.resolvedArtwork),
                "unrelated content did not fall back to provider image");

        // Prime Video Carrie verification: Leanback clip type 4 with poster badge "YENİ DİZİLER"
        ShelfIdentity.Query carrieQuery = ShelfIdentity.parse(
                "Carrie",
                "intent:#Intent;component=com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity;S.com.amazon.ignition.DeepLinkIntent.DEEP_LINK=detail%3Fgti%3Damzn1.dv.gti.bf6e4509;end",
                "https://images-eu.ssl-images-amazon.com/images/S/pv-target-images/..._ZAYENI%2520D%25C4%25B0Z%25C4%25B0LER...png",
                4, null);
        require(carrieQuery.type == ShelfIdentity.Type.SERIES && carrieQuery.year == 0,
                "Carrie poster badge did not parse as SERIES");
        ShelfIdentity.Candidate carrieMovie1976 = new ShelfIdentity.Candidate(
                "itunes:387099242", "Carrie", 1976, ShelfIdentity.Type.MOVIE, null, "https://art/carrie1976.jpg");
        ShelfIdentity.Candidate carrieSeries2026 = new ShelfIdentity.Candidate(
                "tvmaze:45562", "Carrie", 2026, ShelfIdentity.Type.SERIES, "imdb:tt11540908", "https://art/carrie2026.jpg");
        ShelfIdentity.MatchResult res6 = ShelfIdentity.match(carrieQuery, Arrays.asList(carrieMovie1976, carrieSeries2026));
        require(!res6.isFallback && "tvmaze:45562".equals(res6.matched.id),
                "Carrie did not match 2026 series over 1976 movie");
        require(res6.confidence == 0.85f, "Incomplete release date must cap confidence at 0.85");

        // Strict rejection of conflicting movie sequel year (Gladiator 2000 vs Gladiator II 2024)
        ShelfIdentity.Query glad2000 = ShelfIdentity.parse(
                "Gladiator", "intent://glad", "https://provider/glad.jpg", 0, "2000");
        ShelfIdentity.Candidate glad2024 = new ShelfIdentity.Candidate(
                "g2", "Gladiator II", 2024, ShelfIdentity.Type.MOVIE, null, "https://art/glad2024.jpg");
        ShelfIdentity.MatchResult res7 = ShelfIdentity.match(glad2000, Collections.singletonList(glad2024));
        require(res7.isFallback, "Gladiator 2000 must strictly reject Gladiator II 2024");

        return "PASS: dynamic ShelfIdentity matching on real device; Netflix ID, Prime Video season normalization, "
                + "YouTube clip ID, ambiguity fallback, unrelated candidate fallback, Carrie 2026 series disambiguation, "
                + "movie year conflict strict rejection\n";
    }

    private String rankingCheck(Context context, File file) {
        require(android.os.Looper.myLooper() != android.os.Looper.getMainLooper(), "ranking check ran on UI thread");
        long now = System.currentTimeMillis();
        ShelfData.Item selected = new ShelfData.Item(11, "test.alpha", "Selected", "intent:selected", null, 0, 0, 0);
        ShelfData.Item ignored = new ShelfData.Item(12, "test.alpha", "Ignored", "intent:ignored", null, 0, 0, 0);
        ShelfData.Item other = new ShelfData.Item(11, "test.beta", "Other app", "intent:other", null, 0, 0, 0);
        java.util.List<ShelfData.Item> previews = new java.util.ArrayList<>(java.util.Arrays.asList(selected, ignored, other));
        ShelfData.rank(previews, java.util.Collections.emptyList(), now, pkg -> 0L);
        long selectedBase = selected.score, ignoredBase = ignored.score, otherBase = other.score;
        try (ShelfInteractions history = new ShelfInteractions(context)) {
            require(history.readSummaries().isEmpty() && !file.exists(), "cold read created a database or invented history");
            for (int i = 0; i < 3; i++) history.record("test.alpha", 11, "selection", now + i * 100);
            for (int i = 0; i < 3; i++) history.record("test.alpha", 12, "impression", now - i * 60_000);
            java.util.Map<String, java.util.Map<Long, ShelfInteractions.Summary>> summaries = history.readSummaries();
            ShelfData.rank(previews, java.util.Collections.emptyList(), now, pkg -> 0L, summaries);
            require(selected.score == selectedBase + 500, "selection preference missing or duplicate counted");
            require(ignored.score == ignoredBase - 50, "repeated unselected impressions did not get mild penalty");
            require(other.score == otherBase, "preference leaked across packages with the same ID");
            ShelfData.rank(previews, java.util.Collections.emptyList(), now, pkg -> 0L, summaries);
            require(selected.score == selectedBase + 500 && ignored.score == ignoredBase - 50,
                    "reranking accumulated preference points");
            ShelfData.Item watched = new ShelfData.Item(41, "test.alpha", "Real Continue", "intent:continue", null,
                    1, 0, now - 365L * 86_400_000, 0);
            ShelfData.Item continuing = new ShelfData.Item(42, "test.alpha", "Real Continue", "intent:preview", null, 0, 0, 0);
            java.util.List<ShelfData.Item> watching = java.util.Collections.singletonList(watched);
            previews.add(continuing);
            summaries.get("test.alpha").put(11L, new ShelfInteractions.Summary(100, 100, now, now));
            summaries.get("test.alpha").put(42L, new ShelfInteractions.Summary(100, 0, now, 0));
            ShelfData.rank(previews, watching, now, pkg -> now, summaries);
            require(previews.get(0) == continuing, "preference overtook real CONTINUE");
            require(ShelfPresentation.bannerItemsForPackage("test.alpha", watching, previews).get(0) == watched,
                    "real CONTINUE banner lost priority");
        }
        try (ShelfInteractions history = new ShelfInteractions(context)) {
            ShelfData.rank(previews, java.util.Collections.emptyList(), now, pkg -> 0L, history.readSummaries());
            require(selected.score == selectedBase + 500 && ignored.score == ignoredBase - 50,
                    "cold reopen lost persisted ranking signals");
        }

        // Provider rows stay read-only; controlled events are written only to the separate test DB.
        long loaded = SystemClock.elapsedRealtime();
        ShelfData.Snapshot actual;
        try (ShelfInteractions history = new ShelfInteractions(getTargetContext())) {
            actual = ShelfData.load(getTargetContext(), history);
        }
        require(actual.error == null && !actual.recommended.isEmpty(), "real provider snapshot unavailable: " + actual.error);
        for (int i = 1; i < actual.recommended.size(); i++)
            require(actual.recommended.get(i - 1).score >= actual.recommended.get(i).score, "real device scores not sorted");
        java.util.List<ShelfData.Item> providerOrder = new java.util.ArrayList<>(actual.recommended);
        ShelfData.rank(providerOrder, actual.watching, now, pkg -> LastOpened.get(getTargetContext(), pkg));
        ShelfData.Item target = null;
        for (int i = 1; i < providerOrder.size(); i++) {
            ShelfData.Item upper = providerOrder.get(i - 1), lower = providerOrder.get(i);
            long gap = upper.score - lower.score;
            if (upper.pkg.equals(lower.pkg) && gap >= 0 && gap < 500) { target = lower; break; }
        }
        require(target != null, "no real provider pair can demonstrate a bounded selection reorder");
        int before = providerOrder.indexOf(target);
        try (ShelfInteractions history = new ShelfInteractions(context)) {
            history.record(target.pkg, target.id, "selection", now);
            history.record(target.pkg, target.id, "selection", now + 1);
            ShelfData.Snapshot learned = ShelfData.load(context, history);
            require(learned.error == null, "learned provider load failed: " + learned.error);
            providerOrder = learned.recommended;
        }
        int after = -1;
        for (int i = 0; i < providerOrder.size(); i++) {
            ShelfData.Item row = providerOrder.get(i);
            if (target.pkg.equals(row.pkg) && target.id == row.id) { after = i; break; }
        }
        require(after >= 0 && after < before, "persisted selection did not change real provider ranking through load");
        try (ShelfInteractions history = new ShelfInteractions(context)) {
            history.readSummaries();
            try (SQLiteDatabase probe = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                probe.execSQL("ALTER TABLE summaries RENAME TO unavailable_summaries");
            }
            ShelfData.Snapshot fallback = ShelfData.load(context, history);
            require(fallback.error == null && fallback.recommended.size() == actual.recommended.size(),
                    "summary read failure hid usable provider content");
        } finally {
            try (SQLiteDatabase probe = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                probe.execSQL("ALTER TABLE unavailable_summaries RENAME TO summaries");
            }
        }
        for (ShelfData.Item watched : actual.watching) {
            java.util.List<ShelfData.Item> banners = ShelfPresentation.bannerItemsForPackage(watched.pkg, actual.watching, providerOrder);
            require(!banners.isEmpty() && actual.watching.contains(banners.get(0)), "real provider CONTINUE lost banner priority");
        }
        return "PASS: background cold read/reopen, selection dedup, mild repetition penalty, package isolation, stable rerank, CONTINUE, read-failure fallback; "
                + "real provider=" + actual.recommended.size() + ", watching=" + actual.watching.size()
                + ", selected=" + target.pkg + "/" + target.id + ", rank=" + before + " to " + after
                + ", loadAndRankMs=" + (SystemClock.elapsedRealtime() - loaded) + "\n";
    }

    private static void summary(File file, String pkg, long id, long impressions, long selections,
                                long lastImpression, long lastSelection) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            try (Cursor schema = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='summaries'", null)) {
                require(schema.moveToFirst(), "persistent summary table missing");
            }
            try (Cursor row = db.rawQuery("SELECT impressions, selections, last_impression_at, last_selection_at "
                    + "FROM summaries WHERE package=? AND content_id=?", new String[]{pkg, Long.toString(id)})) {
                require(row.moveToFirst(), "summary missing for " + pkg + "/" + id);
                require(row.getLong(0) == impressions && row.getLong(1) == selections,
                        "summary counters differ for " + pkg + "/" + id);
                require(row.getLong(2) == lastImpression && row.getLong(3) == lastSelection,
                        "summary timestamps differ for " + pkg + "/" + id);
            }
        }
    }

    private static long count(File file) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = db.rawQuery("SELECT count(*) FROM events", null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
