package com.joe.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** An HDMI port outline drawn as a path, so it stays crisp at any size. */
final class HdmiGlyph extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    HdmiGlyph(float stroke) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xF2FFFFFF);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float s = paint.getStrokeWidth() / 2f;
        float l = b.left + s, t = b.top + s, r = b.right - s, bt = b.bottom - s;
        float w = r - l, h = bt - t;
        path.reset();
        path.moveTo(l, t);
        path.lineTo(r, t);
        path.lineTo(r, t + h * 0.5f);
        path.lineTo(r - w * 0.14f, bt);
        path.lineTo(l + w * 0.14f, bt);
        path.lineTo(l, t + h * 0.5f);
        path.close();
        // the row of pins
        path.moveTo(l + w * 0.22f, t + h * 0.4f);
        path.lineTo(r - w * 0.22f, t + h * 0.4f);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(path, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
