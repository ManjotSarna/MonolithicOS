package com.joe.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * A focusable rounded card, focused the tvOS way: it leans a few degrees in the direction you
 * moved and springs back flat while it grows and lifts, a glossy edge lights up, and a soft
 * highlight sweeps across in your direction of travel. Everything is RenderNode properties,
 * outlines and gradients: no bitmaps scaled up, no clipPath (software-rendered on MediaTek),
 * and nothing runs once the card has settled.
 */
final class Tile extends FrameLayout {
    interface FocusListener { void onTileFocused(Tile tile, int direction); }

    private static final Interpolator SPRING = new Spring(6f, 9f);
    private static final Interpolator SETTLE = new DecelerateInterpolator(1.6f);
    private static final Interpolator SWEEP = new DecelerateInterpolator(1.2f);
    private static final float TILT = 7f;

    final String title;
    final String subtitle;
    int accent, accent2;     // the aurora's colours while this tile has focus
    Drawable art;            // banner or icon, reused by the launch zoom
    String pkg;              // app tiles
    ActivityInfo activity;   // app tiles
    String inputId;          // input tiles
    TextView label;          // optional name under the card, shown on focus
    FocusListener listener;

    // Card Style > Floating: the card's text gets a soft shadow to read on the wallpaper. A blurred shadow is
    // cached at one size, so while a card grew on focus it was re-blurred on the CPU every frame (57 ms frames,
    // 68% jank). Focused, the card has its glass behind the text instead; the shadow comes back once settled.
    boolean shadowText;
    private final Runnable reshadow = this::textShadows;

    void textShadows() {
        boolean on = shadowText && !hasFocus();
        float d = getResources().getDisplayMetrics().density;
        shadow(this, on ? 10 * d : 0, on ? d : 0);
    }

    private static void shadow(View v, float radius, float dy) {
        if (v instanceof TextView) {
            ((TextView) v).setShadowLayer(radius, 0, dy, radius > 0 ? 0x8C000000 : 0);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) shadow(g.getChildAt(i), radius, dy);
        }
    }

    // MonolithicOS Settings > Dim App Icons: how much app cards are darkened, 0..1 (glare on black)
    static float dim;

    /** Dim App Icons in %, 0..70 (1.0 kept a brightness instead, "icons" 30..100). */
    static int iconDim(SharedPreferences p) {
        return p.contains("icondim") ? p.getInt("icondim", 0) : 100 - p.getInt("icons", 100);
    }

    private final View sheen;
    private final LayerDrawable shine;
    private final float scale, radius;
    private ValueAnimator shineAnim, wobble, shadeAnim;
    private float shade = -1f;   // darkening drawn over the card now; -1 = at rest, follow dim
    private boolean moving;

    Tile(Context c, int w, int h, float radius, float scale, String title, String subtitle, int accent, int accent2) {
        super(c);
        this.title = title;
        this.subtitle = subtitle;
        this.accent = accent;
        this.accent2 = accent2;
        this.scale = scale;
        this.radius = radius;
        setFocusable(true);
        setClickable(true);
        setLongClickable(true);
        setCameraDistance(getResources().getDisplayMetrics().density * 9000f);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                if (v.getBackground() instanceof Glass) o.setAlpha(((Glass) v.getBackground()).shadow());
            }
        });
        setClipToOutline(true);

        sheen = new View(c);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF}));
        sheen.setRotation(20f);
        sheen.setAlpha(0f);
        addView(sheen, new LayoutParams(Math.round(w * 0.42f), h * 2, Gravity.CENTER));

        // Gloss on the top half plus a rim lit from the top left: the Liquid Glass of a focused card.
        GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x24FFFFFF, 0x00FFFFFF});
        gloss.setCornerRadius(radius);
        shine = new LayerDrawable(new Drawable[]{gloss, new LitRim(radius, Math.max(1f, radius / 7f))});
        shine.setLayerInsetBottom(0, h / 2);
        shine.setAlpha(0);
        setForeground(shine);
    }

    /** A stroke that catches light at the top left and fades round the card, like the tvOS material. */
    private static final class LitRim extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float radius;

        LitRim(float radius, float stroke) {
            this.radius = radius;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
        }

        @Override protected void onBoundsChange(Rect b) {
            paint.setShader(new LinearGradient(b.left, b.top, b.left + b.height() * 0.7f + b.width() * 0.2f, b.bottom,
                    new int[]{0xD9FFFFFF, 0x66FFFFFF, 0x26FFFFFF, 0x59FFFFFF}, new float[]{0f, 0.3f, 0.7f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            float half = paint.getStrokeWidth() / 2f;
            r.set(getBounds());
            r.inset(half, half);
            c.drawRoundRect(r, radius - half, radius - half, paint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * The darkening over an app card: over everything on it, the gloss and lit rim included, so Dim App Icons
     * dims the whole card evenly (under it, the rim stayed bright and the logo looked cheap).
     */
    @Override
    public void onDrawForeground(Canvas c) {
        super.onDrawForeground(c);
        float s = shade >= 0 ? shade : rest();
        if (s > 0.004f) c.drawColor(Color.argb(Math.round(255 * s), 0, 0, 0));
    }

    /**
     * How high it lifts on focus, and so how far its glow spreads: app tiles high for a big colour glow, cards
     * low. Glass cards lifted that high cast two big shadows under their frost while one rose and the other
     * settled: 16 ms of GPU a frame against 8.
     */
    private float lift() {
        return radius * (pkg != null ? 1.6f : 0.75f);
    }

    private float rest() {
        return pkg != null ? dim : 0f;
    }

    /** The glow's colour: the app's own, dimmed with Dim App Icons like the app itself. */
    private void glow() {
        int c = pkg != null && dim > 0 ? (Math.round(255 * (1f - dim)) << 24) | (accent & 0xFFFFFF) : accent;
        setOutlineSpotShadowColor(c);
        setOutlineAmbientShadowColor(c);
    }

    /** Fade the darkening to where it belongs now ({@link #dim} on app cards, after a settings change). */
    void settleShade() {
        if (hasFocus()) glow();
        float to = rest(), from = shade >= 0 ? shade : to;
        if (shadeAnim != null) shadeAnim.cancel();
        if (Math.abs(from - to) < 0.004f) {
            shade = -1f;
            invalidate();
            return;
        }
        shadeAnim = ValueAnimator.ofFloat(from, to);
        shadeAnim.setDuration(to < from ? 180 : 320);
        shadeAnim.addUpdateListener(a -> {
            shade = a.getAnimatedFraction() >= 1f ? -1f : (float) a.getAnimatedValue();
            invalidate();
        });
        shadeAnim.start();
    }

    /** OK goes down: the card dips, like pressing glass. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (isOk(keyCode) && e.getRepeatCount() == 0 && !moving) {
            animate().scaleX(scale * 0.95f).scaleY(scale * 0.95f).setInterpolator(SETTLE).setDuration(90).start();
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        if (isOk(keyCode) && !moving && hasFocus()) {
            animate().scaleX(scale).scaleY(scale).setInterpolator(SPRING).setDuration(320).start();
        }
        return super.onKeyUp(keyCode, e);
    }

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Pushed past the end of a row: nudge that way and spring back, so the wall feels physical. */
    void bounce(int direction) {
        if (moving) return;
        float d = getWidth() * 0.035f;
        float dx = direction == FOCUS_RIGHT ? d : direction == FOCUS_LEFT ? -d : 0f;
        float dy = direction == FOCUS_DOWN ? d : direction == FOCUS_UP ? -d : 0f;
        animate().cancel();
        animate().translationX(dx).translationY(dy).setInterpolator(SETTLE).setDuration(80).withEndAction(() ->
                animate().translationX(0f).translationY(0f).setInterpolator(SPRING).setDuration(420).start()).start();
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previous) {
        super.onFocusChanged(gained, direction, previous);
        if (getBackground() instanceof Glass) ((Glass) getBackground()).focus(gained);   // Card Style > Floating
        if (shadowText) {   // off at once while it grows, back after it has shrunk back (see shadowText)
            removeCallbacks(reshadow);
            if (gained) textShadows();
            else postDelayed(reshadow, 260);
        }
        if (moving) return;   // move mode keeps its own pose while the dock reorders
        animate().cancel();
        if (gained) {
            // the lifted card casts its own colour, a soft glow instead of a grey shadow
            glow();
            setRotationY(direction == FOCUS_RIGHT ? TILT : direction == FOCUS_LEFT ? -TILT : 0f);
            setRotationX(direction == FOCUS_DOWN ? -TILT * 0.7f : direction == FOCUS_UP ? TILT * 0.7f : 0f);
            animate().scaleX(scale).scaleY(scale).rotationX(0f).rotationY(0f).translationZ(lift())
                    .setInterpolator(SPRING).setDuration(540).start();
            sweep(direction == FOCUS_LEFT ? -1 : 1);
            if (listener != null) listener.onTileFocused(this, direction);
        } else {
            animate().scaleX(1f).scaleY(1f).rotationX(0f).rotationY(0f).translationZ(0f)
                    .setInterpolator(SETTLE).setDuration(200).start();
        }
        fadeShine(gained ? 255 : 0, gained ? 260 : 160);
        settleShade();
        if (label != null) {
            label.animate().cancel();
            label.animate().alpha(gained ? 1f : 0f).translationY(gained ? 0f : -label.getTextSize() * 0.25f)
                    .setDuration(gained ? 240 : 140).setInterpolator(SETTLE).start();
        }
    }

    /** Move mode: lifted higher and gently wiggling, like rearranging icons on an iPhone. */
    void setMoving(boolean on) {
        moving = on;
        if (wobble != null) wobble.cancel();
        animate().cancel();
        if (on) {
            animate().scaleX(scale * 1.07f).scaleY(scale * 1.07f).translationZ(lift() * 2.2f)
                    .setInterpolator(SPRING).setDuration(360).start();
            wobble = ValueAnimator.ofFloat(-1.3f, 1.3f);
            wobble.setDuration(150);
            wobble.setRepeatMode(ValueAnimator.REVERSE);
            wobble.setRepeatCount(ValueAnimator.INFINITE);
            wobble.addUpdateListener(a -> setRotation((float) a.getAnimatedValue()));
            wobble.start();
        } else {
            setRotation(0f);
            animate().scaleX(scale).scaleY(scale).translationZ(lift())
                    .setInterpolator(SPRING).setDuration(360).start();
        }
    }

    private void sweep(int dir) {
        int w = getWidth();
        if (w == 0) return;
        sheen.animate().cancel();
        sheen.setTranslationX(-dir * w * 0.85f);
        sheen.setAlpha(1f);
        sheen.animate().translationX(dir * w * 0.85f).setStartDelay(90).setDuration(720)
                .setInterpolator(SWEEP).withEndAction(() -> sheen.setAlpha(0f)).start();
    }

    private void fadeShine(int to, long ms) {
        if (shineAnim != null) shineAnim.cancel();
        shineAnim = ValueAnimator.ofInt(shine.getAlpha(), to);
        shineAnim.setDuration(ms);
        shineAnim.addUpdateListener(a -> shine.setAlpha((int) a.getAnimatedValue()));
        shineAnim.start();
    }
}
