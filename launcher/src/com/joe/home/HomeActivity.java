package com.joe.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Apple TV-style home: an aurora in the focused app's colours over black, a frosted dock of
 * favourites you can rearrange from the remote, then Inputs and the rest of the apps in a grid
 * that slides up. Nothing animates while you're not pressing anything.
 */
public final class HomeActivity extends Activity implements Tile.FocusListener {
    static final String[] DEFAULT_DOCK = {
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
            "com.disney.disneyplus",
    };
    // Not shown in Apps: ourselves, the old launcher, TCL's panel and input overlay (Inputs covers it).
    static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.joe.home", "com.spocky.projengmenu", "com.tcl.dashboard", "com.tcl.suspension"));
    private static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx";
    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final int KEY_NETFLIX = 4062, KEY_PRIME = 4099, KEY_YOUTUBE = KeyEvent.KEYCODE_BUTTON_3;
    private static final int COLS = 6, DOCK_MAX = 6;
    private static final int NEUTRAL = 0xFF8E8E93, NEUTRAL2 = 0xFF5A5F73;

    private final Interpolator glide = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, int[]> palettes = new HashMap<>();
    private final Map<Tile, Integer> rowOf = new HashMap<>();
    private final List<View> rows = new ArrayList<>();
    private final List<Tile> dockTiles = new ArrayList<>();
    private final List<String> dockOrder = new ArrayList<>();

    private float density;
    private int tileW, tileH, gap, radius, bandPad, side, contentTop;
    private Typeface thin, light, medium;
    private FrameLayout root, shelf;
    private LinearLayout column, dock;
    private Backdrop backdrop;
    private Snow snow;
    private TextView greeting, title, subtitle;
    private Tile focused, moving;
    private String titleWant, subWant;
    private FrameLayout menu, zoom;
    private long pausedAt, weatherAt;
    private SharedPreferences prefs;
    private TvInputManager tvInputs;
    private TextView weather;
    private TextClock clock;
    // HDMI inputs: a glass pill left of the clock that drops down the list
    private Tile inputsPill;
    private TextView inputsLabel;
    private String inputsSummary = "";
    // wallpaper + Liquid Glass
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int screenW, screenH;
    private Bitmap frost;
    private int lookTries;
    private boolean loading;   // a wallpaper decode is under way
    private final List<View> glassViews = new ArrayList<>();   // views with a Glass background
    // Now Playing
    private Tile nowCard;
    private ImageView nowArt;
    private TextView nowTitle, nowArtist, nowState;
    private Bitmap nowBitmap;
    private MediaSessionManager sessions;
    private MediaController playing;
    // the "Next up" cards under the clock
    private final List<Card> cards = new ArrayList<>();
    private Card f1;
    private final List<TeamCard> teams = new ArrayList<>();   // the teams picked on the phone page
    private boolean f1Wanted;
    private boolean resumed;
    // F1 Next up
    private static final int F1_RED = 0xFFE10600;
    private F1.Data f1Data;
    private F1.Race f1Race;
    private long f1FetchAt;
    // firmware-update watchdog
    private TextView watchBanner;
    private Watchdog.Report watch;
    private long watchAt;

    // ---- lifecycle -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics m = getResources().getDisplayMetrics();
        density = m.density;
        screenW = m.widthPixels;
        screenH = m.heightPixels;
        side = dp(48);
        bandPad = dp(16);
        gap = dp(16);
        radius = dp(12);
        tileW = (m.widthPixels - 2 * side - 2 * bandPad - (COLS - 1) * gap) / COLS;
        tileH = Math.round(tileW * 9f / 16f);
        contentTop = dp(380);   // the dock sits low: the wallpaper gets the screen

        prefs = getSharedPreferences("home", MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefsChanged);
        String saved = prefs.getString("dock", null);
        dockOrder.addAll(saved != null && !saved.isEmpty() ? Arrays.asList(saved.split(",")) : Arrays.asList(DEFAULT_DOCK));

        Resources r = getResources();
        thin = r.getFont(R.font.outfit_thin);
        light = r.getFont(R.font.outfit_light);
        medium = r.getFont(R.font.outfit_medium);

        root = new FrameLayout(this);
        root.setClipChildren(false);
        backdrop = new Backdrop(this);
        root.addView(backdrop, match());
        snow = new Snow(this);
        snow.setVisibility(View.GONE);
        root.addView(snow, match());

        greeting = legible(text(15, medium, 0xB3FFFFFF));
        root.addView(greeting, at(side + bandPad, dp(34)));

        clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        style(clock, 34, thin, Color.WHITE);
        clock.setFontFeatureSettings("tnum");
        LinearLayout topRight = new LinearLayout(this);   // the inputs pill, then the clock
        topRight.setOrientation(LinearLayout.HORIZONTAL);
        topRight.setGravity(Gravity.CENTER_VERTICAL);
        topRight.setClipChildren(false);
        buildInputsPill();
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        pl.rightMargin = dp(20);
        topRight.addView(inputsPill, pl);
        topRight.addView(legible(clock));
        root.addView(topRight, corner(dp(22)));
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        style(date, 13, light, 0xB3FFFFFF);
        root.addView(legible(date), corner(dp(70)));
        weather = legible(text(13, light, 0xB3FFFFFF));
        weather.setText(prefs.getString("weather", ""));
        root.addView(weather, corner(dp(92)));

        shelf = new FrameLayout(this);
        title = legible(text(46, thin, Color.WHITE));
        title.setLetterSpacing(-0.01f);
        title.setSingleLine(true);
        title.setMaxWidth(dp(500));
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = legible(text(15, light, 0xA6FFFFFF));
        shelf.addView(title, at(0, 0));
        shelf.addView(subtitle, at(0, dp(64)));
        root.addView(shelf, at(side + bandPad, contentTop - dp(108)));   // just above the dock it names

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        // A tall, transparent column we slide up ourselves; WRAP_CONTENT would cap it at the space
        // left below the dock and squash every row under the fold.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1600));
        lp.leftMargin = side;
        lp.rightMargin = side;
        lp.topMargin = contentTop;
        root.addView(column, lp);

        buildNowCard();
        buildCards();
        buildWatchBanner();

        setContentView(root);
        // The backdrop is opaque: skip the window's own full-screen black fill underneath it.
        getWindow().setBackgroundDrawable(null);
        tvInputs = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        build();
        applyLook();
        root.post(this::goTop);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packages, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packages, f);
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        initSessions();
        int dim = prefs.getInt("dim", 20);
        if (dim % 10 != 0) prefs.edit().putInt("dim", Math.round(dim / 10f) * 10).apply();   // the old 15/45 steps
        Remote.start(this);
        F1.load(prefs, handler, d -> {
            f1Data = d;
            updateF1();
            refreshF1();
        });
        applyCards();
        worker.execute(() -> {   // the F1 logo comes from ESPN like the crests (it's a trademark: not shipped)
            Bitmap logo = F1.logo(this);
            if (logo != null) handler.post(() -> setLogo(f1, logo, dp(4)));
        });
        checkWatchdog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        greeting.setText(greetingText());
        if (prefs.getBoolean("photo", true) && !backdrop.hasPhoto() && !loading) {   // never made it: try again
            lookTries = 0;
            applyLook();
        }
        refreshInputs();
        refreshWeather();
        pickSession(null);
        applyTone();
        handler.removeCallbacks(toneTick);
        handler.postDelayed(toneTick, 5 * 60_000L);
        // Android drops an app's accessibility services whenever that app is force-stopped (a
        // cleaner, an update gone wrong): put the phone remote's helper back if it should be on.
        if (prefs.getBoolean("remote", true) && Tweaks.allowed(this) && !Tweaks.service(this, Tweaks.TYPE_HELPER)) {
            Tweaks.setService(this, Tweaks.TYPE_HELPER, true);
        }
        resumed = true;
        updateSnow();
        updateF1();
        refreshF1();
        for (TeamCard t : teams) {
            t.update();
            t.refresh();
        }
        if (SystemClock.elapsedRealtime() - watchAt > 30 * 60_000L) checkWatchdog();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = SystemClock.uptimeMillis();
        if (moving != null) endMove();
        if (menu != null) closeMenu(false);
        handler.removeCallbacks(toneTick);
        resumed = false;
        snow.setFalling(false);
        handler.removeCallbacks(f1Tick);
        for (TeamCard t : teams) handler.removeCallbacks(t.tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        clearZoom();   // the app is on screen now; Home is clean when we come back
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while Home was already showing: back to the first tile, like Apple TV.
        if (SystemClock.uptimeMillis() - pausedAt < 500) goTop();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packages); } catch (IllegalArgumentException ignored) { }
        prefs.unregisterOnSharedPreferenceChangeListener(prefsChanged);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        if (playing != null) playing.unregisterCallback(sessionCallback);
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        backdrop.release();
        super.onDestroy();
    }

    // ---- keys ------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled();

        if (moving != null) {
            if (down && k == KeyEvent.KEYCODE_DPAD_LEFT) shift(-1);
            else if (down && k == KeyEvent.KEYCODE_DPAD_RIGHT) shift(1);
            else if (up && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_BACK)) endMove();
            return true;   // nothing else while moving
        }
        if (menu != null && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE)) {
            if (up) closeMenu(true);
            return true;
        }
        if (down && menu == null && focused != null && focused.hasFocus()) {
            int dir = k == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : k == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                    : k == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : k == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : 0;
            if (dir != 0 && e.getRepeatCount() == 0) {
                View next = focused.focusSearch(dir);
                if (next == null || next == focused) focused.bounce(dir);   // nowhere to go: hit the wall
            }
        }
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (up) goTop();
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU && focused != null && menu == null) {
            if (up) showMenu(focused);
            return true;
        }
        if (k == KeyEvent.KEYCODE_NOTIFICATION || k == KeyEvent.KEYCODE_SETTINGS) {
            if (up) start(new Intent(Settings.ACTION_SETTINGS));
            return true;
        }
        String app = k == KEY_NETFLIX ? "com.netflix.ninja"
                : k == KEY_PRIME ? "com.amazon.amazonvideo.livingroom"
                : k == KEY_YOUTUBE ? "com.google.android.youtube.tv" : null;
        if (app != null) {
            if (up) launchPackage(app);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---- building the screen ---------------------------------------------------------------

    private void build() {
        String keep = focused != null ? focused.title : null;
        focused = null;
        column.removeAllViews();
        rows.clear();
        rowOf.clear();
        dockTiles.clear();
        glassViews.removeIf(v -> v != nowCard);

        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> apps = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            apps.putIfAbsent(ri.activityInfo.packageName, ri);
        }

        // Dock: your favourites, in your order, in a frosted band.
        dock = row(0);
        Set<String> docked = new HashSet<>();
        for (String pkg : dockOrder) {
            ResolveInfo ri = apps.get(pkg);
            if (ri == null || docked.contains(pkg) || dockTiles.size() >= DOCK_MAX) continue;
            Tile t = appTile(pm, ri, 1.12f);
            LinearLayout.LayoutParams lp = cell(dock.getChildCount());
            lp.height = tileH;
            dock.addView(t, lp);
            dockTiles.add(t);
            docked.add(pkg);
            rowOf.put(t, 0);
        }
        FrameLayout band = new FrameLayout(this);
        // Liquid Glass: the frosted wallpaper behind it (a light smoke keeps white banners crisp), lit rim
        glass(band, dp(26), 0x29000000, 0x1FFFFFFF);
        band.setPadding(bandPad, dp(14), bandPad, dp(14));
        band.setClipChildren(false);
        band.setClipToPadding(false);
        band.addView(dock);
        // the glass hugs the apps: six fill the width, fewer don't trail an empty stretch of glass
        column.addView(band, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        rows.add(band);
        edges(dockTiles, true, false);
        layoutCards();

        refreshInputs();

        // Everything else, sorted by name, in rows of six.
        List<ResolveInfo> rest = new ArrayList<>();
        for (ResolveInfo ri : apps.values()) {
            String pkg = ri.activityInfo.packageName;
            if (!docked.contains(pkg) && !HIDDEN.contains(pkg)) rest.add(ri);
        }
        rest.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        List<Tile> all = new ArrayList<>();
        for (ResolveInfo ri : rest) all.add(appTile(pm, ri, 1.10f));
        all.add(settingsTile());   // MonolithicOS Settings, last, like the Settings app on Apple TV
        column.addView(section("Apps"));
        for (int i = 0; i < all.size(); i += COLS) {
            LinearLayout line = row(bandPad);
            List<Tile> tiles = all.subList(i, Math.min(all.size(), i + COLS));
            for (Tile t : tiles) {
                line.addView(labelled(t), cell(line.getChildCount()));
                rowOf.put(t, rows.size());
            }
            line.setAlpha(0f);   // below the fold until you head down (scrollTo)
            column.addView(line);
            rows.add(line);
            edges(tiles, false, i + COLS >= all.size());
        }
        // The dock, the only row on screen at rest, is kept as a GPU texture: a frame that changes
        // something else (a card, the snow) draws it as one image instead of every tile and pane again
        // (measured on this TV: a fifth less GPU per frame). Only the dock: a layer clips to its view,
        // and the band's padding is what leaves a focused tile room to grow; an apps row has none.
        if (!rows.isEmpty()) rows.get(0).setLayerType(View.LAYER_TYPE_HARDWARE, null);

        if (keep != null) {
            for (Tile t : rowOf.keySet()) {
                if (keep.equals(t.title)) { t.requestFocus(); return; }
            }
            goTop();
        }
    }

    private Tile appTile(PackageManager pm, ResolveInfo ri, float scale) {
        ActivityInfo ai = ri.activityInfo;
        String pkg = ai.packageName;
        String name = ri.loadLabel(pm).toString();
        Drawable banner = banner(pm, ri);
        Drawable art = banner != null ? banner : ri.loadIcon(pm);
        int[] colors = palette(pkg, art);
        Tile t = new Tile(this, tileW, tileH, radius, scale, name, null, colors[0], colors[1]);
        t.pkg = pkg;
        t.activity = ai;
        t.art = art;
        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            t.addView(iv, 0, match());
        } else {
            // No TV banner: the icon on a dark card tinted with the app's colour, name underneath.
            t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{shade(t.accent, 0.42f), shade(t.accent, 0.18f)}));
            LinearLayout stack = new LinearLayout(this);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(art);
            stack.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView n = text(11, medium, Color.WHITE);
            n.setGravity(Gravity.CENTER_HORIZONTAL);
            n.setSingleLine(true);
            n.setPadding(dp(6), dp(4), dp(6), 0);
            n.setText(name);
            stack.addView(n);
            t.addView(stack, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }
        t.listener = this;
        t.setOnClickListener(v -> zoomLaunch(t, () -> launch(ai)));
        t.setOnLongClickListener(v -> { showMenu(t); return true; });
        return t;
    }

    private Tile settingsTile() {
        Tile t = new Tile(this, tileW, tileH, radius, 1.10f, "MonolithicOS Settings", null, NEUTRAL, NEUTRAL2);
        glass(t, radius, 0x40101014, 0xFF2E2E31);
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER);
        ImageView glyph = new ImageView(this);
        glyph.setImageDrawable(new SlidersGlyph(dp(1.6f)));
        stack.addView(glyph, new LinearLayout.LayoutParams(dp(28), dp(22)));
        TextView n = text(12, medium, Color.WHITE);
        n.setGravity(Gravity.CENTER_HORIZONTAL);
        n.setText("MonolithicOS");
        n.setPadding(0, dp(8), 0, 0);
        stack.addView(n);
        t.addView(stack, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        t.listener = this;
        t.setOnClickListener(v -> showSettings());
        t.setOnLongClickListener(v -> { showSettings(); return true; });
        return t;
    }

    /** Card plus the name that fades in under it on focus. */
    private View labelled(Tile t) {
        LinearLayout cellView = new LinearLayout(this);
        cellView.setOrientation(LinearLayout.VERTICAL);
        cellView.setClipChildren(false);
        cellView.addView(t, new LinearLayout.LayoutParams(tileW, tileH));
        TextView label = legible(text(13, medium, Color.WHITE));
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setText(t.subtitle != null ? t.subtitle : t.title);
        label.setAlpha(0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        cellView.addView(label, lp);
        t.label = label;
        return cellView;
    }

    private LinearLayout row(int padLeft) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(padLeft, 0, 0, 0);
        return line;
    }

    private LinearLayout.LayoutParams cell(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (index > 0) lp.leftMargin = gap;
        return lp;
    }

    private TextView section(String name) {
        TextView s = legible(text(15, medium, 0x99FFFFFF));
        s.setText(name);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = bandPad;
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(12);
        s.setLayoutParams(lp);
        return s;
    }

    /** Stop focus wrapping off the ends of a row (and off the top/bottom of the screen). */
    private void edges(List<Tile> tiles, boolean top, boolean bottom) {
        for (Tile t : tiles) {
            if (t.getId() == View.NO_ID) t.setId(View.generateViewId());
            t.setNextFocusLeftId(View.NO_ID);
            t.setNextFocusRightId(View.NO_ID);
            if (top) t.setNextFocusUpId(t.getId());
            if (bottom) t.setNextFocusDownId(t.getId());
        }
        if (tiles.isEmpty()) return;
        Tile first = tiles.get(0), last = tiles.get(tiles.size() - 1);
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());
    }

    // ---- focus, backdrop, scrolling --------------------------------------------------------

    @Override
    public void onTileFocused(Tile t, int direction) {
        focused = t;
        backdrop.show(t.accent, t.accent2);
        if (t == f1.tile) {
            F1.Race r = f1Race;
            if (r != null) {
                showTitle(r.name, "Round " + r.round + "  ·  " + r.place + "  ·  Race "
                        + F1.when(r.race().start, System.currentTimeMillis(), prefs.getBoolean("clock24", false)), direction);
            }
            scrollTo(0);
            return;
        }
        TeamCard team = teamOf(t);
        if (team != null) {
            team.focused(direction);
            scrollTo(0);
            return;
        }
        if (t == inputsPill) {
            showTitle("Inputs", inputsSummary, direction);
            scrollTo(0);
            return;
        }
        if (t == nowCard) {
            showTitle(nowTitle.getText().toString(), nowArtist.getText().toString(), direction);
            scrollTo(0);
            return;
        }
        Integer row = rowOf.get(t);
        int r = row == null ? 0 : row;
        if (r == 0) showTitle(t.title, t.subtitle, direction);
        scrollTo(r);
    }

    /** The shelf title slides in the way you moved, like tvOS. */
    private void showTitle(String text, String sub, int direction) {
        // compare with the title last asked for, not what's on screen: two focus moves inside the
        // 90 ms fade-out would otherwise let the first one's text land last
        if (text.equals(titleWant) && TextUtils.equals(sub, subWant)) return;
        titleWant = text;
        subWant = sub;
        int sx = direction == View.FOCUS_RIGHT ? 1 : direction == View.FOCUS_LEFT ? -1 : 0;
        title.animate().cancel();
        title.animate().alpha(0f).translationX(-sx * dp(18)).setDuration(90).withEndAction(() -> {
            title.setText(text);
            subtitle.setText(sub == null ? "" : sub);
            title.setTranslationX(sx * dp(28));
            title.setTranslationY(sx == 0 ? dp(6) : 0);
            title.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(340)
                    .setInterpolator(glide).start();
        }).start();
    }

    private void scrollTo(int row) {
        if (column.isLayoutRequested()) {
            // Rows were just rebuilt and have no positions yet: scroll once they're laid out.
            column.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    column.getViewTreeObserver().removeOnPreDrawListener(this);
                    scrollTo(row);
                    return true;
                }
            });
            return;
        }
        float target = 0f;
        if (row > 0 && row < rows.size()) {
            // the first apps row a little lower, so the dock above it stays clear of the clock
            target = Math.min(0f, dp(row == 1 ? 300 : 250) - (contentTop + rows.get(row).getTop()));
        }
        target = Math.round(target);   // whole pixels at rest
        if (target != column.getTranslationY()) {
            // the glass re-aligns its blur with the wallpaper while it slides
            column.animate().translationY(target).setDuration(480).setInterpolator(glide)
                    .setUpdateListener(frost == null ? null : a -> {
                        for (View v : glassViews) if (v != nowCard) v.invalidate();
                    })
                    .start();
        }
        float header = row == 0 ? 1f : 0f;
        shelf.animate().alpha(header).setDuration(row == 0 ? 360 : 200).setInterpolator(glide).start();
        greeting.animate().alpha(header).setDuration(row == 0 ? 360 : 200).setInterpolator(glide).start();
        // the cards can sit low enough for the sliding dock to reach them: they go with the shelf
        for (Card c : cards) c.box.animate().alpha(header).setDuration(row == 0 ? 360 : 200).setInterpolator(glide).start();
        // Deeper than the first apps row the dock has slid up under the clock: let it go.
        if (!rows.isEmpty()) {
            rows.get(0).animate().alpha(row >= 2 ? 0f : 1f).setDuration(280).setInterpolator(glide).start();
        }
        // At rest only the "Apps" heading peeks under the dock; the rows fade in on the way down.
        for (int i = 1; i < rows.size(); i++) {
            rows.get(i).animate().alpha(row >= 1 ? 1f : 0f).setDuration(row >= 1 ? 320 : 200).setInterpolator(glide).start();
        }
    }

    private void goTop() {
        if (!dockTiles.isEmpty()) dockTiles.get(0).requestFocus();
    }

    // ---- hold-OK menu ----------------------------------------------------------------------

    private void showMenu(Tile t) {
        TeamCard team = teamOf(t);
        if (t == f1.tile || team != null || t == inputsPill) {
            if (t == f1.tile) showF1(); else if (t == inputsPill) showInputs(); else team.open();
            return;
        }
        if (menu != null) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (dockTiles.contains(t)) {
            labels.add("Move");                   actions.add(() -> startMove(t));
            labels.add("Remove from Dock");       actions.add(() -> undock(t.pkg));
            labels.add("App info");               actions.add(() -> appInfo(t.pkg));
        } else if (t == nowCard) {
            if (playing != null) {
                String pkg = playing.getPackageName();
                labels.add("Open " + appLabel(pkg)); actions.add(() -> launchPackage(pkg));
            }
        } else if (t.pkg != null) {
            labels.add("Add to Dock");            actions.add(() -> addToDock(t.pkg));
            labels.add("App info");               actions.add(() -> appInfo(t.pkg));
            if (TV_SETTINGS.equals(t.pkg)) {
                // The way back to Google TV's own launcher without a computer: press Enable there.
                labels.add("Restore Google TV Home"); actions.add(() -> appInfo(GOOGLE_HOME));
            }
        }

        labels.add("MonolithicOS Settings");          actions.add(this::showSettings);

        LinearLayout panel = openPanel(t.title, dockTiles.contains(t) ? "In your Dock"
                : t.pkg != null ? "App" : "", dp(330));
        panel.setGravity(Gravity.CENTER_VERTICAL);
        List<View> items = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            TextView item = menuItem(labels.get(i));
            Runnable act = actions.get(i);
            item.setOnClickListener(v -> { closeMenu(false); act.run(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = i == 0 ? 0 : dp(6);
            panel.addView(item, lp);
            items.add(item);
        }
        fence(items);
        items.get(0).requestFocus();
    }

    /**
     * The side panel the hold-OK menu and MonolithicOS Settings slide in on: dark Liquid Glass over the
     * dimmed screen, its right-hand corners tucked off the edge.
     */
    private LinearLayout openPanel(String head, String hint, int width) {
        menu = new FrameLayout(this);
        menu.setBackground(new ColorDrawable(0x73000000));
        menu.setClickable(true);
        menu.setAlpha(0f);

        int r = dp(28);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(28), dp(28) + r, dp(28));
        glass(panel, r, 0x99141416, 0xF2202023);

        TextView title = text(22, medium, Color.WHITE);
        title.setText(head);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(title);
        TextView sub = text(13, light, 0x80FFFFFF);
        sub.setText(hint);
        sub.setPadding(0, dp(4), 0, dp(20));
        panel.addView(sub);

        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(width + r, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END);
        pl.rightMargin = -r;
        menu.addView(panel, pl);
        root.addView(menu, match());
        panel.setTranslationX(width + dp(10));
        panel.animate().translationX(0f).setDuration(320).setInterpolator(glide)
                .setUpdateListener(frost == null ? null : a -> panel.invalidate())   // the glass follows the slide
                .start();
        menu.animate().alpha(1f).setDuration(200).start();
        return panel;
    }

    /** Up/down stay inside the list, left/right go nowhere. */
    private static void fence(List<View> items) {
        for (View v : items) {
            if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
            v.setNextFocusLeftId(v.getId());
            v.setNextFocusRightId(v.getId());
        }
        items.get(0).setNextFocusUpId(items.get(0).getId());
        items.get(items.size() - 1).setNextFocusDownId(items.get(items.size() - 1).getId());
    }

    // ---- MonolithicOS Settings -----------------------------------------------------------------

    /** One line of MonolithicOS Settings: its choices, and how to read and change it. */
    private static final class Choice {
        final String label;
        final boolean system;   // TV-wide: needs the permission the installer grants
        final String[] names;
        final IntSupplier get;
        final IntConsumer set;
        Supplier<String> note;
        boolean scale;          // Left/Right stop at the ends instead of going round

        Choice(String label, boolean system, String[] names, IntSupplier get, IntConsumer set) {
            this.label = label;
            this.system = system;
            this.names = names;
            this.get = get;
            this.set = set;
        }

        Choice note(Supplier<String> n) {
            note = n;
            return this;
        }

        Choice scale() {
            scale = true;
            return this;
        }
    }

    private List<Choice> choices() {
        List<Choice> out = new ArrayList<>();
        out.add(new Choice("Background", false, new String[]{"Wallpaper", "Aurora"},
                () -> prefs.getBoolean("photo", true) ? 0 : 1,
                i -> prefs.edit().putBoolean("photo", i == 0).apply()));
        List<File> photos = Wallpapers.list(this);
        if (photos.size() > 1) {
            String[] names = new String[photos.size()];
            for (int i = 0; i < names.length; i++) names[i] = photoName(photos.get(i));
            out.add(new Choice("Wallpaper", false, names,
                    () -> Math.max(0, photos.indexOf(Wallpapers.current(this))),
                    i -> prefs.edit().putBoolean("photo", true).putString("wallpaper", photos.get(i).getPath()).apply())
                    .note(() -> "More from the phone page"));
        }
        String[] dims = new String[11];   // Off, 10% ... 100% (black)
        for (int i = 0; i < dims.length; i++) dims[i] = i == 0 ? "Off" : i * 10 + "%";
        out.add(new Choice("Dim wallpaper", false, dims,
                () -> Math.max(0, Math.min(10, Math.round(prefs.getInt("dim", 20) / 10f))),
                i -> prefs.edit().putInt("dim", i * 10).apply()).scale());
        File shown = Wallpapers.current(this);
        if (shown != null) {
            out.add(new Choice("Falling snow", false, new String[]{"On", "Off"},
                    () -> Snow.on(prefs, shown) ? 0 : 1,
                    i -> prefs.edit().putBoolean(Snow.key(shown), i == 0).apply())
                    .note(() -> "On this wallpaper"));
        }
        out.add(new Choice("Night mode", false, new String[]{"Auto", "Off"},
                () -> prefs.getBoolean("night", true) ? 0 : 1,
                i -> prefs.edit().putBoolean("night", i == 0).apply())
                .note(() -> "Darker and warmer, 10 pm to 6 am"));
        out.add(new Choice("Clock", false, new String[]{"12-hour", "24-hour"},
                () -> prefs.getBoolean("clock24", false) ? 1 : 0,
                i -> prefs.edit().putBoolean("clock24", i == 1).apply()));
        out.add(new Choice("Ad blocking", true, new String[]{"On", "Off"},
                () -> Tweaks.adblock(this) ? 0 : 1, i -> Tweaks.setAdblock(this, i == 0))
                .note(() -> "AdGuard DNS for the whole TV"));
        out.add(new Choice("Animations", true, new String[]{"Fast", "Normal"},
                () -> Tweaks.fast(this) ? 0 : 1, i -> Tweaks.setFast(this, i == 0)));
        out.add(new Choice("Screensaver", true, new String[]{"MonolithicOS", "Google TV"},
                () -> Tweaks.ambient(this) ? 0 : 1, i -> Tweaks.setAmbient(this, i == 0)));
        out.add(new Choice("App buttons", true, new String[]{"On Home", "Everywhere"},
                () -> Tweaks.service(this, Tweaks.KEY_HELPER) ? 1 : 0,
                i -> Tweaks.setService(this, Tweaks.KEY_HELPER, i == 1))
                .note(() -> "Everywhere: holding volume steps once"));
        out.add(new Choice("Cards", false, new String[]{"Show", "Hide"},
                () -> prefs.getBoolean("cards_on", true) ? 0 : 1,
                i -> prefs.edit().putBoolean("cards_on", i == 0).apply())
                .note(this::cardsNote));
        out.add(new Choice("Phone remote", false, new String[]{"On", "Off"},
                () -> prefs.getBoolean("remote", true) ? 0 : 1, i -> setRemote(i == 0))
                .note(() -> prefs.getBoolean("remote", true)
                        ? Remote.address() + "  ·  PIN " + Remote.pin(this) : "Phone page and keyboard off"));
        return out;
    }

    private void setRemote(boolean on) {
        prefs.edit().putBoolean("remote", on).apply();
        if (on) Remote.start(this); else Remote.stop();
        // the D-pad and typing half needs the accessibility helper
        if (Tweaks.allowed(this)) Tweaks.setService(this, Tweaks.TYPE_HELPER, on);
    }

    private static String photoName(File f) {
        String n = f.getName();
        if (n.startsWith("phone-")) {
            return "Phone, " + new java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(f.lastModified());
        }
        int dot = n.lastIndexOf('.');
        return (dot > 0 ? n.substring(0, dot) : n).replace('-', ' ').replace('_', ' ');
    }

    private void showSettings() {
        if (menu != null) return;
        LinearLayout panel = openPanel("MonolithicOS", "Settings", dp(420));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);   // rows melt away at the edge instead of being cut
        scroll.setFadingEdgeLength(dp(36));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        List<View> items = new ArrayList<>();
        List<Runnable> refresh = new ArrayList<>();
        for (Choice c : choices()) items.add(choiceRow(c, refresh));
        if (watch != null && watch.bad()) {   // first thing in the list while the warning is up
            TextView ignore = menuItem("Ignore the update warning");
            ignore.setOnClickListener(v -> {
                closeMenu(true);
                worker.execute(() -> {
                    Watchdog.snapshot(this);
                    handler.post(this::checkWatchdog);
                });
            });
            items.add(0, ignore);
        }
        TextView pin = menuItem("New phone PIN");
        pin.setOnClickListener(v -> {
            Remote.newPin(this);
            for (Runnable r : refresh) r.run();
            toast("Phones signed out. New PIN " + Remote.pin(this));
        });
        items.add(pin);
        TextView google = menuItem("Restore Google TV Home");
        google.setOnClickListener(v -> { closeMenu(false); appInfo(GOOGLE_HOME); });
        items.add(google);
        for (int i = 0; i < items.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = i == 0 ? 0 : dp(i == items.size() - 2 || (i == 1 && watch != null && watch.bad()) ? 18 : 4);
            list.addView(items.get(i), lp);
        }
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        fence(items);
        items.get(0).requestFocus();
    }

    /** Name on the left, value on the right; left/right (or OK) cycles it, like tvOS. */
    private View choiceRow(Choice c, List<Runnable> refresh) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(18), dp(10), dp(16), dp(10));
        row.setBackground(pill());
        row.setMinimumHeight(dp(46));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);   // so the name turns black on the white pill too
        TextView label = text(16, medium, Color.WHITE);
        label.setTextColor(focusColors(Color.BLACK, 0xE6FFFFFF));
        label.setText(c.label);
        label.setDuplicateParentStateEnabled(true);
        words.addView(label);
        TextView note = null;
        if (c.note != null) {
            note = text(11, light, 0x80FFFFFF);
            note.setTextColor(focusColors(0x99000000, 0x80FFFFFF));
            note.setPadding(0, dp(3), 0, 0);
            note.setDuplicateParentStateEnabled(true);
            words.addView(note);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text(14, light, 0xB3FFFFFF);
        value.setTextColor(focusColors(Color.BLACK, 0xB3FFFFFF));
        value.setDuplicateParentStateEnabled(true);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setMaxWidth(dp(170));
        row.addView(value);

        TextView noteView = note;
        Runnable show = () -> {
            String name = c.names[Math.min(c.names.length - 1, Math.max(0, c.get.getAsInt()))];
            value.setText(row.isFocused() ? "\u2039  " + name + "  \u203A" : name);
            if (noteView != null) noteView.setText(c.note.get());
        };
        show.run();
        refresh.add(show);
        row.setOnFocusChangeListener((v, has) -> show.run());
        row.setOnClickListener(v -> step(c, 1, true, value, refresh));
        row.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                step(c, code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, !c.scale, value, refresh);
            }
            return true;
        });
        return row;
    }

    private void step(Choice c, int d, boolean wrap, TextView value, List<Runnable> refresh) {
        if (c.system && !Tweaks.allowed(this)) {
            toast("Run the MonolithicOS installer once to allow this");
            return;
        }
        int n = c.names.length, i = ((c.get.getAsInt() + d) % n + n) % n;
        if (!wrap && i != c.get.getAsInt() + d) return;   // already at the end
        try {
            c.set.accept(i);
        } catch (RuntimeException e) {
            toast("The TV didn't allow that");
        }
        for (Runnable r : refresh) r.run();
        // the new value slides in from the side you pressed
        value.animate().cancel();
        value.setTranslationX(d * dp(12));
        value.setAlpha(0.2f);
        value.animate().translationX(0f).alpha(1f).setDuration(240).setInterpolator(glide).start();
    }

    private TextView menuItem(String label) {
        TextView v = text(17, medium, Color.WHITE);
        v.setText(label);
        v.setFocusable(true);
        v.setClickable(true);
        v.setPadding(dp(18), dp(13), dp(18), dp(13));
        v.setTextColor(focusColors(Color.BLACK, 0xE6FFFFFF));
        v.setBackground(pill());
        return v;
    }

    /** White when focused, clear otherwise: the tvOS list highlight. */
    private Drawable pill() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.WHITE);
        on.setCornerRadius(dp(14));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, on);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        states.setEnterFadeDuration(120);
        states.setExitFadeDuration(120);
        return states;
    }

    private static ColorStateList focusColors(int focused, int normal) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{focused, normal});
    }

    private void closeMenu(boolean restoreFocus) {
        FrameLayout m = menu;
        if (m == null) return;
        menu = null;
        if (restoreFocus && focused != null) focused.requestFocus();
        glassViews.removeIf(v -> v.getParent() == m);
        m.animate().alpha(0f).setDuration(160).withEndAction(() -> root.removeView(m)).start();
    }

    // ---- rearranging the dock --------------------------------------------------------------

    private void startMove(Tile t) {
        moving = t;
        t.requestFocus();
        t.setMoving(true);
    }

    /** Swap with a neighbour, then slide everyone from their old spot to the new one (FLIP). */
    private void shift(int d) {
        int i = dock.indexOfChild(moving), j = i + d;
        if (i < 0 || j < 0 || j >= dock.getChildCount()) return;
        Map<View, Integer> before = new HashMap<>();
        for (int c = 0; c < dock.getChildCount(); c++) before.put(dock.getChildAt(c), dock.getChildAt(c).getLeft());
        View neighbour = dock.getChildAt(j);   // move the neighbour, not the focused tile, so focus stays put
        dock.removeView(neighbour);
        dock.addView(neighbour, i);
        dockTiles.clear();
        for (int c = 0; c < dock.getChildCount(); c++) {
            Tile t = (Tile) dock.getChildAt(c);
            dockTiles.add(t);
            ((LinearLayout.LayoutParams) t.getLayoutParams()).leftMargin = c == 0 ? 0 : gap;
        }
        edges(dockTiles, true, false);
        dock.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                dock.getViewTreeObserver().removeOnPreDrawListener(this);
                for (Map.Entry<View, Integer> e : before.entrySet()) {
                    View v = e.getKey();
                    int dx = e.getValue() - v.getLeft();
                    if (dx == 0) continue;
                    v.setTranslationX(dx);
                    v.animate().translationX(0f).setDuration(260).setInterpolator(glide).start();
                }
                return true;
            }
        });
        dock.requestLayout();
    }

    private void endMove() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        List<String> order = new ArrayList<>();
        for (Tile d : dockTiles) order.add(d.pkg);
        for (String p : dockOrder) if (!order.contains(p)) order.add(p);   // keep favourites not installed
        saveDock(order);
    }

    private void addToDock(String pkg) {
        if (dockTiles.size() >= DOCK_MAX) {
            toast("The Dock holds " + DOCK_MAX + " apps: remove one first");
            return;
        }
        List<String> order = new ArrayList<>();
        for (Tile d : dockTiles) order.add(d.pkg);
        order.add(pkg);
        for (String p : dockOrder) if (!order.contains(p)) order.add(p);
        saveDock(order);
        build();
    }

    private void undock(String pkg) {
        List<String> order = new ArrayList<>(dockOrder);
        order.remove(pkg);
        saveDock(order);
        build();
    }

    private void saveDock(List<String> order) {
        dockOrder.clear();
        dockOrder.addAll(order);
        prefs.edit().putString("dock", TextUtils.join(",", order)).apply();
    }

    // ---- launching -------------------------------------------------------------------------

    private void launch(ActivityInfo ai) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(new ComponentName(ai.packageName, ai.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        // our zoom is the transition: the app just fades in over it
        Bundle opts = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
                .toBundle();
        try {
            startActivity(i, opts);
        } catch (ActivityNotFoundException | SecurityException e) {
            clearZoom();
            toast("Couldn't open " + ai.loadLabel(getPackageManager()));
        }
    }

    /**
     * tvOS-style launch: the focused card grows to fill the screen in the app's colour, its art
     * gliding to the centre, and the app fades in over it. The overlay doubles as a splash while a
     * cold app starts. One rounded outline and one image animating: cheap.
     */
    private void zoomLaunch(Tile t, Runnable go) {
        if (zoom != null) return;
        int[] loc = new int[2];
        t.getLocationInWindow(loc);
        float s0 = t.getScaleX();
        float w0 = t.getWidth() * s0, h0 = t.getHeight() * s0, l0 = loc[0], t0 = loc[1];
        int sw = root.getWidth(), sh = root.getHeight();
        float[] box = {l0, t0, l0 + w0, t0 + h0, radius * s0};

        FrameLayout z = new FrameLayout(this);
        zoom = z;
        z.setBackgroundColor(t.inputId != null ? 0xFF161618 : shade(t.accent, 0.30f));
        z.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(Math.round(box[0]), Math.round(box[1]), Math.round(box[2]), Math.round(box[3]), box[4]);
            }
        });
        z.setClipToOutline(true);
        ImageView art = new ImageView(this);
        Drawable d = t.art != null && t.art.getConstantState() != null
                ? t.art.getConstantState().newDrawable(getResources()).mutate() : t.art;
        art.setImageDrawable(d);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        art.setClipToOutline(true);
        z.addView(art, new FrameLayout.LayoutParams(tileW, tileH));
        root.addView(z, match());

        float cx0 = l0 + w0 / 2f, cy0 = t0 + h0 / 2f, cx1 = sw / 2f, cy1 = sh / 2f;
        boolean[] fired = {false};
        ValueAnimator grow = ValueAnimator.ofFloat(0f, 1f);
        grow.setDuration(340);
        grow.setInterpolator(glide);
        grow.addUpdateListener(a -> {
            float e = (float) a.getAnimatedValue();
            box[0] = l0 * (1 - e);
            box[1] = t0 * (1 - e);
            box[2] = (l0 + w0) + (sw - l0 - w0) * e;
            box[3] = (t0 + h0) + (sh - t0 - h0) * e;
            box[4] = radius * s0 * (1 - e);
            z.invalidateOutline();
            float s = s0 * (1f + 0.55f * e);
            art.setScaleX(s);
            art.setScaleY(s);
            art.setTranslationX(cx0 + (cx1 - cx0) * e - tileW / 2f);
            art.setTranslationY(cy0 + (cy1 - cy0) * e - tileH / 2f);
            if (!fired[0] && a.getAnimatedFraction() >= 0.45f) {   // start the app mid-zoom: no added wait
                fired[0] = true;
                go.run();
            }
        });
        grow.start();
        // If nothing ever covers us (an input with no picture, say), don't leave the overlay up.
        handler.postDelayed(() -> { if (zoom == z && hasWindowFocus()) clearZoom(); }, 4000);
    }

    private void clearZoom() {
        if (zoom == null) return;
        root.removeView(zoom);
        zoom = null;
    }

    private void launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) start(i);
    }

    private void switchInput(String id) {
        if (!Inputs.switchTo(this, id)) toast("Couldn't switch input");
    }

    private void appInfo(String pkg) {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("Not available on this TV");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- wallpaper, glass and tone ---------------------------------------------------------

    // MonolithicOS Settings and the phone page both just write prefs; Home follows. A field, because
    // SharedPreferences only holds its listeners weakly.
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsChanged = (p, key) -> {
        if (key == null) return;
        switch (key) {
            case "photo": case "wallpaper": applyLook(); break;
            case "dim": applyTone(); updateSnow(); break;
            case "night": applyTone(); break;
            case "clock24":
                applyClock();
                updateF1();
                for (TeamCard t : teams) t.update();
                break;
            case "cards": case "cards_on": applyCards(); break;
            case "dock":   // set from the phone page (our own Move/Add/Remove write it too, then rebuild themselves)
                List<String> order = Arrays.asList(prefs.getString("dock", "").split(","));
                if (!order.equals(dockOrder)) {
                    dockOrder.clear();
                    dockOrder.addAll(order);
                    rebuildSoon();
                }
                break;
            case "name": greeting.setText(greetingText()); break;
            case "city": weatherAt = 0; refreshWeather(); break;
            default:
                if (key.startsWith("name:")) rebuildSoon();
                else if (key.startsWith("snow:")) updateSnow();
        }
    };

    private void applyClock() {
        boolean clock24 = prefs.getBoolean("clock24", false);
        clock.setFormat12Hour(clock24 ? "H:mm" : "h:mm");
        clock.setFormat24Hour(clock24 ? "H:mm" : "h:mm");
    }

    /** The clock format, then wallpaper or aurora (loaded off the main thread), then the tone. */
    private void applyLook() {
        applyClock();
        File chosen = Wallpapers.current(this);
        if (chosen == null) {
            backdrop.setPhoto(null);
            setFrost(null);
            applyTone();
            updateSnow();
            retryLook();
            return;
        }
        loading = true;
        worker.execute(() -> {
            Wallpapers.Loaded l = Wallpapers.load(chosen, screenW, screenH);
            handler.post(() -> {
                loading = false;
                if (isDestroyed()) return;
                if (!chosen.equals(Wallpapers.current(this))) {   // changed again meanwhile
                    applyLook();
                    return;
                }
                if (l == null) retryLook(); else lookTries = 0;
                backdrop.setPhoto(l != null ? l.photo : null);
                setFrost(l != null ? l.frost : null);
                applyTone();
                updateSnow();
            });
        });
    }

    /**
     * Give a view a Liquid Glass background: veilOverFrost over the wallpaper, veilAlone over the aurora.
     * Cards that lift on focus need an opaque veilAlone: through see-through glass their own shadow shows.
     */
    private void glass(View v, float r, int veilOverFrost, int veilAlone) {
        Glass g = new Glass(r, density, veilOverFrost, veilAlone);
        float[] t = backdrop.tone();
        g.setTone(t[0], t[1], t[2]);
        g.setFrost(frost, screenW, screenH);
        v.setBackground(g);
        glassViews.add(v);
    }

    /**
     * Right after a boot Home starts before shared storage (Pictures/Wallpapers) is mounted: keep
     * trying for a few minutes instead of settling on the aurora.
     */
    private void retryLook() {
        handler.removeCallbacks(lookRetry);
        if (!prefs.getBoolean("photo", true) || lookTries >= 8) return;
        handler.postDelayed(lookRetry, 2000L << Math.min(lookTries++, 5));   // 2 s, 4 s ... 64 s
    }

    private final Runnable lookRetry = this::applyLook;

    private void setFrost(Bitmap f) {
        frost = f;
        for (View v : glassViews) ((Glass) v.getBackground()).setFrost(f, screenW, screenH);
    }

    private boolean nightNow() {
        if (!prefs.getBoolean("night", true)) return false;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 22 || h < 6;
    }

    /** Wallpaper dim, and after 10 pm night mode: everything a bit darker and warmer. */
    private void applyTone() {
        float dim = backdrop.hasPhoto() ? prefs.getInt("dim", 20) / 100f : 0f;
        boolean night = nightNow();
        backdrop.setTone((1f - dim) * (night ? 0.62f : 1f), night);
        float[] t = backdrop.tone();
        for (View v : glassViews) ((Glass) v.getBackground()).setTone(t[0], t[1], t[2]);
        snow.setTone(t[0], t[1], t[2]);
    }

    /** Falling snow over wallpapers that have it on, while Home is on screen. */
    private void updateSnow() {
        boolean on = backdrop.hasPhoto() && prefs.getInt("dim", 20) < 100 && Snow.on(prefs, Wallpapers.current(this));
        snow.setVisibility(on ? View.VISIBLE : View.GONE);
        snow.setFalling(on && resumed);
    }

    private final Runnable toneTick = new Runnable() {
        @Override public void run() {
            applyTone();
            greeting.setText(greetingText());
            handler.postDelayed(this, 5 * 60_000L);
        }
    };

    /** A soft shadow under text that sits on the wallpaper, so it reads over snow or sky. */
    private <T extends TextView> T legible(T v) {
        v.setShadowLayer(dp(10), 0, dp(1), 0x8C000000);
        return v;
    }

    // ---- weather ---------------------------------------------------------------------------

    private void refreshWeather() {
        long now = SystemClock.elapsedRealtime();
        if (weatherAt != 0 && now - weatherAt < 30 * 60_000L) return;   // at most every 30 min
        weatherAt = now;
        String city = prefs.getString("city", null);
        if (city == null) {   // first run: a guess from the TV's internet address; the phone page can change it
            Weather.locate(handler, place -> {
                if (place == null) return;
                prefs.edit().putString("lat", place[1]).putString("lon", place[2]).putString("city", place[0]).apply();
            });
            return;
        }
        Weather.fetch(prefs.getString("lat", ""), prefs.getString("lon", ""), handler, s -> {
            String text = s + " · " + city;
            weather.setText(text);
            prefs.edit().putString("weather", text).apply();
        });
    }

    // ---- Now Playing -----------------------------------------------------------------------

    /** A glass card under the clock: album art, track, artist. OK plays/pauses, hold OK opens the app. */
    private void buildNowCard() {
        int w = dp(300), h = dp(84);
        nowCard = new Tile(this, w, h, dp(18), 1.05f, "Now Playing", null, NEUTRAL, NEUTRAL2);
        glass(nowCard, dp(18), 0x40101014, 0xFF26262A);   // smokier: white text on it
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(14), dp(10));
        nowArt = new ImageView(this);
        nowArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        nowArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        nowArt.setClipToOutline(true);
        row.addView(nowArt, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, 0, 0);
        nowTitle = text(15, medium, Color.WHITE);
        nowArtist = text(12, light, 0xB3FFFFFF);
        nowState = text(11, medium, 0x80FFFFFF);
        for (TextView v : new TextView[]{nowTitle, nowArtist, nowState}) {
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
        }
        nowArtist.setPadding(0, dp(3), 0, dp(5));
        words.addView(nowTitle);
        words.addView(nowArtist);
        words.addView(nowState);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nowCard.addView(row, 0, match());
        nowCard.setId(View.generateViewId());
        nowCard.setNextFocusLeftId(nowCard.getId());
        nowCard.setNextFocusRightId(nowCard.getId());
        nowCard.setNextFocusUpId(nowCard.getId());
        nowCard.listener = this;
        nowCard.setOnClickListener(v -> togglePlay());
        nowCard.setOnLongClickListener(v -> {
            if (playing != null) launchPackage(playing.getPackageName());
            return true;
        });
        nowCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.END);
        lp.topMargin = dp(124);
        lp.rightMargin = side + bandPad;
        root.addView(nowCard, lp);
    }

    /**
     * The cards under the clock, top to bottom: Now Playing, then whichever card is live or soonest.
     * Two fit above the dock, so while Now Playing is up only the first shows. Up from the dock reaches
     * the lowest; they glide to their new places when the order changes.
     */
    private void layoutCards() {
        if (nowCard == null || f1 == null || inputsPill == null) return;
        boolean np = nowCard.getVisibility() == View.VISIBLE;
        List<Card> order = new ArrayList<>();
        for (Card c : cards) if (c.want) order.add(c);
        order.sort((a, b) -> Long.compare(a.rank, b.rank));
        int top = np ? dp(124 + 84 + 12) : dp(124);
        int room = Math.max(0, (contentTop - dp(16) - top + dp(12)) / dp(64 + 12));   // three, or two under Now Playing
        List<View> stack = new ArrayList<>();   // focus order, top to bottom
        if (np) stack.add(nowCard);
        for (Card c : cards) {
            int at = order.indexOf(c);
            if (at >= 0 && at < room) {
                place(c, top + at * dp(64 + 12));
            } else if (c.box.getVisibility() == View.VISIBLE) {
                if (c.tile.hasFocus()) goTop();
                c.box.setVisibility(View.GONE);
            }
        }
        for (int i = 0; i < Math.min(room, order.size()); i++) stack.add(order.get(i).tile);
        // up the right-hand side: dock, the cards bottom to top, then the inputs pill by the clock
        if (inputsPill.getVisibility() == View.VISIBLE) stack.add(0, inputsPill);
        View bottom = stack.isEmpty() ? null : stack.get(stack.size() - 1);
        for (Tile t : dockTiles) t.setNextFocusUpId(bottom != null ? bottom.getId() : t.getId());
        int last = dockTiles.isEmpty() ? View.NO_ID : dockTiles.get(dockTiles.size() - 1).getId();
        for (int i = 0; i < stack.size(); i++) {
            View v = stack.get(i);
            v.setNextFocusUpId(i > 0 ? stack.get(i - 1).getId() : v.getId());
            v.setNextFocusDownId(i < stack.size() - 1 ? stack.get(i + 1).getId() : last);
        }
    }

    /** Fades a card in at its place, or glides it there if it's already showing. */
    private void place(Card c, int top) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) c.box.getLayoutParams();
        if (c.box.getVisibility() != View.VISIBLE) {
            lp.topMargin = top;
            c.box.setLayoutParams(lp);
            c.box.setTranslationY(0f);
            c.box.setVisibility(View.VISIBLE);
            c.box.setAlpha(0f);
            c.box.animate().alpha(rows.isEmpty() || column.getTranslationY() > -1f ? 1f : 0f)
                    .setDuration(300).setInterpolator(glide).start();
            return;
        }
        if (lp.topMargin == top) return;
        float from = lp.topMargin - top + c.box.getTranslationY();
        lp.topMargin = top;
        c.box.setLayoutParams(lp);
        c.box.setTranslationY(from);
        c.box.animate().translationY(0f).setDuration(380).setInterpolator(glide)
                .setUpdateListener(frost == null ? null : a -> c.tile.invalidate())   // glass follows
                .start();
    }

    private ComponentName listenerName() {
        return new ComponentName(this, NowPlayingListener.class);
    }

    private void initSessions() {
        sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerName(), handler);
        } catch (SecurityException e) {
            sessions = null;   // listener not allowed (the installer allows it): no Now Playing
        }
        pickSession(null);
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pickSession;

    private final MediaController.Callback sessionCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { updateNowPlaying(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { updateNowPlaying(); }
        @Override public void onSessionDestroyed() { pickSession(null); }
    };

    /** Prefer whatever is playing; otherwise something paused with a track loaded. */
    private void pickSession(List<MediaController> list) {
        if (sessions == null) return;
        if (list == null) {
            try { list = sessions.getActiveSessions(listenerName()); } catch (SecurityException e) { return; }
        }
        MediaController best = null;
        for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s == null || c.getMetadata() == null) continue;
            if (s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            if (best == null && s.getState() == PlaybackState.STATE_PAUSED) best = c;
        }
        if (best == null || playing == null || !best.getSessionToken().equals(playing.getSessionToken())) {
            if (playing != null) playing.unregisterCallback(sessionCallback);
            playing = best;
            if (playing != null) playing.registerCallback(sessionCallback, handler);
        }
        updateNowPlaying();
    }

    private void updateNowPlaying() {
        MediaMetadata md = playing != null ? playing.getMetadata() : null;
        PlaybackState ps = playing != null ? playing.getPlaybackState() : null;
        int st = ps != null ? ps.getState() : PlaybackState.STATE_NONE;
        boolean show = md != null && (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_PAUSED
                || st == PlaybackState.STATE_BUFFERING);
        if (!show) {
            if (nowCard.getVisibility() == View.VISIBLE) {
                if (nowCard.hasFocus()) goTop();
                nowCard.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                    nowCard.setVisibility(View.GONE);
                    layoutCards();
                }).start();
            }
            return;
        }
        String track = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        nowTitle.setText(track == null ? "" : track);
        nowArtist.setText(artist == null ? "" : artist);
        nowState.setText(appLabel(playing.getPackageName()) + " · "
                + (st == PlaybackState.STATE_PAUSED ? "Paused" : "Playing"));
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art != nowBitmap) {
            nowBitmap = art;
            nowArt.setImageBitmap(art);
            int[] p = art != null ? palette("np:" + track + "/" + artist, new BitmapDrawable(getResources(), art))
                    : new int[]{NEUTRAL, NEUTRAL2};
            nowCard.accent = p[0];
            nowCard.accent2 = p[1];
            if (nowCard.hasFocus()) {
                backdrop.show(p[0], p[1]);
                showTitle(nowTitle.getText().toString(), nowArtist.getText().toString(), 0);
            }
        }
        if (nowCard.getVisibility() != View.VISIBLE) {
            nowCard.setAlpha(0f);
            nowCard.setVisibility(View.VISIBLE);
            nowCard.animate().alpha(1f).setDuration(300).setInterpolator(glide).start();
            layoutCards();
        }
    }

    private void togglePlay() {
        if (playing == null) return;
        PlaybackState s = playing.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) playing.getTransportControls().pause();
        else playing.getTransportControls().play();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---- Next up cards ----------------------------------------------------------------------

    /** A glass card under the clock: a badge, a title and a line under it, and a big number on the right. */
    private final class Card {
        final FrameLayout box;   // positions and fades the card, so it never fights the Tile's own animations
        final Tile tile;
        final TextView badge, title, sub, num, unit;
        boolean want;            // has something to show
        long rank;               // which sits higher: 0 while live, otherwise the sooner the higher

        Card(String name, int accent, int accent2, Runnable open) {
            int w = dp(300), h = dp(64);
            tile = new Tile(HomeActivity.this, w, h, dp(18), 1.05f, name, null, accent, accent2);
            glass(tile, dp(18), 0x40101014, 0xFF26262A);
            LinearLayout row = new LinearLayout(HomeActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(8), dp(16), dp(8));
            badge = text(11, medium, Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            row.addView(badge, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout words = new LinearLayout(HomeActivity.this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(dp(12), 0, dp(8), 0);
            title = text(15, medium, Color.WHITE);
            sub = text(12, light, 0xB3FFFFFF);
            for (TextView v : new TextView[]{title, sub}) {
                v.setSingleLine(true);
                v.setEllipsize(TextUtils.TruncateAt.END);
            }
            sub.setPadding(0, dp(4), 0, 0);
            words.addView(title);
            words.addView(sub);
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout count = new LinearLayout(HomeActivity.this);
            count.setOrientation(LinearLayout.VERTICAL);
            count.setGravity(Gravity.END);
            num = text(22, thin, Color.WHITE);
            num.setFontFeatureSettings("tnum");
            unit = text(10, medium, 0x80FFFFFF);
            unit.setPadding(0, dp(2), 0, 0);
            count.addView(num);
            count.addView(unit);
            row.addView(count);
            tile.addView(row, 0, match());
            tile.setId(View.generateViewId());
            tile.setNextFocusLeftId(tile.getId());
            tile.setNextFocusRightId(tile.getId());
            tile.listener = HomeActivity.this;
            tile.setOnClickListener(v -> open.run());
            tile.setOnLongClickListener(v -> { open.run(); return true; });

            box = new FrameLayout(HomeActivity.this);
            box.setClipChildren(false);
            box.addView(tile, new FrameLayout.LayoutParams(w, h));
            box.setVisibility(View.GONE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.END);
            lp.topMargin = dp(124);
            lp.rightMargin = side + bandPad;
            root.addView(box, lp);
            cards.add(this);
        }

        /** Only what changed: an unchanged setText still relayouts the whole screen. */
        void show(String t, String s, String n, String u, int numColor, int unitColor) {
            set(title, t);
            set(sub, s);
            set(num, n);
            set(unit, u);
            num.setTextColor(numColor);
            unit.setTextColor(unitColor);
        }
    }

    private void buildCards() {
        f1 = new Card("F1", F1_RED, 0xFF8A1020, this::showF1);
        f1.badge.setBackground(colourBadge(0xFFFF2A1A, 0xFFB80000));   // until the logo is in
        f1.badge.setText("F1");
    }

    /** A logo (crest, F1) on a faint glass square, in place of the coloured lettering. */
    private void setLogo(Card c, Bitmap logo, int inset) {
        c.badge.setText("");
        c.badge.setBackground(badge(new BitmapDrawable(getResources(), logo), inset, inset));
    }

    private Drawable colourBadge(int from, int to) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        g.setCornerRadius(dp(11));
        return g;
    }

    /** A logo on a faint glass square: the cards' badges. */
    private Drawable badge(Drawable logo, int insetX, int insetY) {
        GradientDrawable square = new GradientDrawable();
        square.setColor(0x1FFFFFFF);
        square.setCornerRadius(dp(11));
        LayerDrawable d = new LayerDrawable(new Drawable[]{square, logo});
        d.setLayerInset(1, insetX, insetY, insetX, insetY);
        return d;
    }

    // ---- F1 Next up -------------------------------------------------------------------------

    /**
     * Shown from three weeks before a race weekend until its race ends. Days out it counts down to the
     * race; from the evening before the first session, to the next session; during one it says LIVE.
     */
    private void updateF1() {
        if (f1 == null) return;
        handler.removeCallbacks(f1Tick);
        long now = System.currentTimeMillis();
        F1.Race r = f1Data != null && f1Wanted ? f1Data.next(now) : null;
        if (r == null || now < r.first() - 21 * 86_400_000L) {
            f1Race = null;
            f1.want = false;
            layoutCards();
            return;
        }
        f1Race = r;
        boolean weekend = now >= r.first() - 12 * 3_600_000L;
        F1.Session s = weekend ? r.current(now) : r.race();
        boolean live = s.live(now), h24 = prefs.getBoolean("clock24", false);
        String[] c = live ? new String[]{"LIVE", r.place} : F1.countdown(s.start, now);
        f1.show(r.name, s.name + "  ·  " + (live ? "on now" : F1.when(s.start, now, h24)), c[0], c[1],
                live ? 0xFFFF453A : Color.WHITE, 0x80FFFFFF);
        f1.want = true;
        f1.rank = live ? 0 : s.start;
        layoutCards();
        // a minute at a time when it's close or live, otherwise every quarter hour; nothing while away
        if (resumed) {
            long wait = live || s.start - now < 86_400_000L ? 60_000L - now % 60_000L + 300 : 15 * 60_000L;
            handler.postDelayed(f1Tick, wait);
        }
    }

    private static void set(TextView v, String s) {
        if (!s.contentEquals(v.getText())) v.setText(s);
    }

    private final Runnable f1Tick = () -> {
        updateF1();
        refreshF1();
    };

    /** Every 6 hours, or sooner when a race has finished and its podium isn't in yet; never twice in 30 min. */
    private void refreshF1() {
        if (!f1Wanted) return;
        long now = System.currentTimeMillis();
        if (f1FetchAt != 0 && SystemClock.elapsedRealtime() - f1FetchAt < 30 * 60_000L) return;
        boolean due = now - prefs.getLong("f1at", 0) > 6 * 3_600_000L;
        if (f1Data != null) {
            for (F1.Race r : f1Data.races) {
                long since = now - r.race().end;
                if (since > 3_600_000L && since < 3 * 86_400_000L && r.round > f1Data.lastRound) due = true;
            }
        }
        if (!due) return;
        f1FetchAt = SystemClock.elapsedRealtime();
        F1.fetch(prefs, handler, d -> {
            f1Data = d;
            updateF1();
        });
    }

    /** The whole weekend in the TV's time, the last podium and the top of the table. */
    private void showF1() {
        F1.Race r = f1Race;
        if (menu != null || r == null || f1Data == null) return;
        LinearLayout list = scrollList(openPanel(r.longName, "Round " + r.round + "  ·  " + r.circuit, dp(420)));
        List<View> items = new ArrayList<>();
        long now = System.currentTimeMillis();
        boolean h24 = prefs.getBoolean("clock24", false);
        View first = null;
        for (F1.Session s : r.sessions) {
            View row = infoRow(s.name, s.live(now) ? "Live now" : F1.full(s.start, h24));
            if (s.end <= now) row.setAlpha(0.45f);
            else if (first == null) first = row;
            list.addView(row, rowParams(items.isEmpty() ? 0 : dp(4)));
            items.add(row);
        }
        if (!f1Data.podium.isEmpty()) {
            list.addView(heading("Last race  ·  " + f1Data.lastRace));
            for (int i = 0; i < f1Data.podium.size(); i++) {
                View row = infoRow((i + 1) + "   " + f1Data.podium.get(i), i == 0 ? "Winner" : "");
                list.addView(row, rowParams(dp(4)));
                items.add(row);
            }
        }
        if (!f1Data.standings.isEmpty()) {
            list.addView(heading("Drivers' standings"));
            for (int i = 0; i < f1Data.standings.size(); i++) {
                String[] d = f1Data.standings.get(i);
                View row = infoRow((i + 1) + "   " + d[0], d[1] + " pts");
                list.addView(row, rowParams(dp(4)));
                items.add(row);
            }
        }
        fence(items);
        (first != null ? first : items.get(0)).requestFocus();
    }

    /** A list that scrolls inside a panel, its rows melting away at the edges. */
    private LinearLayout scrollList(LinearLayout panel) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(36));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return list;
    }

    // ---- team cards -----------------------------------------------------------------------------

    /**
     * The cards picked on the phone page: "cards" holds F1 and up to three teams (Team.Spec JSON),
     * "cards_on" hides or shows them all. Teams still picked keep their card; removed ones forget their data.
     */
    private void applyCards() {
        boolean on = prefs.getBoolean("cards_on", true), wantF1 = false;
        List<Team.Spec> picked = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("cards", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if ("f1".equals(o.optString("t"))) {
                    wantF1 = true;
                } else {
                    Team.Spec s = Team.Spec.of(o);
                    if (s != null && picked.size() < 3) picked.add(s);
                }
            }
        } catch (JSONException ignored) { }
        f1Wanted = on && wantF1;
        Set<String> keys = new HashSet<>();
        for (Team.Spec s : picked) keys.add(s.key());
        for (int i = teams.size() - 1; i >= 0; i--) {
            TeamCard t = teams.get(i);
            if (on && keys.contains(t.spec.key())) continue;
            teams.remove(i).remove();
            if (!keys.contains(t.spec.key())) Team.forget(this, prefs, t.spec);
        }
        if (on) {
            for (Team.Spec s : picked) {
                boolean have = false;
                for (TeamCard t : teams) have |= t.spec.key().equals(s.key());
                if (!have) teams.add(new TeamCard(s));
            }
        }
        updateF1();
        refreshF1();
        layoutCards();
    }

    private TeamCard teamOf(Tile t) {
        for (TeamCard team : teams) if (team.card.tile == t) return team;
        return null;
    }

    private String cardsNote() {
        List<String> names = new ArrayList<>();
        if (f1Wanted) names.add("F1");
        for (TeamCard t : teams) names.add(t.spec.name);
        return names.isEmpty() ? "Add F1 or your teams on the phone page" : TextUtils.join(", ", names) + "  ·  more on the phone page";
    }

    /** A team's card: the game on now with its score, the last result for the evening after, else the next game. */
    private final class TeamCard {
        final Team.Spec spec;
        final Card card;
        final Runnable tick = () -> {
            update();
            refresh();
        };
        Team.Data data;
        Team.Match match;
        long fetchAt, liveAt;
        boolean gone;

        TeamCard(Team.Spec s) {
            spec = s;
            card = new Card(s.name, s.color, s.color2, this::open);
            card.badge.setBackground(colourBadge(s.color, shade(s.color, 0.55f)));   // until the crest is in
            card.badge.setTextColor(lum(s.color) > 0.6f ? Color.BLACK : Color.WHITE);
            card.badge.setText(s.abbr);
            Team.load(prefs, s, handler, d -> {
                if (gone) return;
                data = d;
                update();
                refresh();
            });
            worker.execute(() -> {
                Bitmap crest = Team.crest(HomeActivity.this, s);
                if (crest != null) handler.post(() -> { if (!gone) setLogo(card, crest, dp(6)); });
            });
        }

        void remove() {
            gone = true;
            handler.removeCallbacks(tick);
            if (card.tile.hasFocus()) goTop();
            cards.remove(card);
            glassViews.remove(card.tile);
            root.removeView(card.box);
        }

        /** Live: the score and minute. Done: the result. Otherwise a countdown, from ten days out. */
        void update() {
            handler.removeCallbacks(tick);
            long now = System.currentTimeMillis();
            Team.Match m = data != null ? data.current(now) : null;
            if (m != null && !m.live() && !m.done() && m.start - now > 10 * 86_400_000L) m = null;
            match = m;
            card.want = m != null;
            if (m == null) {
                layoutCards();
                return;
            }
            if (m.live()) {
                card.show(m.title(), m.comp + "  ·  on now", m.score(), m.clock.isEmpty() ? "LIVE" : m.clock,
                        Color.WHITE, 0xFFFF453A);
                card.rank = 0;
            } else if (m.done()) {
                card.show(m.title(), m.comp + "  ·  " + m.outcome(), m.score(), "Final", Color.WHITE, 0x80FFFFFF);
                card.rank = 1;   // fresh news goes on top
            } else if (m.start <= now) {   // started; the first live score is a minute away
                card.show(m.title(), m.comp + "  ·  on now", "0\u20130", "Starting", Color.WHITE, 0xFFFF453A);
                card.rank = 0;
            } else {
                String[] c = F1.countdown(m.start, now);
                card.show(m.title(), m.comp + "  ·  " + F1.when(m.start, now, prefs.getBoolean("clock24", false)),
                        c[0], c[1], Color.WHITE, 0x80FFFFFF);
                card.rank = m.start;
            }
            layoutCards();
            if (resumed) {
                boolean close = m.live() || (!m.done() && m.start - now < 86_400_000L);
                handler.postDelayed(tick, close ? 60_000L - now % 60_000L + 300 : 15 * 60_000L);
            }
        }

        /**
         * During a game, its score once a minute. Otherwise everything every 6 hours, and again half an
         * hour after each final whistle for the result and the table; never twice in 30 minutes.
         */
        void refresh() {
            if (data == null || gone) return;
            long now = System.currentTimeMillis(), up = SystemClock.elapsedRealtime();
            for (Team.Match m : data.matches) {
                boolean on = !m.done() && now >= m.start - 5 * 60_000L && now < m.start + 4 * 3_600_000L;
                if (!on) continue;
                if (liveAt != 0 && up - liveAt < 55_000L) return;
                liveAt = up;
                Team.live(prefs, spec, m, handler, d -> {
                    if (gone) return;
                    data = d;
                    update();
                });
                return;
            }
            if (fetchAt != 0 && up - fetchAt < 30 * 60_000L) return;
            long at = Team.fetchedAt(prefs, spec);
            boolean due = now - at > 6 * 3_600_000L;
            for (Team.Match m : data.matches) {
                long end = m.start + 3 * 3_600_000L;
                if (now - end > 30 * 60_000L && now - end < 6 * 3_600_000L && at < end + 30 * 60_000L) due = true;
            }
            if (!due) return;
            fetchAt = up;
            Team.fetch(prefs, spec, handler, d -> {
                if (gone) return;
                data = d;
                update();
            });
        }

        /** The opponent, big ("Fleetwood Town v Arsenal" doesn't fit at that size), and the details under it. */
        void focused(int direction) {
            Team.Match m = match;
            if (m == null) return;
            String sub = m.live() ? m.scoreLine() + "  ·  " + m.clock
                    : m.done() ? m.outcome() + "  ·  " + m.scoreLine()
                    : (m.atHome ? "Home" : "Away") + "  ·  " + m.comp + (m.venue != null ? "  ·  " + m.venue : "")
                    + "  ·  " + F1.when(m.start, System.currentTimeMillis(), prefs.getBoolean("clock24", false));
            showTitle(m.atHome ? m.away : m.home, sub, direction);
        }

        /** What's coming up, the latest results and (football) the top of the league table, in the TV's time. */
        void open() {
            Team.Data d = data;
            if (menu != null || d == null) return;
            String[] us = d.row(spec.id);
            LinearLayout list = scrollList(openPanel(spec.name, us != null
                    ? ordinal(us[0]) + " in the " + spec.leagueName + "  ·  " + us[2] + " pts" : spec.leagueName, dp(440)));
            List<View> items = new ArrayList<>();
            long now = System.currentTimeMillis();
            boolean h24 = prefs.getBoolean("clock24", false);
            List<Team.Match> next = d.upcoming(now, 5);
            if (!next.isEmpty()) {
                list.addView(heading("Coming up"));
                for (Team.Match m : next) {
                    String value = m.live() ? m.score() + "  ·  " + m.clock : F1.full(m.start, h24);
                    View row = infoRow(m.title(), m.comp + (m.round.isEmpty() ? "" : "  ·  " + m.round), value);
                    list.addView(row, rowParams(items.isEmpty() ? 0 : dp(4)));
                    items.add(row);
                }
            }
            List<Team.Match> done = d.results(5);
            if (!done.isEmpty()) {
                list.addView(heading("Results"));
                for (Team.Match m : done) {
                    View row = infoRow(m.scoreLine(), m.comp, m.outcome() + "  ·  " + Team.day(m.start));
                    list.addView(row, rowParams(dp(4)));
                    items.add(row);
                }
            }
            if (!d.table.isEmpty()) {
                list.addView(heading(spec.leagueName));
                for (int i = 0; i < d.table.size(); i++) {
                    String[] r = d.table.get(i);
                    if (i >= 5 && !spec.id.equals(r[4])) continue;   // the top five, and us wherever we are
                    View row = infoRow(r[0] + "   " + r[1], r[2] + " pts  ·  " + r[3] + " played");
                    list.addView(row, rowParams(dp(4)));
                    items.add(row);
                }
            }
            if (items.isEmpty()) {
                closeMenu(false);
                toast("No games for " + spec.name + " yet. Check the TV is online.");
                return;
            }
            fence(items);
            items.get(0).requestFocus();
        }
    }

    private static float lum(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }

    private static String ordinal(String n) {
        int i;
        try { i = Integer.parseInt(n); } catch (NumberFormatException e) { return n; }
        String suffix = i % 100 >= 11 && i % 100 <= 13 ? "th" : i % 10 == 1 ? "st" : i % 10 == 2 ? "nd" : i % 10 == 3 ? "rd" : "th";
        return i + suffix;
    }

    private LinearLayout.LayoutParams rowParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private TextView heading(String s) {
        TextView h = text(12, medium, 0x80FFFFFF);
        h.setText(s);
        h.setLetterSpacing(0.04f);
        h.setPadding(dp(18), dp(20), dp(18), dp(6));
        return h;
    }

    /** A focusable line with a name and a value, highlighted like the rest of the panel. */
    private View infoRow(String name, String value) {
        return infoRow(name, null, value);
    }

    /** The same with a smaller line under the name. */
    private View infoRow(String name, String note, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setPadding(dp(18), dp(note != null ? 9 : 11), dp(16), dp(note != null ? 9 : 11));
        row.setBackground(pill());
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        TextView l = text(16, medium, Color.WHITE);
        l.setTextColor(focusColors(Color.BLACK, 0xE6FFFFFF));
        l.setDuplicateParentStateEnabled(true);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setText(name);
        words.addView(l);
        if (note != null) {
            TextView n = text(11, light, 0x80FFFFFF);
            n.setTextColor(focusColors(0x99000000, 0x80FFFFFF));
            n.setDuplicateParentStateEnabled(true);
            n.setSingleLine(true);
            n.setPadding(0, dp(2), 0, 0);
            n.setText(note);
            words.addView(n);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(14, light, 0xB3FFFFFF);
        v.setTextColor(focusColors(Color.BLACK, 0xB3FFFFFF));
        v.setDuplicateParentStateEnabled(true);
        v.setSingleLine(true);
        v.setPadding(dp(12), 0, 0, 0);
        v.setText(value);
        row.addView(v);
        return row;
    }

    // ---- firmware-update watchdog ----------------------------------------------------------

    /** A glass pill at the top, only while something the setup switched off is back on. */
    private void buildWatchBanner() {
        watchBanner = text(13, medium, Color.WHITE);
        watchBanner.setSingleLine(true);
        watchBanner.setEllipsize(TextUtils.TruncateAt.END);
        watchBanner.setMaxWidth(dp(620));
        watchBanner.setPadding(dp(16), dp(9), dp(18), dp(9));
        watchBanner.setCompoundDrawablePadding(dp(10));
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFFFF9F0A);
        dot.setSize(dp(8), dp(8));
        watchBanner.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        glass(watchBanner, dp(18), 0x66141416, 0xFF2A2A2E);
        watchBanner.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(30);
        root.addView(watchBanner, lp);
    }

    private final Runnable watchSoon = this::checkWatchdog;

    /** Compare with the installer's snapshot in the background (it reads every package), then show or hide. */
    private void checkWatchdog() {
        watchAt = SystemClock.elapsedRealtime();
        worker.execute(() -> {
            Watchdog.Report r;
            try { r = Watchdog.check(this); } catch (RuntimeException e) { r = null; }
            Watchdog.Report report = r;
            handler.post(() -> {
                if (isDestroyed()) return;
                watch = report;
                boolean bad = report != null && report.bad();
                if (report != null && report.updated && !bad) {   // updated, and nothing came back
                    worker.execute(() -> Watchdog.acceptFirmware(this));
                    toast("TCL updated the TV. MonolithicOS checked: your setup is intact");
                }
                if (bad) {
                    watchBanner.setText(report.text());
                    if (watchBanner.getVisibility() != View.VISIBLE) {
                        watchBanner.setVisibility(View.VISIBLE);
                        watchBanner.setAlpha(0f);
                        watchBanner.animate().alpha(1f).setDuration(400).setInterpolator(glide).start();
                    }
                } else if (watchBanner.getVisibility() == View.VISIBLE) {
                    watchBanner.animate().alpha(0f).setDuration(200)
                            .withEndAction(() -> watchBanner.setVisibility(View.GONE)).start();
                }
            });
        });
    }

    // ---- inputs ----------------------------------------------------------------------------

    private boolean connected(String id) {
        try {
            return tvInputs != null && tvInputs.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The glass pill left of the clock: the connected input's name (else "Inputs"); OK drops the list. */
    private void buildInputsPill() {
        inputsPill = new Tile(this, dp(120), dp(36), dp(18), 1.08f, "Inputs", null, NEUTRAL, NEUTRAL2);
        glass(inputsPill, dp(18), 0x40101014, 0xFF26262A);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(13), 0);
        ImageView glyph = new ImageView(this);
        glyph.setImageDrawable(new HdmiGlyph(dp(1.4f)));
        row.addView(glyph, new LinearLayout.LayoutParams(dp(20), dp(12)));
        inputsLabel = text(13, medium, Color.WHITE);
        inputsLabel.setSingleLine(true);
        inputsLabel.setPadding(dp(9), 0, dp(8), 0);
        inputsLabel.setText("Inputs");
        row.addView(inputsLabel);
        ImageView more = new ImageView(this);
        more.setImageDrawable(new Chevron(dp(1.4f)));
        row.addView(more, new LinearLayout.LayoutParams(dp(9), dp(5)));
        inputsPill.addView(row, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        inputsPill.setId(View.generateViewId());
        inputsPill.setNextFocusLeftId(inputsPill.getId());
        inputsPill.setNextFocusRightId(inputsPill.getId());
        inputsPill.setNextFocusUpId(inputsPill.getId());
        inputsPill.listener = this;
        inputsPill.setOnClickListener(v -> showInputs());
        inputsPill.setOnLongClickListener(v -> { showInputs(); return true; });
        inputsPill.setVisibility(View.GONE);   // until the inputs are known
    }

    private String inputName(TvInputInfo in, String device) {
        String renamed = Inputs.renamed(prefs, in.getId());   // named on the phone page
        return renamed != null ? renamed : device != null ? device : Inputs.port(this, in);
    }

    private void refreshInputs() {
        if (inputsPill == null) return;
        List<TvInputInfo> ins = new ArrayList<>();
        Map<String, String> devices = new HashMap<>();
        Inputs.hdmi(this, tvInputs, ins, devices);
        List<String> on = new ArrayList<>();
        String port = "";
        for (TvInputInfo in : ins) {
            if (!connected(in.getId())) continue;
            on.add(inputName(in, devices.get(in.getId())));
            port = Inputs.port(this, in);
        }
        set(inputsLabel, on.size() == 1 ? on.get(0) : "Inputs");
        inputsSummary = on.isEmpty() ? "Nothing connected" : on.size() == 1
                ? (on.get(0).equals(port) ? port : on.get(0) + " on " + port) + "  ·  connected"
                : TextUtils.join(", ", on) + "  ·  connected";
        boolean show = !ins.isEmpty();
        if (show != (inputsPill.getVisibility() == View.VISIBLE)) {
            if (!show && inputsPill.hasFocus()) goTop();
            inputsPill.setVisibility(show ? View.VISIBLE : View.GONE);
            layoutCards();
        }
    }

    /**
     * The inputs dropping down from the pill, in glass: name, port and state. OK switches (behind a quick
     * fade, TCL's TV app takes a moment), hold OK opens TCL's input menu, Up or Back folds it away.
     */
    private void showInputs() {
        if (menu != null) return;
        List<TvInputInfo> ins = new ArrayList<>();
        Map<String, String> devices = new HashMap<>();
        Inputs.hdmi(this, tvInputs, ins, devices);
        if (ins.isEmpty()) return;
        menu = new FrameLayout(this);
        menu.setBackground(new ColorDrawable(0x59000000));
        menu.setClickable(true);
        menu.setAlpha(0f);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        glass(list, dp(22), 0x99141416, 0xF2202023);
        List<View> items = new ArrayList<>();
        View first = null;
        for (TvInputInfo in : ins) {
            String id = in.getId();
            View row = inputRow(in, devices.get(id));
            row.setOnClickListener(v -> {
                closeMenu(false);
                coverLaunch(() -> switchInput(id));
            });
            row.setOnLongClickListener(v -> {
                closeMenu(false);
                start(new Intent("com.android.tv.action.VIEW_INPUTS"));
                return true;
            });
            list.addView(row, rowParams(items.isEmpty() ? 0 : dp(2)));
            items.add(row);
            if (first == null && connected(id)) first = row;
        }
        // hangs from the pill, right edges lined up
        View holder = (View) inputsPill.getParent();
        int w = dp(300), right = holder.getLeft() + inputsPill.getRight();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = holder.getTop() + inputsPill.getBottom() + dp(10);
        lp.rightMargin = root.getWidth() - right;
        menu.addView(list, lp);
        root.addView(menu, match());
        list.setPivotX(w);
        list.setPivotY(0f);
        list.setScaleX(0.94f);
        list.setScaleY(0.94f);
        list.setTranslationY(-dp(8));
        list.setAlpha(0f);
        list.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(1f).setDuration(260).setInterpolator(glide)
                .setUpdateListener(frost == null ? null : a -> list.invalidate())   // the glass follows
                .start();
        menu.animate().alpha(1f).setDuration(180).start();
        fence(items);
        items.get(0).setOnKeyListener((v, code, e) -> {   // Up from the top folds it back into the pill
            if (code != KeyEvent.KEYCODE_DPAD_UP) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) closeMenu(true);
            return true;
        });
        (first != null ? first : items.get(0)).requestFocus();
    }

    private View inputRow(TvInputInfo in, String device) {
        String port = Inputs.port(this, in), name = inputName(in, device);
        boolean on = connected(in.getId());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(14), dp(10), dp(16), dp(10));
        row.setBackground(pill());
        ImageView glyph = new ImageView(this);
        glyph.setImageDrawable(new HdmiGlyph(dp(1.5f)));
        row.addView(glyph, new LinearLayout.LayoutParams(dp(24), dp(14)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setDuplicateParentStateEnabled(true);
        words.setPadding(dp(14), 0, dp(8), 0);
        TextView n = text(16, medium, Color.WHITE);
        n.setTextColor(focusColors(Color.BLACK, 0xE6FFFFFF));
        n.setDuplicateParentStateEnabled(true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        n.setText(name);
        TextView sub = text(11, light, 0x80FFFFFF);
        sub.setTextColor(focusColors(0x99000000, 0x80FFFFFF));
        sub.setDuplicateParentStateEnabled(true);
        sub.setPadding(0, dp(2), 0, 0);
        sub.setText((name.equals(port) ? "" : port + "  ·  ") + Inputs.state(tvInputs, in.getId()));
        words.addView(n);
        words.addView(sub);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (on) {
            View dot = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0xFF30D158);
            dot.setBackground(g);
            row.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        } else {
            row.setAlpha(0.6f);   // still there to pick, just quieter
        }
        row.setOnFocusChangeListener((v, has) -> {
            if (has) glyph.setColorFilter(Color.BLACK); else glyph.clearColorFilter();
            row.setAlpha(has || on ? 1f : 0.6f);
        });
        return row;
    }

    /** A quick fade to dark while an input comes up; cleared when Home is back, like the zoom. */
    private void coverLaunch(Runnable go) {
        if (zoom != null) return;
        FrameLayout z = new FrameLayout(this);
        zoom = z;
        z.setBackgroundColor(0xFF0B0B0C);
        z.setAlpha(0f);
        root.addView(z, match());
        z.animate().alpha(1f).setDuration(240).setInterpolator(glide).start();
        handler.postDelayed(go, 120);
        handler.postDelayed(() -> { if (zoom == z && hasWindowFocus()) clearZoom(); }, 4000);
    }

    /** A small down chevron, for the pill's "there's a list here". */
    private static final class Chevron extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevron(float stroke) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0x99FFFFFF);
        }

        @Override protected void onBoundsChange(Rect b) {
            float s = paint.getStrokeWidth() / 2f;
            path.reset();
            path.moveTo(b.left + s, b.top + s);
            path.lineTo(b.exactCenterX(), b.bottom - s);
            path.lineTo(b.right - s, b.top + s);
        }

        @Override public void draw(Canvas c) { c.drawPath(path, paint); }
        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String id, int state) { refreshInputs(); }
        @Override public void onInputAdded(String id) { rebuildSoon(); }
        @Override public void onInputRemoved(String id) { rebuildSoon(); }
        @Override public void onInputUpdated(String id) { rebuildSoon(); }
    };

    // ---- apps changing ---------------------------------------------------------------------

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Uri d = i.getData();
            if (d != null) palettes.remove(d.getSchemeSpecificPart());
            rebuildSoon();
            handler.removeCallbacks(watchSoon);
            handler.postDelayed(watchSoon, 3000);   // after a burst of changes (an update), check once
        }
    };

    private final Runnable rebuild = () -> { if (moving == null && menu == null) build(); else rebuildSoon(); };

    private void rebuildSoon() {
        handler.removeCallbacks(rebuild);
        handler.postDelayed(rebuild, 600);
    }

    // ---- art and colour --------------------------------------------------------------------

    /** The TV banner at the screen's density; a square "banner" counts as none. */
    private Drawable banner(PackageManager pm, ResolveInfo ri) {
        ActivityInfo ai = ri.activityInfo;
        int id = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        Drawable d = null;
        try {
            if (id != 0) {
                int dpi = Math.max(DisplayMetrics.DENSITY_XHIGH, getResources().getDisplayMetrics().densityDpi);
                d = pm.getResourcesForApplication(ai.applicationInfo).getDrawableForDensity(id, dpi, null);
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) { }
        if (d == null) d = ai.loadBanner(pm);
        if (d == null) return null;
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        return (w <= 0 || h <= 0 || w >= h * 1.4f) ? d : null;
    }

    /**
     * The art's two most vivid colours, a hue apart, brightened for the aurora. A one-colour app
     * gets a neighbouring hue as its second light. Cached per app.
     */
    private int[] palette(String pkg, Drawable art) {
        int[] cached = palettes.get(pkg);
        if (cached != null) return cached;
        int[] out = {NEUTRAL, NEUTRAL2};
        if (art != null && art.getConstantState() != null) {
            Drawable d = art.getConstantState().newDrawable(getResources()).mutate();
            Bitmap bm = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 32, 18);
            d.draw(new Canvas(bm));
            float[] hsv = new float[3];
            double[] w = new double[18], rs = new double[18], gs = new double[18], bs = new double[18];
            for (int y = 0; y < 18; y++) {
                for (int x = 0; x < 32; x++) {
                    int p = bm.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    if (hsv[2] < 0.15f || hsv[1] < 0.12f) continue;
                    double wt = hsv[1] * hsv[1] * hsv[2];
                    int bin = ((int) (hsv[0] / 20f)) % 18;
                    w[bin] += wt;
                    rs[bin] += Color.red(p) * wt;
                    gs[bin] += Color.green(p) * wt;
                    bs[bin] += Color.blue(p) * wt;
                }
            }
            bm.recycle();
            int b1 = 0;
            for (int i = 1; i < 18; i++) if (w[i] > w[b1]) b1 = i;
            if (w[b1] > 2) {
                out[0] = vivid(Color.rgb((int) (rs[b1] / w[b1]), (int) (gs[b1] / w[b1]), (int) (bs[b1] / w[b1])));
                int b2 = -1;
                for (int i = 0; i < 18; i++) {
                    int dist = Math.min(Math.abs(i - b1), 18 - Math.abs(i - b1));
                    if (dist >= 3 && w[i] >= w[b1] * 0.12 && (b2 < 0 || w[i] > w[b2])) b2 = i;
                }
                out[1] = b2 >= 0
                        ? vivid(Color.rgb((int) (rs[b2] / w[b2]), (int) (gs[b2] / w[b2]), (int) (bs[b2] / w[b2])))
                        : hueShift(out[0], 28f);
            }
        }
        palettes.put(pkg, out);
        return out;
    }

    private static int vivid(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(0.9f, hsv[1] * 1.15f);
        hsv[2] = Math.max(0.85f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    private static int hueShift(int c, float degrees) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[0] = (hsv[0] + degrees + 360f) % 360f;
        hsv[1] *= 0.85f;
        return Color.HSVToColor(hsv);
    }

    private static int shade(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f));
    }

    // ---- small helpers ---------------------------------------------------------------------

    private String greetingText() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String part = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon"
                : h < 22 ? "Good evening" : "Good night";
        String name = prefs.getString("name", "").trim();   // set on the phone page
        return name.isEmpty() ? part : part + ", " + name;
    }

    private TextView text(float sp, Typeface face, int color) {
        TextView t = new TextView(this);
        style(t, sp, face, color);
        return t;
    }

    private static void style(TextView t, float sp, Typeface face, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams at(int left, int top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        lp.topMargin = top;
        return lp;
    }

    private FrameLayout.LayoutParams corner(int top) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = top;
        lp.rightMargin = side + bandPad;
        return lp;
    }
}
