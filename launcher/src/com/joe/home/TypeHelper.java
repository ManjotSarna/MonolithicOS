package com.joe.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The phone page's hands on the TV: D-pad, Back and Home as accessibility actions, and typing into
 * whatever text box has focus, and notes which app was last on screen (so Home knows when it's back from
 * an HDMI input). It never filters keys, so the real remote (held
 * volume included) is untouched. Being bound also keeps the phone page's server running while an
 * app is on screen. And when the phone app pairs with Google's remote service (so it can switch the TV on),
 * it reads the code the TV shows off the screen, so nobody has to type it.
 */
public final class TypeHelper extends AccessibilityService {
    private static volatile TypeHelper live;
    // the last app on screen other than Home (window changes only), so Home knows it's coming back from an input
    static volatile String lastApp;

    @Override
    protected void onServiceConnected() {
        live = this;
        Remote.start(getApplicationContext());
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (live == this) live = null;
        return super.onUnbind(intent);
    }

    static boolean on() {
        return live != null;
    }

    /** up, down, left, right, ok, back or home. False if the helper is off or the key is unknown. */
    static boolean press(String key) {
        TypeHelper s = live;
        if (s == null) return false;
        int a;
        switch (key) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            default:
                if (Build.VERSION.SDK_INT < 33) return false;
                switch (key) {
                    case "up": a = GLOBAL_ACTION_DPAD_UP; break;
                    case "down": a = GLOBAL_ACTION_DPAD_DOWN; break;
                    case "left": a = GLOBAL_ACTION_DPAD_LEFT; break;
                    case "right": a = GLOBAL_ACTION_DPAD_RIGHT; break;
                    case "ok": a = GLOBAL_ACTION_DPAD_CENTER; break;
                    default: return false;
                }
        }
        return s.performGlobalAction(a);
    }

    /**
     * Replace the focused text box's contents with text, then optionally press its Enter / Search.
     * Returns "ok", "none" (no text box has focus: many TV apps draw their own keyboard) or "off".
     */
    static String type(String text, boolean enter) {
        TypeHelper s = live;
        if (s == null) return "off";
        AccessibilityNodeInfo n = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null || !n.isEditable()) return "none";
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (ok && enter && Build.VERSION.SDK_INT >= 30) {
            n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return ok ? "ok" : "none";
    }

    // ---- the pairing code ----

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static long lookUntil;
    private static volatile String code;

    /** For the next 20 s, look for a pairing code on screen (only asked for while Home is in front). */
    static void lookForCode() {
        main.post(() -> {
            code = null;
            boolean already = SystemClock.uptimeMillis() < lookUntil;
            lookUntil = SystemClock.uptimeMillis() + 20_000;
            if (!already) main.post(look);
        });
    }

    /** The code once it's been seen (each one handed out once), or null. */
    static String takeCode() {
        String c = code;
        code = null;
        return c;
    }

    private static final Runnable look = new Runnable() {
        @Override
        public void run() {
            TypeHelper s = live;
            if (s == null || SystemClock.uptimeMillis() > lookUntil) return;
            String c = s.findCode();
            if (c != null) {
                code = c;
                lookUntil = 0;
                return;
            }
            main.postDelayed(this, 250);
        }
    };

    /** 6 hex characters in a window that isn't ours: in one text, or one per box. */
    private String findCode() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) roots.add(r);
            }
        } catch (RuntimeException ignored) { }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) roots.add(active);
        for (AccessibilityNodeInfo r : roots) {
            if (r.getPackageName() == null || getPackageName().contentEquals(r.getPackageName())) continue;
            List<String> texts = new ArrayList<>();
            collect(r, texts, 0);
            StringBuilder run = new StringBuilder();
            for (String t : texts) {
                String h = t.replaceAll("\\s", "");
                if (h.matches("[0-9A-Fa-f]{6}")) return h.toUpperCase(java.util.Locale.US);
                if (h.matches("[0-9A-Fa-f]{1,3}")) {
                    run.append(h);
                    if (run.length() == 6) return run.toString().toUpperCase(java.util.Locale.US);
                } else {
                    run.setLength(0);
                }
            }
        }
        return null;
    }

    private static void collect(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 30) return;
        if (n.getText() != null && n.getText().length() > 0) out.add(n.getText().toString());
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (!pkg.equals(getPackageName()) && !pkg.equals("com.android.systemui")) lastApp = pkg;
    }
    @Override public void onInterrupt() { }
}
