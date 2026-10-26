package com.joe.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * The phone page's hands on the TV: D-pad, Back and Home as accessibility actions, and typing into
 * whatever text box has focus. Unlike KeyHelper it never filters keys, so the real remote (held
 * volume included) is untouched. Being bound also keeps the phone page's server running while an
 * app is on screen.
 */
public final class TypeHelper extends AccessibilityService {
    private static volatile TypeHelper live;

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

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
