package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The Liquid Glass edge on the focused icon, as tvOS 26 draws it: no frosted slab over the artwork,
 * just a rim of light that is brightest along the top, a faint inner edge, and a soft halo around it.
 * One instance floats over the grid and is moved with spring animations between tiles.
 */
public class GlassView extends View {

    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, the reorder blue or a festival's accent otherwise. */
    private int glowRgb = 0xFFFFFF;
    private int glowAlpha = 0x26;
    private int rimTop = 0xE6FFFFFF;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.6f * density);
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(0.8f * density);
        glow.setStyle(Paint.Style.STROKE);
        // Cached as a texture: the spring only changes translation, so the GPU moves it without redrawing.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Tints the rim and the halo; 0 restores the plain white glass. */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x26;
            rimTop = 0xE6FFFFFF;
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0x70;
            // The accent lifted towards white, so the edge still reads as glass, not paint.
            int r = (((glowRgb >> 16) & 0xFF) + 255 * 2) / 3, g = (((glowRgb >> 8) & 0xFF) + 255 * 2) / 3,
                    b = ((glowRgb & 0xFF) + 255 * 2) / 3;
            rimTop = 0xF2000000 | (r << 16) | (g << 8) | b;
        }
        updateShaders(getHeight());
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateShaders(h);
    }

    private void updateShaders(int h) {
        if (h <= 0) return;
        int rgb = rimTop & 0xFFFFFF;
        rim.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{rimTop, 0x59000000 | rgb, 0x26000000 | rgb, 0x66000000 | rgb},
                new float[]{0f, 0.4f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        inner.setShader(new LinearGradient(0, 0, 0, h * 0.5f, 0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // A thin wash of light over the top fifth, where tvOS catches the light on a lifted icon.
        sheen.setShader(new LinearGradient(0, 0, 0, h * 0.22f, 0x24FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the halo
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        for (int i = 1; i <= 3; i++) {
            glow.setStrokeWidth(i * 2.5f * density);
            glow.setColor((glowAlpha / i) << 24 | glowRgb);
            float o = i * density;
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, radius + o, radius + o, glow);
        }
        canvas.drawRoundRect(rect, radius, radius, sheen);
        canvas.drawRoundRect(rect, radius, radius, rim);
        float i = 1.6f * density;
        canvas.drawRoundRect(rect.left + i, rect.top + i, rect.right - i, rect.bottom - i,
                Math.max(0, radius - i), Math.max(0, radius - i), inner);
    }
}
