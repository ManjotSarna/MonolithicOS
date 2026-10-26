package com.joe.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.text.TextUtils;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HDMI inputs, shared by Home and the phone page. */
final class Inputs {
    private static final String LIVE_TV = "com.tcl.tv";
    private static final Pattern HW = Pattern.compile("HW(\\d+)$");

    private Inputs() { }

    /** HDMI ports in port order, plus the names of devices that announce themselves over CEC. */
    static void hdmi(Context c, TvInputManager tm, List<TvInputInfo> ports, Map<String, String> devices) {
        if (tm == null) return;
        for (TvInputInfo in : tm.getTvInputList()) {
            try {
                if (!in.isPassthroughInput() || in.getType() != TvInputInfo.TYPE_HDMI || in.isHidden(c)) continue;
                if (in.getParentId() != null) {
                    CharSequence name = in.loadCustomLabel(c);
                    if (TextUtils.isEmpty(name)) name = in.loadLabel(c);
                    if (!TextUtils.isEmpty(name)) devices.put(in.getParentId(), name.toString());
                } else {
                    ports.add(in);
                }
            } catch (RuntimeException ignored) { }
        }
        ports.sort((a, b) -> Integer.compare(hwIndex(a.getId()), hwIndex(b.getId())));
    }

    private static int hwIndex(String id) {
        Matcher m = HW.matcher(id);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** The port's name, e.g. HDMI 1 (or what it was called in the TV's own input settings). */
    static String port(Context c, TvInputInfo in) {
        CharSequence s = in.loadCustomLabel(c);
        if (TextUtils.isEmpty(s)) s = in.loadLabel(c);
        return TextUtils.isEmpty(s) ? "HDMI" : s.toString();
    }

    /** The name given on the phone page, or null. */
    static String renamed(SharedPreferences prefs, String id) {
        String s = prefs.getString("name:" + id, null);
        return TextUtils.isEmpty(s) ? null : s;
    }

    static String state(TvInputManager tm, String id) {
        int s;
        try { s = tm.getInputState(id); } catch (RuntimeException e) { return ""; }
        // TCL reports empty ports as CONNECTED_STANDBY too, so only a live connection counts
        return s == TvInputManager.INPUT_STATE_CONNECTED ? "Connected" : "Not connected";
    }

    /** Straight to TCL's TV app on that input, no chooser. False if nothing would take it. */
    static boolean switchTo(Context c, String id) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(id);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(new Intent(view).setPackage(LIVE_TV));
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) { }
        try {
            c.startActivity(view);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
