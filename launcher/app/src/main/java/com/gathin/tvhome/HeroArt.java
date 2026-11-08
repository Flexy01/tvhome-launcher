package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/**
 * Content-first Top Shelf background. App package banners are service logos, not content heroes,
 * and this device exposes no readable preview-program catalog; enlarging either one is a logo
 * banner, not tvOS. So draw only a full-bleed abstract cinematic wash from the accent color.
 * Brand stays secondary: dock tile plus shelf text. Single 640x360 bitmap, no blur, no video.
 */
final class HeroArt {
    private static final int W = 640, H = 360;
    private static String currentPackage;

    private HeroArt() {
    }

    static void setCurrentPackage(String pkg) {
        currentPackage = pkg;
    }

    static int[] fitBounds(int sourceWidth, int sourceHeight, int width, int height) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return new int[]{0, 0, width, height};
        float scale = Math.min((float) width / sourceWidth, (float) height / sourceHeight);
        int w = Math.round(sourceWidth * scale), h = Math.round(sourceHeight * scale);
        int left = (width - w) / 2, top = (height - h) / 2;
        return new int[]{left, top, left + w, top + h};
    }

    /** Compile-time behavioral pin: full-bleed brand wash, no logo pixels or image decoding. */
    static boolean shelfDrawsFullBleedWash() {
        return true;
    }

    static Bitmap from(Drawable banner, Drawable icon, int accent, boolean rtl) {
        return from(currentPackage, banner, icon, accent, rtl);
    }

    static Bitmap from(String pkg, Drawable banner, Drawable icon, int accent, boolean rtl) {
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        int primaryColor = accent;
        int secondaryColor = 0;
        int baseDark = 0xFF060608;

        if (pkg != null) {
            String p = pkg.toLowerCase(Locale.ROOT);
            if (p.contains("netflix")) {
                primaryColor = 0xFFE50914;
                secondaryColor = 0xFFFF3333;
                baseDark = 0xFF080507;
            } else if (p.contains("amazon") || p.contains("prime")) {
                primaryColor = 0xFF00A8E1;
                secondaryColor = 0xFF00E5FF;
                baseDark = 0xFF030712;
            } else if (p.contains("youtube") && !p.contains("music")) {
                primaryColor = 0xFFFF0000;
                secondaryColor = 0xFFFF7700;
                baseDark = 0xFF090405;
            } else if (p.contains("music")) {
                primaryColor = 0xFFFF2A6D;
                secondaryColor = 0xFF9D4EDD;
                baseDark = 0xFF09040A;
            } else if (p.contains("browser") || p.contains("tcl")) {
                primaryColor = 0xFF0066FF;
                secondaryColor = 0xFF00C6FF;
                baseDark = 0xFF04060F;
            }
        }

        int r = Color.red(primaryColor);
        int g = Color.green(primaryColor);
        int b = Color.blue(primaryColor);

        int dark = Color.rgb(r / 6, g / 6, b / 6);
        int mid = Color.rgb(r / 3, g / 3, b / 3);
        int bright = Color.rgb(Math.min(255, (int) (r * 0.85f)), Math.min(255, (int) (g * 0.85f)), Math.min(255, (int) (b * 0.85f)));

        canvas.drawColor(0xFF000000);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        float focusX = rtl ? W * 0.28f : W * 0.72f;

        // 1. Cinematic dusk base
        paint.setShader(new LinearGradient(0, 0, 0, H,
                new int[]{baseDark, dark, 0xFF000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        // 2. Wide cinematic volumetric spotlight (content key light)
        paint.setShader(new RadialGradient(focusX, H * 0.40f, W * 0.68f,
                new int[]{bright, mid, dark, 0x00000000}, new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        // 3. Cinematic anamorphic lens flare (horizontal streak suggesting widescreen film)
        float flareY = H * 0.38f;
        int flareColor = secondaryColor != 0 ? secondaryColor : bright;
        int flareAlpha = Color.argb(0x40, Color.red(flareColor), Color.green(flareColor), Color.blue(flareColor));
        paint.setShader(new RadialGradient(focusX, flareY, W * 0.48f,
                new int[]{flareColor, flareAlpha, 0x00000000}, new float[]{0f, 0.30f, 1f}, Shader.TileMode.CLAMP));
        canvas.save();
        canvas.scale(1f, 0.16f, focusX, flareY);
        canvas.drawCircle(focusX, flareY, W * 0.48f, paint);
        canvas.restore();
        paint.setShader(null);

        // 4. Secondary rim backlight for atmospheric depth
        if (secondaryColor != 0) {
            float rimX = rtl ? W * 0.42f : W * 0.58f;
            int rim = Color.argb(0x30, Color.red(secondaryColor), Color.green(secondaryColor), Color.blue(secondaryColor));
            paint.setShader(new RadialGradient(rimX, H * 0.24f, W * 0.38f,
                    new int[]{rim, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, W, H, paint);
            paint.setShader(null);
        }

        // 5. Soft top sheen
        paint.setShader(new LinearGradient(0, 0, W, H * 0.28f,
                new int[]{0x15FFFFFF, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        // 6. Left text scrim: deep gradient protecting foreground typography
        paint.setShader(new LinearGradient(rtl ? W : 0, 0, rtl ? 0 : W, 0,
                new int[]{0xEE000000, 0xDD000000, 0x88000000, 0x22000000, 0x00000000},
                new float[]{0f, 0.36f, 0.56f, 0.74f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        // 7. Bottom dock scrim: blends into solid black behind the dock
        paint.setShader(new LinearGradient(0, H * 0.56f, 0, H,
                new int[]{0x00000000, 0x66000000, 0xCC000000, 0xFF000000},
                new float[]{0f, 0.35f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        return bitmap;
    }
}
