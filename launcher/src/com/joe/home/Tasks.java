package com.joe.home;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Task Manager shows: the TV's memory, storage and uptime (anyone can read those), and through the
 * TV's own debugging (see Adb) its CPU load, temperature and the apps running, each with its CPU and memory,
 * plus force stopping one and clearing out the background. Only read while the Task Manager is open.
 */
final class Tasks {
    private Tasks() { }

    /** One app's processes, added up. cpu: percent of the whole TV (all its cores). mem: bytes resident. */
    static final class App {
        final String pkg;
        float cpu;
        long mem;

        App(String pkg) { this.pkg = pkg; }
    }

    static final class Snapshot {
        boolean adb;                // false: the TV's debugging isn't allowed, so memory, storage, uptime only
        float cpu = -1, temp = -1;  // percent; degrees C
        long memUsed, memTotal, diskUsed, diskTotal, uptime;
        final List<App> apps = new ArrayList<>();
    }

    // the TV's own: stopping these does nothing useful or breaks the picture, so they're never listed
    private static final String[] CORE = {"android", "com.android.systemui", "com.android.phone", "com.tcl.tv",
            "com.google.android.gms", "com.google.android.gsf", "com.android.providers.media.module"};
    private static final Map<String, Boolean> listed = new HashMap<>();

    /** a: a signed-in connection, or null for what needs none. */
    static Snapshot read(Context c, Adb a) {
        Snapshot s = new Snapshot();
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        s.memTotal = mi.totalMem;
        s.memUsed = mi.totalMem - mi.availMem;
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        s.diskTotal = fs.getTotalBytes();
        s.diskUsed = fs.getTotalBytes() - fs.getAvailableBytes();
        s.uptime = SystemClock.elapsedRealtime();
        if (a == null) return s;
        try {
            top(c, a.shell("top -b -n 1 -o PID,%CPU,RES,NAME"), s);
            String t = a.shell("dumpsys thermalservice | grep -m1 'mName=CPU'");
            int i = t.indexOf("mValue=");
            if (i >= 0) s.temp = Float.parseFloat(t.substring(i + 7, t.indexOf(',', i)));
            s.adb = true;
        } catch (IOException | RuntimeException ignored) { }
        return s;
    }

    /** Toybox top: "400%cpu ... 332%idle ..." then a line per process, PID %CPU RES NAME. */
    private static void top(Context c, String out, Snapshot s) {
        float cores = 1;
        Map<String, App> apps = new LinkedHashMap<>();
        boolean rows = false;
        for (String line : out.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (!rows) {
                if (f.length > 0 && f[0].endsWith("%cpu")) {
                    cores = Float.parseFloat(f[0].substring(0, f[0].length() - 4)) / 100f;
                    for (String x : f) {
                        if (x.endsWith("%idle")) s.cpu = 100f - Float.parseFloat(x.substring(0, x.length() - 5)) / cores;
                    }
                }
                if (f.length > 0 && f[0].startsWith("PID")) rows = true;
                continue;
            }
            if (f.length < 4 || f[3].startsWith("[")) continue;   // kernel threads
            String pkg = f[3].split(":")[0];
            if (!app(c, pkg)) continue;
            App app = apps.get(pkg);
            if (app == null) apps.put(pkg, app = new App(pkg));
            try {
                app.cpu += Float.parseFloat(f[1]) / cores;
                app.mem += bytes(f[2]);
            } catch (NumberFormatException ignored) { }
        }
        s.apps.addAll(apps.values());
        s.apps.sort((x, y) -> Long.compare(y.mem, x.mem));
    }

    /** An app you'd recognise: one you installed, or one you can open. Not the TV's own plumbing. */
    private static boolean app(Context c, String pkg) {
        Boolean known = listed.get(pkg);
        if (known != null) return known;
        boolean yes = false;
        for (String core : CORE) if (core.equals(pkg)) { listed.put(pkg, false); return false; }
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            yes = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    || pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
        } catch (PackageManager.NameNotFoundException ignored) { }
        listed.put(pkg, yes);
        return yes;
    }

    /** "156M", "4.1M", "2.1G", "512K", "0" (top's RES). */
    private static long bytes(String v) {
        char u = Character.toUpperCase(v.charAt(v.length() - 1));
        double n = Double.parseDouble(Character.isDigit(u) ? v : v.substring(0, v.length() - 1));
        return (long) (n * (u == 'G' ? 1L << 30 : u == 'M' ? 1L << 20 : u == 'K' ? 1L << 10 : 1));
    }

    static void forceStop(Adb a, String pkg) throws IOException {
        a.shell("am force-stop " + pkg);
    }

    /** Every background process Android can spare, gone (what you're watching and Home stay). */
    static void freeUp(Adb a) throws IOException {
        a.shell("am kill-all");
    }

    // ---- how it reads ----

    static String size(long b) {
        if (b >= 1L << 30) return String.format(java.util.Locale.US, "%.1f GB", b / (double) (1L << 30));
        return (b >> 20) + " MB";
    }

    static String uptime(long ms) {
        long m = ms / 60_000, h = m / 60, d = h / 24;
        return d > 0 ? d + "d " + (h % 24) + "h" : h > 0 ? h + "h " + (m % 60) + "m" : m + "m";
    }
}
