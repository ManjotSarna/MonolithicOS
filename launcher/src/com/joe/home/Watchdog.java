package com.joe.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Notices when a TCL firmware update (or anything else) undoes the setup: Google TV's launcher or
 * debloated apps switched back on, or removed apps put back. The installer takes a snapshot of the good
 * state (a broadcast to {@link Receiver}) once MonolithicOS is Home; Home compares against it
 * in the background, and at boot the receiver brings Home up front if Google TV's launcher is back,
 * because then the Home button goes there and the warning would never be seen.
 */
final class Watchdog {
    static final String SNAPSHOT = "com.joe.home.SNAPSHOT", FORGET = "com.joe.home.FORGET";
    private static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx";
    private static final String WIZARD = "com.google.android.tungsten.setupwraith";

    /** What changed since the snapshot. */
    static final class Report {
        final List<String> back = new ArrayList<>();   // labels of apps on again or put back
        boolean googleHome;                              // Google TV's launcher (or the setup wizard) is on again
        boolean updated;                                 // the firmware itself changed
        String version;

        boolean bad() { return googleHome || !back.isEmpty(); }

        /** One line for the banner and the phone page. */
        String text() {
            String what = googleHome ? "Google TV's launcher is back on"
                    : back.size() + (back.size() == 1 ? " switched-off app is" : " switched-off apps are") + " back on";
            return (updated ? "TCL update: " : "") + what + "  ·  run the MonolithicOS installer again";
        }
    }

    // the last check, for the phone page (which polls too often to scan every package itself)
    static volatile String lastText;

    private Watchdog() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    private static List<ApplicationInfo> apps(Context c) {
        return c.getPackageManager().getInstalledApplications(
                PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS);
    }

    /** Remember the current state as the good one. Off the main thread: it reads every package. */
    static void snapshot(Context c) {
        Set<String> disabled = new HashSet<>(), system = new HashSet<>();
        for (ApplicationInfo ai : apps(c)) {
            if (!ai.enabled) disabled.add(ai.packageName);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) system.add(ai.packageName);
        }
        prefs(c).edit().putString("wd_fp", Build.FINGERPRINT)
                .putString("wd_off", TextUtils.join(",", disabled))
                .putString("wd_sys", TextUtils.join(",", system)).commit();
        lastText = null;
    }

    /** Stop watching (the uninstaller: Google TV's launcher is meant to be on now). */
    static void forget(Context c) {
        prefs(c).edit().remove("wd_fp").remove("wd_off").remove("wd_sys").commit();
        lastText = null;
    }

    /** Null when there's no snapshot to compare with. Off the main thread. */
    static Report check(Context c) {
        SharedPreferences p = prefs(c);
        String fp = p.getString("wd_fp", null);
        if (fp == null) return null;
        Set<String> off = split(p.getString("wd_off", "")), system = split(p.getString("wd_sys", ""));
        Report r = new Report();
        r.updated = !fp.equals(Build.FINGERPRINT);
        r.version = Build.VERSION.INCREMENTAL;
        PackageManager pm = c.getPackageManager();
        for (ApplicationInfo ai : apps(c)) {
            boolean onAgain = ai.enabled && off.contains(ai.packageName);
            boolean putBack = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && !system.contains(ai.packageName);
            if (!onAgain && !putBack) continue;
            if (GOOGLE_HOME.equals(ai.packageName) || WIZARD.equals(ai.packageName)) r.googleHome = true;
            r.back.add(ai.loadLabel(pm).toString());
        }
        lastText = r.bad() ? r.text() : null;
        return r;
    }

    /** The firmware changed but nothing came back: just remember the new version. */
    static void acceptFirmware(Context c) {
        prefs(c).edit().putString("wd_fp", Build.FINGERPRINT).apply();
    }

    private static Set<String> split(String s) {
        return s.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(s.split(",")));
    }

    /** Snapshot / forget from the installer, and the check at boot. */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Context c = context.getApplicationContext();
            String a = intent.getAction();
            PendingResult done = goAsync();
            new Thread(() -> {
                try {
                    if (SNAPSHOT.equals(a)) snapshot(c);
                    else if (FORGET.equals(a)) forget(c);
                    else if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
                        Report r = check(c);
                        if (r != null && r.googleHome) {
                            c.startActivity(new Intent(c, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        }
                    }
                } catch (RuntimeException ignored) {
                } finally {
                    done.finish();
                }
            }, "watchdog").start();
        }
    }
}
