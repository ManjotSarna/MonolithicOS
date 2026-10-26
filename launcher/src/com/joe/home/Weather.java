package com.joe.home;

import android.os.Handler;

import org.json.JSONObject;

/**
 * Current weather from Open-Meteo: free, no account or key, and no location permission. The place is a
 * city typed on the phone page ({@link #geocode}), or at first a guess from the TV's internet address
 * ({@link #locate}). One small HTTPS request; the caller rate-limits it.
 */
final class Weather {
    interface Callback { void onWeather(String text); }
    interface Place { void onPlace(String[] place); }   // {name, latitude, longitude}, or null

    private Weather() { }

    static void fetch(String lat, String lon, Handler main, Callback cb) {
        new Thread(() -> {
            String text = null;
            try {
                String json = Team.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code&timezone=auto");
                if (json != null) {
                    JSONObject now = new JSONObject(json).getJSONObject("current");
                    long t = Math.round(now.getDouble("temperature_2m"));
                    text = t + "° " + describe(now.getInt("weather_code"));
                }
            } catch (Exception ignored) {
                // offline or the API changed: keep showing the last reading
            }
            if (text != null) {
                String s = text;
                main.post(() -> cb.onWeather(s));
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
    static String[] geocode(String name) {
        try {
            String json = Team.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + java.net.URLEncoder.encode(name.trim(), "UTF-8"));
            if (json == null) return null;
            JSONObject r = new JSONObject(json).getJSONArray("results").getJSONObject(0);
            return new String[]{r.getString("name"), String.valueOf(r.getDouble("latitude")),
                    String.valueOf(r.getDouble("longitude"))};
        } catch (Exception e) {
            return null;
        }
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
