package com.joe.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;

/**
 * Lets the phone app switch the TV on and off. While the TV is off, Android's Low Power Standby cuts
 * MonolithicOS off the network, but Google's own remote service still answers, to a client it has paired with
 * (see TvRemote). So the TV pairs with it itself, once, over its own address: Google's service shows its code
 * for a moment, TypeHelper reads it off the screen, and the proof goes back. Every signed-in phone then gets
 * that identity from the phone page (/api/wake) and uses it. Nothing to set up on the phone, one pairing for all.
 *
 * Done a few seconds after Home comes up (never over a show), and tried again until it works, 10 minutes apart
 * at least. The TV can't check the pairing itself: Google's service pairs with its own address but won't take
 * its remote connections (turned away even with a pairing a Mac was using fine, 29 Oct). So a phone that gets
 * turned away says so the next time it loads the page (/api/wake?forgot=1), and the TV pairs again.
 */
final class PhoneWake {
    private PhoneWake() { }

    private static final String TAG = "MonolithicOS";
    private static volatile boolean running;
    private static long triedAt = -600_000;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("wake", Context.MODE_PRIVATE);
    }

    static boolean ready(Context c) {
        return prefs(c).getBoolean("paired", false);
    }

    /** What the phone page hands the phone app: the key, its certificate and the TV's, or not ready yet. */
    static String identity(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("paired", false)) return "{\"ready\":false}";
        return "{\"ready\":true,\"key\":\"" + p.getString("key", "") + "\",\"cert\":\"" + p.getString("cert", "")
                + "\",\"tv\":\"" + p.getString("tv", "") + "\"}";
    }

    /** A phone was turned away with the identity (unpaired in the TV's settings, say): pair again, soon. */
    static void forget(Context context) {
        Context c = context.getApplicationContext();
        prefs(c).edit().putBoolean("paired", false).apply();
        triedAt = -600_000;
        if (HomeActivity.front) new Thread(() -> check(c), "phone-wake").start();   // else when Home next comes up
    }

    /** From Home on screen (on a worker thread): pair, if it isn't. */
    static void check(Context context) {
        Context c = context.getApplicationContext();
        if (running) return;
        SharedPreferences p = prefs(c);
        if (p.getBoolean("paired", false)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - triedAt < 600_000 || !HomeActivity.front || !TypeHelper.on()) return;
        triedAt = now;
        running = true;
        try {
            if (!p.contains("cert")) {
                byte[][] id = TvRemote.identity();
                p.edit().putString("key", Base64.encodeToString(id[0], Base64.NO_WRAP))
                        .putString("cert", Base64.encodeToString(id[1], Base64.NO_WRAP)).commit();
            }
            TvRemote r = remote(p);
            TypeHelper.lookForCode();
            boolean ok = false;
            try (TvRemote.Pairing pairing = r.pair("127.0.0.1", "MonolithicOS")) {
                for (long end = SystemClock.uptimeMillis() + 12_000; SystemClock.uptimeMillis() < end; ) {
                    Thread.sleep(250);
                    String code = TypeHelper.takeCode();
                    if (code != null) {
                        ok = pairing.finish(code);
                        if (!ok) Log.i(TAG, "phone wake: read " + code.length() + " characters, but not the code");
                        break;
                    }
                }
            }
            if (ok) {
                String tv = r.id("127.0.0.1");   // how the phone recognises the TV at a new address
                p.edit().putBoolean("paired", true).putString("tv", tv == null ? "" : tv).apply();
                Log.i(TAG, "phone wake: paired, phones can switch the TV on now");
            } else {
                Log.i(TAG, "phone wake: pairing didn't finish, trying again in 10 minutes");
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            Log.i(TAG, "phone wake: pairing failed (" + e + "), trying again in 10 minutes");
        } finally {
            running = false;
        }
    }

    private static TvRemote remote(SharedPreferences p) throws IOException {
        return new TvRemote(Base64.decode(p.getString("key", ""), 0), Base64.decode(p.getString("cert", ""), 0));
    }
}
