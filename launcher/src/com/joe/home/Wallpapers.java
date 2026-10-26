package com.joe.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Home-screen wallpapers: MonolithicOS's own (the GT3 RS in the snow), photos sent from the phone page
 * (the app's own files/wallpapers) and any in Pictures/Wallpapers on the TV. Loading makes two bitmaps: the
 * photo cropped to the screen as a GPU-only HARDWARE bitmap, and the small frosted copy for the glass.
 */
final class Wallpapers {
    static final int FROST_W = 160, FROST_H = 90;

    static final class Loaded {
        final Bitmap photo, frost;
        Loaded(Bitmap photo, Bitmap frost) { this.photo = photo; this.frost = frost; }
    }

    private Wallpapers() { }

    static File uploads(Context c) {
        File d = new File(c.getFilesDir(), "wallpapers");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** MonolithicOS's own wallpapers, unpacked from the app on first use (so they load like any photo). */
    private static File builtin(Context c) {
        File d = new File(c.getFilesDir(), "builtin");
        File f = new File(d, "GT3 RS Snow.jpg");   // "snow" in the name: it snows on it by default
        if (!f.exists() && (d.isDirectory() || d.mkdirs())) {
            try (java.io.InputStream in = c.getResources().openRawResource(R.raw.gt3rs_snow);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (Exception e) {
                f.delete();
            }
        }
        return d;
    }

    /** Every usable photo: newest uploads first, then the shared Pictures/Wallpapers folder, then ours. */
    static List<File> list(Context c) {
        List<File> out = new ArrayList<>();
        for (File dir : new File[]{uploads(c),
                new File(Environment.getExternalStorageDirectory(), "Pictures/Wallpapers"), builtin(c)}) {
            File[] fs = dir.listFiles((d, n) -> n.matches("(?i).+\\.(jpe?g|png|webp)"));
            if (fs == null) continue;
            Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : fs) if (f.canRead()) out.add(f);
        }
        return out;
    }

    /** The photo Home and the screensaver show (pref "wallpaper", else the newest), or null for the aurora. */
    static File current(Context c) {
        android.content.SharedPreferences prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("photo", true)) return null;
        List<File> photos = list(c);
        if (photos.isEmpty()) return null;
        String want = prefs.getString("wallpaper", "");
        for (File f : photos) if (f.getPath().equals(want)) return f;
        return photos.get(0);
    }

    /** Off the main thread. Null if the file can't be read. */
    static Loaded load(File f, int screenW, int screenH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeFile(f.getPath(), o);
            if (raw == null) return null;

            // centre-crop to exactly the screen
            Bitmap fit = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888);
            float s = Math.max((float) screenW / raw.getWidth(), (float) screenH / raw.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate((screenW - raw.getWidth() * s) / 2f, (screenH - raw.getHeight() * s) / 2f);
            new Canvas(fit).drawBitmap(raw, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            raw.recycle();
            fit.setHasAlpha(false);

            Bitmap frost = frost(fit);
            Bitmap gpu = fit.copy(Bitmap.Config.HARDWARE, false);   // one GPU copy, nothing in app RAM
            fit.recycle();
            return new Loaded(gpu, frost);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * 160x90, saturation 1.4 (blur greys colours out; the tvOS material keeps them vivid), three box
     * passes each way approximating a ~36 px gaussian at 1080p. About 56 KB.
     */
    static Bitmap frost(Bitmap src) {
        Bitmap small = Bitmap.createBitmap(FROST_W, FROST_H, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1.4f);
        p.setColorFilter(new ColorMatrixColorFilter(sat));
        Canvas c = new Canvas(small);
        c.scale((float) FROST_W / src.getWidth(), (float) FROST_H / src.getHeight());
        c.drawBitmap(src, 0, 0, p);
        int[] px = new int[FROST_W * FROST_H];
        small.getPixels(px, 0, FROST_W, 0, 0, FROST_W, FROST_H);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, FROST_W, FROST_H, 3, true);
            boxBlur(px, FROST_W, FROST_H, 3, false);
        }
        small.setPixels(px, 0, FROST_W, 0, 0, FROST_W, FROST_H);
        small.setHasAlpha(false);
        return small;
    }

    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] line = new int[len];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = line[Math.max(0, Math.min(len - 1, i))];
                sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            int win = 2 * r + 1;
            for (int i = 0; i < len; i++) {
                px[horizontal ? l * w + i : i * w + l] = 0xFF000000 | (sr / win) << 16 | (sg / win) << 8 | (sb / win);
                int out = line[Math.max(0, i - r)], in = line[Math.min(len - 1, i + r + 1)];
                sr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                sg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                sb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }
}
