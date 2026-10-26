package com.joe.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvInputInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.media.tv.TvInputManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The phone page: a tiny HTTP server on port 8899 serving one self-contained page (res/raw/remote.html)
 * and a small JSON API. Phones on the home network only (private addresses, and the address typed as
 * an IP, so no website can reach it through DNS tricks), behind a 4-digit PIN shown in MonolithicOS
 * Settings; after the PIN the phone keeps a cookie for a year. Idle, it is one thread blocked in
 * accept(): no CPU, no wakeups.
 */
final class Remote {
    static final int PORT = 8899;
    private static final int MAX_UPLOADS = 12;
    private static final Pattern IP_HOST = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-fA-F:.]+\\]|localhost|[A-Za-z0-9-]+\\.local)(:\\d+)?$");

    private static ServerSocket server;
    private static ExecutorService pool;
    private static Context app;
    private static SharedPreferences prefs;
    private static byte[] page;
    private static final Map<String, byte[]> images = new ConcurrentHashMap<>();
    private static int fails;
    private static long lockedUntil;

    private Remote() { }

    // ---- lifecycle -------------------------------------------------------------------------

    /** Started by Home and by TypeHelper (bound whenever the TV is on); no-op if running or off. */
    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        prefs = app.getSharedPreferences("home", Context.MODE_PRIVATE);
        if (server != null || !prefs.getBoolean("remote", true)) return;
        if (prefs.getString("pin", null) == null) newPin(app);
        ServerSocket s;
        try {
            s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
        } catch (IOException e) {
            return;
        }
        server = s;
        ExecutorService p = Executors.newFixedThreadPool(4);
        pool = p;
        Thread t = new Thread(() -> accept(s, p), "remote");
        t.setDaemon(true);
        t.start();
        announce(true);
    }

    // On the home network as "_monolith._tcp", so the phone app finds the TV if its address changes.
    private static final NsdManager.RegistrationListener announced = new NsdManager.RegistrationListener() {
        @Override public void onRegistrationFailed(NsdServiceInfo i, int error) { Log.w("MonolithicOS", "mDNS failed " + error); }
        @Override public void onUnregistrationFailed(NsdServiceInfo i, int error) { }
        @Override public void onServiceRegistered(NsdServiceInfo i) { }
        @Override public void onServiceUnregistered(NsdServiceInfo i) { }
    };

    private static void announce(boolean on) {
        NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        try {
            if (on) {
                NsdServiceInfo info = new NsdServiceInfo();
                info.setServiceName("MonolithicOS");   // not plain "Monolith": that's the TV's own name, taken by Google's remote
                info.setServiceType("_monolith._tcp");
                info.setPort(PORT);
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, announced);
            } else {
                nsd.unregisterService(announced);
            }
        } catch (RuntimeException ignored) { }   // already (un)registered
    }

    static synchronized void stop() {
        if (server == null) return;
        announce(false);
        try { server.close(); } catch (IOException ignored) { }
        server = null;
        pool.shutdownNow();
        pool = null;
    }

    static boolean running() {
        return server != null;
    }

    /** A new PIN, which also signs every phone out. */
    static void newPin(Context c) {
        SecureRandom r = new SecureRandom();
        byte[] token = new byte[16];
        r.nextBytes(token);
        StringBuilder hex = new StringBuilder();
        for (byte b : token) hex.append(String.format(Locale.US, "%02x", b));
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit()
                .putString("pin", String.format(Locale.US, "%04d", r.nextInt(10000)))
                .putString("token", hex.toString()).apply();
    }

    static String pin(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("pin", "----");
    }

    /** What to type on the phone, e.g. 192.168.1.20:8899. */
    static String address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress() + ":" + PORT;
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return "no network";
    }

    private static void accept(ServerSocket s, ExecutorService p) {
        while (!s.isClosed()) {
            Socket c;
            try { c = s.accept(); } catch (IOException e) { break; }
            if (!local(c.getInetAddress())) { close(c); continue; }
            try { p.execute(() -> serve(c)); } catch (RuntimeException e) { close(c); }
        }
    }

    private static boolean local(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        if (a instanceof Inet6Address) return (a.getAddress()[0] & 0xFE) == 0xFC;   // unique local fc00::/7
        return false;
    }

    private static void close(Socket c) {
        try { c.close(); } catch (IOException ignored) { }
    }

    // ---- HTTP ------------------------------------------------------------------------------

    private static final class Req {
        String method, path;
        final Map<String, String> query = new HashMap<>(), headers = new HashMap<>();
        InputStream in;
        int length;

        String q(String k) { String v = query.get(k); return v == null ? "" : v; }

        byte[] body() throws IOException {
            byte[] b = new byte[length];
            int off = 0;
            while (off < length) {
                int n = in.read(b, off, length - off);
                if (n < 0) throw new IOException("short body");
                off += n;
            }
            return b;
        }

        String text() throws IOException { return new String(body(), StandardCharsets.UTF_8); }
    }

    private static void serve(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(20000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            Req r = new Req();
            r.method = parts[0];
            String target = parts[1];
            int size = 0;
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                size += h.length();
                if (size > 16384) return;
                int i = h.indexOf(':');
                if (i > 0) r.headers.put(h.substring(0, i).trim().toLowerCase(Locale.US), h.substring(i + 1).trim());
            }
            int qm = target.indexOf('?');
            r.path = qm >= 0 ? target.substring(0, qm) : target;
            if (qm >= 0) {
                for (String kv : target.substring(qm + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) r.query.put(decode(kv.substring(0, e)), decode(kv.substring(e + 1)));
                }
            }
            try { r.length = Integer.parseInt(r.headers.getOrDefault("content-length", "0")); }
            catch (NumberFormatException e) { r.length = 0; }
            r.in = in;
            if (r.length < 0 || r.length > ("/api/wallpaper".equals(r.path) ? 40 << 20 : 64 << 10)) {
                send(out, 413, "text/plain", "too big");
                return;
            }
            // Only reachable as http://<ip>:8899: a website rebinding its own name to this TV gets nothing.
            if (!IP_HOST.matcher(r.headers.getOrDefault("host", "")).matches()) {
                send(out, 403, "text/plain", "use the TV's address");
                return;
            }
            handle(r, out);
        } catch (IOException | RuntimeException ignored) { }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) return null;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static void send(OutputStream out, int code, String type, String body, String... headers) throws IOException {
        send(out, code, type, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String... headers) throws IOException {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden"
                : code == 404 ? "Not Found" : code == 413 ? "Too Large" : code == 429 ? "Too Many" : code == 503 ? "Unavailable"
                : code == 400 ? "Bad Request" : "Error";
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Connection: close\r\n");
        boolean cache = false;
        for (String x : headers) {
            h.append(x).append("\r\n");
            cache |= x.startsWith("Cache-Control:");
        }
        if (!cache) h.append("Cache-Control: no-store\r\n");   // the API: always fresh, never kept
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static boolean authed(Req r) {
        String token = prefs.getString("token", null), cookie = r.headers.get("cookie");
        if (token == null || cookie == null) return false;
        for (String part : cookie.split(";")) {
            part = part.trim();
            if (part.startsWith("mt=")) {
                return MessageDigest.isEqual(part.substring(3).getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8));
            }
        }
        return false;
    }

    // ---- routes ----------------------------------------------------------------------------

    private static void handle(Req r, OutputStream out) throws IOException {
        String p = r.path;
        // The page, its manifest, icons and offline helper: no PIN needed (Chrome fetches the manifest
        // without cookies), and allowed to be kept, which saving it to the home screen needs.
        if ("GET".equals(r.method) && ("/".equals(p) || "/index.html".equals(p))) {
            send(out, 200, "text/html; charset=utf-8", page(), "Referrer-Policy: no-referrer", "Cache-Control: no-cache");
            return;
        }
        if ("/manifest.webmanifest".equals(p)) {
            send(out, 200, "application/manifest+json", "{\"id\":\"/\",\"name\":\"MonolithicOS Remote\","
                    + "\"short_name\":\"MonolithicOS\",\"start_url\":\"/\",\"scope\":\"/\",\"display\":\"standalone\","
                    + "\"orientation\":\"portrait\",\"background_color\":\"#000000\",\"theme_color\":\"#000000\","
                    + "\"icons\":[{\"src\":\"/icon-192.png\",\"sizes\":\"192x192\",\"type\":\"image/png\"},"
                    + "{\"src\":\"/icon-512.png\",\"sizes\":\"512x512\",\"type\":\"image/png\"},"
                    + "{\"src\":\"/icon-maskable.png\",\"sizes\":\"512x512\",\"type\":\"image/png\",\"purpose\":\"maskable\"},"
                    + "{\"src\":\"/icon.svg\",\"sizes\":\"any\",\"type\":\"image/svg+xml\"}]}",
                    "Cache-Control: no-cache");
            return;
        }
        if (p.startsWith("/icon-") && p.endsWith(".png")) {
            boolean mask = "/icon-maskable.png".equals(p);
            int size = mask || "/icon-512.png".equals(p) ? 512 : 192;
            send(out, 200, "image/png", iconPng(size, mask), "Cache-Control: max-age=604800");
            return;
        }
        if ("/app".equals(p)) {   // the phone app (remote-app/), carried inside this one
            send(out, 200, "application/vnd.android.package-archive", phoneApp(),
                    "Content-Disposition: attachment; filename=\"MonolithicOS.apk\"", "Cache-Control: no-cache");
            return;
        }
        if ("/sw.js".equals(p)) {
            send(out, 200, "text/javascript", SW, "Cache-Control: no-cache");
            return;
        }
        if ("/icon.svg".equals(p)) {
            send(out, 200, "image/svg+xml", "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 96 96'>"
                    + "<defs><radialGradient id='g' cx='.3' cy='.2' r='1'><stop offset='0' stop-color='#3a3f5c'/>"
                    + "<stop offset='1' stop-color='#000'/></radialGradient></defs>"
                    + "<rect width='96' height='96' rx='22' fill='url(#g)'/>"
                    + "<rect x='30' y='18' width='36' height='60' rx='10' fill='none' stroke='#fff' stroke-width='3'/>"
                    + "<circle cx='48' cy='38' r='9' fill='none' stroke='#fff' stroke-width='3'/></svg>",
                    "Cache-Control: max-age=604800");
            return;
        }
        if (!p.startsWith("/api/")) {
            send(out, 404, "text/plain", "not here");
            return;
        }
        if ("POST".equals(r.method)) {
            // A page from somewhere else can't drive the TV: only this page's own requests count.
            String origin = r.headers.get("origin");
            if (origin != null && !origin.equals("http://" + r.headers.get("host"))) {
                send(out, 403, "text/plain", "wrong origin");
                return;
            }
        }
        if ("/api/pin".equals(p)) {
            pinLogin(r, out);
            return;
        }
        if (!authed(r)) {
            send(out, 401, "application/json", "{\"error\":\"pin\"}");
            return;
        }
        switch (p) {
            case "/api/state": send(out, 200, "application/json", state()); return;
            case "/api/icon": image(out, "icon:" + r.q("pkg"), () -> icon(r.q("pkg")), "image/png"); return;
            case "/api/thumb": image(out, "thumb:" + r.q("path") + new File(r.q("path")).lastModified(),
                    () -> thumb(r.q("path")), "image/jpeg"); return;
            case "/api/teams": send(out, 200, "application/json", Team.search(app, r.q("q"))); return;
            default: break;
        }
        if (!"POST".equals(r.method)) {
            send(out, 404, "text/plain", "not here");
            return;
        }
        switch (p) {
            case "/api/key": {
                String result = key(r.q("k"));
                send(out, result == null ? 200 : 503, "application/json", result == null ? "{}" : "{\"error\":\"" + result + "\"}");
                return;
            }
            case "/api/type": {
                String result = TypeHelper.type(r.text(), "1".equals(r.q("enter")));
                send(out, 200, "application/json", "{\"result\":\"" + result + "\"}");
                return;
            }
            case "/api/launch": {
                send(out, launch(r.q("pkg")) ? 200 : 404, "application/json", "{}");
                return;
            }
            case "/api/input": {
                send(out, Inputs.switchTo(app, r.q("id")) ? 200 : 404, "application/json", "{}");
                return;
            }
            case "/api/rename": {
                String name = r.q("name").trim();
                if (name.length() > 24) name = name.substring(0, 24);
                SharedPreferences.Editor e = prefs.edit();
                if (name.isEmpty()) e.remove("name:" + r.q("id")); else e.putString("name:" + r.q("id"), name);
                e.apply();
                send(out, 200, "application/json", state());
                return;
            }
            case "/api/cards": {   // the whole list: F1 and up to three teams, as picked on the phone
                try {
                    JSONArray in = new JSONArray(r.text()), keep = new JSONArray();
                    int teams = 0;
                    for (int i = 0; i < in.length(); i++) {
                        JSONObject o = in.optJSONObject(i);
                        if (o == null) continue;
                        if ("f1".equals(o.optString("t"))) keep.put(new JSONObject().put("t", "f1"));
                        else if (teams < 3 && Team.Spec.of(o) != null) { keep.put(Team.Spec.of(o).json()); teams++; }
                    }
                    prefs.edit().putString("cards", keep.toString()).apply();
                    send(out, 200, "application/json", state());
                } catch (JSONException e) {
                    send(out, 400, "application/json", "{\"error\":\"bad cards\"}");
                }
                return;
            }
            case "/api/city": {   // a city name for the weather, looked up with Open-Meteo
                String[] place = Weather.geocode(r.text());
                if (place == null) {
                    send(out, 404, "application/json", "{\"error\":\"city\"}");
                    return;
                }
                prefs.edit().putString("lat", place[1]).putString("lon", place[2]).putString("city", place[0]).apply();
                send(out, 200, "application/json", state());
                return;
            }
            case "/api/set": {
                String err = set(r.q("k"), r.q("v"));
                send(out, err == null ? 200 : 403, "application/json", err == null ? state() : "{\"error\":\"" + err + "\"}");
                return;
            }
            case "/api/wallpaper": upload(r, out); return;
            case "/api/wallpaper/pick": {
                File f = new File(r.q("path"));
                if (!Wallpapers.list(app).contains(f)) { send(out, 404, "application/json", "{}"); return; }
                prefs.edit().putBoolean("photo", true).putString("wallpaper", f.getPath()).apply();
                send(out, 200, "application/json", state());
                return;
            }
            case "/api/wallpaper/delete": {
                File f = new File(r.q("path"));
                boolean mine = Wallpapers.uploads(app).equals(f.getParentFile());   // only ones sent from a phone
                if (mine) f.delete();
                send(out, mine ? 200 : 403, "application/json", state());
                return;
            }
            default: send(out, 404, "text/plain", "not here");
        }
    }

    private static synchronized void pinLogin(Req r, OutputStream out) throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (now < lockedUntil) {
            send(out, 429, "application/json", "{\"error\":\"wait\",\"seconds\":" + ((lockedUntil - now) / 1000 + 1) + "}");
            return;
        }
        String given = r.text().trim(), pin = prefs.getString("pin", null);
        if (pin != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8))) {
            fails = 0;
            send(out, 200, "application/json", "{}",
                    "Set-Cookie: mt=" + prefs.getString("token", "") + "; Max-Age=31536000; Path=/; HttpOnly; SameSite=Strict");
            return;
        }
        if (++fails >= 5) {   // five wrong PINs: a minute's pause
            fails = 0;
            lockedUntil = now + 60_000L;
        }
        send(out, 403, "application/json", "{\"error\":\"pin\"}");
    }

    // Keeps the page itself so the home-screen app opens instantly, even before the TV answers. Only
    // runs where Chrome treats the address as secure (see SETUP.md); the API is never cached.
    private static final String SW = "const C='monolith-1';"
            + "self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(C).then(c=>c.add('/')).catch(()=>{}))});"
            + "self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));"
            + "self.addEventListener('fetch',e=>{const u=new URL(e.request.url);"
            + "if(e.request.method!=='GET'||u.origin!==location.origin||u.pathname.startsWith('/api/'))return;"
            + "e.respondWith(fetch(e.request).then(r=>{const k=r.clone();caches.open(C).then(c=>c.put(e.request,k));return r})"
            + ".catch(()=>caches.match(e.request)))});";

    private static final Map<String, byte[]> icons = new ConcurrentHashMap<>();

    /** The icon.svg design as a PNG, which Android's home screen wants; maskable fills the square. */
    private static byte[] iconPng(int size, boolean maskable) {
        return icons.computeIfAbsent(size + ":" + maskable, k -> {
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            float u = size / 96f;
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setShader(new RadialGradient(0.3f * size, 0.2f * size, size, 0xFF3A3F5C, 0xFF000000, Shader.TileMode.CLAMP));
            if (maskable) c.drawRect(0, 0, size, size, bg);
            else c.drawRoundRect(0, 0, size, size, 22 * u, 22 * u, bg);
            if (maskable) c.scale(0.72f, 0.72f, size / 2f, size / 2f);   // inside the launcher's safe circle
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(3 * u);
            line.setColor(0xFFFFFFFF);
            c.drawRoundRect(30 * u, 18 * u, 66 * u, 78 * u, 10 * u, 10 * u, line);
            c.drawCircle(48 * u, 38 * u, 9 * u, line);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            b.recycle();
            return out.toByteArray();
        });
    }

    private static byte[] phoneApp;

    private static byte[] phoneApp() throws IOException {
        if (phoneApp == null) {
            try (InputStream in = app.getResources().openRawResource(R.raw.remote_app)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                phoneApp = b.toByteArray();
            }
        }
        return phoneApp;
    }

    private static byte[] page() throws IOException {
        if (page == null) {
            try (InputStream in = app.getResources().openRawResource(R.raw.remote)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                page = b.toByteArray();
            }
        }
        return page;
    }

    // ---- actions ---------------------------------------------------------------------------

    /** Null when done, else why not. */
    private static String key(String k) {
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        switch (k) {
            case "volup": audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return null;
            case "voldown": audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return null;
            case "mute": audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); return null;
            case "playpause": {
                MediaController c = session();
                PlaybackState s = c != null ? c.getPlaybackState() : null;
                if (s == null) { media(audio, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return null; }
                if (s.getState() == PlaybackState.STATE_PLAYING) c.getTransportControls().pause();
                else c.getTransportControls().play();
                return null;
            }
            case "next": media(audio, KeyEvent.KEYCODE_MEDIA_NEXT); return null;
            case "prev": media(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS); return null;
            case "settings":
                try { app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException e) { return "blocked"; }
                return null;
            default:
                return TypeHelper.press(k) ? null : TypeHelper.on() ? "unknown" : "helper";
        }
    }

    private static void media(AudioManager audio, int code) {
        long t = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private static boolean launch(String pkg) {
        PackageManager pm = app.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        try {
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Null when done, else why not. Look settings go through prefs, which Home is listening to. */
    private static String set(String k, String v) {
        boolean on = "1".equals(v) || "true".equals(v);
        switch (k) {
            case "photo": case "night": case "clock24": case "cards_on":
                prefs.edit().putBoolean(k, on).apply();
                return null;
            case "dock": {   // the dock's apps in order, comma-separated (installed launcher apps only)
                List<String> keep = new ArrayList<>();
                PackageManager pm = app.getPackageManager();
                for (String pkg : v.split(",")) {
                    pkg = pkg.trim();
                    if (!pkg.isEmpty() && !keep.contains(pkg) && keep.size() < 6 && pm.getLeanbackLaunchIntentForPackage(pkg) != null) keep.add(pkg);
                }
                if (keep.isEmpty()) return "bad value";
                prefs.edit().putString("dock", TextUtils.join(",", keep)).apply();
                return null;
            }
            case "name": {   // for the greeting
                String name = v.trim();
                prefs.edit().putString("name", name.length() > 20 ? name.substring(0, 20) : name).apply();
                return null;
            }
            case "fps":   // Frame Rate: 60 or 30
                if (!v.matches("30|60")) return "bad value";
                prefs.edit().putString("fps", v).apply();
                return null;
            case "dockstyle": case "cardstyle":   // Glass or Floating
                if (!v.matches("glass|float")) return "bad value";
                prefs.edit().putString(k, v).apply();
                return null;
            case "fx":   // Weather effects: live, snow, rain or off
                if (!v.matches("live|snow|rain|off")) return "bad value";
                prefs.edit().putString("fx", v).apply();
                return null;
            case "icondim":   // Dim App Icons, %
                try { prefs.edit().putInt("icondim", Math.max(0, Math.min(70, Integer.parseInt(v)))).apply(); }
                catch (NumberFormatException e) { return "bad value"; }
                return null;
            case "dim":
                try { prefs.edit().putInt("dim", Math.max(0, Math.min(100, Integer.parseInt(v)))).apply(); }
                catch (NumberFormatException e) { return "bad value"; }
                return null;
            default: break;
        }
        if (!Tweaks.allowed(app)) return "installer";
        switch (k) {
            case "adblock": Tweaks.setAdblock(app, on); return null;
            case "fast": Tweaks.setFast(app, on); return null;
            case "ambient": Tweaks.setAmbient(app, on); return null;
            default: return "unknown";
        }
    }

    /** A photo from the phone (already resized there): saved, picked, and Home switches to it. */
    private static void upload(Req r, OutputStream out) throws IOException {
        File dir = Wallpapers.uploads(app);
        File tmp = new File(dir, ".incoming");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int left = r.length;
            while (left > 0) {
                int n = r.in.read(buf, 0, Math.min(buf.length, left));
                if (n < 0) throw new IOException("short upload");
                f.write(buf, 0, n);
                left -= n;
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), o);
        if (o.outWidth < 320 || o.outHeight < 180) {
            tmp.delete();
            send(out, 400, "application/json", "{\"error\":\"not a photo\"}");
            return;
        }
        String ext = "image/png".equals(o.outMimeType) ? ".png" : "image/webp".equals(o.outMimeType) ? ".webp" : ".jpg";
        File dest = new File(dir, "phone-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ext);
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            send(out, 500, "application/json", "{\"error\":\"save\"}");
            return;
        }
        File[] mine = dir.listFiles((d, n) -> n.startsWith("phone-"));
        if (mine != null && mine.length > MAX_UPLOADS) {   // keep the newest dozen
            Arrays.sort(mine, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < mine.length - MAX_UPLOADS; i++) mine[i].delete();
        }
        prefs.edit().putBoolean("photo", true).putString("wallpaper", dest.getPath()).apply();
        send(out, 200, "application/json", state());
    }

    // ---- reading the TV --------------------------------------------------------------------

    private static MediaController session() {
        MediaSessionManager m = (MediaSessionManager) app.getSystemService(Context.MEDIA_SESSION_SERVICE);
        List<MediaController> list;
        try { list = m.getActiveSessions(new ComponentName(app, NowPlayingListener.class)); }
        catch (SecurityException e) { return null; }
        MediaController best = null;
        for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s == null || c.getMetadata() == null) continue;
            if (s.getState() == PlaybackState.STATE_PLAYING) return c;
            if (best == null && s.getState() == PlaybackState.STATE_PAUSED) best = c;
        }
        return best;
    }

    private static String state() {
        StringBuilder j = new StringBuilder("{");
        j.append("\"helper\":").append(TypeHelper.on());
        j.append(",\"allowed\":").append(Tweaks.allowed(app));
        j.append(",\"watch\":").append(js(Watchdog.lastText));

        MediaController c = session();
        if (c != null) {
            MediaMetadata md = c.getMetadata();
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(artist)) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            j.append(",\"now\":{\"title\":").append(js(md.getString(MediaMetadata.METADATA_KEY_TITLE)))
                    .append(",\"artist\":").append(js(artist))
                    .append(",\"app\":").append(js(label(c.getPackageName())))
                    .append(",\"playing\":").append(c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING)
                    .append('}');
        }

        j.append(",\"settings\":{\"photo\":").append(prefs.getBoolean("photo", true))
                .append(",\"dim\":").append(prefs.getInt("dim", 0))
                .append(",\"icondim\":").append(Tile.iconDim(prefs))
                .append(",\"night\":").append(prefs.getBoolean("night", true))
                .append(",\"clock24\":").append(prefs.getBoolean("clock24", false))
                .append(",\"cards_on\":").append(prefs.getBoolean("cards_on", true))
                .append(",\"name\":").append(js(prefs.getString("name", "")))
                .append(",\"city\":").append(js(prefs.getString("city", "")))
                .append(",\"fx\":").append(js(prefs.getString("fx", "live")))
                .append(",\"fps\":").append(js(prefs.getString("fps", "60")))
                .append(",\"dockstyle\":").append(js(prefs.getString("dockstyle", "glass")))
                .append(",\"cardstyle\":").append(js(prefs.getString("cardstyle", "glass")))
                .append(",\"adblock\":").append(Tweaks.adblock(app))
                .append(",\"fast\":").append(Tweaks.fast(app))
                .append(",\"ambient\":").append(Tweaks.ambient(app))
                .append('}');

        j.append(",\"cards\":").append(prefs.getString("cards", "[]"));

        File current = Wallpapers.current(app);
        j.append(",\"wallpaper\":").append(js(current != null ? current.getPath() : null));
        j.append(",\"wallpapers\":[");
        File uploads = Wallpapers.uploads(app);
        List<File> photos = Wallpapers.list(app);
        for (int i = 0; i < photos.size(); i++) {
            File f = photos.get(i);
            if (i > 0) j.append(',');
            j.append("{\"path\":").append(js(f.getPath())).append(",\"mine\":").append(uploads.equals(f.getParentFile())).append('}');
        }
        j.append(']');

        j.append(",\"apps\":[");
        boolean first = true;
        for (Map.Entry<String, String> e : apps().entrySet()) {
            if (!first) j.append(',');
            first = false;
            j.append("{\"pkg\":").append(js(e.getKey())).append(",\"label\":").append(js(e.getValue())).append('}');
        }
        j.append(']');

        j.append(",\"inputs\":[");
        TvInputManager tm = (TvInputManager) app.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> ports = new ArrayList<>();
        Map<String, String> devices = new HashMap<>();
        Inputs.hdmi(app, tm, ports, devices);
        for (int i = 0; i < ports.size(); i++) {
            TvInputInfo in = ports.get(i);
            if (i > 0) j.append(',');
            j.append("{\"id\":").append(js(in.getId()))
                    .append(",\"port\":").append(js(Inputs.port(app, in)))
                    .append(",\"device\":").append(js(devices.get(in.getId())))
                    .append(",\"name\":").append(js(Inputs.renamed(prefs, in.getId())))
                    .append(",\"state\":").append(js(Inputs.state(tm, in.getId())))
                    .append('}');
        }
        j.append("]}");
        return j.toString();
    }

    /** Dock apps first in Dock order, then the rest by name. */
    private static Map<String, String> apps() {
        PackageManager pm = app.getPackageManager();
        Map<String, String> all = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String pkg = ri.activityInfo.packageName;
            if (HomeActivity.HIDDEN.contains(pkg)) continue;
            all.putIfAbsent(pkg, ri.loadLabel(pm).toString());
        }
        Map<String, String> out = new LinkedHashMap<>();
        String dock = prefs.getString("dock", null);
        for (String pkg : dock != null && !dock.isEmpty() ? dock.split(",") : HomeActivity.DEFAULT_DOCK) {
            if (all.containsKey(pkg)) out.put(pkg, all.get(pkg));
        }
        List<String> rest = new ArrayList<>(all.keySet());
        rest.removeAll(out.keySet());
        rest.sort((a, b) -> all.get(a).compareToIgnoreCase(all.get(b)));
        for (String pkg : rest) out.put(pkg, all.get(pkg));
        return out;
    }

    private static String label(String pkg) {
        try {
            PackageManager pm = app.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private interface Maker { byte[] make() throws IOException; }

    private static void image(OutputStream out, String key, Maker maker, String type) throws IOException {
        byte[] b = images.get(key);
        if (b == null) {
            b = maker.make();
            if (b == null) { send(out, 404, "text/plain", "none"); return; }
            if (images.size() > 120) images.clear();
            images.put(key, b);
        }
        send(out, 200, type, b);
    }

    /** The app's TV banner at 320x180 (or its icon, square), as on Home. */
    private static byte[] icon(String pkg) {
        PackageManager pm = app.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> found = pm.queryIntentActivities(i, 0);
        if (found.isEmpty()) return null;
        ActivityInfo ai = found.get(0).activityInfo;
        Drawable d = null;
        int id = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        try {
            if (id != 0) d = pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(id, DisplayMetrics.DENSITY_XHIGH, null);
        } catch (Exception ignored) { }
        boolean banner = d != null && d.getIntrinsicWidth() >= d.getIntrinsicHeight() * 1.4f;
        if (!banner) d = ai.loadIcon(pm);
        if (d == null) return null;
        int w = banner ? 320 : 144, h = banner ? 180 : 144;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(bm));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, b);
        bm.recycle();
        return b.toByteArray();
    }

    /** A 320x180 crop of a wallpaper for the phone's picker. */
    private static byte[] thumb(String path) {
        File f = new File(path);
        if (!Wallpapers.list(app).contains(f)) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 640) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap raw = BitmapFactory.decodeFile(path, o);
        if (raw == null) return null;
        Bitmap t = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        float s = Math.max(320f / raw.getWidth(), 180f / raw.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((320 - raw.getWidth() * s) / 2f, (180 - raw.getHeight() * s) / 2f);
        new Canvas(t).drawBitmap(raw, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        raw.recycle();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        t.compress(Bitmap.CompressFormat.JPEG, 82, b);
        t.recycle();
        return b.toByteArray();
    }

    private static String js(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20 || ch == '<' || ch == '>' || ch == '&') b.append(String.format(Locale.US, "\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.append('"').toString();
    }
}
