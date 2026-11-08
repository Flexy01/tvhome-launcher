package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS Liquid Glass band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final LiquidGlassDrawable band;
    private final RectF rect = new RectF();
    private final Rect bounds = new Rect();
    private final Paint texturePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap texture;

    /** @param translucent unused since the band became glass; kept for the cinematic shelf's call. */
    DockDecoration(int dockSize, float density, boolean translucent) {
        this.dockSize = dockSize;
        this.density = density;
        // Quiet, single-rim glass keeps the backdrop visible without a heavy slab or live blur.
        band = new LiquidGlassDrawable(28f, 0x1CFFFFFF, true);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        // Project geometry measured against the Support reference, not an undocumented HIG constant.
        // End icons shift inward as they grow (TvFocusMotion.edgeShift).
        float pad = 20 * density;
        rect.inset(-pad, -pad);
        // Bounds at the origin, moved with the canvas: the band slides with every scroll frame, and
        // onBoundsChange rebuilds the gradients, so only a size change may reset them.
        bounds.set(0, 0, Math.round(rect.width()), Math.round(rect.height()));
        if (!bounds.equals(band.getBounds())) {
            band.setBounds(bounds);
            texture = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
            Canvas cached = new Canvas(texture);
            band.draw(cached);
        }
        c.drawBitmap(texture, null, rect, texturePaint);
    }
}
