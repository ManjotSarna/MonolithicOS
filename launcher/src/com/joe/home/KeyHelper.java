package com.joe.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opt-in: the remote's app buttons, everywhere. With Google TV's launcher off, the YouTube button
 * (BUTTON_3, a "global key" Android hands only to that launcher) does nothing, and Netflix / Prime
 * only work while Home is on screen. Every other key passes straight through. The trade-off on this
 * TV: while any key-filtering service is bound, holding volume steps once instead of ramping.
 */
public final class KeyHelper extends AccessibilityService {
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        String pkg = k == KeyEvent.KEYCODE_BUTTON_3 ? "com.google.android.youtube.tv"
                : k == 4062 ? "com.netflix.ninja"
                : k == 4099 ? "com.amazon.amazonvideo.livingroom" : null;
        if (pkg == null) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) open(pkg);
        return true;
    }

    private void open(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
