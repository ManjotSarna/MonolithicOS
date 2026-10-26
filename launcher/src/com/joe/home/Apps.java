package com.joe.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Your say over the apps, all in prefs so Home and the phone page share it: hidden apps, your own names and
 * pictures, PIN-locked apps (with their own parental PIN, not the phone page's), rows and folders you make,
 * and the apps you opened last (the Recent row).
 */
final class Apps {
    private Apps() { }

    static final int RECENT_MAX = 6;

    /** A row or folder: id, "row" or "folder", its name and its apps in order. */
    static final class Group {
        final String id, type, name;
        final List<String> pkgs = new ArrayList<>();

        Group(String id, String type, String name) {
            this.id = id;
            this.type = type;
            this.name = name;
        }

        boolean folder() { return "folder".equals(type); }
    }

    // ---- sets kept as comma-separated lists ----

    static Set<String> set(SharedPreferences p, String key) {
        String s = p.getString(key, "");
        Set<String> out = new LinkedHashSet<>();
        if (!s.isEmpty()) out.addAll(Arrays.asList(s.split(",")));
        return out;
    }

    static void put(SharedPreferences p, String key, String pkg, boolean in) {
        Set<String> s = set(p, key);
        if (in) s.add(pkg); else s.remove(pkg);
        p.edit().putString(key, TextUtils.join(",", s)).apply();
    }

    static boolean hidden(SharedPreferences p, String pkg) { return set(p, "hidden").contains(pkg); }
    static boolean locked(SharedPreferences p, String pkg) { return set(p, "locked").contains(pkg); }

    /** Your name for the app, or null. */
    static String label(SharedPreferences p, String pkg) {
        String s = p.getString("label:" + pkg, null);
        return s == null || s.isEmpty() ? null : s;
    }

    // ---- your own pictures ----

    static File art(Context c, String pkg) {
        File d = new File(c.getFilesDir(), "art");
        if (!d.exists()) d.mkdirs();
        return new File(d, pkg.replaceAll("[^A-Za-z0-9._]", "_") + ".jpg");
    }

    /** Your picture for the app (16:9, sent from the phone), or null. */
    static Bitmap picture(Context c, String pkg) {
        File f = art(c, pkg);
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }

    // ---- the parental PIN ----

    static String lockPin(SharedPreferences p) {
        String s = p.getString("lockpin", "");
        return s.matches("\\d{4}") ? s : null;
    }

    // ---- recent ----

    /** Opened just now: to the front of Recent. */
    static void opened(SharedPreferences p, String pkg) {
        if (pkg == null || HomeActivity.HIDDEN.contains(pkg)) return;
        List<String> r = new ArrayList<>(set(p, "recent"));
        r.remove(pkg);
        r.add(0, pkg);
        while (r.size() > RECENT_MAX + 6) r.remove(r.size() - 1);   // a few spare for apps the row leaves out
        p.edit().putString("recent", TextUtils.join(",", r)).apply();
    }

    static List<String> recent(SharedPreferences p) {
        return new ArrayList<>(set(p, "recent"));
    }

    // ---- rows and folders ----

    static List<Group> groups(SharedPreferences p) {
        List<Group> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("groups", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Group g = new Group(o.getString("id"), o.optString("type", "row"), o.optString("name", ""));
                JSONArray pk = o.optJSONArray("pkgs");
                if (pk != null) for (int j = 0; j < pk.length(); j++) g.pkgs.add(pk.getString(j));
                out.add(g);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    static void save(SharedPreferences p, List<Group> gs) {
        JSONArray a = new JSONArray();
        try {
            for (Group g : gs) {
                JSONArray pk = new JSONArray();
                for (String s : g.pkgs) pk.put(s);
                a.put(new JSONObject().put("id", g.id).put("type", g.type).put("name", g.name).put("pkgs", pk));
            }
        } catch (JSONException ignored) { }
        p.edit().putString("groups", a.toString()).apply();
    }

    /** The group an app is in, or null. */
    static Group groupOf(List<Group> gs, String pkg) {
        for (Group g : gs) if (g.pkgs.contains(pkg)) return g;
        return null;
    }

    /** Puts an app into a group (or none with a null id), out of any other. */
    static void move(SharedPreferences p, String pkg, String id) {
        List<Group> gs = groups(p);
        for (Group g : gs) g.pkgs.remove(pkg);
        for (Group g : gs) if (g.id.equals(id)) g.pkgs.add(pkg);
        save(p, gs);
    }

    static void add(SharedPreferences p, String type, String name) {
        List<Group> gs = groups(p);
        gs.add(new Group(Long.toString(System.currentTimeMillis(), 36), "folder".equals(type) ? "folder" : "row",
                name.length() > 24 ? name.substring(0, 24) : name));
        save(p, gs);
    }

    static void remove(SharedPreferences p, String id) {
        List<Group> gs = groups(p);
        gs.removeIf(g -> g.id.equals(id));
        save(p, gs);
    }

    static void rename(SharedPreferences p, String id, String name) {
        List<Group> gs = groups(p), out = new ArrayList<>();
        for (Group g : gs) {
            Group n = g.id.equals(id) ? new Group(g.id, g.type, name.length() > 24 ? name.substring(0, 24) : name) : g;
            if (n != g) n.pkgs.addAll(g.pkgs);
            out.add(n);
        }
        save(p, out);
    }
}
