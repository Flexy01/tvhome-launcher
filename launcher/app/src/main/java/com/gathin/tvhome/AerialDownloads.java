package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Background menu > Download aerials: Apple's aerial catalog, fetched from Apple, and downloads straight onto the
 * TV, so the moving backgrounds need no computer. The same files and names as tools/aerials.py: files/aerials/
 * <name>.mov and an entry in files/aerials/aerials.json, which AerialBackdrop.catalog lists.
 *
 * Apple serves the aerials from sylvan.apple.com, whose certificate chains to Apple Root CA, a root Android TVs
 * do not all carry. Rather than skipping checks, connections to Apple trust exactly that root, bundled as
 * res/raw/apple_root_ca.pem (SHA-256 B0:B1:73:0E:...:F0:24, as Apple publishes it).
 */
final class AerialDownloads {
    private AerialDownloads() { }

    private static final String TAG = "TvHome.Aerials";
    private static final String CATALOG = "https://sylvan.apple.com/Aerials/resources-16.tar";
    /** Room left free on the TV after a download, so apps still have space to update. */
    private static final long KEEP_FREE = 500L << 20;

    static final class Aerial {
        final String title, url, file;

        Aerial(String title, String url) {
            this.title = title;
            this.url = url;
            this.file = fileName(title);
        }
    }

    interface Progress {
        /** 0-100, or -1 when it failed (message says why), 101 when done. On the main thread. */
        void update(int percent, String message);
    }

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static SSLSocketFactory apple;
    private static List<Aerial> cached;
    /** The one download in progress, by file name, and its last percentage, so a reopened menu shows it. */
    static volatile String downloading;
    static volatile int downloadPercent;

    static File dir(Context ctx) {
        File d = ctx.getExternalFilesDir("aerials");
        if (d != null && !d.isDirectory()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    static boolean onTv(Context ctx, Aerial a) {
        File d = dir(ctx);
        return d != null && new File(d, a.file).isFile();
    }

    /** Same names as tools/aerials.py: "Hawaii 2" -> hawaii_2.mov. */
    static String fileName(String title) {
        StringBuilder b = new StringBuilder();
        for (char c : title.toLowerCase(java.util.Locale.ROOT).toCharArray()) b.append(Character.isLetterOrDigit(c) ? c : '_');
        String s = b.toString().replaceAll("^_+|_+$", "");
        return s + ".mov";
    }

    /** Apple's catalog, in Apple's order, places with several flights numbered ("Hawaii 2"); cached once loaded. */
    static void loadCatalog(Context ctx, java.util.function.Consumer<List<Aerial>> done) {
        if (cached != null) {
            done.accept(cached);
            return;
        }
        Context app = ctx.getApplicationContext();
        worker.execute(() -> {
            List<Aerial> list = null;
            try {
                list = parseCatalog(readTarEntry(open(app, CATALOG), "entries.json"));
                cached = list;
            } catch (Exception e) {
                Log.w(TAG, "catalog", e);
            }
            final List<Aerial> result = list;
            main.post(() -> done.accept(result));
        });
    }

    /** Downloads one aerial into files/aerials and adds it to aerials.json; one at a time. */
    static void download(Context ctx, Aerial a, Progress progress) {
        if (downloading != null) {
            progress.update(-1, ctx.getString(R.string.aerials_busy));
            return;
        }
        downloading = a.file;
        downloadPercent = 0;
        Context app = ctx.getApplicationContext();
        worker.execute(() -> {
            File d = dir(app);
            File part = d == null ? null : new File(d, a.file + ".part");
            try {
                if (d == null) throw new IOException("no storage");
                HttpsURLConnection c = open(app, a.url);
                long total = c.getContentLengthLong();
                long free = new StatFs(d.getPath()).getAvailableBytes();
                if (total > 0 && free - total < KEEP_FREE) {
                    c.disconnect();
                    String msg = app.getString(R.string.aerials_no_space, (total >> 20), (free >> 20));
                    main.post(() -> progress.update(-1, msg));
                    return;
                }
                long done = 0;
                int last = -1;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        if (pct != last) {
                            last = pct;
                            downloadPercent = pct;
                            final int p = pct;
                            main.post(() -> progress.update(p, null));
                        }
                    }
                }
                File dest = new File(d, a.file);
                if (!part.renameTo(dest)) throw new IOException("rename failed");
                addToIndex(d, a);
                main.post(() -> progress.update(101, null));
            } catch (Exception e) {
                Log.w(TAG, "download " + a.title, e);
                if (part != null) //noinspection ResultOfMethodCallIgnored
                    part.delete();
                main.post(() -> progress.update(-1, app.getString(R.string.aerials_failed)));
            } finally {
                downloading = null;
            }
        });
    }

    /** Removes an aerial and its aerials.json entry. */
    static void remove(Context ctx, Aerial a) {
        File d = dir(ctx);
        if (d == null) return;
        //noinspection ResultOfMethodCallIgnored
        new File(d, a.file).delete();
        try {
            JSONArray old = readIndex(d), keep = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                if (!a.file.equals(o.optString("file"))) keep.put(o);
            }
            writeIndex(d, keep);
        } catch (Exception e) {
            Log.w(TAG, "index", e);
        }
    }

    // ---- Apple connection ---------------------------------------------------------------------

    private static synchronized SSLSocketFactory appleTls(Context ctx) throws Exception {
        if (apple != null) return apple;
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate root;
        try (InputStream in = ctx.getResources().openRawResource(R.raw.apple_root_ca)) {
            root = cf.generateCertificate(in);
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("apple-root", root);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, tmf.getTrustManagers(), null);
        apple = tls.getSocketFactory();
        return apple;
    }

    private static HttpsURLConnection open(Context ctx, String url) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setSSLSocketFactory(appleTls(ctx));
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        return c;
    }

    // ---- catalog --------------------------------------------------------------------------------

    /** The named file out of a plain tar stream (512-byte headers, octal sizes). */
    private static byte[] readTarEntry(HttpsURLConnection c, String name) throws IOException {
        try (InputStream in = c.getInputStream()) {
            byte[] header = new byte[512];
            while (readFully(in, header)) {
                String entry = new String(header, 0, 100, StandardCharsets.US_ASCII).trim().replace("\0", "");
                if (entry.isEmpty()) break;
                long size = Long.parseLong(new String(header, 124, 11, StandardCharsets.US_ASCII).trim().replace("\0", ""), 8);
                long padded = (size + 511) / 512 * 512;
                if (entry.equals(name) || entry.endsWith("/" + name)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
                    byte[] buf = new byte[8192];
                    long left = size;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("short tar");
                        out.write(buf, 0, n);
                        left -= n;
                    }
                    return out.toByteArray();
                }
                skipFully(in, padded);
            }
        }
        throw new IOException(name + " not in catalog");
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("short tar");
            n -= r;
        }
    }

    private static List<Aerial> parseCatalog(byte[] json) throws Exception {
        JSONArray assets = new JSONObject(new String(json, StandardCharsets.UTF_8)).getJSONArray("assets");
        Map<String, Aerial> byName = new LinkedHashMap<>();
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String url = a.optString("url-1080-SDR", a.optString("url-1080-H264", ""));
            String name = a.optString("accessibilityLabel", "").trim();
            if (url.isEmpty() || name.isEmpty()) continue;
            String key = name;
            for (int n = 2; byName.containsKey(key); n++) key = name + " " + n;
            byName.put(key, new Aerial(key, url));
        }
        List<Aerial> out = new ArrayList<>(byName.values());
        out.sort((x, y) -> x.title.compareToIgnoreCase(y.title));
        return out;
    }

    // ---- aerials.json ---------------------------------------------------------------------------

    private static synchronized void addToIndex(File d, Aerial a) throws Exception {
        JSONArray index = readIndex(d);
        for (int i = 0; i < index.length(); i++) {
            if (a.file.equals(index.getJSONObject(i).optString("file"))) return;
        }
        index.put(new JSONObject().put("file", a.file).put("title", a.title));
        writeIndex(d, index);
    }

    private static JSONArray readIndex(File d) {
        File f = new File(d, "aerials.json");
        if (!f.isFile()) return new JSONArray();
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            return new JSONArray(text);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void writeIndex(File d, JSONArray index) throws Exception {
        File tmp = new File(d, "aerials.json.tmp");
        Files.write(tmp.toPath(), index.toString(2).getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(new File(d, "aerials.json"))) throw new IOException("rename failed");
    }
}
