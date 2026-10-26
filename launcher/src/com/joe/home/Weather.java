package com.joe.home;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Current weather from Open-Meteo: free, no account or key, and no location permission. The place is a
 * city typed on the phone page ({@link #geocode}), or at first a guess from the TV's internet address
 * ({@link #locate}). One small HTTPS request; the caller rate-limits it.
 */
final class Weather {
    interface Callback { void onWeather(String text, int code); }
    interface Place { void onPlace(String[] place); }   // {name, latitude, longitude}, or null

    private Weather() { }

    static void fetch(String lat, String lon, Handler main, Callback cb) {
        new Thread(() -> {
            String text = null;
            int code = -1;
            try {
                String json = Team.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code&timezone=auto");
                if (json != null) {
                    JSONObject now = new JSONObject(json).getJSONObject("current");
                    long t = Math.round(now.getDouble("temperature_2m"));
                    code = now.getInt("weather_code");
                    text = t + "° " + describe(code);
                }
            } catch (Exception ignored) {
                // offline or the API changed: keep showing the last reading
            }
            if (text != null) {
                String s = text;
                int c = code;
                main.post(() -> cb.onWeather(s, c));
            }
        }, "weather").start();
    }

    /** Roughly where the TV is, from its internet address (ipapi.co, no key). */
    static void locate(Handler main, Place cb) {
        new Thread(() -> {
            String[] place = null;
            try {
                String json = Team.get("https://ipapi.co/json/");
                if (json != null) {
                    JSONObject o = new JSONObject(json);
                    if (o.has("latitude") && !o.optString("city").isEmpty()) {
                        place = new String[]{o.getString("city"), String.valueOf(o.getDouble("latitude")),
                                String.valueOf(o.getDouble("longitude"))};
                    }
                }
            } catch (Exception ignored) { }
            String[] p = place;
            main.post(() -> cb.onPlace(p));
        }, "weather").start();
    }

    /** A city name to a place, with Open-Meteo's geocoder. Blocking: call it off the main thread. */
    /**
     * A city by name: the biggest place with that name (Open-Meteo's own order isn't by size: for one name it put
     * a 10k town 14 places ahead of a 40k city), and "London, Ontario" / "London, ON" / "London, Canada" narrows
     * it by region (name or initials) or country.
     */
    static String[] geocode(String query) {
        try {
            String[] parts = query.split(",", 2);
            String name = parts[0].trim(), where = parts.length > 1 ? parts[1].trim().toLowerCase(java.util.Locale.ROOT) : "";
            String json = Team.get("https://geocoding-api.open-meteo.com/v1/search?count=50&name="
                    + java.net.URLEncoder.encode(name, "UTF-8"));
            if (json == null) return null;
            JSONArray rs = new JSONObject(json).getJSONArray("results");
            JSONObject pick = null;
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.getJSONObject(i);
                if (!r.optString("name").equalsIgnoreCase(name) || !(where.isEmpty() || in(r, where))) continue;
                if (pick == null || r.optLong("population") > pick.optLong("population")) pick = r;
            }
            if (pick == null) pick = rs.getJSONObject(0);
            return new String[]{pick.getString("name"), String.valueOf(pick.getDouble("latitude")),
                    String.valueOf(pick.getDouble("longitude"))};
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean in(JSONObject r, String where) {
        String region = r.optString("admin1").toLowerCase(java.util.Locale.ROOT);
        StringBuilder initials = new StringBuilder();
        for (String w : region.split(" ")) if (!w.isEmpty()) initials.append(w.charAt(0));
        return region.startsWith(where) || initials.toString().equals(where)
                || r.optString("country").toLowerCase(java.util.Locale.ROOT).startsWith(where)
                || r.optString("country_code").equalsIgnoreCase(where);
    }

    /** WMO weather codes, in words. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }
}
