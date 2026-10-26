package com.joe.home;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * What the computer installer does, done from the TV through its own ADB (see Adb): give MonolithicOS its
 * permissions and switch Google TV's home off, debloat, and put it all back. The same steps and order as
 * installer/files/install.sh, with the same traps handled:
 *  - the setup wizard is a hidden fallback home that outranks normal apps, so it goes off too, but switching
 *    it off deletes any Wi-Fi network it saved during setup: refused while it owns one;
 *  - every package change first, the Home choice last (Android forgets it when the set of homes changes);
 *  - with Google's home off, uninstalling MonolithicOS would leave TV Settings' blank fallback home and no
 *    way out without a computer, so a tiny safety app goes on first (res/raw/guard.apk, see guard/): it only
 *    ever shows if MonolithicOS is gone, with one button back to Google TV.
 * Runs on a worker thread; every step is a shell command, so each returns what failed or null.
 */
final class Setup {
    private Setup() { }

    static final String PKG = "com.joe.home", ACT = PKG + "/.HomeActivity", GUARD = "com.joe.home.guard";
    static final String GTV = "com.google.android.apps.tv.launcherx", ATV = "com.google.android.tvlauncher",
            SETUP = "com.google.android.tungsten.setupwraith", GOOGLE_DREAM = "com.google.android.apps.tv.dreamx/.service.Backdrop";
    private static final String[] JUNK = {
            "com.tcl.esticker", "com.tcl.partnercustomizer", "com.tcl.messagebox", "com.tcl.bi", "com.tcl.tv.tclhome_passive",
            "com.tcl.bootadservice", "com.tcl.overseasappshow", "com.tcl.guard", "com.tcl.tvweishi", "com.tcl.notereminder",
            "com.tcl.smartalexa", "com.tcl.exhibit", "com.tcl.hotelmenu", "com.tcl.ocean.instructions", "com.tcl.logkit",
            "com.tcl.browser", "com.tcl.tv.plus", "com.tcl.channelplus", "com.tcl.smartlink.core", "com.tcl.interactive",
            "com.tcl.usercenter", "android.autoinstalls.config.google.gtvpai", "com.google.android.feedback",
            "com.google.android.play.games", "com.android.printspooler", "com.android.adservices.api",
            "com.android.ondevicepersonalization.services", "com.android.federatedcompute.services",
            "com.android.tv.feedbackconsent"};
    private static final String[] FREEBIES = {
            "com.tubitv", "tv.pluto.android", "com.haystack.android", "com.xumo.xumo", "com.cbs.ca", "com.cbs.ott",
            "ca.bellmedia.cravetv", "com.quickplay.android.bellmediaplayer", "com.google.android.youtube.tvmusic"};

    /** Where the TV stands. */
    enum State { DEBUG_OFF, NEED_ALLOW, GOOGLE_HOME, MONOLITHIC_HOME }

    /** A quick look, without asking to be allowed. */
    static State state(Context c) {
        try (Adb a = Adb.connect(c, 0)) {
            return home(a).equals(ACT) && !enabled(a, GTV) ? State.MONOLITHIC_HOME : State.GOOGLE_HOME;
        } catch (Adb.Closed e) {
            return "USB debugging is off".equals(e.getMessage()) ? State.DEBUG_OFF : State.NEED_ALLOW;
        } catch (IOException e) {
            return State.DEBUG_OFF;
        }
    }

    /** Signs in (the TV asks Allow, up to a minute). Null when it worked, else why not. */
    static String allow(Context c) {
        try (Adb a = Adb.connect(c, 60_000)) {
            return null;
        } catch (Adb.Closed e) {
            return "USB debugging is off".equals(e.getMessage()) ? "Turn on USB debugging first" : "The TV didn't allow it";
        } catch (IOException e) {
            return "Couldn't reach the TV's debugging";
        }
    }

    /** Permissions, the safety app, Google's home off, MonolithicOS the Home. Null when done, else why not. */
    static String switchOver(Context c) {
        try (Adb a = Adb.connect(c, 0)) {
            if (enabled(a, SETUP) && a.shell("dumpsys wifi").contains("cname=" + SETUP)) {
                return "The TV's setup wizard still owns your Wi-Fi and switching would delete it. Once: Settings > "
                        + "Network & Internet > your network > Forget, join it again, then try this again.";
            }
            permissions(a);
            String err = installGuard(c, a);
            if (err != null) return err;
            for (String p : new String[]{SETUP, GTV, ATV}) if (enabled(a, p)) a.shell("pm disable-user --user 0 " + p);
            a.shell("sleep 1; cmd package set-home-activity --user 0 " + ACT);
            if (!home(a).equals(ACT)) {   // didn't stick: back as it was
                restoreHome(a);
                return "The switch didn't stick on this TV and was undone";
            }
            Watchdog.snapshot(c);   // this is the good state now
            return null;
        } catch (IOException e) {
            return "Lost the connection to the TV's debugging: " + e.getMessage();
        }
    }

    /** What the installer grants: Now Playing, photos, Settings' TV-wide switches, the overlay, boot, the helper. */
    private static void permissions(Adb a) throws IOException {
        a.shell("cmd notification allow_listener " + PKG + "/" + PKG + ".NowPlayingListener; "
                + "pm grant " + PKG + " android.permission.READ_MEDIA_IMAGES; "
                + "pm grant " + PKG + " android.permission.WRITE_SECURE_SETTINGS; "
                + "cmd appops set " + PKG + " SYSTEM_ALERT_WINDOW allow; "
                + "cmd appops set " + PKG + " AUTO_START allow; cmd appops write-settings; "
                + "settings put secure screensaver_components " + PKG + "/" + PKG + ".AmbientDream");
        String svc = PKG + "/" + PKG + ".TypeHelper", cur = a.shell("settings get secure enabled_accessibility_services");
        if (cur.equals("null")) cur = "";
        if (!cur.contains(svc)) {
            a.shell("settings put secure enabled_accessibility_services '" + (cur.isEmpty() ? svc : cur + ":" + svc)
                    + "'; settings put secure accessibility_enabled 1");
        }
    }

    private static String installGuard(Context c, Adb a) throws IOException {
        byte[] apk;
        try (InputStream in = c.getResources().openRawResource(R.raw.guard)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            apk = o.toByteArray();
        }
        String tmp = "/data/local/tmp/monolithicos-guard.apk";
        a.push(apk, tmp);
        String out = a.shell("pm install -r " + tmp + "; rm -f " + tmp);
        return out.contains("Success") ? null : "Couldn't install the safety app: " + out;
    }

    /** The installer's debloat and tune: TCL and Google ad, promo and telemetry off, freebies gone, ads blocked. */
    static String debloat(Context c) {
        try (Adb a = Adb.connect(c, 0)) {
            String off = a.shell("pm list packages -d"), all = a.shell("pm list packages");
            StringBuilder cmd = new StringBuilder();
            for (String p : JUNK) if (has(all, p) && !has(off, p)) cmd.append("pm disable-user --user 0 ").append(p).append(" >/dev/null 2>&1; ");
            for (String p : FREEBIES) if (has(all, p)) cmd.append("pm uninstall --user 0 ").append(p).append(" >/dev/null 2>&1; ");
            cmd.append("settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.adguard-dns.com; ");
            for (String p : new String[]{"window_animation_scale", "transition_animation_scale", "animator_duration_scale"}) {
                cmd.append("settings put global ").append(p).append(" 0.5; ");
            }
            a.shell(cmd.toString());
            Watchdog.snapshot(c);
            return null;
        } catch (IOException e) {
            return "Lost the connection to the TV's debugging: " + e.getMessage();
        }
    }

    /** Everything back the way Google shipped it, MonolithicOS still installed. Null when done. */
    static String restore(Context c) {
        try (Adb a = Adb.connect(c, 0)) {
            Watchdog.forget(c);
            String svc = PKG + "/" + PKG + ".TypeHelper", cur = a.shell("settings get secure enabled_accessibility_services");
            if (cur.contains(svc)) {
                String rest = cur.replace(":" + svc, "").replace(svc + ":", "").replace(svc, "");
                a.shell(rest.isEmpty() || rest.equals("null") ? "settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0"
                        : "settings put secure enabled_accessibility_services '" + rest + "'");
            }
            if (a.shell("settings get secure screensaver_components").equals(PKG + "/" + PKG + ".AmbientDream")) {
                a.shell("settings put secure screensaver_components " + GOOGLE_DREAM);
            }
            String all = a.shell("pm list packages");
            StringBuilder cmd = new StringBuilder();
            for (String p : JUNK) if (has(all, p)) cmd.append("pm enable --user 0 ").append(p).append(" >/dev/null 2>&1; ");
            for (String p : FREEBIES) cmd.append("cmd package install-existing --user 0 ").append(p).append(" >/dev/null 2>&1; ");
            if (a.shell("settings get global private_dns_specifier").equals("dns.adguard-dns.com")) {
                cmd.append("settings put global private_dns_mode opportunistic; settings delete global private_dns_specifier; ");
            }
            for (String p : new String[]{"window_animation_scale", "transition_animation_scale", "animator_duration_scale"}) {
                cmd.append("settings put global ").append(p).append(" 1; ");
            }
            a.shell(cmd.toString());
            restoreHome(a);
            a.shell("pm uninstall " + GUARD + " >/dev/null 2>&1");   // Google's home is back: not needed
            return null;
        } catch (IOException e) {
            return "Lost the connection to the TV's debugging: " + e.getMessage();
        }
    }

    private static void restoreHome(Adb a) throws IOException {
        String all = a.shell("pm list packages");
        for (String p : new String[]{SETUP, GTV, ATV}) if (has(all, p)) a.shell("pm enable --user 0 " + p);
        String homes = a.shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
        for (String p : new String[]{GTV, ATV}) {
            for (String line : homes.split("\n")) {
                line = line.trim();
                if (line.startsWith(p + "/")) {
                    a.shell("cmd package set-home-activity --user 0 " + line + "; sleep 2; input keyevent KEYCODE_HOME");
                    return;
                }
            }
        }
    }

    // ---- small helpers ----

    private static String home(Adb a) throws IOException {
        String[] lines = a.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").split("\n");
        return lines[lines.length - 1].trim();
    }

    /** Installed and not switched off. ("pm list packages -e" with a name lists disabled ones too, here.) */
    private static boolean enabled(Adb a, String pkg) throws IOException {
        return has(a.shell("pm list packages " + pkg), pkg) && !has(a.shell("pm list packages -d " + pkg), pkg);
    }

    private static boolean has(String list, String pkg) {
        for (String line : list.split("\n")) if (line.trim().equals("package:" + pkg)) return true;
        return false;
    }

    // ---- the phone remote's keys and typing, when the accessibility helper is off ----

    private static Adb keys;

    /** Presses a key on the TV ("input keyevent"): false if the TV's debugging isn't there. */
    static synchronized boolean key(Context c, int code) {
        return run(c, "cmd input keyevent " + code);
    }

    /** Types text into whatever has focus. */
    static synchronized boolean type(Context c, String text) {
        return run(c, "cmd input text '" + text.replace(" ", "%s").replace("'", "") + "'");
    }

    private static boolean run(Context c, String cmd) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (keys == null) keys = Adb.connect(c, 0);
                keys.shell(cmd);
                return true;
            } catch (IOException e) {
                if (keys != null) keys.close();
                keys = null;
            }
        }
        return false;
    }
}
