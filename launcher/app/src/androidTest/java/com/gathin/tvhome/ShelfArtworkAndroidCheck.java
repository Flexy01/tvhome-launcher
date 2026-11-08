package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone on-device check. Run with app_process using the app and androidTest APKs on CLASSPATH. */
public final class ShelfArtworkAndroidCheck {
    public static void main(String[] args) throws Exception {
        require(args.length == 1, "supply one public Fanart image URL");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Method decode = ShelfArtworkResolver.class.getDeclaredMethod("decodeFanartImage", byte[].class);
        decode.setAccessible(true);
        Method fetch = ShelfArtworkResolver.class.getDeclaredMethod("fetchFanartImage", String.class);
        fetch.setAccessible(true);

        byte[] fixture = image(3840, 2160);
        AtomicReference<ShelfArtworkResolver.DecodedImage> decoded = new AtomicReference<>();
        ShelfArtworkResolver.BackdropScorer scorer = ShelfArtworkResolver.scoringFanartScorer(
                url -> fixture, bytes -> {
                    ShelfArtworkResolver.DecodedImage probe =
                            (ShelfArtworkResolver.DecodedImage) decode.invoke(null, (Object) bytes);
                    decoded.set(probe);
                    return probe;
                }, 0);
        require(scorer.score("fixture", 0) >= 0, "real bitmap scoring failed");
        ShelfArtworkResolver.DecodedImage probe = decoded.get();
        require(probe != null && probe.probeWidth <= 960 && probe.probeHeight <= 540,
                "decoded bitmap exceeds the 960x540 budget");
        Field bitmapField = probe.source.getClass().getDeclaredField("val$bitmap");
        bitmapField.setAccessible(true);
        Bitmap bitmap = (Bitmap) bitmapField.get(probe.source);
        require(bitmap.isRecycled(), "scoring did not recycle the bitmap");
        require(decode.invoke(null, (Object) new byte[]{1, 2, 3}) == null, "corrupt image accepted");
        ShelfArtworkResolver.BackdropScorer corrupt = ShelfArtworkResolver.scoringFanartScorer(
                url -> new byte[]{1, 2, 3}, bytes ->
                        (ShelfArtworkResolver.DecodedImage) decode.invoke(null, (Object) bytes), 0);
        require(corrupt.score("fixture", 0) < 0, "corrupt image received a score");
        require(fetch.invoke(null, "http://assets.fanart.tv/image.jpg") == null, "insecure URL accepted");

        String asset = args[0];
        byte[] downloaded = (byte[]) fetch.invoke(null, asset);
        require(downloaded != null && downloaded.length > 0 && downloaded.length <= 4 * 1024 * 1024,
                "real HTTPS asset download failed or exceeded the byte budget");
        ShelfIdentity.Query query = new ShelfIdentity.Query("Dune", 2021,
                ShelfIdentity.Type.MOVIE, "imdb:tt1160419", "provider-poster");
        AtomicReference<Thread> requestThread = new AtomicReference<>();
        CompletableFuture<String> chosen = ShelfArtworkResolver.selectFanartBackdropAsync(query, path -> {
            requestThread.set(Thread.currentThread());
            return response(asset);
        });
        require(asset.equals(chosen.get(60, TimeUnit.SECONDS)), "production selection failed");
        require(requestThread.get() != null && requestThread.get() != Looper.getMainLooper().getThread(),
                "production request ran on the main thread");
        CompletableFuture<String> failed = ShelfArtworkResolver.selectFanartBackdropAsync(query,
                path -> response("https://assets.fanart.tv/"));
        require("provider-poster".equals(failed.get(60, TimeUnit.SECONDS)), "failed image did not fall back");
        System.out.println("PASS: Android bounded decode, pixel scoring, bitmap recycle, HTTPS, worker, fallback");
    }

    private static String response(String url) {
        return "{\"name\":\"Dune\",\"imdb_id\":\"tt1160419\",\"moviebackground\":["
                + "{\"id\":\"1\",\"url\":\"" + url + "\",\"lang\":\"\",\"likes\":\"1\","
                + "\"width\":\"1920\",\"height\":\"1080\"}]}";
    }

    private static byte[] image(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        try {
            new Canvas(bitmap).drawColor(Color.DKGRAY);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            require(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output), "fixture compression failed");
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
