package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.Property;

import androidx.annotation.NonNull;

/**
 * ShineView's highlight as a foreground drawable, for the round top-bar buttons, which are single TextViews
 * with no room for a child view. The button clips it to its round outline. TvFocusMotion.applyButton moves
 * its centre (shineX/shineY, px from the middle) and fades it with setAlpha.
 */
final class ShineDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float radius;
    private float shineX, shineY;
    private int alpha;

    // Typed properties, not "shineX" strings: R8 renames the setters, which would break a reflective animator.
    static final Property<ShineDrawable, Float> SHINE_X = new Property<ShineDrawable, Float>(Float.class, "shineX") {
        @Override public Float get(ShineDrawable d) { return d.shineX; }
        @Override public void set(ShineDrawable d, Float x) { d.shineX = x; d.invalidateSelf(); }
    };
    static final Property<ShineDrawable, Float> SHINE_Y = new Property<ShineDrawable, Float>(Float.class, "shineY") {
        @Override public Float get(ShineDrawable d) { return d.shineY; }
        @Override public void set(ShineDrawable d, Float y) { d.shineY = y; d.invalidateSelf(); }
    };
    static final Property<ShineDrawable, Integer> ALPHA = new Property<ShineDrawable, Integer>(Integer.class, "alpha") {
        @Override public Integer get(ShineDrawable d) { return d.alpha; }
        @Override public void set(ShineDrawable d, Integer a) { d.setAlpha(a); }
    };

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        radius = Math.max(1f, b.width() * 0.75f);
        paint.setShader(new RadialGradient(0f, 0f, radius,
                new int[]{0x40FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (alpha == 0) return;
        Rect b = getBounds();
        int save = canvas.save();
        canvas.translate(b.exactCenterX() + shineX, b.exactCenterY() + shineY);
        paint.setAlpha(alpha);
        canvas.drawCircle(0f, 0f, radius, paint);
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
