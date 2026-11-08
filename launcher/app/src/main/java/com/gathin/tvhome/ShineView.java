package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Property;
import android.view.View;

/**
 * The specular highlight on a focused icon (TvFocusMotion.shine): a soft white bloom that fades to nothing at
 * its rim. The view itself stays exactly over the card; only the bloom's centre moves (shineX/shineY, px from
 * the middle), so the light never shows this view's edge as a line across the icon. The card's rounded outline
 * is the only thing that clips it.
 */
public class ShineView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float radius;
    private float shineX, shineY;

    // Typed properties, not "shineX" strings: R8 renames the setters, which would break a reflective animator.
    static final Property<ShineView, Float> SHINE_X = new Property<ShineView, Float>(Float.class, "shineX") {
        @Override public Float get(ShineView v) { return v.shineX; }
        @Override public void set(ShineView v, Float x) { v.setShineX(x); }
    };
    static final Property<ShineView, Float> SHINE_Y = new Property<ShineView, Float>(Float.class, "shineY") {
        @Override public Float get(ShineView v) { return v.shineY; }
        @Override public void set(ShineView v, Float y) { v.setShineY(y); }
    };

    public ShineView(Context context) {
        super(context);
    }

    public ShineView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public float getShineX() { return shineX; }
    public float getShineY() { return shineY; }

    public void setShineX(float x) {
        shineX = x;
        invalidate();
    }

    public void setShineY(float y) {
        shineY = y;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        radius = Math.max(1f, w * 0.75f);
        paint.setShader(new RadialGradient(0f, 0f, radius,
                new int[]{0x40FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(getWidth() / 2f + shineX, getHeight() / 2f + shineY);
        canvas.drawCircle(0f, 0f, radius, paint);
        canvas.restoreToCount(save);
    }
}
