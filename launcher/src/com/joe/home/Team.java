package com.joe.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A team for a "Next up" card, any sport ESPN covers, from ESPN's public site API (what espn.com
 * itself reads): no account or key. Football (soccer) clubs get every competition's fixtures and their
 * league table; NFL, NBA, NHL and MLB teams their season. During a game, one small scoreboard request a
 * minute. ESPN's replies are big, so only what the card needs is kept, as compact JSON in prefs.
 */
final class Team {
    interface Callback { void onTeam(Data data); }

    private static final String SITE = "https://site.api.espn.com/apis/site/v2/sports/";

    /** Which team: picked on the phone page from {@link #search}, stored in the "cards" pref. */
    static final class Spec {
        String sport, league, leagueName, id, name, abbr, logo;
        int color = 0xFF3A3A40, color2 = 0xFF1C1C20;

        String key() { return sport + ":" + id; }
        boolean soccer() { return "soccer".equals(sport); }

        JSONObject json() throws JSONException {
            return new JSONObject().put("t", "team").put("s", sport).put("l", league).put("ln", leagueName)
                    .put("id", id).put("n", name).put("a", abbr).put("logo", logo)
                    .put("c", String.format(Locale.US, "%06X", color & 0xFFFFFF))
                    .put("c2", String.format(Locale.US, "%06X", color2 & 0xFFFFFF));
        }

        static Spec of(JSONObject o) {
            Spec s = new Spec();
            s.sport = o.optString("s");
            s.league = o.optString("l");
            s.leagueName = o.optString("ln");
            s.id = o.optString("id");
            s.name = o.optString("n");
            s.abbr = o.optString("a");
            s.logo = o.optString("logo", null);
            s.color = colour(o.optString("c"), s.color);
            s.color2 = colour(o.optString("c2"), s.color2);
            return s.sport.isEmpty() || s.id.isEmpty() ? null : s;
        }
    }

    static final class Match {
        String id, comp, round, slug, venue, home, away, state, clock;
        long start;
        boolean atHome, homeWon, awayWon;
        int homeGoals = -1, awayGoals = -1;

        boolean live() { return "in".equals(state); }
        boolean done() { return "post".equals(state); }
        String title() { return home + " v " + away; }
        String score() { return homeGoals + "–" + awayGoals; }
        String scoreLine() { return home + "  " + score() + "  " + away; }

        /** Won, drew or lost, for our team (a shoot-out counts for whoever went through). */
        String outcome() {
            int us = atHome ? homeGoals : awayGoals, them = atHome ? awayGoals : homeGoals;
            if (us != them) return us > them ? "Won" : "Lost";
            if (homeWon || awayWon) return (atHome ? homeWon : awayWon) ? "Won on pens" : "Lost on pens";
            return "Drew";
        }
    }

    static final class Data {
        final List<Match> matches = new ArrayList<>();   // oldest first
        final List<String[]> table = new ArrayList<>();  // {rank, team, points, played, team id}

        /** The game the card is about: one on now, else a result from the last 14 hours, else the next. */
        Match current(long now) {
            Match next = null, last = null;
            for (Match m : matches) {
                if (m.live() && now - m.start < 4 * 3_600_000L) return m;   // older: a stale copy
                if (m.done()) { if (m.start < now) last = m; }
                else if (next == null && m.start + 3 * 3_600_000L > now) next = m;
            }
            if (last != null && now - last.start < 14 * 3_600_000L) return last;
            return next;
        }

        List<Match> upcoming(long now, int n) {
            List<Match> out = new ArrayList<>();
            for (Match m : matches) if (!m.done() && m.start + 3 * 3_600_000L > now && out.size() < n) out.add(m);
            return out;
        }

        List<Match> results(int n) {
            List<Match> out = new ArrayList<>();
            for (int i = matches.size() - 1; i >= 0 && out.size() < n; i--) if (matches.get(i).done()) out.add(matches.get(i));
            return out;
        }

        String[] row(String teamId) {
            for (String[] r : table) if (teamId.equals(r[4])) return r;
            return null;
        }
    }

    private Team() { }

    private static String k(Spec s, String what) {
        return "tm:" + s.key() + ":" + what;
    }

    /** What's cached (possibly nothing), parsed off the main thread. */
    static void load(SharedPreferences prefs, Spec s, Handler main, Callback cb) {
        new Thread(() -> {
            Data d = parse(prefs, s);
            main.post(() -> cb.onTeam(d));
        }, "team").start();
    }

    /** The season's games and (football clubs) the league table, fresh; a failure keeps the cache. */
    static void fetch(SharedPreferences prefs, Spec s, Handler main, Callback cb) {
        new Thread(() -> {
            String[] queries = s.soccer()
                    ? new String[]{SITE + "soccer/all/teams/" + s.id + "/schedule",   // every competition
                            SITE + "soccer/all/teams/" + s.id + "/schedule?fixture=true"}
                    : new String[]{SITE + s.sport + "/" + s.league + "/teams/" + s.id + "/schedule"};
            Map<String, Match> fresh = new LinkedHashMap<>();
            int got = 0;
            for (String q : queries) {   // results, then fixtures (newer state wins)
                String json = get(q);
                if (json == null) continue;
                try {
                    JSONArray events = new JSONObject(json).getJSONArray("events");
                    for (int i = 0; i < events.length(); i++) {
                        Match m = event(events.getJSONObject(i), s);
                        if (m != null) fresh.put(m.id, m);
                    }
                    got++;
                } catch (JSONException ignored) { }
            }
            SharedPreferences.Editor e = prefs.edit();
            if (got > 0) {
                // all of it in: that's the whole season (a postponed game drops out); part: top up the cache
                Map<String, Match> all = new LinkedHashMap<>();
                if (got < queries.length) for (Match m : parse(prefs, s).matches) all.put(m.id, m);
                all.putAll(fresh);
                e.putString(k(s, "m"), store(new ArrayList<>(all.values())));
            }
            if (s.soccer()) {
                String table = table(get("https://site.api.espn.com/apis/v2/sports/soccer/" + s.league + "/standings"));
                if (table != null) e.putString(k(s, "t"), table);
            }
            e.putLong(k(s, "at"), System.currentTimeMillis()).commit();
            Data d = parse(prefs, s);
            main.post(() -> cb.onTeam(d));
        }, "team").start();
    }

    static long fetchedAt(SharedPreferences prefs, Spec s) {
        return prefs.getLong(k(s, "at"), 0);
    }

    /** One game's score and clock, while it's on: about 11 KB. */
    static void live(SharedPreferences prefs, Spec s, Match m, Handler main, Callback cb) {
        String id = m.id, slug = m.slug;
        new Thread(() -> {
            String json = get(SITE + s.sport + "/" + slug + "/scoreboard/" + id);
            Data d = parse(prefs, s);
            if (json != null) {
                try {
                    Match fresh = event(new JSONObject(json), s);
                    if (fresh != null) {
                        for (int i = 0; i < d.matches.size(); i++) {
                            if (!d.matches.get(i).id.equals(id)) continue;
                            Match old = d.matches.get(i);
                            fresh.comp = old.comp;   // the scoreboard doesn't say which competition
                            fresh.round = old.round;
                            fresh.slug = old.slug;
                            if (fresh.venue == null) fresh.venue = old.venue;
                            d.matches.set(i, fresh);
                        }
                        prefs.edit().putString(k(s, "m"), store(d.matches)).commit();
                    }
                } catch (JSONException ignored) { }
            }
            main.post(() -> cb.onTeam(d));
        }, "team").start();
    }

    /** The team's crest for the card's badge: fetched once, then from the app's own storage. */
    static Bitmap crest(Context c, Spec s) {
        File f = new File(c.getFilesDir(), "crest-" + s.sport + "-" + s.id + ".png");
        if (!f.exists()) {
            if (s.logo == null || s.logo.isEmpty()) return null;
            byte[] png = bytes(s.logo);
            if (png == null) return null;
            Bitmap big = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (big == null) return null;
            Bitmap small = Bitmap.createScaledBitmap(big, 128, Math.max(1, 128 * big.getHeight() / big.getWidth()), true);
            try (FileOutputStream out = new FileOutputStream(f)) {
                small.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception e) {
                return small;
            }
            return small;
        }
        return BitmapFactory.decodeFile(f.getPath());
    }

    /** Forget a team's cache and crest (the card was removed). */
    static void forget(Context c, SharedPreferences prefs, Spec s) {
        prefs.edit().remove(k(s, "m")).remove(k(s, "t")).remove(k(s, "at")).apply();
        new File(c.getFilesDir(), "crest-" + s.sport + "-" + s.id + ".png").delete();
    }

    // ---- picking a team: ESPN's team lists for the big leagues, searched on the TV --------------

    // {sport, league}: the leagues the phone page searches
    private static final String[][] LEAGUES = {
            {"soccer", "eng.1"}, {"soccer", "esp.1"}, {"soccer", "ita.1"}, {"soccer", "ger.1"},
            {"soccer", "fra.1"}, {"soccer", "usa.1"}, {"soccer", "ned.1"}, {"soccer", "por.1"},
            {"soccer", "sco.1"}, {"soccer", "mex.1"}, {"soccer", "eng.2"}, {"soccer", "bra.1"},
            {"soccer", "arg.1"}, {"soccer", "sau.1"}, {"football", "nfl"}, {"basketball", "nba"},
            {"basketball", "wnba"}, {"hockey", "nhl"}, {"baseball", "mlb"}};
    private static volatile JSONArray index;

    /** Teams whose name matches, as phone-page JSON ({@link Spec#json} plus a full name). */
    static String search(Context c, String q) {
        JSONArray all = index(c);
        JSONArray out = new JSONArray();
        String needle = q.trim().toLowerCase(Locale.US);
        if (needle.isEmpty() || all == null) return out.toString();
        for (int i = 0; i < all.length() && out.length() < 20; i++) {
            JSONObject t = all.optJSONObject(i);
            if (t == null) continue;
            String hay = (t.optString("n") + " " + t.optString("full") + " " + t.optString("a")).toLowerCase(Locale.US);
            if (hay.contains(needle)) out.put(t);
        }
        return out.toString();
    }

    /** Every team in {@link #LEAGUES}: fetched once a week (about 2 MB in all), kept compact on disk. */
    private static synchronized JSONArray index(Context c) {
        File f = new File(c.getFilesDir(), "teams.json");
        if (index != null && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) return index;
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) {
            try {
                index = new JSONArray(new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"));
                return index;
            } catch (Exception ignored) { }
        }
        JSONArray[] parts = new JSONArray[LEAGUES.length];
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < LEAGUES.length; i++) {
            int n = i;
            pool.execute(() -> parts[n] = teams(LEAGUES[n][0], LEAGUES[n][1]));
        }
        pool.shutdown();
        try { pool.awaitTermination(25, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        JSONArray all = new JSONArray();
        for (JSONArray p : parts) if (p != null) for (int i = 0; i < p.length(); i++) all.put(p.opt(i));
        if (all.length() == 0) return index;   // offline: whatever we had
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(all.toString().getBytes("UTF-8"));
        } catch (Exception ignored) { }
        index = all;
        return all;
    }

    private static JSONArray teams(String sport, String league) {
        String json = get(SITE + sport + "/" + league + "/teams");
        if (json == null) return null;
        JSONArray out = new JSONArray();
        try {
            JSONObject lg = new JSONObject(json).getJSONArray("sports").getJSONObject(0).getJSONArray("leagues").getJSONObject(0);
            String leagueName = lg.optString("shortName", lg.optString("name"));
            if (leagueName.startsWith("English ")) leagueName = leagueName.substring(8);
            JSONArray ts = lg.getJSONArray("teams");
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.getJSONObject(i).getJSONObject("team");
                Spec s = new Spec();
                s.sport = sport;
                s.league = league;
                s.leagueName = leagueName;
                s.id = t.optString("id");
                s.name = t.optString("shortDisplayName", t.optString("displayName"));
                s.abbr = t.optString("abbreviation");
                JSONArray logos = t.optJSONArray("logos");
                s.logo = logos != null && logos.length() > 0 ? logos.getJSONObject(0).optString("href", null) : null;
                s.color = colour(t.optString("color"), s.color);
                s.color2 = colour(t.optString("alternateColor"), s.color2);
                out.put(s.json().put("full", t.optString("displayName")));
            }
        } catch (JSONException e) {
            return null;
        }
        return out;
    }

    private static int colour(String hex, int fallback) {
        if (hex == null || !hex.matches("[0-9a-fA-F]{6}")) return fallback;
        return 0xFF000000 | Integer.parseInt(hex, 16);
    }

    // ---- ESPN's JSON -> ours ---------------------------------------------------------------

    private static Match event(JSONObject e, Spec spec) throws JSONException {
        JSONArray comps = e.optJSONArray("competitions");
        if (comps == null || comps.length() == 0) return null;
        JSONObject c = comps.getJSONObject(0);
        JSONObject status = c.optJSONObject("status");
        JSONObject type = status != null ? status.optJSONObject("type") : null;
        if (type == null) return null;
        String name = type.optString("name");
        if (name.contains("POSTPONED") || name.contains("CANCELED") || name.contains("ABANDONED")) return null;
        Match m = new Match();
        m.id = e.getString("id");
        try {
            m.start = OffsetDateTime.parse(e.getString("date")).toInstant().toEpochMilli();
        } catch (RuntimeException bad) {
            return null;
        }
        m.state = type.optString("state", "pre");
        // the minute for football ("67'"), the period and clock elsewhere ("Q3 - 5:12")
        m.clock = spec.soccer() ? status.optString("displayClock", "") : type.optString("shortDetail", "");
        JSONObject league = e.optJSONObject("league");
        if (league != null) {
            m.comp = league.optString("shortName").replace("UEFA ", "").replace("English ", "");
            m.slug = league.optString("slug");
        } else {   // US sports: the schedule doesn't say; it's the team's own league
            m.comp = spec.leagueName;
            m.slug = spec.league;
        }
        JSONObject season = e.optJSONObject("seasonType");
        String round = season != null ? season.optString("name") : "";
        m.round = round.matches(".*\\d{4}.*") || "Regular Season".equals(round) ? "" : round;
        JSONObject venue = c.optJSONObject("venue");
        if (venue != null && !venue.optString("fullName").isEmpty()) m.venue = venue.optString("fullName");
        JSONArray teams = c.getJSONArray("competitors");
        for (int i = 0; i < teams.length(); i++) {
            JSONObject t = teams.getJSONObject(i);
            boolean home = "home".equals(t.optString("homeAway"));
            JSONObject team = t.getJSONObject("team");
            String tn = team.optString("shortDisplayName", team.optString("displayName"));
            Object sc = t.opt("score");   // an object in schedules, a string on the scoreboard
            String goals = sc instanceof JSONObject ? ((JSONObject) sc).optString("displayValue") : sc != null ? sc.toString() : "";
            int g = goals.matches("\\d+") ? Integer.parseInt(goals) : -1;
            if (home) {
                m.home = tn; m.homeGoals = g; m.homeWon = t.optBoolean("winner");
                m.atHome = spec.id.equals(team.optString("id"));
            } else {
                m.away = tn; m.awayGoals = g; m.awayWon = t.optBoolean("winner");
            }
        }
        if (m.home == null || m.away == null) return null;
        if (m.done() && (m.homeGoals < 0 || m.awayGoals < 0)) return null;
        return m;
    }

    private static String table(String json) {
        if (json == null) return null;
        try {
            JSONArray entries = new JSONObject(json).getJSONArray("children").getJSONObject(0)
                    .getJSONObject("standings").getJSONArray("entries");
            JSONArray out = new JSONArray();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject en = entries.getJSONObject(i), team = en.getJSONObject("team");
                String rank = "", pts = "", played = "";
                JSONArray stats = en.getJSONArray("stats");
                for (int j = 0; j < stats.length(); j++) {
                    JSONObject s = stats.getJSONObject(j);
                    switch (s.optString("name")) {
                        case "rank": rank = s.optString("displayValue"); break;
                        case "points": pts = s.optString("displayValue"); break;
                        case "gamesPlayed": played = s.optString("displayValue"); break;
                        default: break;
                    }
                }
                out.put(new JSONArray().put(rank).put(team.optString("shortDisplayName")).put(pts).put(played)
                        .put(team.optString("id")));
            }
            return out.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    private static String store(List<Match> ms) {
        ms.sort((a, b) -> Long.compare(a.start, b.start));
        JSONArray a = new JSONArray();
        try {
            for (Match m : ms) {
                a.put(new JSONObject().put("id", m.id).put("t", m.start).put("c", m.comp).put("r", m.round)
                        .put("s", m.slug).put("v", m.venue).put("h", m.home).put("a", m.away).put("st", m.state)
                        .put("k", m.clock).put("home", m.atHome).put("hw", m.homeWon).put("aw", m.awayWon)
                        .put("hg", m.homeGoals).put("ag", m.awayGoals));
            }
        } catch (JSONException ignored) { }
        return a.toString();
    }

    private static Data parse(SharedPreferences prefs, Spec s) {
        Data d = new Data();
        try {
            JSONArray a = new JSONArray(prefs.getString(k(s, "m"), "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Match m = new Match();
                m.id = o.getString("id");
                m.start = o.getLong("t");
                m.comp = o.optString("c", "");
                m.round = o.optString("r", "");
                m.slug = o.optString("s", s.league);
                m.venue = o.optString("v", null);
                m.home = o.getString("h");
                m.away = o.getString("a");
                m.state = o.optString("st", "pre");
                m.clock = o.optString("k", "");
                m.atHome = o.optBoolean("home");
                m.homeWon = o.optBoolean("hw");
                m.awayWon = o.optBoolean("aw");
                m.homeGoals = o.optInt("hg", -1);
                m.awayGoals = o.optInt("ag", -1);
                d.matches.add(m);
            }
            d.matches.sort((x, y) -> Long.compare(x.start, y.start));
        } catch (JSONException ignored) { }
        try {
            JSONArray t = new JSONArray(prefs.getString(k(s, "t"), "[]"));
            for (int i = 0; i < t.length(); i++) {
                JSONArray r = t.getJSONArray(i);
                d.table.add(new String[]{r.getString(0), r.getString(1), r.getString(2), r.getString(3), r.getString(4)});
            }
        } catch (JSONException ignored) { }
        return d;
    }

    /** "Sat Oct 24", for results. */
    static String day(long t) {
        return Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US));
    }

    static String get(String url) {
        byte[] b = bytes(url);
        try {
            return b != null ? new String(b, "UTF-8") : null;
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    static byte[] bytes(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "MonolithicOS");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
