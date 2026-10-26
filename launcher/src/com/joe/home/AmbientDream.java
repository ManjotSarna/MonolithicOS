package com.joe.home;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;

import java.io.File;
import java.util.Random;

/**
 * MonolithicOS Ambient, the screensaver: a big thin clock over either Home's wallpaper (the GT3 RS
 * unless another is picked) drifting in a slow Ken Burns pan (with its falling snow, if it has it), or (aurora mode) the aurora
 * changing colour every 15 s. The clock sits top-left and eases to a nearby spot every minute.
 */
public final class AmbientDream extends DreamService {
    private static final int[][] MOODS = {
            {0xFF2E6BFF, 0xFFB04DFF}, {0xFF14C8B4, 0xFF2E6BFF}, {0xFFFF6A3C, 0xFFFFB03C},
            {0xFF7A5CFF, 0xFFFF4DA6}, {0xFF3CC86E, 0xFF1EA0FF},
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private ValueAnimator burns;
    private Backdrop aurora;
    private ImageView photoView;
    private Snow snow;
    private LinearLayout clock;
    private int mood;
    private float density;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        density = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        Bitmap photo = loadPhoto();
        if (photo != null) {
            photoView = new ImageView(this);
            photoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photoView.setImageBitmap(photo);
            root.addView(photoView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            if (Snow.on(getSharedPreferences("home", MODE_PRIVATE), Wallpapers.current(this))) {
                snow = new Snow(this);   // over the drifting photo, not drifting with it
                root.addView(snow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
        } else {
            aurora = new Backdrop(this);
            root.addView(aurora, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("H:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 112);
        time.setTypeface(getResources().getFont(R.font.outfit_thin));
        time.setTextColor(Color.WHITE);
        time.setIncludeFontPadding(false);
        time.setFontFeatureSettings("tnum");
        time.setShadowLayer(dp(18), 0, dp(2), 0x99000000);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        date.setTypeface(getResources().getFont(R.font.outfit_light));
        date.setTextColor(0xD9FFFFFF);
        date.setShadowLayer(dp(12), 0, dp(1), 0x99000000);
        date.setPadding(dp(6), dp(4), 0, 0);
        clock.addView(time);
        clock.addView(date);
        // top-left: over the photo's dark sky, clear of the car
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        cl.leftMargin = dp(72);
        cl.topMargin = dp(48);
        root.addView(clock, cl);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (photoView != null) {
            burns = ValueAnimator.ofFloat(0f, 1f);
            burns.setDuration(45_000);
            burns.setRepeatMode(ValueAnimator.REVERSE);
            burns.setRepeatCount(ValueAnimator.INFINITE);
            burns.setInterpolator(new AccelerateDecelerateInterpolator());
            burns.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                float s = 1.06f + 0.10f * t;
                photoView.setScaleX(s);
                photoView.setScaleY(s);
                photoView.setTranslationX(dp(-28) + dp(56) * t);
                photoView.setTranslationY(dp(10) - dp(20) * t);
            });
            burns.start();
            if (snow != null) snow.setFalling(true);
        } else {
            nextMood.run();
        }
        handler.postDelayed(drift, 60_000);
    }

    private final Runnable nextMood = new Runnable() {
        @Override public void run() {
            int[] m = MOODS[mood++ % MOODS.length];
            aurora.show(m[0], m[1]);
            handler.postDelayed(this, 15_000);
        }
    };

    /** Ease the clock to a nearby spot every minute so nothing sits still all night. */
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            clock.animate().translationX(dp(random.nextInt(120))).translationY(dp(random.nextInt(50)))
                    .setDuration(2400).setInterpolator(new AccelerateDecelerateInterpolator()).start();
            handler.postDelayed(this, 60_000);
        }
    };

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacksAndMessages(null);
        if (burns != null) burns.cancel();
        if (snow != null) snow.setFalling(false);
        super.onDreamingStopped();
    }

    private Bitmap loadPhoto() {
        File file = Wallpapers.current(this);
        try {
            if (file == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
            if (b == null) return null;
            Bitmap gpu = b.copy(Bitmap.Config.HARDWARE, false);   // one GPU copy, nothing in RAM
            b.recycle();
            return gpu;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
