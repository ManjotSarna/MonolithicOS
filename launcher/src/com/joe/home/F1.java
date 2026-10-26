package com.joe.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Formula 1 for the "Next up" card, from the Jolpica F1 API (the community successor to Ergast): free,
 * no account or key. Three small requests a few times a day: this season's calendar, the last race's
 * podium and the drivers' standings. The raw replies are cached in prefs, so the card is there at once
 * after a reboot, and offline.
 */
final class F1 {
    interface Callback { void onF1(Data data); }

    static final class Session {
        final String name;
        final long start, end;
        Session(String name, long start, long minutes) {
            this.name = name;
            this.start = start;
            this.end = start + minutes * 60_000L;
        }
        boolean live(long now) { return now >= start && now < end; }
    }

    static final class Race {
        int round;
        String name, longName, circuit, place;
        final List<Session> sessions = new ArrayList<>();
        Session race() { return sessions.get(sessions.size() - 1); }
        long first() { return sessions.get(0).start; }

        /** The session on now, or else the next one; null once the race is over. */
        Session current(long now) {
            for (Session s : sessions) if (s.end > now) return s;
            return null;
        }
    }

    static final class Data {
        final List<Race> races = new ArrayList<>();
        int lastRound;
        String lastRace;
        final List<String> podium = new ArrayList<>();
        final List<String[]> standings = new ArrayList<>();   // {family name, points}

        Race next(long now) {
            for (Race r : races) if (r.race().end > now) return r;
            return null;
        }
    }

    private static final String BASE = "https://api.jolpi.ca/ergast/f1/current";
    // {JSON key, name on screen, minutes it runs}; the race itself is the race's own date and time
    private static final String[][] KINDS = {
            {"FirstPractice", "Practice 1", "60"}, {"SecondPractice", "Practice 2", "60"},
            {"ThirdPractice", "Practice 3", "60"}, {"SprintQualifying", "Sprint Qualifying", "45"},
            {"Sprint", "Sprint", "60"}, {"Qualifying", "Qualifying", "60"}};
    private static final long RACE_MINUTES = 120;

    private F1() { }

    /** What's cached (possibly nothing), parsed off the main thread. */
    static void load(SharedPreferences prefs, Handler main, Callback cb) {
        new Thread(() -> {
            Data d = parse(prefs);
            main.post(() -> cb.onF1(d));
        }, "f1").start();
    }

    /** Fresh from the API; anything that fails keeps its cached copy. */
    static void fetch(SharedPreferences prefs, Handler main, Callback cb) {
        new Thread(() -> {
            SharedPreferences.Editor e = prefs.edit();
            String cal = get(BASE + ".json"), last = get(BASE + "/last/results.json?limit=3"),
                    table = get(BASE + "/driverStandings.json?limit=5");
            if (cal != null) e.putString("f1cal", cal);
            if (last != null) e.putString("f1last", last);
            if (table != null) e.putString("f1table", table);
            e.putLong("f1at", System.currentTimeMillis()).commit();
            Data d = parse(prefs);
            main.post(() -> cb.onF1(d));
        }, "f1").start();
    }

    /** The F1 logo for the card's badge, from ESPN's league logos (fetched once; not shipped, it's a trademark). */
    static Bitmap logo(Context c) {
        File f = new File(c.getFilesDir(), "f1-logo.png");
        if (!f.exists()) {
            byte[] png = Team.bytes("https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500/f1.png&w=128&h=128");
            if (png == null) return null;
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(png);
            } catch (Exception e) {
                return BitmapFactory.decodeByteArray(png, 0, png.length);
            }
        }
        return BitmapFactory.decodeFile(f.getPath());
    }

    private static Data parse(SharedPreferences prefs) {
        Data d = new Data();
        try {
            JSONArray races = new JSONObject(prefs.getString("f1cal", "{}")).getJSONObject("MRData")
                    .getJSONObject("RaceTable").getJSONArray("Races");
            for (int i = 0; i < races.length(); i++) {
                JSONObject r = races.getJSONObject(i);
                Race race = new Race();
                race.round = r.getInt("round");
                race.longName = r.getString("raceName");
                race.name = race.longName.replace("Grand Prix", "GP");
                race.circuit = r.getJSONObject("Circuit").getString("circuitName");
                race.place = r.getJSONObject("Circuit").getJSONObject("Location").optString("locality");
                for (String[] k : KINDS) {
                    JSONObject s = r.optJSONObject(k[0]);
                    long t = s != null ? time(s) : -1;
                    if (t > 0) race.sessions.add(new Session(k[1], t, Long.parseLong(k[2])));
                }
                long t = time(r);
                if (t <= 0) continue;
                race.sessions.sort((a, b) -> Long.compare(a.start, b.start));
                race.sessions.add(new Session("Race", t, RACE_MINUTES));
                d.races.add(race);
            }
        } catch (Exception ignored) { }
        try {
            JSONObject r = new JSONObject(prefs.getString("f1last", "{}")).getJSONObject("MRData")
                    .getJSONObject("RaceTable").getJSONArray("Races").getJSONObject(0);
            d.lastRound = r.getInt("round");
            d.lastRace = r.getString("raceName").replace("Grand Prix", "GP");
            JSONArray res = r.getJSONArray("Results");
            for (int i = 0; i < Math.min(3, res.length()); i++) {
                d.podium.add(res.getJSONObject(i).getJSONObject("Driver").getString("familyName"));
            }
        } catch (Exception ignored) { }
        try {
            JSONArray list = new JSONObject(prefs.getString("f1table", "{}")).getJSONObject("MRData")
                    .getJSONObject("StandingsTable").getJSONArray("StandingsLists").getJSONObject(0)
                    .getJSONArray("DriverStandings");
            for (int i = 0; i < Math.min(5, list.length()); i++) {
                JSONObject s = list.getJSONObject(i);
                d.standings.add(new String[]{s.getJSONObject("Driver").getString("familyName"), s.getString("points")});
            }
        } catch (Exception ignored) { }
        return d;
    }

    private static long time(JSONObject o) {
        String date = o.optString("date"), time = o.optString("time");
        if (date.isEmpty() || time.isEmpty()) return -1;
        try { return Instant.parse(date + "T" + time).toEpochMilli(); } catch (RuntimeException e) { return -1; }
    }

    // ---- words for the screen, in the TV's time zone -------------------------------------------

    /** "Sun 12:00 pm" this week, "Sun Nov 8, 9:00 am" further out. */
    static String when(long t, long now, boolean h24) {
        ZonedDateTime z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault());
        boolean soon = t - now < 6 * 86_400_000L;
        return z.format(DateTimeFormatter.ofPattern(soon ? "EEE " : "EEE MMM d, ", Locale.US)) + clock(z, h24);
    }

    /** "Fri Oct 30 · 11:30 am", for the weekend list. */
    static String full(long t, boolean h24) {
        ZonedDateTime z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault());
        return z.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)) + "  ·  " + clock(z, h24);
    }

    private static String clock(ZonedDateTime z, boolean h24) {
        return h24 ? z.format(DateTimeFormatter.ofPattern("H:mm", Locale.US))
                : z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).toLowerCase(Locale.US);
    }

    /** Until t, as a big number and its unit: {"6", "days"}, {"17:40", "hrs"}, {"42", "min"}. */
    static String[] countdown(long t, long now) {
        long d = Math.max(0, t - now);
        if (d < 3_600_000L) return new String[]{String.valueOf(Math.max(1, (d + 59_999) / 60_000)), "min"};
        if (d < 86_400_000L) {
            long m = (d + 59_999) / 60_000;
            return new String[]{String.format(Locale.US, "%d:%02d", m / 60, m % 60), "hrs"};
        }
        ZoneId zone = ZoneId.systemDefault();
        long days = ChronoUnit.DAYS.between(LocalDate.now(zone), Instant.ofEpochMilli(t).atZone(zone).toLocalDate());
        return new String[]{String.valueOf(Math.max(1, days)), days == 1 ? "day" : "days"};
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "MonolithicOS");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
