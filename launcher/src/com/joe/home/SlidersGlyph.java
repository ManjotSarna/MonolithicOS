package com.joe.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Three slider tracks with knobs: the MonolithicOS Settings tile, drawn so it stays crisp. */
final class SlidersGlyph extends Drawable {
    private static final float[] KNOBS = {0.68f, 0.28f, 0.55f};
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);

    SlidersGlyph(float stroke) {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(stroke);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(0xF2FFFFFF);
        knob.setColor(0xF2FFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float r = b.height() / 9f, s = line.getStrokeWidth();
        float l = b.left + r + s, w = b.width() - 2 * (r + s);
        for (int i = 0; i < 3; i++) {
            float y = b.top + b.height() * (i + 0.5f) / 3f, x = l + w * KNOBS[i];
            c.drawLine(l, y, x - r - s, y, line);
            c.drawLine(x + r + s, y, l + w, y, line);
            c.drawCircle(x, y, r, knob);
        }
    }

    @Override public void setAlpha(int alpha) { line.setAlpha(alpha); knob.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { line.setColorFilter(cf); knob.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
