package com.gathin.tvhome;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;

import org.xmlpull.v1.XmlPullParser;

/**
 * tvOS 26 "Liquid Glass" without a live blur (the TV's PowerVR BXE cannot blur moving video every frame):
 * a faint tinted body, a darker lower half so the edge reads as thickness, a rim lit from above that fades
 * down the sides, and a soft specular bloom near the top. Everything is gradients, so it costs a few
 * fills per draw and nothing per frame while the video plays underneath.
 *
 * Usable from XML (API 24+ custom drawables): {@code <drawable class="com.gathin.tvhome.LiquidGlassDrawable"/>}
 * is a capsule; {@link Panel} has 22dp corners for menus.
 */
public class LiquidGlassDrawable extends Drawable {

    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint depth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lens = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float density = Resources.getSystem().getDisplayMetrics().density;
    /** Corner radius in dp; negative means a capsule (half the height). */
    private final float radiusDp;
    /** Body tint, 0xAARRGGBB: white glass on video, a darker smoked glass for menus. */
    private final int tint;
    private final boolean dockSurface;
    private int alpha = 255;

    public LiquidGlassDrawable() {
        this(-1f, 0x1FFFFFFF, false);
    }

    LiquidGlassDrawable(float radiusDp, int tint) {
        this(radiusDp, tint, false);
    }

    LiquidGlassDrawable(float radiusDp, int tint, boolean dockSurface) {
        this.radiusDp = radiusDp;
        this.tint = tint;
        this.dockSurface = dockSurface;
        rim.setStyle(Paint.Style.STROKE);
        innerRim.setStyle(Paint.Style.STROKE);
        lens.setStyle(Paint.Style.STROKE);
    }

    /** Menus and dropdowns: smoked glass so rows stay legible over a bright aerial. */
    public static class Panel extends LiquidGlassDrawable {
        public Panel() {
            super(22f, 0xB31A1C22);
        }
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser, @NonNull AttributeSet attrs,
                        Resources.Theme theme) {
        density = r.getDisplayMetrics().density;
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        float h = b.height();
        body.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                dockSurface ? new int[]{lift(tint, 0x14), tint, tint} : new int[]{lift(tint, 0x14), tint, darken(tint)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        if (dockSurface) {
            body.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{0x34FFFFFF, 0x1EFFFFFF, 0x140C1014},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            depth.setShader(new LinearGradient(0, b.top + h * 0.40f, 0, b.bottom,
                    0x00000000, 0x22000000, Shader.TileMode.CLAMP));
            rim.setStrokeWidth(Math.max(1f, 1.15f * density));
            rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{0xB8FFFFFF, 0x34FFFFFF, 0x10FFFFFF, 0x32FFFFFF},
                    new float[]{0f, 0.35f, 0.8f, 1f}, Shader.TileMode.CLAMP));
            innerRim.setStrokeWidth(Math.max(1f, 0.75f * density));
            innerRim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    0x36FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            bloom.setShader(new RadialGradient(b.left + b.width() * 0.5f, b.top,
                    Math.max(b.width() * 0.6f, h * 1.2f), 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            lens.setStrokeWidth(Math.max(1.5f, 3.2f * density));
            lens.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    new int[]{0x22FFFFFF, 0x08FFFFFF, 0x04FFFFFF, 0x14FFFFFF},
                    new float[]{0f, 0.3f, 0.75f, 1f}, Shader.TileMode.CLAMP));
            specular.setShader(new RadialGradient(b.left + b.width() * 0.35f, b.top + h * 0.08f,
                    Math.max(1f, Math.min(b.width(), h) * 0.9f),
                    0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            return;
        }
        // Lower half slightly darker: the glass has thickness and the light comes from above.
        depth.setShader(new LinearGradient(0, b.top + h * 0.45f, 0, b.bottom,
                0x00000000, 0x1F000000, Shader.TileMode.CLAMP));
        // Rim: bright along the top edge, almost gone by the bottom, as on tvOS buttons.
        rim.setStrokeWidth(Math.max(1f, 1.2f * density));
        rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0xB3FFFFFF, 0x2EFFFFFF, 0x14FFFFFF, 0x40FFFFFF}, new float[]{0f, 0.35f, 0.8f, 1f},
                Shader.TileMode.CLAMP));
        // A second, finer rim one pixel in: the "double edge" of refracted glass.
        innerRim.setStrokeWidth(Math.max(1f, 0.8f * density));
        innerRim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        float bw = b.width() * 0.55f, bh = Math.max(1f, h * 0.9f);
        bloom.setShader(new RadialGradient(b.left + b.width() * 0.32f, b.top, Math.max(bw, bh),
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // Lensing: Liquid Glass bends the backdrop most at its edges, so a wide, faint band just inside the rim
        // reads as the thick curved border, light at the top and again where the bottom edge catches it.
        lens.setStrokeWidth(Math.max(2f, Math.min(h * 0.12f, 7f * density)));
        lens.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0x26FFFFFF, 0x08FFFFFF, 0x05FFFFFF, 0x1AFFFFFF}, new float[]{0f, 0.3f, 0.75f, 1f},
                Shader.TileMode.CLAMP));
        // The specular glint on the top-left shoulder, where tvOS 26 puts its light source.
        float sr = Math.max(1f, Math.min(b.width(), h) * 0.9f);
        specular.setShader(new RadialGradient(b.left + sr * 0.35f, b.top + h * 0.08f, sr,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    private static int lift(int argb, int add) {
        int a = Math.min(255, ((argb >>> 24) & 0xFF) + add);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    private static int darken(int argb) {
        int a = (argb >>> 24) & 0xFF;
        return (Math.max(0, a - 0x0A) << 24) | (argb & 0xFFFFFF);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float inset = rim.getStrokeWidth() / 2f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        float r = radiusDp < 0 ? rect.height() / 2f : Math.min(radiusDp * density, rect.height() / 2f);
        int save = alpha < 255 ? canvas.saveLayerAlpha(b.left, b.top, b.right, b.bottom, alpha) : -1;
        canvas.drawRoundRect(rect, r, r, body);
        canvas.drawRoundRect(rect, r, r, depth);
        canvas.drawRoundRect(rect, r, r, bloom);
        canvas.drawRoundRect(rect, r, r, specular);
        float li = lens.getStrokeWidth() / 2f;
        if (li > 0f) {
            rect.inset(li, li);
            canvas.drawRoundRect(rect, Math.max(0, r - li), Math.max(0, r - li), lens);
            rect.inset(-li, -li);
        }
        canvas.drawRoundRect(rect, r, r, rim);
        if (innerRim.getStrokeWidth() > 0f) {
            float i = 1.2f * density;
            rect.inset(i, i);
            canvas.drawRoundRect(rect, Math.max(0, r - i), Math.max(0, r - i), innerRim);
        }
        if (save >= 0) canvas.restoreToCount(save);
    }

    /** The rounded shape, so a lifted view casts a round shadow and clipToOutline clips to it, not to a square. */
    @Override
    public void getOutline(@NonNull android.graphics.Outline outline) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float r = radiusDp < 0 ? b.height() / 2f : Math.min(radiusDp * density, b.height() / 2f);
        outline.setRoundRect(b, r);
        outline.setAlpha(1f);
    }

    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
