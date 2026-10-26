package com.joe.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * If MonolithicOS keeps crashing (a bad update, a setting it can't cope with), Android would just keep
 * restarting it as the TV's Home, and with Google's home switched off there'd be nothing else to go to. So
 * every crash is written down, and after 3 inside a minute Home starts as a bare dark screen instead, with
 * the ways out: try again, bring Google TV back, or open Settings.
 */
final class SafeMode {
    private SafeMode() { }

    private static final int CRASHES = 3;
    private static final long WINDOW = 60_000L;
    private static boolean watching;

    /** Writes down every crash, whatever thread it's on (before the app dies, so commit, not apply). */
    static synchronized void watch(Context context) {
        if (watching) return;
        watching = true;
        Context c = context.getApplicationContext();
        Thread.UncaughtExceptionHandler then = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                SharedPreferences p = prefs(c);
                String was = p.getString("at", "");
                p.edit().putString("at", (was.isEmpty() ? "" : was + ",") + System.currentTimeMillis())
                        .putString("why", String.valueOf(e)).commit();
            } catch (Throwable ignored) { }
            if (then != null) then.uncaughtException(t, e);
        });
    }

    /** Crashed 3 times in the last minute. */
    static boolean needed(Context c) {
        String at = prefs(c).getString("at", "");
        if (at.isEmpty()) return false;
        long now = System.currentTimeMillis();
        int recent = 0;
        StringBuilder keep = new StringBuilder();
        for (String s : at.split(",")) {
            try {
                long t = Long.parseLong(s);
                if (now - t < WINDOW) {
                    recent++;
                    keep.append(keep.length() == 0 ? "" : ",").append(t);
                }
            } catch (NumberFormatException ignored) { }
        }
        prefs(c).edit().putString("at", keep.toString()).apply();   // the old ones don't count
        return recent >= CRASHES;
    }

    static void clear(Context c) {
        prefs(c).edit().remove("at").apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("crashes", Context.MODE_PRIVATE);
    }

    /** The bare screen: nothing of MonolithicOS's own that could crash again. */
    static View screen(Activity a, Runnable tryAgain) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(Color.BLACK);
        col.addView(text(a, "MonolithicOS hit a problem", 34, 0xFFFFFFFF));
        TextView hint = text(a, "It stopped 3 times in a minute, so it started in safe mode.", 16, 0x99FFFFFF);
        hint.setPadding(0, dp(a, 14), 0, dp(a, 36));
        col.addView(hint);

        TextView again = button(a, "Try MonolithicOS Again");
        again.setOnClickListener(v -> { clear(a); tryAgain.run(); });
        col.addView(again, wide(a, 0));

        TextView back = button(a, "Bring Back Google TV");
        back.setOnClickListener(v -> {
            back.setText("Bringing back Google TV...");
            Handler main = new Handler(Looper.getMainLooper());
            new Thread(() -> {
                // with the TV's debugging allowed, all of it put back the way Google shipped it; otherwise
                // Google TV's app page, where Enable turns its home back on
                Setup.State s = Setup.state(a);
                boolean done = s != Setup.State.DEBUG_OFF && s != Setup.State.NEED_ALLOW && Setup.restore(a) == null;
                main.post(() -> {
                    back.setText("Bring Back Google TV");
                    if (!done) open(a, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + Setup.GTV)));
                });
            }).start();
        });
        col.addView(back, wide(a, dp(a, 10)));

        TextView settings = button(a, "Open Settings");
        settings.setOnClickListener(v -> open(a, new Intent(Settings.ACTION_SETTINGS)));
        col.addView(settings, wide(a, dp(a, 10)));
        again.post(again::requestFocus);
        return col;
    }

    private static void open(Activity a, Intent i) {
        try {
            a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private static LinearLayout.LayoutParams wide(Context c, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 420), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** White when focused, the tvOS way. */
    private static TextView button(Context c, String s) {
        TextView b = text(c, s, 18, 0xFFFFFFFF);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(c, 24), dp(c, 14), dp(c, 24), dp(c, 14));
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.WHITE);
        on.setCornerRadius(dp(c, 14));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x22FFFFFF);
        off.setCornerRadius(dp(c, 14));
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, on);
        st.addState(new int[]{}, off);
        b.setBackground(st);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.BLACK, Color.WHITE}));
        return b;
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
