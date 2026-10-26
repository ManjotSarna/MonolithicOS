package com.joe.remote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
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

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * MonolithicOS on the phone: the TV's remote page (http://<tv>:8899) full screen, without a browser around
 * it. The page does everything; this keeps its PIN cookie, lets it buzz and pick photos, and finds the TV
 * by itself, the first time and whenever its address changes (the TV announces itself on the home network as
 * _monolith._tcp); typing the address is the fallback.
 */
public final class MainActivity extends Activity {
    private static final int PICK = 1;

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
    private boolean failed;

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
                    showOffline();
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (failed) return;
                manual.setVisibility(View.GONE);
                handler.removeCallbacks(askAddress);
                offline.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
                stopFinding();
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
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        offline = buildOffline();
        root.addView(offline, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        if (address() != null) {
            load(address());
        } else {   // first run: find the TV on the Wi-Fi
            web.setVisibility(View.INVISIBLE);
            offline.setVisibility(View.VISIBLE);
            offlineBody.setText("Looking for MonolithicOS on your Wi-Fi\u2026");
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
        web.loadUrl(url);
    }

    /** Nothing answered: let them type the address MonolithicOS Settings shows. */
    private final Runnable askAddress = () -> {
        offlineBody.setText("Couldn't find it by itself. Type the address shown in MonolithicOS Settings on the TV"
                + " (Phone remote).");
        manual.setVisibility(View.VISIBLE);
    };

    /** The TV didn't answer: say so, keep trying, and look for it on the network. */
    private void showOffline() {
        web.setVisibility(View.INVISIBLE);   // never Chrome's error page
        offline.setVisibility(View.VISIBLE);
        offlineBody.setText("Check the phone is on the home Wi-Fi and the TV is on.\nLooking for it…");
        find();
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, 5000);
        handler.removeCallbacks(askAddress);
        handler.postDelayed(askAddress, 15000);
    }

    private final Runnable retry = () -> { if (address() != null) load(address()); };

    private LinearLayout buildOffline() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(40), 0, dp(40), dp(40));
        box.setBackgroundColor(Color.BLACK);
        box.setVisibility(View.GONE);
        TextView title = text(22, Typeface.create("sans-serif-medium", Typeface.NORMAL), Color.WHITE);
        title.setText("Can't reach Monolith");
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
            offlineBody.setText("Trying…");
            if (address() != null) load(address()); else find();
        });
        box.addView(again);

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
                offlineBody.setText("That doesn't look like an address: four numbers with dots, like 192.168.1.20.");
                return;
            }
            String url = "http://" + ip + ":8899/";
            prefs.edit().putString("url", url).apply();
            offlineBody.setText("Connecting…");
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
        if (offline.getVisibility() == View.VISIBLE && address() != null) load(address());   // the TV may be back
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();   // the PIN's cookie survives the app being killed
        handler.removeCallbacks(retry);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
}
