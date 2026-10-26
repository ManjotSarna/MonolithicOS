package com.joe.home;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * tvOS 26-style Liquid Glass for one rounded surface, with no live blur (technique from
 * glassify-my-tv / CleanYourTV): the wallpaper's small pre-blurred copy ({@link Wallpapers#frost})
 * drawn through a BitmapShader whose matrix maps it back to screen coordinates, so the blur lines up
 * with the wallpaper really behind the surface; then a milky veil and a rim lit from the top left.
 * The veil and the wallpaper's tone (dim, night) are folded into one colour filter on the frost, so
 * a surface is two draws, not three: on this GPU every full-surface blend counts. Shaders and
 * drawRoundRect only, no clipPath. Without a wallpaper (aurora mode) it is just the veil and the rim.
 * The view that owns it must invalidate it when it moves on screen.
 */
final class Glass extends Drawable {
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final int[] onScreen = new int[2];
    private final float radius;
    private final int veilOverFrost, veilAlone;
    private BitmapShader shader;
    private int frostW, frostH, screenW, screenH;
    private float toneR = 1f, toneG = 1f, toneB = 1f;

    Glass(float radius, float density, int veilOverFrost, int veilAlone) {
        this.radius = radius;
        this.veilOverFrost = veilOverFrost;
        this.veilAlone = veilAlone;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 1.25f * density));
        veil.setColor(veilAlone);
    }

    /** The blurred wallpaper copy, and the screen size it stands for; null = veil and rim only. */
    void setFrost(Bitmap frost, int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
        if (frost == null) {
            shader = null;
            body.setShader(null);
            veil.setColor(veilAlone);
        } else {
            shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            frostW = frost.getWidth();
            frostH = frost.getHeight();
            body.setShader(shader);
            body.setColorFilter(filter());
        }
        invalidateSelf();
    }

    /** The wallpaper's tone (per-channel brightness), so the frost matches the photo around it. */
    void setTone(float r, float g, float b) {
        toneR = r;
        toneG = g;
        toneB = b;
        if (shader != null) body.setColorFilter(filter());
        invalidateSelf();
    }

    /** frost x tone, then the veil over it: out = frost * tone * (1 - a) + veil * a, in one multiply-add. */
    private LightingColorFilter filter() {
        float a = Color.alpha(veilOverFrost) / 255f;
        int mul = Color.rgb(Math.round(255 * toneR * (1 - a)), Math.round(255 * toneG * (1 - a)),
                Math.round(255 * toneB * (1 - a)));
        int add = Color.rgb(Math.round(Color.red(veilOverFrost) * a), Math.round(Color.green(veilOverFrost) * a),
                Math.round(Color.blue(veilOverFrost) * a));
        return new LightingColorFilter(mul, add);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        // the rim catches light from the top left, like the tvOS material
        rim.setShader(new LinearGradient(b.left, b.top, b.left + b.height() * 0.6f + b.width() * 0.15f, b.bottom,
                new int[]{0x8CFFFFFF, 0x2EFFFFFF, 0x14FFFFFF, 0x33FFFFFF}, new float[]{0f, 0.35f, 0.7f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        rect.set(b);
        if (shader != null) {
            Callback cb = getCallback();
            if (cb instanceof View) ((View) cb).getLocationOnScreen(onScreen);
            matrix.setScale((float) screenW / frostW, (float) screenH / frostH);
            matrix.postTranslate(-onScreen[0], -onScreen[1]);
            shader.setLocalMatrix(matrix);
            c.drawRoundRect(rect, radius, radius, body);   // frost, toned and veiled
        } else {
            c.drawRoundRect(rect, radius, radius, veil);
        }
        float half = rim.getStrokeWidth() / 2f;
        rect.inset(half, half);
        c.drawRoundRect(rect, radius - half, radius - half, rim);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
