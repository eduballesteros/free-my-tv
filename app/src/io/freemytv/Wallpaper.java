package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Calendar;

/**
 * The background when no photo is shown: soft blobs of colour on a base, like iOS's wallpapers, for the time of day
 * (dawn, day, dusk, night), in a light version (by day) and a dark one (at night). Painted once, small: the screen
 * scales the light one up (it is all soft gradients) and the glass reads the larger one, as with a photo (Backdrop).
 */
final class Wallpaper {
    private Wallpaper() {}

    static final int DAWN = 0, DAY = 1, DUSK = 2, NIGHT = 3;

    // Per moment, light then dark: base colour, then blobs as {colour, x %, y %, radius % of the width}.
    private static final int[][][] BASE = {
        {{0xFFFCE9E1}, {0xFF1B1428}}, {{0xFFEAF2FF}, {0xFF0B1424}}, {{0xFFFFEDE3}, {0xFF1C1020}}, {{0xFFE6E6F5}, {0xFF05070F}},
    };
    private static final int[][][][] BLOBS = {
        {{{0xFFFFB199, 18, 22, 55}, {0xFFC9B6FF, 78, 18, 50}, {0xFF9EC9FF, 60, 92, 60}},
         {{0xFF6A3A64, 20, 20, 55}, {0xFF3A2E6E, 80, 25, 50}, {0xFF5B2A3A, 55, 95, 60}}},
        {{{0xFF9CC8FF, 15, 15, 55}, {0xFFB9F0E6, 85, 30, 50}, {0xFFD9CCFF, 50, 100, 60}},
         {{0xFF1E3A6E, 18, 18, 55}, {0xFF0F4A55, 85, 30, 50}, {0xFF2A2860, 50, 100, 60}}},
        {{{0xFFFFAE7A, 20, 25, 55}, {0xFFF48FB1, 80, 20, 50}, {0xFFB39DFF, 60, 100, 60}},
         {{0xFF7A3424, 18, 25, 55}, {0xFF6B2148, 82, 18, 50}, {0xFF3B2A70, 55, 100, 60}}},
        {{{0xFFB8C0F0, 20, 20, 55}, {0xFFD0C2F2, 80, 25, 50}, {0xFFAFC7EE, 55, 100, 60}},
         {{0xFF18245A, 20, 15, 55}, {0xFF2A1A50, 80, 30, 50}, {0xFF0E2A4A, 50, 100, 60}}},
    };

    /**
     * The moment of the day from your sunrise and sunset (epoch ms of today's; 0 = unknown: 7:00 and 19:00). Dawn: an
     * hour before sunrise to 90 min after; dusk: 90 min before sunset to an hour after.
     */
    static int moment(long now, long sunrise, long sunset) {
        long rise = minuteOfDay(sunrise, 7 * 60), set = minuteOfDay(sunset, 19 * 60), m = minuteOfDay(now, 12 * 60);
        if (m >= rise - 60 && m < rise + 90) return DAWN;
        if (m >= rise + 90 && m < set - 90) return DAY;
        if (m >= set - 90 && m < set + 60) return DUSK;
        return NIGHT;
    }

    private static long minuteOfDay(long at, long fallback) {
        if (at <= 0) return fallback;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(at);
        return c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE);
    }

    /** The wallpaper of a moment, as a backdrop (the light version by day, the dark one at night). Background thread. */
    static Backdrop backdrop(int moment) {
        return Backdrop.wallpaper(paint(moment, 0, Backdrop.FW, Backdrop.FH), paint(moment, 0, Backdrop.GW, Backdrop.GH),
                paint(moment, 1, Backdrop.FW, Backdrop.FH), paint(moment, 1, Backdrop.GW, Backdrop.GH));
    }

    private static int[] paint(int moment, int dark, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(BASE[moment][dark][0]);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        for (int[] blob : BLOBS[moment][dark]) {
            float cx = w * blob[1] / 100f, cy = h * blob[2] / 100f, r = w * blob[3] / 100f;
            p.setShader(new RadialGradient(cx, cy, r, blob[0], blob[0] & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        return px;
    }
}
