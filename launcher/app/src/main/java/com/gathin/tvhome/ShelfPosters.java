package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 8 MiB decoded RAM, 30 MiB encoded private disk. Never persists program metadata. */
final class ShelfPosters {
    static final int MAX_RAM_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private final Context context;
    private final ShelfPosterStore disk;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.DiscardOldestPolicy());
    private volatile int generation;
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(MAX_RAM_BYTES) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getAllocationByteCount(); }
    };

    ShelfPosters(Context context) {
        this.context = context.getApplicationContext();
        disk = new ShelfPosterStore(new File(context.getCacheDir(), "shelf-posters"), ShelfPosterStore.LIMIT_BYTES);
    }

    void load(ImageView view, String raw) {
        load(view, raw, false, () -> true);
    }

    void load(ImageView view, String raw, boolean keepCurrent, java.util.function.BooleanSupplier valid) {
        Object request;
        if (keepCurrent) {
            request = view.getTag();
        } else {
            view.setImageDrawable(null);
            request = new Object();
            view.setTag(request);
        }
        if (raw == null || raw.length() > 4096 || worker.isShutdown()) return;
        final String targetUrl = ShelfLogic.upgradeImageUrl(raw);
        Bitmap cached = cache.get(targetUrl);
        if (cached != null) {
            if (valid.getAsBoolean()) {
                if (keepCurrent) view.setTag(new Object());
                view.setImageBitmap(cached);
            }
            return;
        }
        WeakReference<ImageView> target = new WeakReference<>(view);
        int current = generation;
        worker.execute(() -> {
            if (target.get() == null || current != generation) return;
            byte[] bytes = null;
            try { bytes = disk.read(targetUrl); }
            catch (IOException ignored) { /* An expendable cache miss can use the original provider URI. */ }
            boolean cachedOnDisk = bytes != null;
            if (bytes == null) bytes = read(targetUrl);
            if (bytes == null || worker.isShutdown() || current != generation) return;
            Bitmap bitmap = decode(bytes);
            if (bitmap == null) return;
            if (!cachedOnDisk) {
                try { disk.put(targetUrl, bytes); }
                catch (IOException ignored) { Log.w("TvHome.Posters", "Disk cache unavailable; keeping RAM only"); }
            }
            synchronized (cache) {
                if (current != generation) return;
                cache.put(targetUrl, bitmap);
            }
            main.post(() -> {
                ImageView image = target.get();
                if (!worker.isShutdown() && current == generation && image != null && image.getTag() == request
                        && valid.getAsBoolean()) {
                    if (keepCurrent) image.setTag(new Object());
                    image.setImageBitmap(bitmap);
                }
            });
        });
    }

    private byte[] read(String raw) {
        Uri uri = Uri.parse(raw);
        HttpURLConnection connection = null;
        try {
            InputStream stream;
            if (ShelfLogic.httpsImage(raw)) {
                connection = (HttpURLConnection) new URL(raw).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_IMAGE_BYTES) return null;
                stream = connection.getInputStream();
            } else if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                stream = context.getContentResolver().openInputStream(uri);
            } else return null;
            if (stream == null) return null;
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n, count = 0;
                while ((n = input.read(buffer)) != -1) {
                    count += n;
                    if (count > MAX_IMAGE_BYTES) return null;
                    output.write(buffer, 0, n);
                }
                return output.toByteArray();
            }
        } catch (Exception ignored) {
            return null; // Keep the real title and neutral artwork when the provider image is unavailable.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static int sampleSize(int outWidth, int outHeight) {
        int inSampleSize = 1;
        while (outWidth / inSampleSize > 1280 || outHeight / inSampleSize > 720) inSampleSize *= 2;
        return inSampleSize;
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = sampleSize(options.outWidth, options.outHeight);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    void clear() {
        synchronized (cache) { generation++; cache.evictAll(); }
        worker.getQueue().clear();
        if (!worker.isShutdown()) worker.execute(() -> {
            try { disk.clear(); }
            catch (IOException ignored) { Log.w("TvHome.Posters", "Could not clear expendable poster cache"); }
        });
    }

    int memoryBytes() { return cache.size(); }

    void close() { generation++; worker.shutdownNow(); cache.evictAll(); }
}
