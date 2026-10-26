package com.joe.home;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The TV-wide switches MonolithicOS Settings and the phone page flip: ad-blocking DNS, animation speed,
 * the screensaver and the two accessibility helpers. Writing them needs WRITE_SECURE_SETTINGS,
 * which the installer grants once with pm grant; without it everything still reads fine.
 */
final class Tweaks {
    static final String DNS_HOST = "dns.adguard-dns.com";
    static final String AMBIENT = "com.joe.home/com.joe.home.AmbientDream";
    static final String GOOGLE_DREAM = "com.google.android.apps.tv.dreamx/.service.Backdrop";
    static final String TYPE_HELPER = "com.joe.home/com.joe.home.TypeHelper";
    static final String KEY_HELPER = "com.joe.home/com.joe.home.KeyHelper";
    private static final String[] SCALES = {Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.ANIMATOR_DURATION_SCALE};

    private Tweaks() { }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean adblock(Context c) {
        return "hostname".equals(Settings.Global.getString(c.getContentResolver(), "private_dns_mode"));
    }

    static void setAdblock(Context c, boolean on) {
        ContentResolver cr = c.getContentResolver();
        if (on) Settings.Global.putString(cr, "private_dns_specifier", DNS_HOST);
        Settings.Global.putString(cr, "private_dns_mode", on ? "hostname" : "off");
    }

    static boolean fast(Context c) {
        return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) < 0.9f;
    }

    static void setFast(Context c, boolean on) {
        for (String k : SCALES) Settings.Global.putFloat(c.getContentResolver(), k, on ? 0.5f : 1f);
    }

    static boolean ambient(Context c) {
        return AMBIENT.equals(Settings.Secure.getString(c.getContentResolver(), "screensaver_components"));
    }

    static void setAmbient(Context c, boolean on) {
        Settings.Secure.putString(c.getContentResolver(), "screensaver_components", on ? AMBIENT : GOOGLE_DREAM);
    }

    static boolean service(Context c, String component) {
        return services(c).contains(component);
    }

    /** Add or drop one accessibility service, leaving any others alone. */
    static void setService(Context c, String component, boolean on) {
        ContentResolver cr = c.getContentResolver();
        List<String> list = services(c);
        list.remove(component);
        if (on) list.add(component);
        // G08 can leave a list it already had unbound until the switch flips: clear, then write back
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, TextUtils.join(":", list));
        if (!list.isEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    private static List<String> services(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> out = new ArrayList<>();
        if (!TextUtils.isEmpty(s)) {
            for (String part : Arrays.asList(s.split(":"))) if (!part.isEmpty()) out.add(part);
        }
        return out;
    }
}
