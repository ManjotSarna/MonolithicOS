package com.joe.remote;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.pm.PackageInstaller;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * MonolithicOS on the phone: the TV's remote page (http://<tv>:8899) full screen, without a browser around
 * it. The page does everything; this keeps its PIN cookie, lets it buzz and pick photos, and finds the TV
 * by itself, the first time and whenever its address changes (the TV announces itself on the home network as
 * _monolith._tcp); typing the address is the fallback.
 *
 * It also switches the TV on, like the power button on a remote. While the TV is off Android cuts MonolithicOS
 * off the network, so when the page doesn't answer the app shows a power button; pressing it asks Google's
 * remote service (which stays reachable) to turn the TV on, and the remote comes up once it's awake. Opening
 * the app never switches anything by itself. That takes an identity Google's service knows: the TV pairs itself
 * once (its PhoneWake) and the page hands the identity over, so there's nothing to set up here. If the router
 * gives the TV a new address, the app finds it again by the certificate Google's remote service shows. The
 * page's power button switches the TV off from here the same way, and brings this one up to switch it back on.
 *
 * And it keeps itself up to date: the TV carries the newest phone app (/app), and when that's newer than this
 * one, a note at the bottom offers to update in place.
 */
public final class MainActivity extends Activity {
    private static final int PICK = 1;
    private static final Interpolator GLIDE = new PathInterpolator(0.2f, 0f, 0f, 1f);   // the intro's curve

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WebView web;
    private LinearLayout offline;
    private TextView offlineBody;
    private LinearLayout manual;
    private android.widget.EditText manualIp;
    private ValueCallback<Uri[]> picked;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener finding;
    private LinearLayout power;      // the TV is off: its power button
    private PowerButton powerButton;
    private TextView powerTitle, powerHint;   // what the TV's doing, and what a tap does
    private View screen;             // what's up: the page, the power button or the offline note (none under the intro)
    private boolean failed;
    private boolean checking;        // finding out whether the TV is answering
    private volatile boolean busy;   // switching the TV on, in flight
    private long wokeAt = -60_000;   // when the TV was last told to come on
    private long offAt = -60_000;    // when the page last switched it off
    private volatile long wakeFetchedAt = -60_000;   // when the TV's wake identity was last collected
    private long updateAt = -3_600_000;   // when the TV was last asked for a newer app
    private long relocatedAt = -120_000;
    private String why;              // what's known about why the page isn't answering, in words, or null
    private LinearLayout banner;     // the note at the bottom: an update, or turning the TV on not set up
    private TextView bannerText, bannerGo;
    private static final String INSTALLED = "com.joe.remote.INSTALLED";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        nsd = (NsdManager) getSystemService(NSD_SERVICE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setAlpha(0f);   // drawing underneath until it's ready to come up (see show)
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);   // the page is laid out for its own sizes
        s.setUserAgentString(s.getUserAgentString() + " MonolithRemote/1");   // the page hides "Get the app"
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String here = address();
                if (here != null && u.getHost() != null && u.getHost().equals(Uri.parse(here).getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));   // anything else opens in the browser
                } catch (RuntimeException ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                failed = false;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    failed = true;
                    handler.removeCallbacks(reveal);
                    if (screen == web) {   // never Chrome's error page
                        screen = null;
                        web.animate().alpha(0f).setDuration(200).start();
                    }
                    if (waking() || checking) {   // coming on, or still finding out why: no error screen yet
                        handler.removeCallbacks(retry);
                        handler.postDelayed(retry, 1500);
                    } else if (screen != power) {   // off: the power button says it already
                        showOffline();
                    }
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (failed) return;
                handler.removeCallbacks(askAddress);
                handler.removeCallbacks(lookAgain);
                // it comes up when it says it's drawn (MonolithApp.ready); this, if it never does
                handler.removeCallbacks(reveal);
                handler.postDelayed(reveal, 1500);
                stopFinding();
                why = null;
                fetchWake();
                checkUpdate();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (picked != null) picked.onReceiveValue(null);
                picked = cb;
                try {
                    startActivityForResult(params.createIntent(), PICK);   // "Add photo": the phone's picker
                } catch (RuntimeException e) {
                    picked = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Object() {
            /** The page's power button, in the app: off from here, and this app's power button up to switch it back on. */
            @JavascriptInterface
            public void power() {
                handler.post(MainActivity.this::turnOff);
            }

            /** The page has drawn itself (the remote, or its PIN screen): up it comes, never half-drawn. */
            @JavascriptInterface
            public void ready() {
                handler.post(reveal);
            }
        }, "MonolithApp");
        FrameLayout stage = new FrameLayout(this);   // what the intro reveals
        stage.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        offline = buildOffline();
        stage.addView(offline, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        power = buildPower();
        stage.addView(power, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        banner = buildBanner();
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bp.setMargins(dp(14), 0, dp(14), dp(18));
        root.addView(banner, bp);
        setContentView(root);
        registerReceiver(installed, new IntentFilter(INSTALLED), Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0);
        if (saved == null) {   // opening the app, not coming back to it: the TV's intro, while the page loads under it
            Intro.play(root, stage, getResources().getFont(R.font.outfit_thin), () -> screen != null);
        }
        if (address() != null) {
            load(address());
        } else {   // first run: find the TV on the Wi-Fi
            offlineBody.setText("Looking for MonolithicOS on your Wi-Fi\u2026");
            show(offline);
            find();
            handler.postDelayed(askAddress, 8000);
        }
    }

    private String address() {
        return prefs.getString("url", null);
    }

    private void load(String url) {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(askAddress);
        handler.removeCallbacks(reveal);
        if (screen != web) web.setAlpha(0f);   // loads and draws under what's up, and comes up when it's ready
        web.loadUrl(url);
        checkOn();
    }

    /** The page is up and drawn: it comes up over whatever was showing. */
    private final Runnable reveal = () -> {
        handler.removeCallbacks(this.reveal);
        if (!failed) show(web);
    };

    /**
     * Brings up one screen (the page, the power button or the offline note) over whichever is showing: the new one
     * fades in as it settles to size, the intro's way, and the old one fades off. No cuts anywhere.
     */
    private void show(View next) {
        View prev = screen;
        screen = next;
        if (prev == next && next.getVisibility() == View.VISIBLE) {   // already up: finish bringing it up
            next.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(GLIDE).start();
            return;
        }
        next.animate().cancel();
        next.setAlpha(0f);
        next.setScaleX(next == web ? 1.03f : 1.02f);
        next.setScaleY(next == web ? 1.03f : 1.02f);
        next.setVisibility(View.VISIBLE);
        next.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(next == web ? 520 : 420).setInterpolator(GLIDE).start();
        if (prev != null && prev != next) {
            prev.animate().cancel();
            prev.animate().alpha(0f).setDuration(260).setInterpolator(GLIDE).withEndAction(() -> {
                if (screen == prev) return;
                if (prev != web) prev.setVisibility(View.GONE);   // the page stays, unseen, to load under the rest
                prev.setScaleX(1f);
                prev.setScaleY(1f);
            }).start();
        }
    }

    /** Changes a line of text with a quick fade rather than a jump (at once if it isn't on screen). */
    private static void say(TextView t, String s) {
        if (s.contentEquals(t.getText()) && t.getAlpha() == 1f) return;
        t.animate().cancel();
        if (!t.isShown()) {
            t.setText(s);
            t.setAlpha(1f);
            return;
        }
        t.animate().alpha(0f).setDuration(110).withEndAction(() -> {
            t.setText(s);
            t.animate().alpha(1f).setDuration(180).start();
        }).start();
    }

    /** Nothing answered: let them type the address MonolithicOS Settings shows. */
    private final Runnable askAddress = () -> {
        say(offlineBody, why != null ? why : "Couldn't find it by itself. Type the address shown in MonolithicOS"
                + " Settings on the TV (Phone remote).");
        if (manual.getVisibility() != View.VISIBLE) {
            manual.setAlpha(0f);
            manual.setVisibility(View.VISIBLE);
            manual.animate().alpha(1f).setDuration(300).start();
        }
    };

    /** The TV didn't answer: say so, keep trying, and look for it on the network. */
    private void showOffline() {
        handler.removeCallbacks(lookAgain);
        handler.removeCallbacks(reveal);
        String body = why != null ? why : "Check the phone is on the home Wi-Fi and the TV is on.\nLooking for it…";
        if (screen == offline) say(offlineBody, body); else offlineBody.setText(body);
        show(offline);
        find();
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, 5000);
        handler.removeCallbacks(askAddress);
        handler.postDelayed(askAddress, 15000);
    }

    private final Runnable retry = () -> { if (address() != null && screen != power) load(address()); };

    /**
     * Stops whatever the page is loading (stuck on a dark TV, or the page still polling it). The WebView reports
     * a stopped load to onPageFinished like a finished one, which took down the power button and put the dead
     * page back: marked failed, so it doesn't.
     */
    private void abort() {
        failed = true;
        web.stopLoading();
    }

    private LinearLayout buildOffline() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(40), 0, dp(40), dp(40));
        box.setBackgroundColor(Color.BLACK);
        box.setVisibility(View.GONE);
        box.setClickable(true);   // nothing underneath (the page, loading unseen) takes a tap meant for this
        TextView title = text(22, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        title.setText("Can't reach the TV");
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        offlineBody = text(15, Typeface.DEFAULT, 0x99FFFFFF);
        offlineBody.setGravity(Gravity.CENTER);
        offlineBody.setLineSpacing(0, 1.25f);
        offlineBody.setPadding(0, dp(10), 0, dp(26));
        box.addView(offlineBody);
        TextView again = text(16, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.BLACK);
        again.setText("Try again");
        again.setPadding(dp(26), dp(12), dp(26), dp(12));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(24));
        again.setBackground(pill);
        again.setOnClickListener(v -> {
            say(offlineBody, "Trying…");
            why = null;   // a fresh look: the power button again if it's just off
            if (address() != null) load(address()); else find();
        });
        box.addView(again, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));   // a pill, not a bar

        manual = new LinearLayout(this);   // typing the address, when finding it didn't work
        manual.setOrientation(LinearLayout.HORIZONTAL);
        manual.setGravity(Gravity.CENTER_VERTICAL);
        manual.setPadding(0, dp(26), 0, 0);
        manual.setVisibility(View.GONE);
        manualIp = new android.widget.EditText(this);
        manualIp.setHint("192.168.1.20");
        manualIp.setHintTextColor(0x55FFFFFF);
        manualIp.setTextColor(Color.WHITE);
        manualIp.setSingleLine(true);
        manualIp.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        manual.addView(manualIp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = text(16, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        go.setText("Connect");
        go.setPadding(dp(16), dp(10), dp(4), dp(10));
        go.setOnClickListener(v -> {
            String ip = manualIp.getText().toString().trim().replaceAll("^https?://", "").replaceAll("[:/].*$", "");
            if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                say(offlineBody, "That doesn't look like an address: four numbers with dots, like 192.168.1.20.");
                return;
            }
            String url = "http://" + ip + ":8899/";
            prefs.edit().putString("url", url).apply();
            say(offlineBody, "Connecting…");
            load(url);
        });
        manual.addView(go);
        box.addView(manual, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    // ---- finding the TV --------------------------------------------------------------------

    private void find() {
        if (finding != null || nsd == null) return;
        NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int error) { finding = null; }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int error) { }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        String host = ipv4(i);
                        if (host == null) return;
                        String url = "http://" + host + ":" + i.getPort() + "/";
                        handler.post(() -> {
                            prefs.edit().putString("url", url).apply();
                            load(url);
                        });
                    }
                });
            }
        };
        finding = l;
        try {
            nsd.discoverServices("_monolith._tcp", NsdManager.PROTOCOL_DNS_SD, l);
        } catch (RuntimeException e) {
            finding = null;
        }
    }

    private void stopFinding() {
        if (finding == null) return;
        try { nsd.stopServiceDiscovery(finding); } catch (RuntimeException ignored) { }
        finding = null;
    }

    /** The TV's IPv4 address: the page only answers to plain IP addresses. */
    private static String ipv4(NsdServiceInfo i) {
        if (Build.VERSION.SDK_INT >= 34) {
            for (InetAddress a : i.getHostAddresses()) if (a instanceof Inet4Address) return a.getHostAddress();
            return null;
        }
        InetAddress a = i.getHost();
        return a instanceof Inet4Address ? a.getHostAddress() : null;
    }

    // ---- switching the TV on (TvRemote) ---------------------------------------------------

    /** Told the TV to come on in the last 25 s, or finding out whether it needs to be. */
    private boolean waking() {
        return busy || SystemClock.uptimeMillis() - wokeAt < 25_000;
    }

    // why the page isn't answering, when it can be told
    private static final String NO_WIFI = "Your phone isn't on Wi-Fi. Join the home Wi-Fi and the remote comes up.";
    private static final String AWAY = "Your phone's on a different Wi-Fi from the TV. Join the home Wi-Fi and the"
            + " remote comes up.";
    private static final String NOT_SET_UP = "Turn the TV on with its remote. Once it's sat on Home for a few"
            + " seconds, this app's power button can switch it on too.";
    private static final String UNREACHABLE = "The TV isn't answering at all. It may be unplugged or off at the wall,"
            + " or set to Low energy (TV Settings > System > Power & Energy), which switches its Wi-Fi off when it sleeps.";
    private static final String ON_BUT_QUIET = "The TV is on, but MonolithicOS isn't answering yet. If it's just"
            + " starting up, give it a few seconds; otherwise check Phone remote is on in MonolithicOS Settings.";
    private static final String FORGOTTEN = "The TV needs to set up switching on again. Turn it on with its remote"
            + " and leave it on Home for a few seconds.";

    /**
     * Is the page answering? If not, the power button (the TV is off, most likely), or why it can't help, for
     * the offline screen. Quick (the page is on the same Wi-Fi), so a TV that's off never shows as a dead page.
     * Never switches anything.
     */
    private void checkOn() {
        String url = address();
        if (url == null || checking || waking()) return;
        Uri u = Uri.parse(url);
        boolean paired = remote() != null;
        checking = true;
        new Thread(() -> {
            boolean up = answers(u.getHost(), u.getPort(), 1500);
            String reason = null;
            if (!up) {
                if (!onWifi()) reason = NO_WIFI;
                else if (!onTvNetwork(u.getHost())) reason = AWAY;   // someone else's Wi-Fi: no power button there
                else if (!paired) reason = NOT_SET_UP;
            }
            String because = reason;
            handler.post(() -> {
                checking = false;
                if (up) return;   // the page is loading (or its error says why not)
                abort();
                if (because != null) {
                    why = because;
                    showOffline();
                } else if (UNREACHABLE.equals(why) || ON_BUT_QUIET.equals(why)) {
                    showOffline();   // the power button already tried: keep saying why until Try again
                } else if (screen != power) {   // (already up: leave it as it is, mid-word or not)
                    powerButton.rest();
                    showPower(OFF, TAP);
                }
            });
        }, "check").start();
    }

    private static final String OFF = "The TV is off", ON = "The TV is on", TAP = "Tap to turn it on";

    /** The TV is off: its power button, and a look now and then in case it's switched on another way. */
    private void showPower(String title, String hint) {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(askAddress);
        handler.removeCallbacks(reveal);
        if (screen == power) {
            say(powerTitle, title);
            say(powerHint, hint);
        } else {
            powerTitle.setText(title);
            powerHint.setText(hint);
        }
        show(power);
        find();   // on, at a new address: found by name
        handler.removeCallbacks(lookAgain);
        handler.postDelayed(lookAgain, 4000);
    }

    /** Switched on with its own remote? Then the remote comes up here by itself. */
    private final Runnable lookAgain = new Runnable() {
        @Override public void run() {
            String url = address();
            if (url == null || busy || screen != power) return;
            // just switched off from the page: it still answers for a few seconds as it goes
            if (SystemClock.uptimeMillis() - offAt < 20_000) {
                handler.postDelayed(this, 4000);
                return;
            }
            Uri u = Uri.parse(url);
            new Thread(() -> {
                boolean up = answers(u.getHost(), u.getPort(), 800);
                handler.post(() -> {
                    if (busy || screen != power) return;
                    if (up) {   // on with its own remote: light up, and the remote comes up once it's drawn
                        powerButton.light();
                        say(powerTitle, ON);
                        say(powerHint, "");
                        load(url);
                    } else {
                        handler.postDelayed(this, 4000);
                    }
                });
            }, "look").start();
        }
    };

    /**
     * The power button: Google's remote service turns the TV on (paired), at a new address if the router gave
     * it another, and the remote comes up once the page answers. Or why not, for the offline screen.
     */
    private void turnOn() {
        String url = address();
        TvRemote r = remote();
        if (url == null || busy) return;
        if (r == null) {
            why = NOT_SET_UP;
            showOffline();
            return;
        }
        Uri u = Uri.parse(url);
        busy = true;
        handler.removeCallbacks(lookAgain);
        say(powerTitle, "Turning on…");
        say(powerHint, "");
        powerButton.breathe();
        long offSince = offAt;
        new Thread(() -> {
            String reason = null, moved = null;
            boolean woke = false, up = false;
            try {
                try {
                    woke = wakeAt(r, u.getHost());
                    // just switched off from the page: it can say it's still on for a moment
                    for (int i = 0; !woke && i < 4 && SystemClock.uptimeMillis() - offSince < 10_000; i++) {
                        Thread.sleep(1500);
                        woke = wakeAt(r, u.getHost());
                    }
                } catch (TvRemote.Unreachable e) {   // nothing (of ours) there: has it moved?
                    moved = relocate(r, u.getHost());
                    if (moved == null) throw e;
                    woke = wakeAt(r, moved);
                }
                // the page answers a second or so after it comes on (at once, if it was on all along)
                String h = moved != null ? moved : u.getHost();
                for (long end = SystemClock.uptimeMillis() + (woke ? 20_000 : 3000); SystemClock.uptimeMillis() < end; ) {
                    up = answers(h, u.getPort(), 600);
                    if (up) break;
                    Thread.sleep(300);
                }
                if (!up) reason = ON_BUT_QUIET;
            } catch (TvRemote.Unreachable e) {
                reason = UNREACHABLE;
            } catch (TvRemote.Rejected e) {
                forgotten();
                reason = FORGOTTEN;
            } catch (IOException | RuntimeException | InterruptedException ignored) {   // a blip: press it again
            }
            String because = reason, at = moved;
            boolean on = woke, answered = up;
            handler.post(() -> {
                busy = false;
                if (on) wokeAt = SystemClock.uptimeMillis();
                if (at != null) prefs.edit().putString("url", "http://" + at + ":" + u.getPort() + "/").apply();
                if (answered) {   // lit, a tap of confirmation, and the remote comes up over it once it's drawn
                    why = null;
                    powerButton.light();
                    powerButton.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                            ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
                    say(powerTitle, ON);
                    load(address());   // replaces the old request, which may be stuck on the dark TV
                } else if (because != null) {
                    powerButton.rest();
                    why = because;
                    showOffline();
                } else {
                    powerButton.rest();
                    say(powerTitle, OFF);
                    say(powerHint, "That didn't go through. Tap to try again");
                    handler.postDelayed(lookAgain, 4000);
                }
            });
        }, "wake").start();
    }

    /**
     * The page's power button, in the app: off through Google's remote service, the TV's own remote's way (the
     * TV can't do that itself: the service turns away its own connections), else through the page (the TV's own
     * debugging). The power button comes up at once, to switch it back on.
     */
    private void turnOff() {
        String url = address();
        if (url == null || busy) return;
        TvRemote r = remote();
        String cookie = CookieManager.getInstance().getCookie(url);
        busy = true;
        offAt = SystemClock.uptimeMillis();
        abort();
        showPower("Turning off…", "");
        powerButton.fade(1f, 0f, 1800);   // comes up lit, and dims as the TV goes down
        new Thread(() -> {
            boolean off = false;
            if (r != null) {
                try {
                    r.sleep(Uri.parse(url).getHost(), Build.MODEL, Build.MANUFACTURER);
                    off = true;
                } catch (TvRemote.Rejected e) {
                    forgotten();
                } catch (IOException | RuntimeException ignored) {
                }
            }
            if (!off) {
                try {
                    call(url + "api/key?k=power", cookie, true);
                    off = true;
                } catch (IOException ignored) {
                }
            }
            boolean done = off;
            handler.post(() -> {
                busy = false;
                if (done) {
                    powerButton.dim(400);
                    say(powerTitle, OFF);
                    say(powerHint, TAP);
                    handler.removeCallbacks(lookAgain);
                    handler.postDelayed(lookAgain, 4000);
                } else {   // still on: the remote comes back up
                    powerButton.rest();
                    offAt = -60_000;
                    load(url);
                    showBanner("Couldn't turn the TV off.", "Try again", () -> { hideBanner(); turnOff(); });
                }
            });
        }, "off").start();
    }

    /** Google's service turned this phone away: tell the TV next time the page is up, so it pairs again. */
    private void forgotten() {
        prefs.edit().remove("rpaired").putBoolean("rforgot", true).apply();
        wakeFetchedAt = -60_000;
    }

    private LinearLayout buildPower() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(40), 0, dp(40), dp(40));
        box.setBackgroundColor(Color.BLACK);
        box.setVisibility(View.GONE);
        box.setClickable(true);   // nothing underneath (the page, loading unseen) takes a tap meant for this
        powerButton = new PowerButton(this);
        powerButton.setContentDescription("Turn the TV on");
        powerButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            turnOn();
        });
        box.addView(powerButton, new LinearLayout.LayoutParams(dp(136), dp(136)));
        powerTitle = text(22, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        powerTitle.setText(OFF);
        powerTitle.setGravity(Gravity.CENTER);
        powerTitle.setPadding(0, dp(34), 0, 0);
        box.addView(powerTitle);
        powerHint = text(15, Typeface.DEFAULT, 0x99FFFFFF);
        powerHint.setGravity(Gravity.CENTER);
        powerHint.setPadding(0, dp(8), 0, 0);
        box.addView(powerHint);
        return box;
    }

    /** wake() at host, if it's our TV: another device there (a new address handed out) counts as nothing there. */
    private boolean wakeAt(TvRemote r, String host) throws IOException {
        String mine = prefs.getString("rtv", null);
        try {
            boolean woke = r.wake(host, Build.MODEL, Build.MANUFACTURER);
            if (r.seen != null && !r.seen.equals(mine)) prefs.edit().putString("rtv", r.seen).apply();
            return woke;
        } catch (TvRemote.Rejected e) {
            if (mine != null && r.seen != null && !mine.equals(r.seen)) throw new TvRemote.Unreachable(e);
            throw e;
        }
    }

    /**
     * The TV at a new address: every device on this Wi-Fi that answers on Google's remote port, checked for the
     * certificate our TV showed. Takes a few seconds; once a minute at most.
     */
    private String relocate(TvRemote r, String old) {
        String mine = prefs.getString("rtv", null);
        long now = SystemClock.uptimeMillis();
        if (mine == null || now - relocatedAt < 60_000) return null;
        relocatedAt = now;
        List<String> hosts = neighbours(old);
        ExecutorService pool = Executors.newFixedThreadPool(64);
        try {
            List<Future<Boolean>> open = new ArrayList<>();
            for (String h : hosts) open.add(pool.submit(() -> answers(h, TvRemote.REMOTE, 400)));
            for (int i = 0; i < hosts.size(); i++) {
                try {
                    if (open.get(i).get() && mine.equals(r.id(hosts.get(i)))) return hosts.get(i);
                } catch (Exception ignored) { }
            }
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The TV's address is on the phone's network (its subnet): at home, as opposed to another Wi-Fi. */
    private boolean onTvNetwork(String tv) {
        byte[] t;
        try {
            t = InetAddress.getByName(tv).getAddress();
        } catch (IOException e) {
            return true;   // not an address: let the usual checks speak
        }
        if (t.length != 4) return true;
        int at = ((t[0] & 0xFF) << 24) | ((t[1] & 0xFF) << 16) | ((t[2] & 0xFF) << 8) | (t[3] & 0xFF);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network n = lan();
        LinkProperties lp = cm == null || n == null ? null : cm.getLinkProperties(n);
        if (lp == null) return true;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (!(la.getAddress() instanceof Inet4Address)) continue;
            byte[] b = la.getAddress().getAddress();
            int me = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
            int mask = -1 << (32 - Math.max(16, Math.min(30, la.getPrefixLength())));
            return (me & mask) == (at & mask);
        }
        return true;
    }

    /** Every other address on the phone's Wi-Fi (its subnet, /22 at the widest: 1022 addresses). */
    private List<String> neighbours(String old) {
        List<String> out = new ArrayList<>();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network n = lan();
        LinkProperties lp = cm == null || n == null ? null : cm.getLinkProperties(n);
        if (lp == null) return out;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (!(la.getAddress() instanceof Inet4Address)) continue;
            byte[] b = la.getAddress().getAddress();
            int me = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
            int bits = Math.max(22, Math.min(30, la.getPrefixLength()));
            int mask = -1 << (32 - bits), base = me & mask, size = 1 << (32 - bits);
            for (int i = 1; i < size - 1; i++) {
                int a = base + i;
                if (a == me) continue;
                String h = ((a >>> 24) & 0xFF) + "." + ((a >>> 16) & 0xFF) + "." + ((a >>> 8) & 0xFF) + "." + (a & 0xFF);
                if (!h.equals(old)) out.add(h);
            }
            break;
        }
        return out;
    }

    private boolean onWifi() {
        return lan() != null;
    }

    /**
     * The phone's Wi-Fi (or Ethernet). Not the "active" network: under a VPN (Tailscale, say) that's the VPN, with
     * its own address, and the TV would look like it's on another network.
     */
    @SuppressWarnings("deprecation")   // getAllNetworks: the one call that lists them without a callback
    private Network lan() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return n;
        }
        return null;
    }

    private static boolean answers(String host, int port, int ms) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), ms);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Our side of the pairing (made once), or null until it's paired. */
    private TvRemote remote() {
        if (!prefs.getBoolean("rpaired", false)) return null;
        try {
            return new TvRemote(Base64.decode(prefs.getString("rkey", ""), 0), Base64.decode(prefs.getString("rcert", ""), 0));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Whenever the page is up (a minute apart at most): the identity the TV paired with Google's remote service
     * (the TV pairs itself, see its PhoneWake), so this phone can switch it on. Nothing to set up here.
     */
    private void fetchWake() {
        String url = address();
        long now = SystemClock.uptimeMillis();
        if (url == null || now - wakeFetchedAt < 60_000) return;
        wakeFetchedAt = now;
        String cookie = CookieManager.getInstance().getCookie(url);
        boolean forgot = prefs.getBoolean("rforgot", false);
        new Thread(() -> {
            try {
                org.json.JSONObject o = new org.json.JSONObject(call(url + "api/wake?v=" + build()
                        + (forgot ? "&forgot=1" : ""), cookie, false));
                SharedPreferences.Editor e = prefs.edit();
                if (forgot) e.remove("rforgot");   // told: the TV is pairing again
                if (o.optBoolean("ready")) {
                    e.putString("rkey", o.getString("key")).putString("rcert", o.getString("cert")).putBoolean("rpaired", true);
                    if (!o.optString("tv").isEmpty()) e.putString("rtv", o.getString("tv"));
                } else {
                    e.putBoolean("rpaired", false);   // the TV is (re)pairing: whatever this phone had is no good
                }
                e.apply();
            } catch (IOException | org.json.JSONException | RuntimeException ignored) {   // not signed in yet, or a blip: next time
                wakeFetchedAt = -60_000;
            }
        }, "wake-id").start();
    }

    // ---- keeping the app up to date ---------------------------------------------------------

    /*
     * The TV carries the newest phone app (/app). When it's newer than this one, it's fetched in the background
     * into an install session, and installed when you leave the app: from Android 12, an app that installed
     * itself last time may update itself without asking (UPDATE_PACKAGES_WITHOUT_USER_ACTION), so next time
     * it just opens as the new version. When Android still wants a yes (the first time, installed from a file),
     * a note offers Update while you're here, and the app reopens itself after.
     */

    /** Opening or coming back to the app (hourly at most): is the phone app the TV carries newer than this one? */
    private void checkUpdate() {
        String url = address();
        if (url == null || SystemClock.uptimeMillis() - updateAt < 3_600_000L) return;
        updateAt = SystemClock.uptimeMillis();
        new Thread(() -> {
            try {
                String body = call(url + "app/version?v=" + build(), null, false);
                long tv = Long.parseLong(body.replaceAll("[^0-9]", ""));
                if (tv <= build()) return;
                if (prefs.getLong("stagedVersion", 0) != tv || !staged()) stage(url, tv);
                if (prefs.getBoolean("updateAsks", false)) handler.post(this::offerUpdate);
            } catch (Exception ignored) { }
        }, "update").start();
    }

    /** The new version written into an install session, not installed yet (on a worker thread). */
    private void stage(String url, long version) throws IOException {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        int old = prefs.getInt("stagedSession", -1);
        if (old >= 0) try { pi.abandonSession(old); } catch (RuntimeException ignored) { }
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(getPackageName());
        if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session session = pi.openSession(id)) {
            HttpURLConnection c = (HttpURLConnection) new URL(url + "app").openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("remote.apk", 0, -1)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            try { pi.abandonSession(id); } catch (RuntimeException ignored) { }
            throw e;
        }
        prefs.edit().putInt("stagedSession", id).putLong("stagedVersion", version).apply();
    }

    private boolean staged() {
        int id = prefs.getInt("stagedSession", -1);
        return id >= 0 && getPackageManager().getPackageInstaller().getSessionInfo(id) != null;
    }

    /** Install what's staged. here: you're in the app (a yes, if asked for, is shown now; it reopens after). */
    private void install(boolean here) {
        int id = prefs.getInt("stagedSession", -1);
        if (id < 0 || !staged()) return;
        if (here) prefs.edit().putLong("reopenAt", System.currentTimeMillis()).apply();
        try (PackageInstaller.Session session = getPackageManager().getPackageInstaller().openSession(id)) {
            Intent done = new Intent(INSTALLED).setPackage(getPackageName()).putExtra("here", here);
            PendingIntent p = PendingIntent.getBroadcast(this, id, done, PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            prefs.edit().remove("stagedSession").apply();
            session.commit(p.getIntentSender());
        } catch (IOException | RuntimeException e) {
            prefs.edit().remove("stagedSession").apply();
            if (here) showBanner("The update didn't install.", "Try again", this::retryUpdate);
        }
    }

    private void offerUpdate() {
        showBanner("A new version of this app is ready.", "Update", () -> {
            bannerText.setText("Updating\u2026 the app reopens by itself");
            bannerGo.setVisibility(View.GONE);
            if (staged()) install(true); else retryUpdate();
        });
    }

    private void retryUpdate() {
        updateAt = -3_600_000;
        prefs.edit().putBoolean("updateAsks", true).apply();
        hideBanner();
        checkUpdate();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // leaving the app: the moment to update quietly (unless Android is known to want a yes, which needs you here)
        if (!isChangingConfigurations() && !prefs.getBoolean("updateAsks", false)) install(false);
    }

    /** Android's installer reporting back. */
    private final BroadcastReceiver installed = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            boolean here = i.getBooleanExtra("here", false);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                if (here) {   // you're here and it wants a yes: its screen now
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } else {      // it wanted a yes while you were away: ask next time you're here
                    int id = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
                    if (id >= 0) try { getPackageManager().getPackageInstaller().abandonSession(id); } catch (RuntimeException ignored) { }
                    prefs.edit().putBoolean("updateAsks", true).apply();
                    updateAt = -3_600_000;
                }
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {   // they said not now
                if (here) showBanner("A new version of this app is ready.", "Update", MainActivity.this::retryUpdate);
            } else if (status != PackageInstaller.STATUS_SUCCESS && here) {
                showBanner("The update didn't install.", "Try again", MainActivity.this::retryUpdate);
            }
        }
    };

    /** After an update you said yes to: Android closed the app to replace it, so open it again. */
    public static final class Updated extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            SharedPreferences p = c.getSharedPreferences("remote", MODE_PRIVATE);
            p.edit().remove("updateAsks").apply();   // it installed: next time may not need a yes
            if (System.currentTimeMillis() - p.getLong("reopenAt", 0) > 120_000) return;
            p.edit().remove("reopenAt").apply();
            try {
                c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { }
        }
    }

    // ---- the note at the bottom -----------------------------------------------------------

    private LinearLayout buildBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(18), dp(12), dp(10), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21C1C1F);
        bg.setStroke(dp(1), 0x1FFFFFFF);
        bg.setCornerRadius(dp(22));
        b.setBackground(bg);
        b.setElevation(dp(6));
        b.setVisibility(View.GONE);
        bannerText = text(14, Typeface.DEFAULT, 0xD9FFFFFF);
        bannerText.setLineSpacing(0, 1.15f);
        b.addView(bannerText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView later = text(14, Typeface.DEFAULT, 0x80FFFFFF);
        later.setText("Later");
        later.setPadding(dp(12), dp(8), dp(10), dp(8));
        later.setOnClickListener(v -> hideBanner());
        b.addView(later);
        bannerGo = text(14, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.BLACK);
        bannerGo.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(18));
        bannerGo.setBackground(pill);
        b.addView(bannerGo);
        return b;
    }

    private void showBanner(String text, String go, Runnable action) {
        bannerText.setText(text);
        bannerGo.setText(go);
        bannerGo.setVisibility(View.VISIBLE);
        bannerGo.setOnClickListener(v -> action.run());
        if (banner.getVisibility() != View.VISIBLE) {
            banner.setAlpha(0f);
            banner.setTranslationY(dp(16));
            banner.setVisibility(View.VISIBLE);
            banner.animate().alpha(1f).translationY(0f).setDuration(350).start();
        }
    }

    private void hideBanner() {
        banner.animate().alpha(0f).translationY(dp(16)).setDuration(250)
                .withEndAction(() -> banner.setVisibility(View.GONE)).start();
    }

    /** This app's build (its version code), sent along so the TV's log shows which one is asking. */
    private long build() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /** The TV's own API, signed in with the page's PIN cookie (none for what's open to all). */
    private static String call(String url, String cookie, boolean post) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            if (post) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.getOutputStream().close();
            }
            if (c.getResponseCode() == 401) throw new IOException("not signed in");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == PICK && picked != null) {
            picked.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            picked = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (address() == null) return;
        if (screen == offline || screen == power) {
            load(address());   // the TV may be back
            return;
        }
        // coming back to the app (it was still open): the TV may have been switched off since, so the power
        // button now rather than when the old page's next request gives up (that took ~40 s)
        checkOn();
        if (!failed && screen == web && web.getProgress() >= 100) {
            fetchWake();       // a page that's still up won't load again, so ask from here
            checkUpdate();
        }
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();   // the PIN's cookie survives the app being killed
        handler.removeCallbacks(retry);
        handler.removeCallbacks(lookAgain);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(installed); } catch (RuntimeException ignored) { }
        stopFinding();
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }

    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * A round power button, drawn like the one on the TV's remote. Its glow tells the story: it breathes while the
     * TV comes on, lights up when it's on, and dims as it goes off.
     */
    private static final class PowerButton extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
                icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private ValueAnimator glowing;
        private float glow;

        PowerButton(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            fill.setColor(Color.WHITE);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.5f * d);
            ring.setColor(Color.WHITE);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(3.2f * d);
            icon.setStrokeCap(Paint.Cap.ROUND);
            icon.setColor(Color.WHITE);
            setClickable(true);
        }

        void breathe() {
            fade(glow, 1f, 700);
            glowing.setRepeatMode(ValueAnimator.REVERSE);
            glowing.setRepeatCount(ValueAnimator.INFINITE);
        }

        void light() {
            fade(glow, 1f, 260);
        }

        void dim(long ms) {
            fade(glow, 0f, ms);
        }

        void rest() {
            if (glowing != null) glowing.cancel();
            glowing = null;
            glow = 0f;
            invalidate();
        }

        void fade(float from, float to, long ms) {
            if (glowing != null) glowing.cancel();
            glowing = ValueAnimator.ofFloat(from, to);
            glowing.setDuration(ms);
            glowing.setInterpolator(GLIDE);
            glowing.addUpdateListener(a -> {
                glow = (float) a.getAnimatedValue();
                invalidate();
            });
            glowing.start();
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            animate().scaleX(pressed ? 0.93f : 1f).scaleY(pressed ? 0.93f : 1f).setDuration(140).start();
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            rest();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) - ring.getStrokeWidth();
            fill.setAlpha(isPressed() ? 0x30 : 0x14 + Math.round(0x14 * glow));
            c.drawCircle(cx, cy, r, fill);
            ring.setAlpha(0x33 + Math.round(0x70 * glow));
            c.drawCircle(cx, cy, r, ring);
            icon.setAlpha(0xC8 + Math.round(0x37 * glow));
            float s = r * 0.34f;   // the symbol: a ring open at the top, and a stroke down into the gap
            arc.set(cx - s, cy - s + s * 0.12f, cx + s, cy + s + s * 0.12f);
            c.drawArc(arc, -50f, 280f, false, icon);
            c.drawLine(cx, cy - s * 1.1f, cx, cy - s * 0.1f, icon);
        }
    }
}
