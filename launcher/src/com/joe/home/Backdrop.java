package com.joe.home;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * The background: either a wallpaper photo (one opaque, screen-sized GPU bitmap) or the aurora, two
 * soft lights in the focused app's colours over black. Both take a tone (dim / night warmth) as a
 * colour multiply on that same single draw.
 *
 * The lights' shapes never change, only their colours, so the shapes are baked once into a small
 * texture (red channel = left light, green = right light, darker floor included). An AGSL shader
 * reads it with bilinear filtering, colours it with two uniforms and adds a one-pixel dither at the
 * screen's full resolution, so the dark fade neither bands, facets nor shows grain. An app change
 * only updates two uniforms; nothing is re-uploaded.
 *
 * Tried first on this TV (MediaTek, 2.4 GB, 85"): a colour bitmap stretched 10x showed its dither
 * as centimetre blotches, and without dither its texel grid showed as faint facets; Android's
 * composed gradient shaders were too heavy at full screen.
 */
final class Backdrop extends View {
    static final int W = 384, H = 216;
    private static final float STRENGTH_A = 0.30f, STRENGTH_B = 0.17f;

    private static final String AURORA =
            "uniform shader light;\n"
            + "uniform float2 scale;\n"
            + "uniform half3 a;\n"
            + "uniform half3 b;\n"
            + "half4 main(float2 p) {\n"
            + "  half4 l = light.eval(p * scale);\n"
            + "  half3 c = a * l.r + b * l.g;\n"
            // interleaved gradient noise (Jimenez): +-0.5 of an 8-bit step, one pixel fine
            + "  float g = fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715))));\n"
            + "  c += half3((g - 0.5) / 255.0);\n"
            + "  return half4(clamp(c, 0.0, 1.0), 1.0);\n"
            + "}\n";

    private final Paint paint = new Paint();
    private final Paint photoPaint = new Paint();
    private final Rect dst = new Rect();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final RuntimeShader shader;
    private int fromA = Color.BLACK, fromB = Color.BLACK, toA = Color.BLACK, toB = Color.BLACK;
    private int curA = Color.BLACK, curB = Color.BLACK;
    private ValueAnimator fade;
    private boolean started;
    // wallpaper mode: a screen-sized GPU bitmap drawn opaque instead of the aurora
    private Bitmap photo;
    // tone: overall brightness (dim / night) and night warmth, for either mode
    private float red = 1f, green = 1f, blue = 1f;

    Backdrop(Context context) {
        super(context);
        if (Build.VERSION.SDK_INT >= 33) {
            BitmapShader shapes = new BitmapShader(bakeLights(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shapes.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            shader = new RuntimeShader(AURORA);
            shader.setInputShader("light", shapes);
            paint.setShader(shader);
        } else {
            shader = null;   // older TVs: plain black
        }
    }

    @Override
    public boolean isOpaque() {
        return true;
    }

    /** Wallpaper mode with this photo (screen-sized), or back to the aurora with null. */
    void setPhoto(Bitmap p) {
        photo = p;
        invalidate();
    }

    boolean hasPhoto() {
        return photo != null;
    }

    /**
     * brightness 0..1 (wallpaper dim, night mode); warm pulls blue and a little green down for the
     * evening. A colour multiply on the one draw already happening, so it costs nothing extra.
     */
    void setTone(float brightness, boolean warm) {
        red = brightness;
        green = brightness * (warm ? 0.92f : 1f);
        blue = brightness * (warm ? 0.78f : 1f);
        if (brightness >= 0.999f && !warm) {
            photoPaint.setColorFilter(null);
        } else {
            photoPaint.setColorFilter(new LightingColorFilter(
                    Color.rgb(Math.round(255 * red), Math.round(255 * green), Math.round(255 * blue)), 0));
        }
        invalidate();
    }

    /** The tone as red, green and blue multipliers, for anything drawn from the wallpaper (the glass). */
    float[] tone() {
        return new float[]{red, green, blue};
    }

    void show(int primary, int secondary) {
        if (photo != null) {   // the wallpaper doesn't follow focus; just remember the colours
            toA = curA = primary;
            toB = curB = secondary;
            return;
        }
        if (shader == null || (primary == toA && secondary == toB)) return;
        if (fade != null) fade.cancel();
        fromA = curA;   // from whatever is on screen now, so an interrupted fade never jumps
        fromB = curB;
        toA = primary;
        toB = secondary;
        fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(started ? 520 : 700);
        fade.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        fade.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            curA = (int) argb.evaluate(t, fromA, toA);
            curB = (int) argb.evaluate(t, fromB, toB);
            invalidate();
        });
        fade.start();
        started = true;
    }

    void release() { }

    @Override
    protected void onDraw(Canvas canvas) {
        if (photo != null) {
            dst.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(photo, null, dst, photoPaint);
            return;
        }
        if (shader == null) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        shader.setFloatUniform("scale", (float) W / getWidth(), (float) H / getHeight());
        shader.setFloatUniform("a", STRENGTH_A * red * Color.red(curA) / 255f,
                STRENGTH_A * green * Color.green(curA) / 255f, STRENGTH_A * blue * Color.blue(curA) / 255f);
        shader.setFloatUniform("b", STRENGTH_B * red * Color.red(curB) / 255f,
                STRENGTH_B * green * Color.green(curB) / 255f, STRENGTH_B * blue * Color.blue(curB) / 255f);
        canvas.drawPaint(paint);
    }

    /** Both lights' brightness, 0..1, with the darker floor applied. Smooth, so 8 bits are plenty. */
    private static Bitmap bakeLights() {
        float aspect = (float) H / W;
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) {
            float ny = (y + 0.5f) / H;
            float fy = ny * aspect;
            float floor = 1f - 0.55f * smooth(0.45f, 1f, ny);
            for (int x = 0; x < W; x++) {
                float nx = (x + 0.5f) / W;
                float d1 = sq(nx - 0.22f) + sq(fy - 0.10f * aspect);
                float d2 = sq(nx - 0.90f) + sq(fy - 0.02f * aspect);
                int l1 = Math.round(255f * floor * (float) Math.exp(-d1 * 5.2f));
                int l2 = Math.round(255f * floor * (float) Math.exp(-d2 * 9.0f));
                px[y * W + x] = 0xFF000000 | (l1 << 16) | (l2 << 8);
            }
        }
        Bitmap bm = Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
        bm.setHasAlpha(false);
        return bm;
    }

    private static float sq(float v) {
        return v * v;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
