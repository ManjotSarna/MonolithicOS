package com.joe.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Four level bars on a baseline: the Task Manager tile, drawn so it stays crisp next to SlidersGlyph. */
final class MeterGlyph extends Drawable {
    private static final float[] LEVELS = {0.45f, 0.85f, 0.6f, 0.3f};
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

    MeterGlyph(float stroke) {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(stroke);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(0xF2FFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = line.getStrokeWidth(), bottom = b.bottom - s, h = b.height() - 2 * s;
        float step = (b.width() - 2 * s) / (LEVELS.length - 1);
        for (int i = 0; i < LEVELS.length; i++) {
            float x = b.left + s + step * i;
            c.drawLine(x, bottom, x, bottom - h * LEVELS[i], line);
        }
    }

    @Override public void setAlpha(int alpha) { line.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { line.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
