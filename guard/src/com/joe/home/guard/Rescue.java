package com.joe.home.guard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Only ever on screen if MonolithicOS is gone while Google TV's home is still switched off: a dark screen
 * with the way back. Google TV's home is turned on again from its app page in Settings (Enable), which needs
 * no computer; then Home goes to Google TV.
 */
public final class Rescue extends Activity {
    private static final String GTV = "com.google.android.apps.tv.launcherx";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(Color.BLACK);
        col.addView(text("MonolithicOS isn't installed", 34, 0xFFFFFFFF));
        TextView hint = text("Turn Google TV's home back on: Enable on the next screen, then press Home.", 16, 0x99FFFFFF);
        hint.setPadding(0, dp(14), 0, dp(36));
        col.addView(hint);
        TextView back = button("Bring Back Google TV");
        back.setOnClickListener(v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + GTV))));
        col.addView(back, new LinearLayout.LayoutParams(dp(420), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView settings = button("Open Settings");
        settings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(420), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        col.addView(settings, lp);
        setContentView(col);
        back.requestFocus();
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** White when focused, the tvOS way. */
    private TextView button(String s) {
        TextView b = text(s, 18, 0xFFFFFFFF);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(24), dp(14), dp(24), dp(14));
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.WHITE);
        on.setCornerRadius(dp(14));
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x22FFFFFF);
        off.setCornerRadius(dp(14));
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, on);
        st.addState(new int[]{}, off);
        b.setBackground(st);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.BLACK, Color.WHITE}));
        return b;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
