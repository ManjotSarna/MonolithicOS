package com.joe.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;

import java.util.function.BooleanSupplier;

/**
 * The TV's intro, on the phone: MonolithicOS's name drawing together out of black, drifting up and away, then
 * the remote coming up under it (the black fading off as the page settles into place). Same timings and curve
 * as Home's (HomeActivity.intro). Played when the app starts; the page loads underneath meanwhile, and the
 * reveal waits for it (2.5 s at most), so the load is never seen.
 */
final class Intro {
    private Intro() { }

    private static final Interpolator GLIDE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    static void play(FrameLayout root, View content, Typeface face, BooleanSupplier ready) {
        Context c = root.getContext();
        FrameLayout curtain = new FrameLayout(c);
        curtain.setBackgroundColor(Color.BLACK);
        curtain.setClickable(true);   // nothing under it can be tapped while it's up
        root.addView(curtain, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Wordmark word = new Wordmark(c, "MonolithicOS", face,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30, c.getResources().getDisplayMetrics()));
        word.setAlpha(0f);
        curtain.addView(word, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // stepped by frames, not the clock: a stall holds for an instant instead of jumping to catch up
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            long last;
            float t;
            @Override public void doFrame(long nanos) {
                t += last == 0 ? 0f : Math.min(1000f / 60f, (nanos - last) / 1e6f);
                last = nanos;
                word.spacing(0.42f - 0.36f * GLIDE.getInterpolation(Math.min(1f, t / 1500f)));
                word.setAlpha(GLIDE.getInterpolation(Math.min(1f, t / 900f)));
                if (t < 1700f) Choreographer.getInstance().postFrameCallback(this);
                else outro(root, curtain, word, content, ready);
            }
        });
    }

    private static void outro(FrameLayout root, FrameLayout curtain, Wordmark word, View content, BooleanSupplier ready) {
        word.animate().alpha(0f).translationY(-dp(root, 10)).setDuration(500).setInterpolator(GLIDE).start();
        Handler h = new Handler(Looper.getMainLooper());
        long start = SystemClock.uptimeMillis();
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!ready.getAsBoolean() && SystemClock.uptimeMillis() - start < 2500) {
                    h.postDelayed(this, 50);
                    return;
                }
                lift(root, curtain, content);
            }
        }, 300);
    }

    /** The black fading off while the page settles: a touch large and low, easing into place. */
    private static void lift(FrameLayout root, FrameLayout curtain, View content) {
        Interpolator settle = new DecelerateInterpolator(1.6f);
        float drop = dp(root, 28);
        content.setScaleX(1.05f);
        content.setScaleY(1.05f);
        content.setTranslationY(drop);
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            long last;
            float ms;
            @Override public void doFrame(long nanos) {
                ms += last == 0 ? 0f : Math.min(1000f / 60f, (nanos - last) / 1e6f);
                last = nanos;
                curtain.setAlpha(1f - GLIDE.getInterpolation(part(ms, 0, 1100)));
                float s = 1f + 0.05f * (1f - settle.getInterpolation(part(ms, 0, 1700)));
                content.setScaleX(s);
                content.setScaleY(s);
                content.setTranslationY(drop * (1f - GLIDE.getInterpolation(part(ms, 150, 1100))));
                if (ms < 1800f) {
                    Choreographer.getInstance().postFrameCallback(this);
                } else {
                    content.setScaleX(1f);
                    content.setScaleY(1f);
                    content.setTranslationY(0f);
                    root.removeView(curtain);
                }
            }
        });
    }

    private static float part(float ms, float start, float length) {
        return Math.max(0f, Math.min(1f, (ms - start) / length));
    }

    private static float dp(View v, float x) {
        return x * v.getResources().getDisplayMetrics().density;
    }

    /** The name, letter by letter at moving positions (animating letter spacing re-lays out every frame). */
    private static final class Wordmark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final String text;
        private final float[] advances;
        private final float width, em;
        private float spacing;

        Wordmark(Context c, String text, Typeface face, float size) {
            super(c);
            this.text = text;
            paint.setTypeface(face);
            paint.setTextSize(size);
            paint.setColor(Color.WHITE);
            em = size;
            advances = new float[text.length()];
            paint.getTextWidths(text, advances);
            float w = 0;
            for (float a : advances) w += a;
            width = w;
        }

        void spacing(float s) {
            spacing = s;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float gap = spacing * em, total = width + gap * (text.length() - 1);
            float x = (getWidth() - total) / 2f;
            float y = getHeight() / 2f - (paint.ascent() + paint.descent()) / 2f;
            for (int i = 0; i < text.length(); i++) {
                c.drawText(text, i, i + 1, x, y, paint);
                x += advances[i] + gap;
            }
        }
    }
}
