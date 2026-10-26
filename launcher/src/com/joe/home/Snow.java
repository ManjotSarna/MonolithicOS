package com.joe.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.AnimationUtils;

import java.io.File;
import java.util.Locale;
import java.util.Random;

/**
 * Snow falling over the wallpaper, Wallpaper Engine style: fine soft flakes at three depths (far ones
 * tiny, dim and slower, near ones bigger and quicker), each swaying on its own sine in a light wind.
 * Positions come from the frame clock, so motion stays even whatever the frame rate. It asks for a
 * frame every other vsync and smears each flake over about a frame of its fall, which reads as smooth
 * motion; 60 a second would take most of this TV's GPU (measured: 30 costs a quarter of a CPU core
 * and 6 ms of GPU a frame). Only while it's falling and on screen, and a layer of its own, so the
 * wallpaper underneath is never re-recorded.
 */
final class Snow extends View implements Choreographer.FrameCallback {
    private static final int FAR = 110, MID = 70, NEAR = 35, COUNT = FAR + MID + NEAR;
    private static final float BLUR = 0.022f;   // seconds of travel each flake is smeared over

    private final Bitmap[] sprites = new Bitmap[3];
    private final int[] depth = new int[COUNT];
    private final float[] x0 = new float[COUNT], y0 = new float[COUNT], fall = new float[COUNT],
            sway = new float[COUNT], rate = new float[COUNT], phase = new float[COUNT], size = new float[COUNT];
    private final int[] alpha = new int[COUNT];
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final float dp, wind;
    private final long start = AnimationUtils.currentAnimationTimeMillis();
    private boolean falling, visible, ticking, odd;

    Snow(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        wind = 7f * dp;   // dp a second, to the right
        // a small soft disc per depth; the near one a touch out of focus
        sprites[0] = disc(Math.round(1.2f * dp), 1f, 0.45f);
        sprites[1] = disc(Math.round(1.8f * dp), 1f, 0.42f);
        sprites[2] = disc(Math.round(2.6f * dp), 0.95f, 0.35f);
        Random r = new Random(1225);
        for (int i = 0; i < COUNT; i++) {
            int d = i < FAR ? 0 : i < FAR + MID ? 1 : 2;
            depth[i] = d;
            x0[i] = r.nextFloat();
            y0[i] = r.nextFloat();
            // ranges: fall dp/s, sway dp, alpha, size
            float[] s = d == 0 ? new float[]{40, 55, 4, 8, 0.35f, 0.6f, 0.8f, 1.15f}
                    : d == 1 ? new float[]{60, 80, 6, 12, 0.55f, 0.8f, 0.85f, 1.15f}
                    : new float[]{90, 120, 10, 16, 0.7f, 0.95f, 0.9f, 1.2f};
            fall[i] = lerp(s[0], s[1], r.nextFloat()) * dp;
            sway[i] = lerp(s[2], s[3], r.nextFloat()) * dp;
            alpha[i] = Math.round(255 * lerp(s[4], s[5], r.nextFloat()));
            size[i] = lerp(s[6], s[7], r.nextFloat());
            rate[i] = (float) (2 * Math.PI / lerp(3f, 7f, r.nextFloat()));   // one sway every 3-7 s
            phase[i] = (float) (2 * Math.PI * r.nextFloat());
        }
    }

    /** Whether this wallpaper snows: on by default for photos with snow or winter in the name. */
    static boolean on(SharedPreferences p, File wallpaper) {
        if (wallpaper == null) return false;
        String n = wallpaper.getName().toLowerCase(Locale.US);
        return p.getBoolean(key(wallpaper), n.contains("snow") || n.contains("winter"));
    }

    static String key(File wallpaper) {
        return "snow:" + wallpaper.getName();
    }

    /** Falls while true (and on screen); otherwise the flakes hold still. */
    void setFalling(boolean on) {
        falling = on;
        tick();
    }

    /** The wallpaper's dim and night warmth, so the flakes sit in the picture instead of on top of it. */
    void setTone(float red, float green, float blue) {
        paint.setColorFilter(red >= 0.999f && green >= 0.999f && blue >= 0.999f ? null
                : new LightingColorFilter(Color.rgb(Math.round(255 * red), Math.round(255 * green), Math.round(255 * blue)), 0));
        invalidate();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        visible = isVisible;
        tick();
    }

    @Override
    protected void onDetachedFromWindow() {
        visible = false;
        tick();
        super.onDetachedFromWindow();
    }

    private void tick() {
        boolean want = falling && visible && isAttachedToWindow();
        if (want == ticking) return;
        ticking = want;
        if (want) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!ticking) return;
        odd = !odd;
        if (odd) invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0) return;
        double t = (AnimationUtils.currentAnimationTimeMillis() - start) / 1000.0;   // double: Home runs for weeks
        float margin = 6 * dp, span = h + 2 * margin;
        for (int i = 0; i < COUNT; i++) {
            Bitmap b = sprites[depth[i]];
            float r = b.getWidth() / 2f * size[i];
            float y = (float) ((y0[i] * span + fall[i] * t) % span) - margin;
            float x = (float) ((x0[i] * w + wind * t * (0.5 + 0.5 * depth[i]) + sway[i] * Math.sin(rate[i] * t + phase[i])) % w);
            if (x < 0) x += w;
            // motion blur: stretched along the fall by about a frame's travel, so 30 fps reads as smooth
            float blur = fall[i] * BLUR;
            dst.set(x - r, y - r - blur, x + r, y + r);
            paint.setAlpha(alpha[i]);
            canvas.drawBitmap(b, null, dst, paint);
        }
    }

    /** A white disc fading out to its edge: solid to `core` of the radius, then soft. */
    private static Bitmap disc(int radius, float centre, float core) {
        int s = Math.max(2, radius * 2);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int a0 = Math.round(255 * centre);
        p.setShader(new RadialGradient(s / 2f, s / 2f, s / 2f,
                new int[]{Color.argb(a0, 255, 255, 255), Color.argb(Math.round(a0 * 0.8f), 255, 255, 255), 0x00FFFFFF},
                new float[]{0f, core, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p);
        return b;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
